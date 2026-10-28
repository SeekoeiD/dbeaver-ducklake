/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.model.guard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the table scans out of a DuckDB query plan, so the query guard can size them.
 *
 * <p>Two input formats. The guard itself runs {@code EXPLAIN (FORMAT json)} and reads the JSON,
 * because the box-drawing text wraps long table and column names over several lines. The text
 * parser exists for plans a user copies out of an {@code EXPLAIN ANALYZE} result, which is where
 * DuckDB lists the files a scan actually read ({@code Total Files Read}, {@code Filename(s)}); the
 * plain {@code EXPLAIN} the guard runs never has those, because DuckLake only prunes files when the
 * scan starts.
 *
 * <p>Pure Java plus Gson, no DBeaver classes, so it can be unit tested without an OSGi runtime.
 */
public final class QueryGuardPlan {

    /**
     * One scan of a table.
     *
     * @param table             table name as the plan prints it: bare in DuckDB 1.5, qualified
     *                          ({@code "lake.public".main.trades}) in 2.0
     * @param projections       columns the scan reads; empty for {@code count(*)}
     * @param filtered          the scan has pushed-down filters, so DuckDB may skip files and row
     *                          groups and the estimate is an upper bound
     * @param filesRead         "Total Files Read" from an analyzed plan, or null
     * @param fileNames         "Filename(s)" from an analyzed plan, possibly cut short
     * @param fileListTruncated the file list ended with "..."
     * @param limited           only a LIMIT (through projections) sits above an unfiltered scan, so
     *                          DuckDB stops reading after the first rows
     * @param filterText        the scan's filters as printed, or null; DuckDB leaves columns used
     *                          only by a pushed-down filter out of the projections, so the
     *                          estimator looks for column names in here
     */
    public record Scan(
        String table,
        List<String> projections,
        boolean filtered,
        Integer filesRead,
        List<String> fileNames,
        boolean fileListTruncated,
        boolean limited,
        String filterText
    ) {

        public Scan(String table, List<String> projections, boolean filtered, Integer filesRead,
                    List<String> fileNames, boolean fileListTruncated, boolean limited) {
            this(table, projections, filtered, filesRead, fileNames, fileListTruncated, limited, null);
        }
    }

    private static final Pattern KEY_LINE = Pattern.compile("^([A-Za-z][A-Za-z0-9 ()#_]*):\\s*(.*)$");

    private static final Set<String> SCAN_KEYS = Set.of(
        "Table", "Projections", "Filters", "Dynamic Filters", "Total Files Read", "Total Files Skipped",
        "Inlined Tables Read", "Filename(s)", "Estimated Cardinality");

    /** Row counts ("~1,200,001 rows") and timings ("0.10s", "330.0ms") that close a box's entries. */
    private static final Pattern END_LINE = Pattern.compile("^~?[0-9][0-9,]* rows\\b.*|^[0-9.]+\\s*m?s$");

    private QueryGuardPlan() {
    }

    /** True for the operators that read a table: DUCKLAKE_SCAN in plain plans, TABLE_SCAN in analyzed ones. */
    static boolean isScan(String operator) {
        String name = normalize(operator);
        return name.equals("DUCKLAKE_SCAN") || name.equals("TABLE_SCAN");
    }

    private static boolean isLimit(String operator) {
        String name = normalize(operator);
        return name.equals("STREAMING_LIMIT") || name.equals("LIMIT");
    }

