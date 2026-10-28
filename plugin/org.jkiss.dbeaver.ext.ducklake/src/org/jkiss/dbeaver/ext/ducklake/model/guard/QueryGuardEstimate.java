/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.model.guard;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
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
 * <p>Two refinements use the catalog beyond the file list. A scan whose filter bounds an integer
 * column ({@code height BETWEEN a AND b}, {@code id = n}) counts only the files whose
 * min/max statistics overlap the bounds, which is the pruning DuckLake itself does before it opens
 * a file. And a scan that a LIMIT stops early counts what such a scan was measured to read on
 * DuckDB 1.5.4: the footer of every file plus the first rows of one file (see {@link #estimate}).
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
     * @param footerBytes what reading every data file's Parquet footer costs: the catalog's
     *                    {@code data_file_footer_size} plus the 8-byte trailer, per file. Zero for
     *                    files the catalog has no footer size for.
     * @param largestFile size of the largest data file
     */
    public record TableFiles(long fileCount, long dataBytes, long deleteBytes, Map<String, Long> fileSizes,
                             long footerBytes, long largestFile) {

        /** Without footer sizes; the largest file is taken to be the mean one. */
        public TableFiles(long fileCount, long dataBytes, long deleteBytes, Map<String, Long> fileSizes) {
            this(fileCount, dataBytes, deleteBytes, fileSizes, 0, fileCount > 0 ? (dataBytes + fileCount - 1) / fileCount : 0);
        }
    }

    /**
     * Bytes of the Parquet trailer DuckDB fetches with each footer: the 4-byte footer length and
     * the {@code PAR1} magic. Measured on the demo stack: the footer GETs were 851 bytes for an
     * 843-byte {@code data_file_footer_size}.
     */
    static final long FOOTER_TRAILER = 8;

    /**
     * The estimate for one scan.
     *
     * @param table       qualified table name
     * @param files       files the scan is expected to open
     * @param bytes       estimated bytes read from object storage
     * @param projected   columns projected
     * @param columns     columns in the table
     * @param upperBound  the scan has filters, so DuckDB may read less
     * @param limited     a LIMIT stops the scan early: bytes is every file's footer plus the first
     *                    rows of one file
     * @param footerBytes the part of {@code bytes} that is footers (limited scans only)
     * @param tableFiles  data files in the whole table; more than {@code files} when the catalog's
     *                    min/max statistics ruled files out
     */
    public record ScanEstimate(String table, long files, long bytes, int projected, int columns,
                               boolean upperBound, boolean limited, long footerBytes, long tableFiles) {

        public ScanEstimate(String table, long files, long bytes, int projected, int columns,
                            boolean upperBound, boolean limited) {
            this(table, files, bytes, projected, columns, upperBound, limited, 0, files);
        }

        /** Files ruled out by the catalog's min/max statistics before the scan starts. */
        public boolean pruned() {
            return !limited && tableFiles > files;
        }
    }

    /**
     * Closed integer range a pushed-down filter puts on one column; a null end is open. Built from
     * the plan's filter text by {@link #filterBounds}.
     */
    public record Range(BigInteger low, BigInteger high) {

        Range intersect(Range other) {
            BigInteger lo = low == null ? other.low : other.low == null ? low : low.max(other.low);
            BigInteger hi = high == null ? other.high : other.high == null ? high : high.min(other.high);
            return new Range(lo, hi);
        }
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

        // A qualified mention says which table that mention reads, not which one this scan reads.
        // When the SQL also names the table unqualified ("small.main.events JOIN events"), DuckDB
        // 1.5 prints "events" for both scans, so neither can be pinned: size them all.
        if (!named.isEmpty() && mentionsBare(sql, candidates.get(0).table())) {
            return null;
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

    /**
     * Whether the SQL names the table on its own, not as the last part of a qualified name and
     * not as the qualifier of a column ({@code events.id}).
     */
    static boolean mentionsBare(String sql, String table) {
        String quoted = Pattern.quote(table);
        java.util.regex.Matcher m = Pattern.compile(
            "(?i)(?<![\\w\"])(?:" + quoted + "|\"" + quoted + "\")(?![\\w\"])").matcher(sql);

        while (m.find()) {
            int before = m.start() - 1;

            while (before >= 0 && Character.isWhitespace(sql.charAt(before))) {
                before--;
            }

            int after = m.end();

            while (after < sql.length() && Character.isWhitespace(sql.charAt(after))) {
                after++;
            }

            boolean qualified = before >= 0 && sql.charAt(before) == '.';
            boolean qualifier = after < sql.length() && sql.charAt(after) == '.';

            if (!qualified && !qualifier) {
                return true;
            }
        }

        return false;
    }

    // ------------------------------------------------------------------ bytes

    /** Estimate what one scan reads, given what the catalog says about its table. */
    public static ScanEstimate estimate(QueryGuardPlan.Scan scan, Candidate table, TableFiles files) {
        return estimate(scan, table, files, null);
    }

    /**
     * Estimate what one scan reads.
     *
     * <p>A scan a LIMIT stops early ({@link QueryGuardPlan.Scan#limited()}: nothing but projections
     * between it and the LIMIT, no filter) is counted as the footer of every file plus the projected
     * columns of the largest file. Measured on the demo stack with DuckDB 1.5.4 (cold instance, the
     * guard's HTTP log), {@code SELECT * FROM t LIMIT 200} read:
     * <ul>
     *     <li>40 files of 2.5 MB, one row group each: 41 GETs, 2,602,340 bytes, which is the 40
     *     footers (34,040 bytes) plus all of one file;</li>
     *     <li>300 files of 140 KB: 301 GETs, 295,270 bytes;</li>
     *     <li>20 files of 14 MB, 12 row groups each: 21 GETs, 1,246,807 bytes, the 20 footers plus
     *     one row group (1.1 MB). The largest file overstates that by about 12 times.</li>
     * </ul>
     * The same at 1, 4 and 32 threads, so the thread count plays no part: DuckDB opens every file of
     * the table to read its footer and then reads only as many row groups as the LIMIT needs. On a
     * table of thousands of files that is thousands of small requests, which is why the footers
     * are counted and not dropped as small.
     *
     * @param pruned the files left after the catalog's min/max statistics ruled some out for the
     *               scan's filter, or null when nothing was ruled out
     */
    public static ScanEstimate estimate(QueryGuardPlan.Scan scan, Candidate table, TableFiles files, TableFiles pruned) {
        int columns = Math.max(table.columnCount(), 1);
        int projected = Math.min(readColumns(scan, table), columns);

        if (scan.limited()) {
            long data = 0;

            if (projected > 0 && files.fileCount() > 0) {
                data = Math.round(files.largestFile() * (projected / (double) columns)
                    + files.deleteBytes() / (double) files.fileCount());
            }

            return new ScanEstimate(table.qualifiedName(), files.fileCount(), files.footerBytes() + data, projected, columns,
                false, true, files.footerBytes(), files.fileCount());
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
        } else if (pruned != null && pruned.fileCount() < files.fileCount()) {
            fileCount = pruned.fileCount();
            dataBytes = pruned.dataBytes();
            deleteBytes = pruned.deleteBytes();
        }

        double fraction = projected / (double) columns;
        long bytes = Math.round(dataBytes * fraction + deleteBytes);

        return new ScanEstimate(table.qualifiedName(), fileCount, bytes, projected, columns, scan.filtered(), false,
            0, files.fileCount());
    }

    /**
     * What a plain scan reads once the "Newest file only" condition is added: the scan as if it
     * had the filter {@code column BETWEEN min AND max}, over the files that the catalog's min/max
     * statistics leave for that bound ({@code CatalogStats.prunedFiles}).
     */
    public static ScanEstimate newestFileEstimate(QueryGuardPlan.Scan scan, Candidate table,
                                                  NewestFileFilter.Choice choice, TableFiles pruned) {
        String filter = choice.column() + ">=" + choice.min() + " AND " + choice.column() + "<=" + choice.max();
        QueryGuardPlan.Scan bounded = new QueryGuardPlan.Scan(scan.table(), scan.projections(), true, null, List.of(),
            false, false, filter);
        return estimate(bounded, table, pruned, null);
    }

    /**
     * Count each table's footers once. A {@code SELECT * ... LIMIT n} plan has two limited scans of
     * the same table (late materialization: a row-id scan under the LIMIT and a wide scan that
     * fetches the chosen rows), and DuckDB reads each footer once for both: the measurement above
     * saw one footer GET per file. The first scan of a table keeps its footers, later ones drop them.
     */
    public static List<ScanEstimate> countFootersOnce(List<ScanEstimate> estimates) {
        Set<String> seen = new java.util.HashSet<>();
        List<ScanEstimate> result = new ArrayList<>();

        for (ScanEstimate e : estimates) {
            if (e.limited() && e.footerBytes() > 0 && !seen.add(e.table().toLowerCase(Locale.ROOT))) {
                e = new ScanEstimate(e.table(), e.files(), e.bytes() - e.footerBytes(), e.projected(), e.columns(),
                    e.upperBound(), true, 0, e.tableFiles());
            }

            result.add(e);
        }

        return result;
    }

    // ------------------------------------------------------------------ filter bounds

    /**
     * A whole conjunct that is {@code column op integer}, as DuckDB prints a pushed-down filter:
     * {@code height>=975000} in 1.5, {@code (height >= 975000)} in 2.0 (the parentheses are taken
     * off first), the column possibly quoted. Matched against the entire conjunct, never searched
     * for inside one: {@code (height = 5) IS NOT DISTINCT FROM false} (what 1.5.4 pushes down for
     * {@code WHERE (height = 5) IS FALSE}), a CASE or COALESCE around the comparison, a NOT, an OR,
     * a cast on the column side or a decimal constant all leave the conjunct unmatched, and an
     * unmatched conjunct is ignored, which only makes the bound looser. Reading a bound out of such
     * a conjunct would rule files out that hold matching rows and hide a full read behind a small
     * estimate (found by a local Codex review, two rounds).
     */
    private static final Pattern BOUND = Pattern.compile(
        "\\s*\"?([A-Za-z_][A-Za-z0-9_]*)\"?\\s*(>=|<=|=|>|<)\\s*(-?[0-9]+)\\s*");

    /**
     * A single-quoted SQL string, doubled quotes included. Blanked out before parsing, so that
     * {@code contains(payload, 'height=5')} holds no comparison and no AND or parenthesis inside a
     * literal can split or unbalance a conjunct.
     */
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");

    /**
     * The integer ranges a scan's filter text puts on its columns, keyed by lower-case column name.
     * The text, with the parentheses around the whole of it taken off, is split into its top-level
     * AND conjuncts, each of which must hold for every row, and each conjunct is unwrapped and split
     * the same way ({@code ((a) AND (b))} is how 1.5.4 prints a wrapped conjunction). Only a conjunct
     * that is nothing but one comparison ({@link #BOUND}) narrows its column. Empty when no conjunct
     * is one.
     */
    public static Map<String, Range> filterBounds(String filterText) {
        Map<String, Range> bounds = new LinkedHashMap<>();

        if (filterText != null) {
            collectBounds(STRING_LITERAL.matcher(filterText).replaceAll(" "), bounds);
        }

        return bounds;
    }

    private static void collectBounds(String text, Map<String, Range> bounds) {
        String bare = unwrap(text);
        List<String> parts = conjuncts(bare);

        if (parts.size() > 1) {
            for (String part : parts) {
                collectBounds(part, bounds);
            }

            return;
        }

        Matcher m = BOUND.matcher(bare);

        if (!m.matches()) {
            return;
        }

        BigInteger value = new BigInteger(m.group(3));
        Range range = switch (m.group(2)) {
            case "=" -> new Range(value, value);
            case ">=" -> new Range(value, null);
            case ">" -> new Range(value.add(BigInteger.ONE), null);
            case "<=" -> new Range(null, value);
            default -> new Range(null, value.subtract(BigInteger.ONE));
        };
        bounds.merge(m.group(1).toLowerCase(Locale.ROOT), range, Range::intersect);
    }

    /**
     * The text split at the ANDs outside every parenthesis; an AND inside parentheses stays put. A
     * comma outside every parenthesis splits too: a plan that lists several pushed-down filters as a
     * JSON array reaches here joined with ", " ({@code QueryGuardPlan.string}), each entry a required
     * filter of its own, and no comparison has a bare comma (those sit inside IN (...) or a CAST).
     */
    static List<String> conjuncts(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && c == ',') {
                parts.add(text.substring(start, i));
                start = i + 1;
            } else if (depth == 0 && text.regionMatches(true, i, "AND", 0, 3)
                && (i == 0 || !isWordChar(text.charAt(i - 1)))
                && (i + 3 >= text.length() || !isWordChar(text.charAt(i + 3)))) {
                parts.add(text.substring(start, i));
                start = i + 3;
                i += 2;
            }
        }

        parts.add(text.substring(start));
        return parts;
    }

    /** {@code ((x))} as {@code x}: parentheses that wrap the whole conjunct, however many. */
    static String unwrap(String conjunct) {
        String s = conjunct.trim();

        while (s.length() >= 2 && s.charAt(0) == '(' && s.charAt(s.length() - 1) == ')' && closesAtEnd(s)) {
            s = s.substring(1, s.length() - 1).trim();
        }

        return s;
    }

    /** Whether the opening parenthesis at index 0 is closed by the last character and not earlier. */
    private static boolean closesAtEnd(String s) {
        int depth = 0;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;

                if (depth == 0) {
                    return i == s.length() - 1;
                }
            }
        }

        return false;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '"';
    }

    /** A catalog statistic as an integer, or null when it is missing or not an integer. */
    public static BigInteger integer(String value) {
        if (value == null || !value.trim().matches("-?[0-9]+")) {
            return null;
        }

        return new BigInteger(value.trim());
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
        return dialogMessage(estimates, thresholdBytes, null);
    }

    /**
     * The body of the confirmation dialog, with the "Newest file only" choice's own line after the
     * scans when the statement has one.
     *
     * @param newestFileLine {@link #newestFileLine}, or null
     */
    public static String dialogMessage(List<ScanEstimate> estimates, long thresholdBytes, String newestFileLine) {
        StringBuilder sb = new StringBuilder();
        long total = total(estimates);
        boolean anyUpper = false;
        boolean anyLimited = false;

        sb.append("This query will read about ").append(formatBytes(total))
            .append(" from object storage (warning threshold ").append(formatBytes(thresholdBytes)).append(").\n\n");

        for (ScanEstimate e : estimates) {
            sb.append("  ").append(e.table()).append(": ").append(scanLine(e)).append('\n');
            anyUpper |= e.upperBound();
            anyLimited |= e.limited();
        }

        sb.append('\n');

        if (newestFileLine != null) {
            sb.append(newestFileLine).append("\n\n");
        }

        if (anyLimited) {
            sb.append("The LIMIT estimate assumes the LIMIT stops the scan early: DuckDB still opens every file"
                + " of the table for its footer, one request each, then reads only the first rows.\n");
        }

        if (anyUpper) {
            sb.append("Filters may let DuckDB skip files and row groups, so filtered scans read less.\n");
        }

        sb.append("DuckDB's in-process cache may already hold some of this data. Catalog round trips"
            + " and inlined rows are not counted.");
        return sb.toString();
    }

    /**
     * The dialog's line for the "Newest file only" choice, e.g.
     * {@code Newest file only: height BETWEEN 3203188 AND 3203190 (1 file, ~4.2 MB)}.
     *
     * @param where    the added condition without the table qualifier
     * @param estimate the scan sized for that condition by the catalog's min/max statistics
     */
    public static String newestFileLine(String where, ScanEstimate estimate) {
        return "Newest file only: " + where + " (" + estimate.files() + (estimate.files() == 1 ? " file" : " files")
            + ", ~" + formatBytes(estimate.bytes()) + ")";
    }

    /** One scan's line in the dialog, after the table name. */
    static String scanLine(ScanEstimate e) {
        String columns = " (" + e.projected() + " of " + e.columns() + " columns)";

        if (e.limited()) {
            String files = e.files() + (e.files() == 1 ? " file" : " files");

            if (e.footerBytes() == 0 && e.bytes() == 0) {
                return files + ", stops early (LIMIT), counted with the other scan of this table";
            }

            return files + ", ~" + formatBytes(e.bytes()) + ", stops early (LIMIT): every file's footer"
                + (e.projected() > 0 ? " plus the first rows" + columns : "");
        }

        String files = e.pruned()
            ? e.files() + " of " + e.tableFiles() + " files after the catalog's min/max statistics, "
            : e.files() + (e.files() == 1 ? " file, " : " files, ");
        return files + (e.upperBound() ? "up to " : "~") + formatBytes(e.bytes()) + columns;
    }

    /**
     * The Output panel line with the estimate, so it sits next to the measured line that follows
     * the query and the two can be compared.
     */
    public static String estimateMessage(List<ScanEstimate> estimates) {
        StringBuilder sb = new StringBuilder("Query guard: estimated ").append(formatBytes(total(estimates)));
        List<String> notes = new ArrayList<>();

        for (ScanEstimate e : estimates) {
            if (e.limited() && e.footerBytes() > 0) {
                notes.add(e.table() + ": LIMIT assumed to stop the scan early, footers of " + e.files()
                    + (e.files() == 1 ? " file" : " files") + " plus the first rows");
            } else if (e.pruned()) {
                notes.add(e.table() + ": " + e.files() + " of " + e.tableFiles() + " files after min/max statistics");
            }
        }

        if (!notes.isEmpty()) {
            sb.append(" (").append(String.join("; ", notes)).append(')');
        }

        return sb.toString();
    }

    // ------------------------------------------------------------------ measurement

    /**
     * Value the guard puts in {@code disabled_log_types} when it switches HTTP logging on. Logging
     * settings are global to a DuckDB instance; the marker tells every connection of the instance
     * that the log is the guard's. {@code CALL enable_logging(...)} clears it, so a user who sets
     * up logging takes the log over.
     */
    public static final String LOGGING_MARKER = "DuckLakeQueryGuard";

    /** Who runs a DuckDB instance's logging. */
    public enum LoggingState { OFF, GUARD, USER }

    public static LoggingState loggingState(boolean enabled, String enabledTypes, String disabledTypes) {
        if (!enabled) {
            return LoggingState.OFF;
        }

        boolean httpOnly = "HTTP".equalsIgnoreCase(String.valueOf(enabledTypes).trim());
        boolean marked = LOGGING_MARKER.equals(String.valueOf(disabledTypes).trim());
        return httpOnly && marked ? LoggingState.GUARD : LoggingState.USER;
    }

    /**
     * Requests and GET bytes one connection logged since a point in time. Other connections of the
     * same DuckDB instance write to the same log, so the connection id keeps their traffic out.
     */
    public static String httpStatsSql(long connectionId, long sinceEpochMicros) {
        return "SELECT count(*), coalesce(sum(TRY_CAST(coalesce(response.headers['content-length'],"
            + " response.headers['Content-Length']) AS BIGINT)) FILTER (WHERE request.type = 'GET'), 0)"
            + " FROM duckdb_logs_parsed('HTTP')"
            + " WHERE connection_id = " + connectionId + " AND epoch_us(timestamp) >= " + sinceEpochMicros;
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
