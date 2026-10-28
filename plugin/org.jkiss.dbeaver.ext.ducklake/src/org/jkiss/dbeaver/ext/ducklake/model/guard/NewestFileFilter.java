/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.model.guard;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * The default filter the data editor opens a DuckLake table with: the range of one integer column
 * in the table's newest data file, {@code <column> BETWEEN <min> AND <max>}. Double-clicking a
 * table runs {@code SELECT * FROM t} with only a JDBC fetch limit, which the DuckDB driver ignores
 * (measured on the demo stack: {@code setMaxRows(200)} still returned and read all 1,000,000 rows,
 * 98 MB), so without a filter it downloads the whole table; for a 2,815-file table that was 220 GB.
 * In a table sorted by that column the newest file covers one tight range, and a BETWEEN on it lets
 * DuckLake prune by the catalog's min/max statistics to that file plus any file straddling the
 * range (measured: 1 file, 3 to 4 GETs, out of 40 or 300). No ORDER BY is added: the rows come in
 * file order.
 *
 * <p>Which file is newest and which column is used depend on the connection's preferred column
 * names ({@code DuckLakeConstants.PROP_NEWEST_FILE_COLUMNS}). With names, the newest file is the
 * one whose statistics for them reach the highest ({@link CatalogStats#newestFileSql}), which in a
 * table sorted by such a column is its most recent range even when compaction rewrote an older
 * range later. Without names it is the file committed last. BETWEEN rather than {@code >= min}:
 * the last commit can be a backfill or rewrite file far below the top of the range, and
 * {@code >= min} would then open every file above it.
 *
 * <p>Pure Java, so the choice of column and the SQL can be unit tested.
 */
public final class NewestFileFilter {

    /** The column and range the default filter uses. */
    public record Choice(String column, BigInteger min, BigInteger max) {
    }

    private NewestFileFilter() {
    }

    /**
     * The connection's preferred column names from the stored property: split on commas, trimmed,
     * lower-cased, empty entries dropped. Null or blank gives an empty list.
     */
    public static List<String> parseColumns(String value) {
        List<String> names = new ArrayList<>();

        if (value == null) {
            return names;
        }

        for (String part : value.split(",")) {
            String name = part.trim().toLowerCase(Locale.ROOT);

            if (!name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
        }

        return names;
    }

    /**
     * Pick the column to filter on, in this order: an integer column named in {@code preferred}
     * (case-insensitive, in the order given), then an integer identity partition column (DuckLake
     * records file statistics for it like for any other column), then the first integer column.
     * Within the last two groups the table's column order decides. A column only qualifies when
     * the newest file has integer min and max statistics for it, so a preferred name that is not
     * an integer column with statistics is skipped. Null when nothing qualifies; the table then
     * opens without a default filter.
     */
    public static Choice choose(List<CatalogStats.ColumnStats> columns, List<String> preferred) {
        List<CatalogStats.ColumnStats> ordered = new ArrayList<>();

        for (String name : preferred) {
            for (CatalogStats.ColumnStats c : columns) {
                if (isInteger(c) && c.name().equalsIgnoreCase(name.trim())) {
                    ordered.add(c);
                }
            }
        }

        for (CatalogStats.ColumnStats c : columns) {
            if (isInteger(c) && c.partition()) {
                ordered.add(c);
            }
        }

        for (CatalogStats.ColumnStats c : columns) {
            if (isInteger(c)) {
                ordered.add(c);
            }
        }

        for (CatalogStats.ColumnStats c : ordered) {
            BigInteger min = QueryGuardEstimate.integer(c.min());
            BigInteger max = QueryGuardEstimate.integer(c.max());

            if (min != null && max != null && min.compareTo(max) <= 0) {
                return new Choice(c.name(), min, max);
            }
        }

        return null;
    }

    /**
     * The WHERE text for the data editor's filter bar, e.g. {@code height BETWEEN 1000 AND 1999}.
     * The bounds are integers parsed from the catalog, so nothing but digits reaches the SQL.
     *
     * @param quote quotes the column name for the connection's SQL dialect where it needs it
     */
    public static String where(Choice choice, UnaryOperator<String> quote) {
        return quote.apply(choice.column()) + " BETWEEN " + choice.min() + " AND " + choice.max();
    }

    private static boolean isInteger(CatalogStats.ColumnStats c) {
        return c.type() != null && CatalogStats.INTEGER_TYPES.contains(c.type().toLowerCase(Locale.ROOT));
    }
}
