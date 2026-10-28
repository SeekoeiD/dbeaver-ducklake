/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.model.guard;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Per-file statistics from a DuckLake catalog's own metadata tables. Two features need what
 * {@code ducklake_list_files} does not return: the query guard's pruning of a filtered scan (which
 * files a {@code height BETWEEN a AND b} can touch) and the data editor's default filter (the
 * height range of the newest file). Both are a handful of rows the catalog already holds, so they
 * cost one catalog round trip and nothing on object storage.
 *
 * <p>DuckLake attaches the metadata database of catalog {@code X} as
 * {@code __ducklake_metadata_X}; for a Postgres catalog that is a DuckDB postgres attachment whose
 * tables sit in the catalog's metadata schema. Each lookup is one {@code postgres_query} against
 * it, so the joins, the {@code ORDER BY ... LIMIT 1} and the aggregation run inside Postgres in a
 * single round trip. Going through DuckDB's postgres scanner instead would scan each metadata table
 * separately, one round trip or more apiece (about 170 ms each from South Africa to the Ireland
 * catalog), and would push down only the filters that are constants.
 *
 * <p>Table and column names follow the DuckLake 1.0 catalog that DuckDB 1.5.4 writes:
 * {@code ducklake_data_file (data_file_id, table_id, begin_snapshot, end_snapshot, file_size_bytes,
 * footer_size, ...)}, {@code ducklake_file_column_stats (data_file_id, table_id, column_id,
 * min_value, max_value, ...)} with min/max as text, {@code ducklake_column (column_id, table_id,
 * column_name, column_type, column_order, parent_column, end_snapshot, ...)} with types such as
 * {@code int64}, and {@code ducklake_partition_column (partition_id, table_id, column_id,
 * transform)}. A row is live while its {@code end_snapshot} is NULL.
 */
public final class CatalogStats {

    /** DuckLake's names for the integer types, as {@code ducklake_column.column_type} stores them. */
    static final List<String> INTEGER_TYPES = List.of(
        "int8", "int16", "int32", "int64", "int128", "uint8", "uint16", "uint32", "uint64", "uint128");

    /**
     * One column of a table with the statistics of the table's newest data file.
     *
     * @param partition the column is an identity partition key of the current partition spec
     * @param min       the newest file's minimum as the catalog stores it (text), or null
     * @param max       its maximum, or null
     */
    public record ColumnStats(String name, String type, boolean partition, String min, String max) {
    }

    /** Data files a filtered scan can still touch after min/max pruning, and what they weigh. */
    public record Pruned(long files, long dataBytes, long deleteBytes) {
    }

    private CatalogStats() {
    }

    // ------------------------------------------------------------------ SQL

