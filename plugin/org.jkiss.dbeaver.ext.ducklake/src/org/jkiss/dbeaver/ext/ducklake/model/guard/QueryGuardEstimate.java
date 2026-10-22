/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.model.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The arithmetic of the query guard: which statements it looks at, which catalog table a scan in
 * the plan refers to, and how many bytes a scan will pull from object storage.
 *
 * <p>The estimate is deliberately simple. A scan reads the files of its table (only the listed ones
 * when the plan names them) times the share of the table's columns it projects, plus the table's
 * delete files. Parquet columns differ in size, filters let DuckDB skip row groups, and an
 * in-process cache may already hold some of the data, so the number is a rough guide to the order of
 * magnitude and never a promise.
 *
 * <p>Pure Java, no DBeaver classes, so it can be unit tested without an OSGi runtime.
 */
public final class QueryGuardEstimate {

    public static final long MB = 1024L * 1024L;

    /** Statement types the guard runs EXPLAIN for. Everything else (SET, ATTACH, COPY, ...) runs untouched. */
    private static final Set<String> GUARDED_KEYWORDS = Set.of("SELECT", "WITH", "FROM", "TABLE", "PIVOT", "UNPIVOT");

    /**
     * A catalog table a scan could refer to: the plan prints only the bare table name.
     *
     * @param catalog     attached DuckLake catalog
     * @param schema      schema inside it
     * @param table       table name
     * @param columnCount number of columns in the table
     */
    public record Candidate(String catalog, String schema, String table, int columnCount, List<String> columnNames) {

        public Candidate(String catalog, String schema, String table, int columnCount) {
            this(catalog, schema, table, columnCount, List.of());
        }

        public String qualifiedName() {
            return catalog + "." + schema + "." + table;
        }
    }

    /**
     * What the catalog knows about a table's files.
     *
     * @param fileCount   number of data files
     * @param dataBytes   their total size
     * @param deleteBytes total size of the delete files that go with them
     * @param fileSizes   size per data file path, or null when not fetched (only needed when the
     *                    plan lists file names)
     */
    public record TableFiles(long fileCount, long dataBytes, long deleteBytes, Map<String, Long> fileSizes) {
    }

    /**
     * The estimate for one scan.
     *
     * @param table      qualified table name
     * @param files      files the scan is expected to open
     * @param bytes      estimated bytes read from object storage
     * @param projected  columns projected
     * @param columns    columns in the table
     * @param upperBound the scan has filters, so DuckDB may read less
     * @param limited    a LIMIT stops the scan early; bytes is 0
     */
    public record ScanEstimate(String table, long files, long bytes, int projected, int columns,
                               boolean upperBound, boolean limited) {
    }

    private QueryGuardEstimate() {
    }

    // ------------------------------------------------------------------ statements

    /**
     * Whether the guard should look at this statement: read queries only. Leading comments and
     * parentheses are skipped; EXPLAIN, SET, ATTACH, USE, CALL, COPY, DDL and DML are not guarded.
     */
    public static boolean isGuardable(String sql) {
        String keyword = firstKeyword(sql);
        return keyword != null && GUARDED_KEYWORDS.contains(keyword);
    }

    public static String firstKeyword(String sql) {
        if (sql == null) {
            return null;
        }

        int i = 0;
        int n = sql.length();

        while (i < n) {
            char c = sql.charAt(i);

            if (Character.isWhitespace(c) || c == '(') {
                i++;
            } else if (sql.startsWith("--", i)) {
                int eol = sql.indexOf('\n', i);
                i = eol < 0 ? n : eol + 1;
            } else if (sql.startsWith("/*", i)) {
                int close = sql.indexOf("*/", i + 2);
                i = close < 0 ? n : close + 2;
            } else {
                break;
            }
        }

        int start = i;

        while (i < n && Character.isLetter(sql.charAt(i))) {
            i++;
        }

        return i > start ? sql.substring(start, i).toUpperCase(Locale.ROOT) : null;
    }

    // ------------------------------------------------------------------ table resolution

    /**
     * Split the table name a plan prints into its parts. DuckDB 1.5 prints the bare name
     * ({@code trades}), DuckDB 2.0 the qualified one ({@code "lake.public".main.trades}); quoted
     * parts may contain dots and doubled quotes.
     */
    public static List<String> splitQualified(String name) {
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);

            if (c == '"') {
                if (quoted && i + 1 < name.length() && name.charAt(i + 1) == '"') {
                    part.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == '.' && !quoted) {
                parts.add(part.toString());
                part.setLength(0);
            } else {
                part.append(c);
            }
        }

