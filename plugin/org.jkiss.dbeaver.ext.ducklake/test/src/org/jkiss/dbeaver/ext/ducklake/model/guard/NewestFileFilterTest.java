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

    public static void main(String[] args) {
        demoBlocks();
        bronzeTableWithBlockheight();
        heightWithoutStatistics();
        partitionColumnFallback();
        firstIntegerFallback();
        nothingFits();
        whereText();

        System.out.println("OK, " + passed + " checks passed");
    }

    /** What CatalogStats.newestFile returned for lake.main.blocks on the demo stack. */
    private static void demoBlocks() {
        NewestFileFilter.Choice c = NewestFileFilter.choose(List.of(
            col("height_bucket", "int64", true, "0", "0"),
            col("height", "int64", false, "975000", "999999"),
            col("hash", "varchar", false, "0003883910709aa39bf38b05c51c03a3", "ffff8e040521f62207881376ecc964d5"),
            col("payload", "varchar", false, "000d", "ffff")));
        check(c != null && c.column().equals("height") && c.min().longValue() == 975_000 && c.max().longValue() == 999_999,
            "blocks: height wins over the partition column " + c);
    }

    private static void bronzeTableWithBlockheight() {
        NewestFileFilter.Choice c = NewestFileFilter.choose(List.of(
            col("height_bucket", "int64", true, "3", "3"),
            col("txid", "varchar", false, "00", "ff"),
            col("n", "int32", false, "0", "4000"),
            col("BlockHeight", "int32", false, "3071230", "3071299")));
        check(c != null && c.column().equals("BlockHeight"), "vouts: blockheight, any case, ahead of earlier integers " + c);

        NewestFileFilter.Choice textHeight = NewestFileFilter.choose(List.of(
            col("height", "varchar", false, "1", "9"),
            col("height_bucket", "int64", true, "3", "3")));
        check(textHeight != null && textHeight.column().equals("height_bucket"), "a text height is not used " + textHeight);

        NewestFileFilter.Choice unsigned = NewestFileFilter.choose(List.of(
            col("height", "uint64", false, "18446744073709551000", "18446744073709551615")));
        check(unsigned != null && unsigned.max().equals(new BigInteger("18446744073709551615")), "uint64 beyond long");
    }

    /** Inlined rows or a column added later: no statistics in the newest file, so the next candidate. */
    private static void heightWithoutStatistics() {
        NewestFileFilter.Choice c = NewestFileFilter.choose(List.of(
            col("height", "int64", false, null, null),
            col("height_bucket", "int64", true, "2", "2")));
        check(c != null && c.column().equals("height_bucket"), "height without stats falls back " + c);

        NewestFileFilter.Choice garbled = NewestFileFilter.choose(List.of(
            col("height", "int64", false, "12x", "15"),
            col("other", "int16", false, "9", "3")));
        check(garbled == null, "non-integer or inverted statistics are not used " + garbled);
    }

    private static void partitionColumnFallback() {
        NewestFileFilter.Choice c = NewestFileFilter.choose(List.of(
            col("id", "int32", false, "1", "4"),
            col("day", "int32", true, "20261027", "20261027")));
        check(c != null && c.column().equals("day"), "partition column before the first integer " + c);
    }

    private static void firstIntegerFallback() {
        NewestFileFilter.Choice c = NewestFileFilter.choose(List.of(
            col("name", "varchar", false, "a", "z"),
            col("id", "int32", false, "1", "4"),
            col("total", "int64", false, "5", "42")));
        check(c != null && c.column().equals("id"), "first integer column " + c);
    }

    private static void nothingFits() {
        check(NewestFileFilter.choose(List.of()) == null, "no columns");
        check(NewestFileFilter.choose(List.of(
            col("ts", "timestamp", false, "2026-01-01", "2026-01-02"),
            col("amount", "decimal(10,2)", false, "1.5", "9.5"))) == null, "no integer column, no default filter");
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
