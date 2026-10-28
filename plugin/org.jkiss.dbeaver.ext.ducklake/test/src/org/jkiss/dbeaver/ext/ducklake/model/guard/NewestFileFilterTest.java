/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.model.guard;

import java.math.BigInteger;
import java.util.List;

/**
 * Unit tests for the data editor's default filter: which column {@link NewestFileFilter} picks
 * from the newest file's statistics, and the WHERE text it builds. Plain main(), like
 * {@link QueryGuardTest}; {@code test.ps1} runs both. The first case is the column list the demo
 * stack's {@code lake.main.blocks} returned from {@link CatalogStats#newestFile}.
 */
public final class NewestFileFilterTest {

    private static int passed;

    /** What a connection's "Newest-file columns" might hold in the tests below. */
    private static final List<String> HEIGHT = List.of("height");

    public static void main(String[] args) {
        demoBlocks();
        preferredNames();
        preferredOrderWins();
        noPreferredNames();
        heightWithoutStatistics();
        partitionColumnFallback();
        firstIntegerFallback();
        nothingFits();
        parseColumns();
        whereText();

        System.out.println("OK, " + passed + " checks passed");
    }

    /** What CatalogStats.newestFile returned for lake.main.blocks on the demo stack. */
    private static void demoBlocks() {
        NewestFileFilter.Choice c = NewestFileFilter.choose(List.of(
            col("height_bucket", "int64", true, "0", "0"),
            col("height", "int64", false, "975000", "999999"),
            col("hash", "varchar", false, "0003883910709aa39bf38b05c51c03a3", "ffff8e040521f62207881376ecc964d5"),
            col("payload", "varchar", false, "000d", "ffff")), HEIGHT);
        check(c != null && c.column().equals("height") && c.min().longValue() == 975_000 && c.max().longValue() == 999_999,
            "blocks: a preferred height wins over the partition column " + c);
    }

    private static void preferredNames() {
        NewestFileFilter.Choice c = NewestFileFilter.choose(List.of(
            col("bucket", "int64", true, "3", "3"),
            col("txid", "varchar", false, "00", "ff"),
            col("n", "int32", false, "0", "4000"),
            col("SeqNo", "int32", false, "3071230", "3071299")), List.of("seqno"));
        check(c != null && c.column().equals("SeqNo"), "a preferred name, any case, ahead of earlier integers " + c);

        NewestFileFilter.Choice textHeight = NewestFileFilter.choose(List.of(
            col("height", "varchar", false, "1", "9"),
            col("bucket", "int64", true, "3", "3")), HEIGHT);
        check(textHeight != null && textHeight.column().equals("bucket"), "a preferred text column is skipped " + textHeight);

        NewestFileFilter.Choice unsigned = NewestFileFilter.choose(List.of(
            col("height", "uint64", false, "18446744073709551000", "18446744073709551615")), HEIGHT);
        check(unsigned != null && unsigned.max().equals(new BigInteger("18446744073709551615")), "uint64 beyond long");

        NewestFileFilter.Choice missing = NewestFileFilter.choose(List.of(
            col("id", "int32", false, "1", "4")), List.of("nosuchcolumn"));
        check(missing != null && missing.column().equals("id"), "a preferred name the table lacks is skipped " + missing);
    }

    /** The order of the preferred names decides, not the table's column order. */
    private static void preferredOrderWins() {
        List<CatalogStats.ColumnStats> columns = List.of(
            col("seq", "int64", false, "10", "20"),
            col("height", "int64", false, "975000", "999999"));
        NewestFileFilter.Choice heightFirst = NewestFileFilter.choose(columns, List.of("height", "seq"));
        check(heightFirst != null && heightFirst.column().equals("height"), "preferred order: height first " + heightFirst);

        NewestFileFilter.Choice seqFirst = NewestFileFilter.choose(columns, List.of("seq", "height"));
        check(seqFirst != null && seqFirst.column().equals("seq"), "preferred order: seq first " + seqFirst);
    }

