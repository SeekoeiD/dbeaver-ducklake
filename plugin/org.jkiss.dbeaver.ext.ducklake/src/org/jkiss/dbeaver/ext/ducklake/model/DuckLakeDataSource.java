/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.duckdb.model.DuckDBDataSource;
import org.jkiss.dbeaver.ext.generic.model.GenericCatalog;
import org.jkiss.dbeaver.ext.generic.model.meta.GenericMetaModel;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DuckLake data source. Reuses the DuckDB data source, and on initialization turns off
 * "Show system objects" for the connection so DuckDB's built-in {@code memory}/{@code system}/
 * {@code temp} catalogs (marked system by {@link DuckLakeGenericCatalog}) are hidden — without an
 * object filter, so the connection shows no "Filtered by settings" badge.
 *
 * <p>On initialization it also discovers the other DuckLake catalogs on the same Postgres server
 * and ATTACHes each so it appears as its own top-level node: {@code <schema>} for catalogs in this
 * database ({@code ducklake.discover_schemas}), {@code <database>.<schema>} for catalogs in other
 * databases ({@code ducklake.discover_databases}). Both default on. Every execution context opened
 * afterwards (Metadata, SQL editors) replays those ATTACHes one catalog at a time.
 */
public class DuckLakeDataSource extends DuckDBDataSource {

    private static final Log log = Log.getLog(DuckLakeDataSource.class);

    /**
     * Catalog name to ATTACH statement, set by discovery. Null until then: the Main context opens
     * inside the super constructor, before this field could be initialized.
     */
    private volatile Map<String, String> discoveredAttachments;

    public DuckLakeDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container,
        @NotNull GenericMetaModel metaModel
    ) throws DBException {
        super(monitor, container, metaModel);
    }

    @Override
    public void initialize(@NotNull DBRProgressMonitor monitor) throws DBException {
        try {
            // Remove any legacy catalog filter from earlier builds (it caused the
            // "Filtered by settings" badge); hiding now happens via system-object marking.
            if (getContainer().getObjectFilter(GenericCatalog.class, null, false) != null) {
                getContainer().setObjectFilter(GenericCatalog.class, null, null);
            }
            getContainer().getNavigatorSettings().setShowSystemObjects(false);
        } catch (Throwable t) {
            // Never let this cosmetic setting break connecting.
            log.debug("Could not adjust DuckLake navigator settings", t);
        }

        try {
            discoverCatalogs(monitor);
        } catch (Throwable t) {
            // Discovery is best-effort; never let it break connecting. The message can carry the
            // Postgres connection string, so log it redacted and without the raw exception.
            log.warn("DuckLake catalog discovery failed: " + DuckLakeCatalogDiscovery.redact(String.valueOf(t.getMessage())));
        }

        super.initialize(monitor);
    }

    /**
     * Attach the discovered catalogs on each new execution context. A context can run on its own
     * DuckDB instance, where only the init file (the primary catalog) has run. Each catalog is
     * attached separately, so one that has become unreachable since discovery is logged and skipped
     * instead of failing the whole connection.
     */
    @Override
    protected void initializeContextState(
        @NotNull DBRProgressMonitor monitor,
        @NotNull JDBCExecutionContext context,
        JDBCExecutionContext initFrom
    ) throws DBException {
        Map<String, String> attachments = discoveredAttachments;

        if (attachments != null && !attachments.isEmpty()) {
            try (JDBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "Attach DuckLake catalogs")) {
                for (String warning : DuckLakeCatalogDiscovery.attachBestEffort(session, attachments)) {
                    log.warn(warning);
                }
            }
        }

        super.initializeContextState(monitor, context, initFrom);
    }

    /**
     * Discover and ATTACH the other DuckLake catalogs on the Postgres server. The ATTACHes run
     * directly on this (Main) connection's DuckDB instance; {@link #initializeContextState} replays
     * them on every execution context opened afterwards.
     */
    private void discoverCatalogs(@NotNull DBRProgressMonitor monitor) throws Exception {
        DBPConnectionConfiguration cfg = getContainer().getActualConnectionConfiguration();

        boolean includeSchemas = CommonUtils.getBoolean(cfg.getProviderProperty(DuckLakeConstants.PROP_DISCOVER_SCHEMAS), true);
        boolean includeDatabases = CommonUtils.getBoolean(cfg.getProviderProperty(DuckLakeConstants.PROP_DISCOVER_DATABASES), true);

        if (!includeSchemas && !includeDatabases) {
            return;
        }

        String primarySchema = CommonUtils.isEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_METADATA_SCHEMA))
            ? DuckLakeConstants.DEF_METADATA_SCHEMA : cfg.getProviderProperty(DuckLakeConstants.PROP_METADATA_SCHEMA);

        String primaryAlias = CommonUtils.isEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_LAKE_ALIAS))
            ? primarySchema : cfg.getProviderProperty(DuckLakeConstants.PROP_LAKE_ALIAS);

        String host = CommonUtils.notEmpty(cfg.getHostName());
        String port = CommonUtils.notEmpty(cfg.getHostPort());
        String db = CommonUtils.notEmpty(cfg.getDatabaseName());

        JDBCExecutionContext context = (JDBCExecutionContext) getDefaultInstance().getDefaultContext(monitor, true);
        DuckLakeCatalogDiscovery.Result result;

        try (JDBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "Discover DuckLake catalogs")) {
            result = DuckLakeCatalogDiscovery.discover(
                session, host, port, db,
                CommonUtils.notEmpty(cfg.getUserName()),
                CommonUtils.notEmpty(cfg.getUserPassword()),
                primarySchema, primaryAlias, includeSchemas, includeDatabases);
        }

        for (String warning : result.warnings()) {
            log.warn(warning);
        }

        discoveredAttachments = Collections.unmodifiableMap(new LinkedHashMap<>(result.attachStatements()));

        log.info("DuckLake discovery attached " + result.attached() + " additional catalog(s), "
            + result.attachStatements().size() + " replayed on new connections (primary '"
            + primaryAlias + "' from schema '" + primarySchema + "')");
    }
}