        parts.add(part.toString());
        return parts;
    }

    /**
     * Keep the candidates a possibly qualified plan name can refer to: the last part is the table,
     * the one before it the schema, the one before that the catalog.
     */
    public static List<Candidate> matching(List<Candidate> candidates, List<String> nameParts) {
        int n = nameParts.size();
        String schema = n >= 2 ? nameParts.get(n - 2) : null;
        String catalog = n >= 3 ? nameParts.get(n - 3) : null;
        List<Candidate> result = new ArrayList<>();

        for (Candidate c : candidates) {
            if (c.table().equalsIgnoreCase(nameParts.get(n - 1))
                && (schema == null || c.schema().equalsIgnoreCase(schema))
                && (catalog == null || c.catalog().equalsIgnoreCase(catalog))) {
                result.add(c);
            }
        }

        return result;
    }

    /**
     * Pick the table a scan refers to when several attached catalogs hold a table of that name.
     * In order: the one whose qualified name appears in the SQL, the one in the current catalog and
     * schema, the one in the current catalog. Returns null when that still leaves a tie, and the
     * caller then sizes all of them and keeps the largest.
     */
    public static Candidate choose(List<Candidate> candidates, String sql, String currentCatalog, String currentSchema) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        List<Candidate> named = new ArrayList<>();

        for (Candidate c : candidates) {
            // DuckDB also accepts catalog.table, meaning the catalog's default schema.
            if (mentions(sql, c.schema(), c.table()) || mentions(sql, c.catalog(), c.table())
                || mentions(sql, c.catalog(), c.schema(), c.table())) {
                named.add(c);
            }
        }

        // "emea.leads" also matches a catalog "emea" holding a table "leads" in some schema; the
        // fully qualified form wins over that.
        List<Candidate> fully = new ArrayList<>();

        for (Candidate c : named) {
            if (mentions(sql, c.catalog(), c.schema(), c.table())) {
                fully.add(c);
            }
        }

        if (fully.size() == 1) {
            return fully.get(0);
        }

        if (named.size() == 1) {
            return named.get(0);
        }

        // Several tables of this name are named in the SQL (a join across catalogs, say): which
        // one this scan reads is unknown, so let the caller size them all and keep the largest.
        if (named.size() > 1) {
            return null;
        }

        List<Candidate> pool = candidates;
        Candidate inCatalog = null;
        int inCatalogCount = 0;

        for (Candidate c : pool) {
            if (c.catalog().equalsIgnoreCase(currentCatalog)) {
                if (c.schema().equalsIgnoreCase(currentSchema)) {
                    return c;
                }

                inCatalog = c;
                inCatalogCount++;
            }
        }

        return inCatalogCount == 1 ? inCatalog : null;
    }

    /** Whether the SQL names {@code a.b[.c]}, each part optionally double-quoted, case-insensitively. */
    static boolean mentions(String sql, String... parts) {
        StringBuilder regex = new StringBuilder("(?i)(?<![\\w\"])");

        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                regex.append("\\s*\\.\\s*");
            }

            String quoted = Pattern.quote(parts[i]);
            regex.append("(?:").append(quoted).append("|\"").append(quoted).append("\")");
        }

        regex.append("(?![\\w\"])");
        return Pattern.compile(regex.toString()).matcher(sql).find();
    }

    // ------------------------------------------------------------------ bytes

    /** Estimate what one scan reads, given what the catalog says about its table. */
    public static ScanEstimate estimate(QueryGuardPlan.Scan scan, Candidate table, TableFiles files) {
        int columns = Math.max(table.columnCount(), 1);
        int projected = Math.min(readColumns(scan, table), columns);

        if (scan.limited()) {
            return new ScanEstimate(table.qualifiedName(), 0, 0, projected, columns, false, true);
        }

        long fileCount = files.fileCount();
        double dataBytes = files.dataBytes();
        double deleteBytes = files.deleteBytes();

        // DuckDB 2.0 lists the scan's candidate files, skipped ones included ("Total Files Read: 1",
        // "Total Files Skipped: 5", five names): names only count when they are not more than the
        // files read.
        boolean namesUsable = !scan.fileNames().isEmpty() && files.fileSizes() != null
            && (scan.filesRead() == null || scan.filesRead() >= scan.fileNames().size());

        if (namesUsable) {
            long listedBytes = 0;
            int listed = 0;

            for (String name : scan.fileNames()) {
                Long size = sizeOf(files.fileSizes(), name);

                if (size != null) {
                    listedBytes += size;
                    listed++;
                }
            }

            long read = scan.filesRead() != null ? Math.max(scan.filesRead(), listed) : listed;
            long unlisted = read - listed;
            long otherFiles = files.fileCount() - listed;
            double otherAverage = otherFiles > 0 ? (files.dataBytes() - listedBytes) / (double) otherFiles : 0;

            if (listed > 0 || scan.fileListTruncated()) {
                double share = files.fileCount() > 0 ? read / (double) files.fileCount() : 1;
                fileCount = read;
                dataBytes = listedBytes + unlisted * Math.max(otherAverage, 0);
                deleteBytes = deleteBytes * Math.min(share, 1);
            }
        } else if (scan.filesRead() != null && files.fileCount() > 0) {
            double share = Math.min(scan.filesRead() / (double) files.fileCount(), 1);
            fileCount = scan.filesRead();
            dataBytes = dataBytes * share;
            deleteBytes = deleteBytes * share;
        }

        double fraction = projected / (double) columns;
        long bytes = Math.round(dataBytes * fraction + deleteBytes);

        return new ScanEstimate(table.qualifiedName(), fileCount, bytes, projected, columns, scan.filtered(), false);
    }

    /**
     * Columns the scan reads: its projections plus the columns its filters test, which DuckDB
     * leaves out of the projections when nothing above the scan needs them
     * ({@code SELECT count(*) ... WHERE v = 3} projects nothing). Without the table's column names a
     * filtered scan counts at least one column.
     */
    static int readColumns(QueryGuardPlan.Scan scan, Candidate table) {
        Set<String> read = new java.util.HashSet<>();

        for (String p : scan.projections()) {
            read.add(p.toLowerCase(Locale.ROOT));
        }

        String filters = scan.filterText();

        if (filters != null) {
            int before = read.size();

            for (String column : table.columnNames()) {
                if (!read.contains(column.toLowerCase(Locale.ROOT)) && mentions(filters, column)) {
                    read.add(column.toLowerCase(Locale.ROOT));
                }
            }

            if (read.isEmpty() && before == 0) {
                return 1;
            }
        }

        return read.size();
    }

    /** Look a plan file name up in the catalog listing; either side may be a suffix of the other. */
    private static Long sizeOf(Map<String, Long> sizes, String name) {
        Long exact = sizes.get(name);

        if (exact != null) {
            return exact;
        }

        for (Map.Entry<String, Long> e : sizes.entrySet()) {
            if (e.getKey().endsWith(name) || name.endsWith(e.getKey())) {
                return e.getValue();
            }
        }

        return null;
    }

    public static long total(List<ScanEstimate> estimates) {
        long sum = 0;

        for (ScanEstimate e : estimates) {
            sum += e.bytes();
        }

        return sum;
    }

    // ------------------------------------------------------------------ text

    /** "552 MB", "4.2 MB", "0.3 MB", "12 KB". */
    public static String formatBytes(long bytes) {
        if (bytes <= 0) {
            return "0 KB";
        }

        if (bytes < MB / 10) {
            return Math.max(1, Math.round(bytes / 1024.0)) + " KB";
        }

        double mb = bytes / (double) MB;

        if (mb >= 1024 * 10) {
            return String.format(Locale.ROOT, "%.0f GB", mb / 1024);
        }

        if (mb >= 1024) {
            return String.format(Locale.ROOT, "%.1f GB", mb / 1024);
        }

        return mb >= 10 ? String.format(Locale.ROOT, "%.0f MB", mb) : String.format(Locale.ROOT, "%.1f MB", mb);
    }

    /** The body of the confirmation dialog. */
    public static String dialogMessage(List<ScanEstimate> estimates, long thresholdBytes) {
        StringBuilder sb = new StringBuilder();
        long total = total(estimates);
        boolean anyUpper = false;

        sb.append("This query will read about ").append(formatBytes(total))
            .append(" from object storage (warning threshold ").append(formatBytes(thresholdBytes)).append(").\n\n");

        for (ScanEstimate e : estimates) {
            sb.append("  ").append(e.table()).append(": ");

            if (e.limited()) {
                sb.append("stops early (LIMIT)\n");
                continue;
            }

            sb.append(e.files()).append(e.files() == 1 ? " file, " : " files, ")
                .append(e.upperBound() ? "up to " : "~").append(formatBytes(e.bytes()))
                .append(" (").append(e.projected()).append(" of ").append(e.columns()).append(" columns)\n");
            anyUpper |= e.upperBound();
        }

        sb.append('\n');

        if (anyUpper) {
            sb.append("Filters may let DuckDB skip files and row groups, so filtered scans read less.\n");
        }

        sb.append("DuckDB's in-process cache may already hold some of this data. Catalog round trips"
            + " and inlined rows are not counted.");
        return sb.toString();
    }

    /** The line the output panel gets after a statement ran. */
    public static String measurementMessage(long requests, long bytes, double seconds) {
        String time = String.format(Locale.ROOT, "%.2f s", seconds);

        if (requests == 0) {
            return "Query guard: nothing read from object storage (cache or catalog only) in " + time;
        }

        return "Query guard: read " + formatBytes(bytes) + " from object storage (" + requests
            + (requests == 1 ? " request" : " requests") + ") in " + time;
    }
}
