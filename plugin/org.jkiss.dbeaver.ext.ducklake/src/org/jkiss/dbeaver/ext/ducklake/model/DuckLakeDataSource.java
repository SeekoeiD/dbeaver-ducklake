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
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

/**
 * DuckLake data source. Reuses the DuckDB data source, and on initialization turns off
 * "Show system objects" for the connection so DuckDB's built-in {@code memory}/{@code system}/
 * {@code temp} catalogs (marked system by {@link DuckLakeGenericCatalog}) are hidden — without an
 * object filter, so the connection shows no "Filtered by settings" badge.
 */
public class DuckLakeDataSource extends DuckDBDataSource {

    private static final Log log = Log.getLog(DuckLakeDataSource.class);

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
}