    private static String normalize(String operator) {
        return operator == null ? "" : operator.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    // ------------------------------------------------------------------ JSON

    /** Parse the output of {@code EXPLAIN (FORMAT json)}. */
    public static List<Scan> parseJson(String json) {
        List<Scan> scans = new ArrayList<>();
        JsonElement root = JsonParser.parseString(json);

        if (root.isJsonArray()) {
            for (JsonElement node : root.getAsJsonArray()) {
                walk(node, false, scans);
            }
        } else {
            walk(root, false, scans);
        }

        return scans;
    }

    /**
     * The plan's one table scan when the plan is a plain read of one table: a chain of nothing but
     * projections from the root down to a single scan, which is neither filtered nor limited. Null
     * for anything else (aggregate, sort, join, window, DISTINCT, LIMIT, filter, two scans). The
     * query guard offers "Newest file only" for such a plan, where adding a WHERE on the scanned
     * table changes how much is read and nothing else.
     */
    public static Scan plainScan(String json) {
        JsonElement node = JsonParser.parseString(json);

        if (node.isJsonArray()) {
            JsonArray roots = node.getAsJsonArray();

            if (roots.size() != 1) {
                return null;
            }

            node = roots.get(0);
        }

        while (node != null && node.isJsonObject()) {
            JsonObject object = node.getAsJsonObject();
            String name = string(object.has("name") ? object.get("name") : object.get("operator_name"));
            JsonElement children = object.get("children");
            int childCount = children != null && children.isJsonArray() ? children.getAsJsonArray().size() : 0;

            if (isScan(name)) {
                List<Scan> scans = new ArrayList<>();
                walk(node, false, scans);
                return childCount == 0 && scans.size() == 1 && !scans.get(0).filtered() ? scans.get(0) : null;
            }

            if (!normalize(name).equals("PROJECTION") || childCount != 1) {
                return null;
            }

            node = children.getAsJsonArray().get(0);
        }

        return null;
    }

    private static void walk(JsonElement element, boolean underLimit, List<Scan> scans) {
        if (element == null || !element.isJsonObject()) {
            return;
        }

        JsonObject node = element.getAsJsonObject();
        // "name" in EXPLAIN (FORMAT json), "operator_name" in EXPLAIN (ANALYZE, FORMAT json).
        String name = string(node.has("name") ? node.get("name") : node.get("operator_name"));
        JsonObject info = node.has("extra_info") && node.get("extra_info").isJsonObject()
            ? node.getAsJsonObject("extra_info") : new JsonObject();

        if (isScan(name)) {
            boolean filtered = info.has("Filters") || info.has("Dynamic Filters");
            List<String> files = new ArrayList<>();
            boolean truncated = splitFileNames(strings(info.get("Filename(s)")), files);
            scans.add(new Scan(
                string(info.get("Table")),
                strings(info.get("Projections")),
                filtered,
                integer(string(info.get("Total Files Read"))),
                files,
                truncated,
                underLimit && !filtered,
                filtered ? string(info.has("Filters") ? info.get("Filters") : info.get("Dynamic Filters")) : null));
        }

        // A LIMIT only bounds what is read when nothing between it and the scan needs every row,
        // which in practice means projections only.
        boolean childUnderLimit = isLimit(name) || (underLimit && normalize(name).equals("PROJECTION"));

        // ORDER BY ... LIMIT plans use late materialization: a TOP_N over a narrow scan picks the
        // rows, then a semi join on (filename, file_row_number) fetches only those rows from a
        // second, wide scan. That second scan is the join's first child and reads next to nothing.
        String conditions = string(info.get("Conditions"));
        boolean lateMaterialization = normalize(name).endsWith("_JOIN")
            && conditions != null && conditions.contains("file_row_number");
        JsonElement children = node.get("children");

        if (children != null && children.isJsonArray()) {
            JsonArray list = children.getAsJsonArray();

            for (int i = 0; i < list.size(); i++) {
                walk(list.get(i), childUnderLimit || (lateMaterialization && i == 0), scans);
            }
        }
    }

    private static String string(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }

        if (element.isJsonArray()) {
            return String.join(", ", strings(element));
        }

        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }

    private static List<String> strings(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();

        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();

            for (JsonElement item : array) {
                if (item != null && item.isJsonPrimitive()) {
                    result.add(item.getAsString().trim());
                }
            }
        } else if (element.isJsonPrimitive()) {
            for (String line : element.getAsString().split("\n")) {
                if (!line.isBlank()) {
                    result.add(line.trim());
                }
            }
        }

