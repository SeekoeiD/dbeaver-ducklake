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

import java.io.File;

/**
 * DuckLake data source. Reuses the DuckDB data source, and on initialization turns off
 * "Show system objects" for the connection so DuckDB's built-in {@code memory}/{@code system}/
 * {@code temp} catalogs (marked system by {@link DuckLakeGenericCatalog}) are hidden — without an
 * object filter, so the connection shows no "Filtered by settings" badge.
 *
 * <p>On initialization it also discovers the other DuckLake catalogs on the same Postgres server
 * and ATTACHes each so it appears as its own top-level node: {@code <schema>} for catalogs in this
 * database ({@code ducklake.discover_schemas}), {@code <database>.<schema>} for catalogs in other
 * databases ({@code ducklake.discover_databases}). Both default on.
 */
public class DuckLakeDataSource extends DuckDBDataSource {

    private static final Log log = Log.getLog(DuckLakeDataSource.class);

    /**
     * URL the provider generated for this data source. The first one is assigned while the super
     * constructor opens the Main connection, so these fields deliberately have no initializers.
     */
    private volatile String generatedUrl;

    /**
     * Set once discovery has written its ATTACH block into the init file. DBeaver asks for a
     * connection URL for every physical connection it opens (Metadata context, SQL editors, ...), and
     * the provider regenerates the init file on each call, which would drop the block again.
     */
    private volatile String discoveredInitUrl;

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
            // Discovery is best-effort; never let it break connecting.
            log.warn("DuckLake catalog discovery failed", t);
        }

        super.initialize(monitor);
    }

    @Override
    protected String getConnectionURL(DBPConnectionConfiguration connectionInfo) throws DBException {
        String url = discoveredInitUrl;
        if (url != null) {
            return url;
        }

        generatedUrl = super.getConnectionURL(connectionInfo);
        return generatedUrl;
    }

    /**
     * Discover and ATTACH the other DuckLake catalogs on the Postgres server. The ATTACHes run
     * directly on this (metadata) connection's DuckDB instance, and are then also written into the
     * generated init file so SQL-editor connections — which can land on separate DuckDB instances —
     * replay them and see the same catalogs.
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

        // DBeaver's SQL editors open separate physical connections that can land on separate
        // DuckDB instances, where only the init file runs — so the discovered ATTACHes must be
        // replayed from there, or queries against discovered catalogs fail to bind.
        File initFile = DuckLakeDataSourceProvider.initFileFromURL(generatedUrl);

        if (initFile == null) {
            log.warn("No DuckLake init file to record discovered catalogs in; new connections may not see them");
        } else {
            try {
                DuckLakeDataSourceProvider.updateInitFileDiscoveries(initFile, result.initStatements());
                discoveredInitUrl = generatedUrl;
            } catch (Exception e) {
                log.warn("Could not write discovered DuckLake catalogs to the init file", e);
            }
        }

        log.info("DuckLake discovery attached " + result.attached() + " additional catalog(s), "
            + result.initStatements().size() + " in init file (primary '"
            + primaryAlias + "' from schema '" + primarySchema + "')");
    }
}