    /**
     * Every top-level column of the table, in table order, with the min/max of the table's newest
     * live data file: the file whose {@code height}/{@code blockheight} statistics reach the
     * highest (the chain tip; the file committed last is often an old range the hourly rewrite
     * merged), else the last one committed. No file, or no statistics for a column, gives null
     * min/max.
     *
     * <p>Written as hash joins over the table's files, never as a subquery per file: the catalog's
     * {@code ducklake_file_column_stats} has no index (186,626 rows for litecoin, 2,943 files in
     * {@code bronze.vouts}), and a per-file subquery over it ran for more than four minutes on the
     * catalog mirror, while this form takes 0.85 s.
     */
    public static String newestFileSql(String metadataSchema, String schema, String table) {
        String m = pgIdentifier(metadataSchema) + ".";
        return "WITH tbl AS (" + liveTable(m, schema, table) + "),"
            + " files AS (SELECT f.data_file_id, f.begin_snapshot FROM " + m + "ducklake_data_file f"
            + " JOIN tbl ON tbl.table_id = f.table_id WHERE f.end_snapshot IS NULL),"
            + " hcols AS (SELECT c.column_id FROM " + m + "ducklake_column c JOIN tbl ON tbl.table_id = c.table_id"
            + " WHERE c.end_snapshot IS NULL AND c.parent_column IS NULL"
            + " AND lower(c.column_name) IN ('height', 'blockheight') AND c.column_type IN " + integerTypes() + "),"
            + " tops AS (SELECT st.data_file_id, max(CASE WHEN st.max_value ~ '^-?[0-9]+$' THEN st.max_value::numeric END) AS top"
            + " FROM " + m + "ducklake_file_column_stats st JOIN tbl ON tbl.table_id = st.table_id"
            + " JOIN files USING (data_file_id) JOIN hcols USING (column_id) GROUP BY st.data_file_id),"
            + " nf AS (SELECT f.data_file_id FROM files f LEFT JOIN tops USING (data_file_id)"
            + " ORDER BY tops.top DESC NULLS LAST, f.begin_snapshot DESC, f.data_file_id DESC LIMIT 1)"
            + " SELECT c.column_name, c.column_type,"
            + " EXISTS (SELECT 1 FROM " + m + "ducklake_partition_column pc"
            + " JOIN " + m + "ducklake_partition_info pi ON pi.partition_id = pc.partition_id AND pi.table_id = pc.table_id"
            + " WHERE pc.table_id = c.table_id AND pc.column_id = c.column_id AND pc.transform = 'identity'"
            + " AND pi.end_snapshot IS NULL) AS is_partition,"
            + " st.min_value, st.max_value"
            + " FROM " + m + "ducklake_column c JOIN tbl ON tbl.table_id = c.table_id"
            + " LEFT JOIN " + m + "ducklake_file_column_stats st ON st.table_id = c.table_id"
            + " AND st.column_id = c.column_id AND st.data_file_id = (SELECT data_file_id FROM nf)"
            + " WHERE c.end_snapshot IS NULL AND c.parent_column IS NULL"
            + " ORDER BY c.column_order";
    }

    /**
     * Count and size the live data files whose statistics overlap every bound, plus their delete
     * files. A file is ruled out only by a top-level integer column whose min and max are both
     * integers and lie wholly outside the bound; missing or non-integer statistics keep it, so the
     * result never undercounts what DuckLake will open. The regex guards keep Postgres from casting
     * text that is not a number (a struct field of the same name is skipped by
     * {@code parent_column IS NULL}: its statistics must not rule the file out).
     *
     * <p>Hash joins, not a {@code NOT EXISTS} per file: see {@link #newestFileSql}; the per-file
     * form ran 107 s on the catalog mirror for litecoin's vouts, this one 1.5 s.
     */
    public static String prunedFilesSql(String metadataSchema, String schema, String table,
                                        Map<String, QueryGuardEstimate.Range> bounds) {
        String m = pgIdentifier(metadataSchema) + ".";
        List<String> excluded = new ArrayList<>();

        for (Map.Entry<String, QueryGuardEstimate.Range> bound : bounds.entrySet()) {
            QueryGuardEstimate.Range range = bound.getValue();
            List<String> outside = new ArrayList<>();

            if (range.low() != null) {
                outside.add("st.max_value::numeric < " + range.low());
            }

            if (range.high() != null) {
                outside.add("st.min_value::numeric > " + range.high());
            }

            if (outside.isEmpty()) {
                continue;
            }

            excluded.add("SELECT st.data_file_id FROM " + m + "ducklake_file_column_stats st"
                + " JOIN tbl ON tbl.table_id = st.table_id JOIN files USING (data_file_id)"
                + " JOIN " + m + "ducklake_column c ON c.table_id = st.table_id AND c.column_id = st.column_id"
                + " AND c.end_snapshot IS NULL AND c.parent_column IS NULL"
                + " WHERE lower(c.column_name) = lower(" + pgLiteral(bound.getKey()) + ")"
                + " AND c.column_type IN " + integerTypes()
                + " AND st.min_value ~ '^-?[0-9]+$' AND st.max_value ~ '^-?[0-9]+$'"
                + " AND (" + String.join(" OR ", outside) + ")");
        }

        String kept = excluded.isEmpty()
            ? "SELECT * FROM files"
            : "SELECT * FROM files WHERE data_file_id NOT IN (" + String.join(" UNION ", excluded) + ")";

        // postgres_query needs distinct column names.
        return "WITH tbl AS (" + liveTable(m, schema, table) + "),"
            + " files AS (SELECT f.data_file_id, f.file_size_bytes FROM " + m + "ducklake_data_file f"
            + " JOIN tbl ON tbl.table_id = f.table_id WHERE f.end_snapshot IS NULL),"
            + " kept AS (" + kept + ")"
            + " SELECT (SELECT count(*) FROM kept) AS files,"
            + " (SELECT coalesce(sum(file_size_bytes), 0) FROM kept) AS data_bytes,"
            + " (SELECT coalesce(sum(d.file_size_bytes), 0) FROM " + m + "ducklake_delete_file d"
            + " JOIN kept ON kept.data_file_id = d.data_file_id WHERE d.end_snapshot IS NULL) AS delete_bytes";
    }

