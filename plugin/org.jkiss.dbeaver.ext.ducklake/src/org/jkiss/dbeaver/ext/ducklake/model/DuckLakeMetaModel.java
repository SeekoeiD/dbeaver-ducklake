/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.duckdb.model.DuckMetaModel;
import org.jkiss.dbeaver.ext.generic.model.GenericCatalog;
import org.jkiss.dbeaver.ext.generic.model.GenericDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

/**
 * DuckLake metadata model. Reuses the bundled DuckDB model wholesale (catalogs → schemas → tables,
 * DDL, sequences, data types, and reliable table listing via the default JDBC path). The only
 * customization is to create a {@link DuckLakeDataSource}, which installs a default filter hiding
 * DuckDB's internal {@code memory}/{@code system}/{@code temp} catalogs.
 *
 * <p>The schema level (e.g. {@code main}) is kept on purpose: DuckLake supports multiple schemas
 * (catalog → schema → table), so flattening it away would merge schemas and lose that grouping.
 */
public class DuckLakeMetaModel extends DuckMetaModel {

    @NotNull
    @Override
    public GenericDataSource createDataSourceImpl(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container
    ) throws DBException {
        return new DuckLakeDataSource(monitor, container, this);
    }

    @Override
    public GenericCatalog createCatalogImpl(@NotNull GenericDataSource dataSource, @NotNull String catalogName) {
        return new DuckLakeGenericCatalog(dataSource, catalogName);
    }
}