        return result;
    }

    // ------------------------------------------------------------------ text

    /**
     * Parse box-drawing plan text, as printed by {@code EXPLAIN} or {@code EXPLAIN ANALYZE}, in
     * both layouts: DuckDB 1.5's square boxes with the operator name on the first line
     * ({@code DUCKLAKE_SCAN}, {@code TABLE_SCAN}) and DuckDB 2.0's rounded boxes with the name in
     * the top border ({@code ╭─ Ducklake Scan ─╮}). Boxes that sit side by side (joins) are
     * handled; which box is whose child is not reconstructed, so {@link Scan#limited()} is always
     * false here.
     */
    public static List<Scan> parseText(String text) {
        List<Scan> scans = new ArrayList<>();
        String[] lines = text.replace("\r", "").split("\n");

        for (int row = 0; row < lines.length; row++) {
            String line = lines[row];

            for (int col = 0; col < line.length(); col++) {
                char corner = line.charAt(col);

                if (corner != '┌' && corner != '╭') {
                    continue;
                }

                int end = line.indexOf(corner == '┌' ? '┐' : '╮', col);

                if (end < 0) {
                    break;
                }

                List<String> body = boxBody(lines, row, col, end);
                String name = line.substring(col + 1, end).replaceAll("[^A-Za-z0-9_ ]", "").trim();

                if (name.isEmpty() && !body.isEmpty()) {
                    name = body.remove(0).trim();
                }

                if (isScan(name)) {
                    scans.add(scanFromBox(body));
                }

                col = end;
            }
        }

        return scans;
    }

    /** The inside of the box whose top-left corner is at (row, col), one string per line. */
    private static List<String> boxBody(String[] lines, int row, int col, int end) {
        List<String> body = new ArrayList<>();

        for (int r = row + 1; r < lines.length; r++) {
            String line = lines[r];

            if (line.length() <= col) {
                break;
            }

            char border = line.charAt(col);

            if (border != '│' && border != '├') {
                break;
            }

            body.add(line.substring(col + 1, Math.min(end, line.length())));
        }

        return body;
    }

    private static Scan scanFromBox(List<String> body) {
        // Group "Key: value" lines with the lines that continue them, up to the next blank line,
        // row count or timing.
        Map<String, List<String>> entries = new LinkedHashMap<>();
        Set<String> inline = new java.util.HashSet<>();
        List<String> current = null;

        for (String raw : body) {
            String line = raw.trim();

            if (line.isEmpty() || line.chars().allMatch(ch -> ch == '─') || END_LINE.matcher(line).matches()) {
                current = null;
                continue;
            }

            Matcher m = KEY_LINE.matcher(line);

            // A known key always starts a new entry: analyzed scans print "Total Files Read"
            // straight under "Filters", and DuckDB 2.0 never leaves a blank line between keys.
            if (m.matches() && (current == null || SCAN_KEYS.contains(m.group(1).trim()))) {
                String key = m.group(1).trim();
                current = new ArrayList<>();
                entries.put(key, current);

                if (!m.group(2).isEmpty()) {
                    current.add(m.group(2).trim());
                    inline.add(key);
                }
            } else if (current != null) {
                current.add(line);
            }
        }

        // Wrapped values were split mid-word (1.5) or after a comma (2.0), so the lines are glued
        // back without a separator.
        String table = String.join("", entries.getOrDefault("Table", List.of()));
        List<String> files = new ArrayList<>();
        boolean truncated = splitFileNames(
            List.of(String.join("", entries.getOrDefault("Filename(s)", List.of()))), files);
        List<String> projections = new ArrayList<>();

        if (inline.contains("Projections")) {
            // 2.0: "Projections: a, b, c", possibly wrapped.
            for (String column : String.join("", entries.get("Projections")).split(",")) {
                if (!column.isBlank()) {
                    projections.add(column.trim());
                }
            }
        } else {
            // 1.5: "Projections:" and then one column per line.
            projections.addAll(entries.getOrDefault("Projections", List.of()));
        }

        return new Scan(
            table.isEmpty() ? null : table,
            List.copyOf(projections),
            entries.containsKey("Filters") || entries.containsKey("Dynamic Filters"),
            integer(String.join("", entries.getOrDefault("Total Files Read", List.of()))),
            files,
            truncated,
            false,
            filterText(entries));
    }

    private static String filterText(Map<String, List<String>> entries) {
        List<String> parts = new ArrayList<>();
        parts.addAll(entries.getOrDefault("Filters", List.of()));
        parts.addAll(entries.getOrDefault("Dynamic Filters", List.of()));
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    /**
     * Split a comma-separated file list into {@code out}. Returns true when the list ends with
     * "...", DuckDB's way of saying it printed only the first few.
     */
    private static boolean splitFileNames(List<String> parts, List<String> out) {
        boolean truncated = false;

        for (String part : parts) {
            for (String name : part.split(",")) {
                String file = name.trim();

                if (file.isEmpty()) {
                    continue;
                }

                if (file.equals("...") || file.equals("…")) {
                    truncated = true;
                } else {
                    out.add(file);
                }
            }
        }

        return truncated;
    }

    private static Integer integer(String value) {
        if (value == null) {
            return null;
        }

        String digits = value.replace(",", "").trim();

        if (digits.isEmpty()) {
            return null;
        }

        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