    /** The live table's id by schema and table name, compared case-insensitively like DuckDB does. */
    private static String liveTable(String m, String schema, String table) {
        return "SELECT t.table_id FROM " + m + "ducklake_table t"
            + " JOIN " + m + "ducklake_schema s ON s.schema_id = t.schema_id AND s.end_snapshot IS NULL"
            + " WHERE t.end_snapshot IS NULL" + tableMatch(schema, table);
    }

    private static String integerTypes() {
        StringJoiner types = new StringJoiner(", ", "(", ")");

        for (String type : INTEGER_TYPES) {
            types.add(pgLiteral(type));
        }

        return types.toString();
    }

    /** DuckDB SQL that runs a Postgres statement on catalog {@code catalog}'s metadata database. */
    public static String viaPostgres(String catalog, String postgresSql) {
        return "SELECT * FROM postgres_query(" + duckLiteral("__ducklake_metadata_" + catalog) + ", "
            + duckLiteral(postgresSql) + ")";
    }

    private static String tableMatch(String schema, String table) {
        // DuckDB identifiers compare case-insensitively, so the catalog's names are matched that way.
        return " AND lower(s.schema_name) = lower(" + pgLiteral(schema) + ")"
            + " AND lower(t.table_name) = lower(" + pgLiteral(table) + ")";
    }

    static String pgLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    static String pgIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    static String duckLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    // ------------------------------------------------------------------ JDBC

    /** {@link #newestFileSql} on a raw DuckDB connection, cancelled at the deadline. */
    public static List<ColumnStats> newestFile(Connection connection, String catalog, String metadataSchema,
                                               String schema, String table, long deadline) throws SQLException {
        List<ColumnStats> columns = new ArrayList<>();
        QueryGuard.query(connection, viaPostgres(catalog, newestFileSql(metadataSchema, schema, table)), deadline,
            rs -> columns.add(new ColumnStats(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4),
                rs.getString(5))));
        return columns;
    }

    /** {@link #prunedFilesSql} on a raw DuckDB connection, cancelled at the deadline. */
    public static Pruned prunedFiles(Connection connection, String catalog, String metadataSchema, String schema,
                                     String table, Map<String, QueryGuardEstimate.Range> bounds, long deadline)
        throws SQLException {
        long[] row = new long[3];
        QueryGuard.query(connection, viaPostgres(catalog, prunedFilesSql(metadataSchema, schema, table, bounds)), deadline,
            rs -> {
                row[0] = rs.getLong(1);
                row[1] = rs.getLong(2);
                row[2] = rs.getLong(3);
            });
        return new Pruned(row[0], row[1], row[2]);
    }
}
