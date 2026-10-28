/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.duckdb.model.DuckMetaModel;
import org.jkiss.dbeaver.ext.generic.model.GenericCatalog;
import org.jkiss.dbeaver.ext.generic.model.GenericDataSource;
import org.jkiss.dbeaver.ext.generic.model.GenericStructContainer;
import org.jkiss.dbeaver.ext.generic.model.GenericTableBase;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

/**
 * DuckLake metadata model. Reuses the bundled DuckDB model wholesale (catalogs → schemas → tables,
 * DDL, sequences, data types, and reliable table listing via the default JDBC path). It creates a
 * {@link DuckLakeDataSource}, which hides DuckDB's internal {@code memory}/{@code system}/
 * {@code temp} catalogs, and {@link DuckLakeTable}s, which open in the data editor on their newest
 * data file.
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

    /**
     * Tables become {@link DuckLakeTable}, which opens in the data editor on the newest data file
     * instead of downloading the whole table. Views stay DBeaver's.
     */
    @Override
    public GenericTableBase createTableOrViewImpl(
        GenericStructContainer container,
        @Nullable String tableName,
        @Nullable String tableType,
        @Nullable JDBCResultSet dbResult
    ) {
        if (tableType != null && isView(tableType)) {
            return super.createTableOrViewImpl(container, tableName, tableType, dbResult);
        }

        return new DuckLakeTable(container, tableName, tableType, dbResult);
    }
}