    /** No preferred names: a height column is just another integer column. */
    private static void noPreferredNames() {
        NewestFileFilter.Choice partition = NewestFileFilter.choose(List.of(
            col("height", "int64", false, "975000", "999999"),
            col("bucket", "int64", true, "0", "0")), List.of());
        check(partition != null && partition.column().equals("bucket"), "no names: identity partition first " + partition);

        NewestFileFilter.Choice first = NewestFileFilter.choose(List.of(
            col("name", "varchar", false, "a", "z"),
            col("height", "int64", false, "975000", "999999"),
            col("total", "int64", false, "5", "42")), List.of());
        check(first != null && first.column().equals("height"), "no names, no partition: first integer " + first);
    }

    /** Inlined rows or a column added later: no statistics in the newest file, so the next candidate. */
    private static void heightWithoutStatistics() {
        NewestFileFilter.Choice c = NewestFileFilter.choose(List.of(
            col("height", "int64", false, null, null),
            col("bucket", "int64", true, "2", "2")), HEIGHT);
        check(c != null && c.column().equals("bucket"), "height without stats falls back " + c);

        NewestFileFilter.Choice garbled = NewestFileFilter.choose(List.of(
            col("height", "int64", false, "12x", "15"),
            col("other", "int16", false, "9", "3")), HEIGHT);
        check(garbled == null, "non-integer or inverted statistics are not used " + garbled);
    }

    private static void partitionColumnFallback() {
        NewestFileFilter.Choice c = NewestFileFilter.choose(List.of(
            col("id", "int32", false, "1", "4"),
            col("day", "int32", true, "20261027", "20261027")), HEIGHT);
        check(c != null && c.column().equals("day"), "partition column before the first integer " + c);
    }

    private static void firstIntegerFallback() {
        NewestFileFilter.Choice c = NewestFileFilter.choose(List.of(
            col("name", "varchar", false, "a", "z"),
            col("id", "int32", false, "1", "4"),
            col("total", "int64", false, "5", "42")), HEIGHT);
        check(c != null && c.column().equals("id"), "first integer column " + c);
    }

    private static void nothingFits() {
        check(NewestFileFilter.choose(List.of(), HEIGHT) == null, "no columns");
        check(NewestFileFilter.choose(List.of(
            col("ts", "timestamp", false, "2026-01-01", "2026-01-02"),
            col("amount", "decimal(10,2)", false, "1.5", "9.5")), HEIGHT) == null, "no integer column, no default filter");
    }

    private static void parseColumns() {
        List<String> parsed = NewestFileFilter.parseColumns(" Height, blockheight ,, ");
        check(parsed.equals(List.of("height", "blockheight")), "parseColumns trims, lower-cases, drops empties " + parsed);
        check(NewestFileFilter.parseColumns(null).isEmpty(), "parseColumns: null is empty");
        check(NewestFileFilter.parseColumns("  ").isEmpty(), "parseColumns: blank is empty");
        check(NewestFileFilter.parseColumns(",,").isEmpty(), "parseColumns: only commas is empty");
    }

    private static void whereText() {
        NewestFileFilter.Choice c = new NewestFileFilter.Choice("height", BigInteger.valueOf(975_000), BigInteger.valueOf(999_999));
        check(NewestFileFilter.where(c, n -> n).equals("height BETWEEN 975000 AND 999999"),
            NewestFileFilter.where(c, n -> n));

        String quoted = NewestFileFilter.where(new NewestFileFilter.Choice("Block Height", BigInteger.valueOf(-5),
            BigInteger.TEN), n -> "\"" + n.replace("\"", "\"\"") + "\"");
        check(quoted.equals("\"Block Height\" BETWEEN -5 AND 10"), quoted);
        check(!quoted.toUpperCase().contains("ORDER BY"), "no ORDER BY in the default filter");
    }

    private static CatalogStats.ColumnStats col(String name, String type, boolean partition, String min, String max) {
        return new CatalogStats.ColumnStats(name, type, partition, min, max);
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            System.out.println("FAILED: " + what);
            System.exit(1);
        }

        passed++;
    }
}
