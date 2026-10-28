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
     * Every top-level column of the table, in table order, with the min/max of the newest live data
     * file. Newest means the file whose {@code height} or {@code blockheight} statistics reach the
     * highest, i.e. the file at the chain tip; a table without such a column falls back to the file
     * with the highest {@code begin_snapshot}, the last one committed. The last commit is not the tip
     * on our lakes: the hourly range-rewrite job and DuckLake's merges rewrite old height ranges, so
     * the file committed last is often an old bucket, and a backfill commits beside the tip follower.
     * No file, or no statistics for a column, gives null min/max.
     */
    public static String newestFileSql(String metadataSchema, String schema, String table) {
        String m = pgIdentifier(metadataSchema) + ".";
        return "SELECT c.column_name, c.column_type,"
            + " EXISTS (SELECT 1 FROM " + m + "ducklake_partition_column pc"
            + " JOIN " + m + "ducklake_partition_info pi ON pi.partition_id = pc.partition_id AND pi.table_id = pc.table_id"
            + " WHERE pc.table_id = t.table_id AND pc.column_id = c.column_id AND pc.transform = 'identity'"
            + " AND pi.end_snapshot IS NULL) AS is_partition,"
            + " st.min_value, st.max_value"
            + " FROM " + m + "ducklake_table t"
            + " JOIN " + m + "ducklake_schema s ON s.schema_id = t.schema_id AND s.end_snapshot IS NULL"
            + " JOIN " + m + "ducklake_column c ON c.table_id = t.table_id AND c.end_snapshot IS NULL"
            + " AND c.parent_column IS NULL"
            + " LEFT JOIN LATERAL (SELECT f.data_file_id FROM " + m + "ducklake_data_file f"
            + " LEFT JOIN LATERAL (SELECT max(CASE WHEN hs.max_value ~ '^-?[0-9]+$' THEN hs.max_value::numeric END) AS top"
            + " FROM " + m + "ducklake_file_column_stats hs"
            + " JOIN " + m + "ducklake_column hc ON hc.table_id = hs.table_id AND hc.column_id = hs.column_id"
            + " AND hc.end_snapshot IS NULL"
            + " WHERE hs.data_file_id = f.data_file_id AND hs.table_id = f.table_id"
            + " AND lower(hc.column_name) IN ('height', 'blockheight')) h ON true"
            + " WHERE f.table_id = t.table_id AND f.end_snapshot IS NULL"
            + " ORDER BY h.top DESC NULLS LAST, f.begin_snapshot DESC, f.data_file_id DESC LIMIT 1) nf ON true"
            + " LEFT JOIN " + m + "ducklake_file_column_stats st ON st.data_file_id = nf.data_file_id"
            + " AND st.table_id = t.table_id AND st.column_id = c.column_id"
            + " WHERE t.end_snapshot IS NULL" + tableMatch(schema, table)
            + " ORDER BY c.column_order";
    }

    /**
     * Count and size the live data files whose statistics overlap every bound, plus their delete
     * files. A file is ruled out only by an integer column whose min and max are both integers and
     * lie wholly outside the bound; missing or non-integer statistics keep it, so the result never
     * undercounts what DuckLake will open. The CASE keeps Postgres from casting text that is not a
     * number.
     */
    public static String prunedFilesSql(String metadataSchema, String schema, String table,
                                        Map<String, QueryGuardEstimate.Range> bounds) {
        String m = pgIdentifier(metadataSchema) + ".";
        // postgres_query needs distinct column names.
        StringBuilder sql = new StringBuilder("SELECT count(*) AS files, coalesce(sum(f.file_size_bytes), 0) AS data_bytes,"
            + " coalesce(sum(del.bytes), 0) AS delete_bytes"
            + " FROM " + m + "ducklake_data_file f"
            + " JOIN " + m + "ducklake_table t ON t.table_id = f.table_id AND t.end_snapshot IS NULL"
            + " JOIN " + m + "ducklake_schema s ON s.schema_id = t.schema_id AND s.end_snapshot IS NULL"
            + " LEFT JOIN LATERAL (SELECT sum(d.file_size_bytes) AS bytes FROM " + m + "ducklake_delete_file d"
            + " WHERE d.data_file_id = f.data_file_id AND d.end_snapshot IS NULL) del ON true"
            + " WHERE f.end_snapshot IS NULL" + tableMatch(schema, table));

        StringJoiner types = new StringJoiner(", ", "(", ")");

        for (String type : INTEGER_TYPES) {
            types.add(pgLiteral(type));
        }

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

            sql.append(" AND NOT EXISTS (SELECT 1 FROM ").append(m).append("ducklake_file_column_stats st")
                .append(" JOIN ").append(m).append("ducklake_column c ON c.table_id = st.table_id")
                .append(" AND c.column_id = st.column_id AND c.end_snapshot IS NULL")
                .append(" WHERE st.data_file_id = f.data_file_id AND st.table_id = f.table_id")
                .append(" AND lower(c.column_name) = lower(").append(pgLiteral(bound.getKey())).append(")")
                // top-level columns only: a struct field of the same name has its own statistics,
                // and under NOT EXISTS an out-of-range field would rule the file out (Codex review)
                .append(" AND c.parent_column IS NULL")
                .append(" AND c.column_type IN ").append(types)
                .append(" AND CASE WHEN st.min_value ~ '^-?[0-9]+$' AND st.max_value ~ '^-?[0-9]+$'")
                .append(" THEN ").append(String.join(" OR ", outside)).append(" ELSE false END)");
        }

        return sql.toString();
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
