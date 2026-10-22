/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.ext.duckdb.model.DuckDBGenericCatalog;
import org.jkiss.dbeaver.ext.generic.model.GenericDataSource;

import java.util.Set;

/**
 * DuckLake catalog. Marks DuckDB's built-in {@code memory}/{@code system}/{@code temp} databases as
 * system catalogs so they are hidden when "Show system objects" is off — leaving just the lake(s).
 * This avoids an object filter (and its "Filtered by settings" badge on the connection). The
 * short-lived Postgres passthroughs used by catalog discovery are hidden the same way.
 */
public class DuckLakeGenericCatalog extends DuckDBGenericCatalog {

    private static final Set<String> INTERNAL = Set.of(
        "memory", "system", "temp", DuckLakeCatalogDiscovery.META_ALIAS, DuckLakeCatalogDiscovery.PROBE_ALIAS);

    public DuckLakeGenericCatalog(@NotNull GenericDataSource dataSource, @NotNull String catalogName) {
        super(dataSource, catalogName);
    }

    @Override
    public boolean isSystem() {
        return INTERNAL.contains(getName());
    }

    /**
     * The Postgres database and schema the catalog's metadata lives in, e.g.
     * {@code Postgres ducklake.ethereum}. Catalogs are named after the schema alone, so this is
     * where the navigator's tooltip (and the description column, when shown) says which database
     * a catalog comes from.
     */
    @Override
    public String getDescription() {
        if (getDataSource() instanceof DuckLakeDataSource lake) {
            String source = lake.getCatalogSource(getName());

            if (source != null) {
                return "Postgres " + source;
            }
        }

        return super.getDescription();
    }
}
