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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DuckLake data source. Reuses the DuckDB data source, and on initialization turns off
 * "Show system objects" for the connection so DuckDB's built-in {@code memory}/{@code system}/
 * {@code temp} catalogs (marked system by {@link DuckLakeGenericCatalog}) are hidden — without an
 * object filter, so the connection shows no "Filtered by settings" badge.
 *
 * <p>It also owns the ATTACHes. The generated init file only loads extensions and creates the S3
 * secret, so every catalog is attached from here, one at a time, and a catalog the Postgres role
 * cannot read becomes a warning instead of a failed connection. Discovery runs once, lazily, the
 * first time {@link #initializeContextState} is called, which is the Main context: the ATTACHes and
 * the {@code USE} have to happen before DBeaver reads the context's active catalog and schema, and
 * that read is the {@code super} call at the end of the same method. Every context opened after
 * that replays the stored ATTACH statements and the same {@code USE}.
 *
 * <p>Which catalogs are looked for is governed by {@code ducklake.discover_schemas} (other schemas
 * of this database) and {@code ducklake.discover_databases} (other databases on the server), both
 * on by default.
 */
public class DuckLakeDataSource extends DuckDBDataSource {

    private static final Log log = Log.getLog(DuckLakeDataSource.class);

    /**
     * Catalog name to ATTACH statement, set by discovery. Null until then: the Main context opens
     * inside the super constructor, before this field could be initialized, so nothing here may
     * rely on a field initializer having run.
     */
    private volatile Map<String, String> discoveredAttachments;

    /** The catalog {@code USE} selects, chosen by discovery. Null until discovery has run. */
    private volatile String activeCatalog;

    /** Guarded by {@code this}: discovery must run once, however many contexts open at once. */
    private boolean discoveryDone;

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

        super.initialize(monitor);
    }

    /**
     * Put the catalogs in place on every execution context, before {@code super} reads the context's
     * active catalog and schema back from the connection. The first call is the Main context and
     * runs discovery; later contexts, which each get their own DuckDB instance, replay the ATTACH
     * statements discovery recorded. Each catalog is attached separately, so one that has become
     * unreachable since is logged and skipped instead of failing the whole connection.
     */
    @Override
    protected void initializeContextState(
        @NotNull DBRProgressMonitor monitor,
        @NotNull JDBCExecutionContext context,
        JDBCExecutionContext initFrom
    ) throws DBException {
        prepareCatalogs(monitor, context);

        super.initializeContextState(monitor, context, initFrom);
        restoreDefaultSchema(monitor, context);
    }

    /**
     * Run discovery on the first context to reach this, and replay its ATTACHes plus the same
     * {@code USE} on every later one. Discovery is the only part that can fail the connection, and
     * only when no catalog at all could be attached.
     */
    private void prepareCatalogs(@NotNull DBRProgressMonitor monitor, @NotNull JDBCExecutionContext context)
        throws DBException {
        DBPConnectionConfiguration cfg = getContainer().getActualConnectionConfiguration();
        String defaultSchema = CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_DEFAULT_SCHEMA)).trim();

        synchronized (this) {
            if (!discoveryDone) {
                discoverCatalogs(monitor, context, cfg, defaultSchema);
                discoveryDone = true;
                return;
            }
        }

        String catalog = activeCatalog;
        Map<String, String> attachments = discoveredAttachments;

        if (catalog == null || attachments == null) {
            return;
        }

        try (JDBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "Attach DuckLake catalogs")) {
            for (String warning : DuckLakeCatalogDiscovery.attachBestEffort(session, attachments)) {
                log.warn(warning);
            }

            for (String warning : DuckLakeCatalogDiscovery.useCatalog(session, catalog, defaultSchema)) {
                log.warn(warning);
            }
        }
    }

    /**
     * Re-apply the Default schema. {@link #prepareCatalogs} already ran
     * {@code USE "<catalog>"."<schema>"} on this context, but a context that inherits the active
     * catalog from another one gets it through JDBC {@code setCatalog} inside {@code super}
     * (GenericExecutionContext.initDefaultsFrom does that whenever the catalog was detected through
     * the JDBC API, which is how the DuckDB model detects it), and DuckDB resets the schema to
     * {@code main} when that runs. Only applies while the discovered catalog is active, so a catalog
     * picked in the editor stays as it is.
     */
    private void restoreDefaultSchema(DBRProgressMonitor monitor, JDBCExecutionContext context) {
        DBPConnectionConfiguration cfg = getContainer().getActualConnectionConfiguration();
        String defaultSchema = CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_DEFAULT_SCHEMA)).trim();
        String alias = activeCatalog;

        if (defaultSchema.isEmpty() || alias == null) {
            return;
        }

        try (JDBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "Restore DuckLake default schema");
             Statement stmt = session.createStatement()) {
            String catalog;
            String schema;

            try (ResultSet rs = stmt.executeQuery("SELECT current_database(), current_schema()")) {
                if (!rs.next()) {
                    return;
                }

                catalog = rs.getString(1);
                schema = rs.getString(2);
            }

            if (!alias.equalsIgnoreCase(catalog) || defaultSchema.equalsIgnoreCase(schema)) {
                return;
            }

            stmt.execute("USE " + DuckLakeDataSourceProvider.id(alias) + "." + DuckLakeDataSourceProvider.id(defaultSchema));
            log.info("Restored DuckLake default schema '" + defaultSchema + "' on " + context.getContextName()
                + " (was '" + schema + "')");
        } catch (Exception e) {
            log.warn("Could not restore DuckLake default schema '" + defaultSchema + "': "
                + DuckLakeCatalogDiscovery.redact(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Discover and ATTACH every DuckLake catalog on the Postgres server, then make the first one
     * that attached current. The ATTACHes run on the context being initialized, which is the Main
     * one; {@link #prepareCatalogs} replays them on every context opened afterwards.
     *
     * @throws DBException when the catalog database cannot be reached at all, or when not one
     *                     catalog could be attached. The message carries the warnings, because the
     *                     connection dialog is the only place the user will see them.
     */
    private void discoverCatalogs(
        @NotNull DBRProgressMonitor monitor,
        @NotNull JDBCExecutionContext context,
        @NotNull DBPConnectionConfiguration cfg,
        @NotNull String defaultSchema
    ) throws DBException {
        boolean includeSchemas = CommonUtils.getBoolean(cfg.getProviderProperty(DuckLakeConstants.PROP_DISCOVER_SCHEMAS), true);
        boolean includeDatabases = CommonUtils.getBoolean(cfg.getProviderProperty(DuckLakeConstants.PROP_DISCOVER_DATABASES), true);

        DuckLakeCatalogDiscovery.Result result;
        List<String> warnings;

        try (JDBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "Discover DuckLake catalogs")) {
            result = DuckLakeCatalogDiscovery.discover(
                session,
                CommonUtils.notEmpty(cfg.getHostName()),
                CommonUtils.notEmpty(cfg.getHostPort()),
                CommonUtils.notEmpty(cfg.getDatabaseName()),
                CommonUtils.notEmpty(cfg.getUserName()),
                CommonUtils.notEmpty(cfg.getUserPassword()),
                CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_METADATA_SCHEMA)),
                CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_LAKE_ALIAS)),
                CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_DATA_PATH)),
                includeSchemas, includeDatabases);

            warnings = new ArrayList<>(result.warnings());

            if (result.activeCatalog() != null) {
                warnings.addAll(DuckLakeCatalogDiscovery.useCatalog(session, result.activeCatalog(), defaultSchema));
            }
        } catch (SQLException e) {
            // The message can carry the Postgres connection string, password included.
            throw new DBException("DuckLake catalog discovery failed: "
                + DuckLakeCatalogDiscovery.redact(String.valueOf(e.getMessage())));
        }

        for (String warning : warnings) {
            log.warn(warning);
        }

        if (result.activeCatalog() == null) {
            throw new DBException("No DuckLake catalog could be attached.\n" + String.join("\n", warnings));
        }

        discoveredAttachments = Collections.unmodifiableMap(new LinkedHashMap<>(result.attachStatements()));
        activeCatalog = result.activeCatalog();

        log.info("DuckLake active catalog '" + result.activeCatalog() + "', " + result.attached()
            + " catalog(s) attached, " + result.skipped() + " skipped");
    }
}
