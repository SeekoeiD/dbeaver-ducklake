/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.model.guard;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The default filter the data editor opens a DuckLake table with: the height range of the table's
 * newest data file, {@code height BETWEEN <min> AND <max>}. Double-clicking a table runs
 * {@code SELECT * FROM t} with only a JDBC fetch limit, which the DuckDB driver ignores (measured on
 * the demo stack: {@code setMaxRows(200)} still returned and read all 1,000,000 rows, 98 MB), so
 * without a filter it downloads the whole table; for {@code litecoin.bronze.raw_responses} that is
 * 2,815 files and 220 GB. Our tables are sorted by height, so the newest file covers one tight
 * range, and a BETWEEN on it lets DuckLake prune by the catalog's min/max statistics to that file
 * plus any file straddling the range (measured: 1 file, 3 to 4 GETs, out of 40 or 300). No ORDER
 * BY is added: the rows come in file order, which for these tables is height order.
 *
 * <p>Newest is the file whose height statistics reach the highest, the tip ({@link
 * CatalogStats#newestFileSql}); only a table without a height column falls back to the file
 * committed last. BETWEEN rather than {@code >= min}: on that fallback the last commit can be a
 * backfill or rewrite file far below the tip, and {@code >= min} would then open every file above
 * it.
 *
 * <p>Pure Java, so the choice of column and the SQL can be unit tested.
 */
public final class NewestFileFilter {

    /** Column names that hold the block height in our lakes: raw_responses has height, the rest blockheight. */
    private static final Set<String> HEIGHT_NAMES = Set.of("height", "blockheight");

    /** The column and range the default filter uses. */
    public record Choice(String column, BigInteger min, BigInteger max) {
    }

    private NewestFileFilter() {
    }

    /**
     * Pick the column to filter on, in this order: an integer column named {@code height} or
     * {@code blockheight}, then an integer identity partition column (DuckLake records file
     * statistics for it like for any other column), then the first integer column. Within each
     * group the table's column order decides. A column only qualifies when the newest file has
     * integer min and max statistics for it. Null when nothing qualifies; the table then opens
     * without a default filter.
     */
    public static Choice choose(List<CatalogStats.ColumnStats> columns) {
        List<CatalogStats.ColumnStats> ordered = new ArrayList<>();

        for (CatalogStats.ColumnStats c : columns) {
            if (isInteger(c) && HEIGHT_NAMES.contains(c.name().toLowerCase(Locale.ROOT))) {
                ordered.add(c);
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
