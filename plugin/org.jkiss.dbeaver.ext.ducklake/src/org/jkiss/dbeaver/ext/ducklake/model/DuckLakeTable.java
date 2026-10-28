/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.ducklake.model.guard.CatalogStats;
import org.jkiss.dbeaver.ext.ducklake.model.guard.NewestFileFilter;
import org.jkiss.dbeaver.ext.generic.model.GenericStructContainer;
import org.jkiss.dbeaver.ext.generic.model.GenericTable;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDDataFilter;
import org.jkiss.dbeaver.model.data.DBDDataReceiver;
import org.jkiss.dbeaver.model.exec.DBCExecutionSource;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatistics;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A DuckLake table that opens in the data editor on its newest data only.
 *
 * <p>Double-clicking a table runs {@code SELECT * FROM t} with a JDBC fetch limit, which DuckDB
 * ignores, so the whole table comes down from object storage: the query guard rightly warned
 * "about 220 GB" for {@code litecoin.bronze.raw_responses}. The first time a data editor reads a
 * table with no filter of the user's own, this class adds a WHERE on the height range of the
 * table's newest data file ({@link NewestFileFilter}), taken from the catalog's statistics in one
 * metadata round trip. DuckLake prunes the scan to that file; there is no ORDER BY.
 *
 * <p>The WHERE goes into the data editor's own filter, so it shows in the filter bar and the user
 * clears it like any filter they typed; the next read then has no filter and runs as before, with
 * the guard's warning. DBeaver has no default-filter hook for a data container, so this is done in
 * {@link #readData}, and needs two things the API does not offer directly:
 * <ul>
 *     <li>Telling the first read from a cleared filter. A viewer (the data editor's
 *     {@code ResultSetViewer}, recognised by its {@code IResultSetController} interface: the UI
 *     classes are not visible from this bundle) gets the default only on its first read of this
 *     table. Every later read in that viewer, including one after the filter was cleared, is left
 *     alone. Exports and other readers are not viewers and never get it.</li>
 *     <li>Keeping the filter visible. On a first read the viewer replaces its filter with a fresh
 *     one when the result's columns arrive ({@code ResultSetModel.updateDataFilter} with
 *     {@code metadataChanged}), which drops a WHERE set on the filter that was passed in. So after
 *     the read the WHERE is set again on the viewer's live filter, through its public
 *     {@code getDataFilter()} found by reflection, before the viewer refreshes the filter bar
 *     ({@code ResultSetJobDataRead} does that after {@code readData} returns). When that method is
 *     not there (another DBeaver version), no default filter is applied at all, so a filter is
 *     never active without showing.</li>
 * </ul>
 * Checked against DBeaver 26.2.3's bytecode ({@code ResultSetViewer.refresh},
 * {@code ResultSetExecutionSource.getUseDataFilter}, {@code ResultSetDataReceiver.fetchEnd}).
 */
public class DuckLakeTable extends GenericTable {

    private static final Log log = Log.getLog(DuckLakeTable.class);

    /** How long the catalog lookup may take before the table opens without a default filter. */
    private static final long LOOKUP_MS = 5000;

    private static final String RESULT_SET_CONTROLLER = "org.jkiss.dbeaver.ui.controls.resultset.IResultSetController";

    /** Viewer to the tables it has read at least once. Weak: a closed editor drops out. */
    private static final Map<Object, Set<String>> READ_BY_VIEWER = Collections.synchronizedMap(new WeakHashMap<>());

    public DuckLakeTable(GenericStructContainer container, String tableName, String tableType, JDBCResultSet dbResult) {
        super(container, tableName, tableType, dbResult);
    }

    @NotNull
    @Override
    public DBCStatistics readData(
        @Nullable DBCExecutionSource source,
        @NotNull DBCSession session,
        @NotNull DBDDataReceiver dataReceiver,
        @Nullable DBDDataFilter dataFilter,
        long firstRow,
        long maxRows,
        long flags,
        int fetchSize
    ) throws DBException {
        Object viewer = source == null ? null : source.getExecutionController();
        String where = null;

        // firstReadInViewer goes first: it also records reads that come with a filter of their own,
        // so clearing that filter later does not bring the default back.
        if (firstReadInViewer(viewer) && dataFilter != null && !dataFilter.hasFilters() && liveFilter(viewer) != null) {
            where = defaultWhere(session);

            if (where != null) {
                dataFilter.setWhere(where);
                log.debug("DuckLake default data filter for " + getFullyQualifiedName(null) + ": " + where);
            }
        }

        DBCStatistics statistics = super.readData(source, session, dataReceiver, dataFilter, firstRow, maxRows, flags,
            fetchSize);

        if (where != null) {
            showInFilterBar(viewer, where);
        }

        return statistics;
    }

    /** Whether this is the data editor's first read of this table. */
    private boolean firstReadInViewer(@Nullable Object viewer) {
        if (viewer == null || !implementsInterface(viewer.getClass(), RESULT_SET_CONTROLLER)) {
            return false;
        }

        return READ_BY_VIEWER.computeIfAbsent(viewer, v -> ConcurrentHashMap.newKeySet())
            .add(CommonUtils.notEmpty(getFullyQualifiedName(null)).toLowerCase());
    }

    /**
     * The WHERE for the newest file's height range, or null when the table has no file, no integer
     * column with statistics, or the lookup fails. Runs on the raw JDBC connection, so the query
     * guard does not see it, and only in auto-commit mode: any failed statement aborts an open
     * DuckDB transaction, and a default filter is not worth that.
     */
    @Nullable
    private String defaultWhere(@NotNull DBCSession session) {
        if (!(session instanceof JDBCSession jdbc) || !(getDataSource() instanceof DuckLakeDataSource lake)
            || getCatalog() == null || getSchema() == null) {
            return null;
        }

        String catalog = getCatalog().getName();
        String metadataSchema = lake.getMetadataSchema(catalog);

        if (metadataSchema == null) {
            return null;
        }

        try {
            Connection connection = jdbc.getOriginal();

            if (!connection.getAutoCommit()) {
                return null;
            }

            List<CatalogStats.ColumnStats> columns = CatalogStats.newestFile(connection, catalog, metadataSchema,
                getSchema().getName(), getName(), System.currentTimeMillis() + LOOKUP_MS);
            NewestFileFilter.Choice choice = NewestFileFilter.choose(columns);

            if (choice == null) {
                return null;
            }

            return NewestFileFilter.where(choice, name -> DBUtils.getQuotedIdentifier(getDataSource(), name));
        } catch (Exception e) {
            log.warn("No default data filter for " + getName() + ": "
                + DuckLakeCatalogDiscovery.redact(String.valueOf(e.getMessage())));
            return null;
        }
    }

    /** Put the WHERE back on the viewer's live filter, which the first read replaced. */
    private static void showInFilterBar(Object viewer, String where) {
        try {
            DBDDataFilter live = liveFilter(viewer);

            if (live != null && CommonUtils.isEmpty(live.getWhere()) && !live.hasConditions()) {
                live.setWhere(where);
            }
        } catch (Exception e) {
            log.debug("Could not show the DuckLake default filter in the filter bar", e);
        }
    }

    /** The viewer's current data filter through its public {@code getDataFilter()}, or null. */
    @Nullable
    private static DBDDataFilter liveFilter(Object viewer) {
        try {
            Method getter = viewer.getClass().getMethod("getDataFilter");
            Object filter = getter.invoke(viewer);
            return filter instanceof DBDDataFilter f ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean implementsInterface(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                if (i.getName().equals(name) || implementsInterface(i, name)) {
                    return true;
                }
            }
        }

        return false;
    }
}
