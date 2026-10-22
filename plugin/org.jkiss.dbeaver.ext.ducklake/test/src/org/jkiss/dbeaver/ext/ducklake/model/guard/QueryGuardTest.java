/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.model.guard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for the query guard's plan parser and estimator. No JUnit: the repo has no Maven or
 * Tycho build, so this is a plain main() that {@code test.ps1} compiles and runs against the
 * DBeaver install's jars (only Gson is needed at run time). Exits non-zero on the first failure.
 *
 * <p>The plans under {@code test/resources} were captured against a local DuckLake on an
 * S3-compatible store, with a 6-file {@code transfers} table plus one inlined row; files without a
 * prefix come from DuckDB 1.5.4, {@code v2-*} from DuckDB 2.0.0 (which DBeaver's driver resolution
 * may pick for the plugin's JDBC driver). {@code *analyze-filtered.txt} is a filtered scan that
 * still reads all 6 files ("Total Files Read: 6", "Inlined Tables Read: 1") and whose file list
 * DuckDB cut short with "...", {@code *analyze-groupby.txt} an unfiltered GROUP BY.
 */
public final class QueryGuardTest {

    private static Path resources;
    private static int passed;

    /** The six data files of the captured {@code transfers} table, as {@code ducklake_list_files} returns them. */
    private static final Map<String, Long> TRANSFER_FILES = new LinkedHashMap<>();

    static {
        String prefix = "s3://lake/data/main/transfers/ducklake-01a16391-";
        TRANSFER_FILES.put(prefix + "d3d0-7372-bbe4-2efbb4516f29.parquet", 16_659_431L);
        TRANSFER_FILES.put(prefix + "d526-71b8-9caa-a085dadaf85f.parquet", 16_658_778L);
        TRANSFER_FILES.put(prefix + "d638-7c29-84f2-a0fa3c5b3c25.parquet", 16_659_265L);
        TRANSFER_FILES.put(prefix + "d784-738d-b786-2dfb60083b22.parquet", 16_656_726L);
        TRANSFER_FILES.put(prefix + "d8b8-74a6-b027-361eeb93e85c.parquet", 16_657_331L);
        TRANSFER_FILES.put(prefix + "d9e9-7c9e-a220-bceb995301dc.parquet", 16_657_148L);
    }

    private static final long TRANSFER_BYTES = 99_948_679L;

    private static final QueryGuardEstimate.Candidate TRANSFERS =
        new QueryGuardEstimate.Candidate("lake", "main", "transfers", 6);

    public static void main(String[] args) throws Exception {
        resources = Path.of(args.length > 0 ? args[0] : "test/resources");

        textFilteredAnalyzedScan();
        textGroupByAnalyzedScan();
        textPlainExplain();
        textSideBySideScans();
        jsonGroupBy();
        jsonJoin();
        jsonLimit();
        jsonTopNLateMaterialization();
        jsonCountStar();
        jsonAnalyzed();
        v2TextFilteredAnalyzedScan();
        v2TextGroupByAnalyzedScan();
        v2TextPlainExplain();
        v2TextJoinSkippedFiles();
        v2JsonQualifiedNames();
        qualifiedNames();
        estimateFractionOfColumns();
        estimateListedFilesWithTruncation();
        estimateFilesReadWithoutNames();
        estimateLimitedScan();
        estimateDeleteFiles();
        chooseCandidate();
        loggingOwnership();
        filterOnlyColumns();
        guardableStatements();
        formatting();

        System.out.println("OK, " + passed + " checks passed");
    }

    // ------------------------------------------------------------------ text plans

    private static void textFilteredAnalyzedScan() throws IOException {
        List<QueryGuardPlan.Scan> scans = QueryGuardPlan.parseText(read("analyze-filtered.txt"));
        check(scans.size() == 1, "filtered: one scan, got " + scans.size());
        QueryGuardPlan.Scan scan = scans.get(0);
        check("transfers".equals(scan.table()), "filtered: table " + scan.table());
        check(scan.projections().equals(List.of("id", "amount")), "filtered: projections " + scan.projections());
        check(scan.filtered(), "filtered: has filters");
        check(Integer.valueOf(6).equals(scan.filesRead()), "filtered: files read " + scan.filesRead());
        check(scan.fileNames().size() == 5, "filtered: 5 listed files, got " + scan.fileNames());
        check(scan.fileListTruncated(), "filtered: list ends with ...");
        check(TRANSFER_FILES.containsKey(scan.fileNames().get(0)), "filtered: first file name " + scan.fileNames().get(0));
        check(TRANSFER_FILES.containsKey(scan.fileNames().get(4)), "filtered: last file name " + scan.fileNames().get(4));
        check(!scan.limited(), "filtered: not limited");
    }

    private static void textGroupByAnalyzedScan() throws IOException {
        List<QueryGuardPlan.Scan> scans = QueryGuardPlan.parseText(read("analyze-groupby.txt"));
        check(scans.size() == 1, "group by: one scan, got " + scans.size());
        QueryGuardPlan.Scan scan = scans.get(0);
        check("transfers".equals(scan.table()), "group by: table " + scan.table());
        check(scan.projections().equals(List.of("asset", "amount")), "group by: projections " + scan.projections());
        check(!scan.filtered(), "group by: no filters");
        check(Integer.valueOf(6).equals(scan.filesRead()), "group by: files read " + scan.filesRead());
        check(scan.fileListTruncated(), "group by: list ends with ...");
    }

    private static void textPlainExplain() throws IOException {
        List<QueryGuardPlan.Scan> scans = QueryGuardPlan.parseText(read("explain-groupby.txt"));
        check(scans.size() == 1, "explain: one scan");
        QueryGuardPlan.Scan scan = scans.get(0);
        check("transfers".equals(scan.table()), "explain: table");
        check(scan.projections().size() == 2, "explain: projections " + scan.projections());
        check(scan.filesRead() == null && scan.fileNames().isEmpty(), "explain: no file info before execution");
    }

    private static void textSideBySideScans() throws IOException {
        List<QueryGuardPlan.Scan> scans = QueryGuardPlan.parseText(read("explain-join.txt"));
        check(scans.size() == 2, "join text: two scans, got " + scans.size());
        check("transfers".equals(scans.get(0).table()) && "small".equals(scans.get(1).table()),
            "join text: tables " + scans.get(0).table() + ", " + scans.get(1).table());
        check(scans.get(0).projections().equals(List.of("id", "asset")), "join text: left projections " + scans.get(0).projections());
        check(scans.get(1).projections().equals(List.of("id", "name")), "join text: right projections " + scans.get(1).projections());
    }

    // ------------------------------------------------------------------ JSON plans

    private static void jsonGroupBy() throws IOException {
        List<QueryGuardPlan.Scan> scans = QueryGuardPlan.parseJson(read("groupby.json"));
        check(scans.size() == 1, "json group by: one scan");
        check(scans.get(0).projections().equals(List.of("asset", "amount")), "json group by: projections");
        check(!scans.get(0).limited() && !scans.get(0).filtered(), "json group by: full scan");
    }

    private static void jsonJoin() throws IOException {
        List<QueryGuardPlan.Scan> scans = QueryGuardPlan.parseJson(read("join.json"));
        check(scans.size() == 2, "json join: two scans");
        check("small".equals(scans.get(1).table()), "json join: second table");
    }

    private static void jsonLimit() throws IOException {
        // SELECT * ... LIMIT 200 is planned as late materialization too: a row-id-only scan under
        // the LIMIT picks the rows, a semi join fetches them from the wide scan. Both stop early.
        List<QueryGuardPlan.Scan> plain = QueryGuardPlan.parseJson(read("limit.json"));
        check(plain.size() == 2 && plain.get(0).limited() && plain.get(1).limited(),
            "json limit: scans under LIMIT stop early");
        check(plain.get(1).projections().isEmpty(), "json limit: the row-id scan projects nothing");

        List<QueryGuardPlan.Scan> filtered = QueryGuardPlan.parseJson(read("filtered-limit.json"));
        check(filtered.size() == 1 && !filtered.get(0).limited() && filtered.get(0).filtered(),
            "json limit: a filtered scan under LIMIT may still read everything");
    }

    private static void jsonTopNLateMaterialization() throws IOException {
        List<QueryGuardPlan.Scan> scans = QueryGuardPlan.parseJson(read("topn.json"));
        check(scans.size() == 2, "json top-n: two scans, got " + scans.size());
        check(scans.get(0).limited() && scans.get(0).projections().size() == 6,
            "json top-n: the wide late-materialization scan reads only the chosen rows");
        check(!scans.get(1).limited() && scans.get(1).projections().equals(List.of("ts")),
            "json top-n: the narrow ORDER BY scan reads the whole column");
    }

    private static void jsonCountStar() throws IOException {
        check(QueryGuardPlan.parseJson(read("count.json")).isEmpty(), "json count(*): answered from metadata, no scan");
    }

    private static void jsonAnalyzed() throws IOException {
        List<QueryGuardPlan.Scan> scans = QueryGuardPlan.parseJson(read("analyze.json"));
        check(scans.size() == 1, "json analyzed: one scan, got " + scans.size());
        QueryGuardPlan.Scan scan = scans.get(0);
        check(Integer.valueOf(6).equals(scan.filesRead()), "json analyzed: files read");
        check(scan.fileNames().size() == 5 && scan.fileListTruncated(), "json analyzed: 5 names and ...");
    }

    // ------------------------------------------------------------------ DuckDB 2.0 plans

    /** The same filtered scan as {@link #textFilteredAnalyzedScan}, in 2.0's rounded-box layout. */
    private static void v2TextFilteredAnalyzedScan() throws IOException {
        List<QueryGuardPlan.Scan> scans = QueryGuardPlan.parseText(read("v2-analyze-filtered.txt"));
        check(scans.size() == 1, "v2 filtered: one scan, got " + scans.size());
        QueryGuardPlan.Scan scan = scans.get(0);
        check("lake.main.transfers".equals(scan.table()), "v2 filtered: table " + scan.table());
        check(scan.projections().equals(List.of("id", "amount")), "v2 filtered: projections " + scan.projections());
        check(scan.filtered(), "v2 filtered: has filters");
        check(Integer.valueOf(6).equals(scan.filesRead()), "v2 filtered: files read " + scan.filesRead());
        check(scan.fileNames().size() == 5 && scan.fileListTruncated(), "v2 filtered: 5 names and ... " + scan.fileNames());
        check(TRANSFER_FILES.keySet().containsAll(scan.fileNames()), "v2 filtered: names match the catalog " + scan.fileNames());

        QueryGuardEstimate.ScanEstimate e = QueryGuardEstimate.estimate(scan, TRANSFERS,
            new QueryGuardEstimate.TableFiles(6, TRANSFER_BYTES, 0, TRANSFER_FILES));
        check(e.files() == 6 && Math.abs(e.bytes() - TRANSFER_BYTES * 2 / 6.0) < 2, "v2 filtered: estimate " + e);
    }

    private static void v2TextGroupByAnalyzedScan() throws IOException {
        List<QueryGuardPlan.Scan> scans = QueryGuardPlan.parseText(read("v2-analyze-groupby.txt"));
        check(scans.size() == 1, "v2 group by: one scan, got " + scans.size());
        QueryGuardPlan.Scan scan = scans.get(0);
        check(scan.projections().equals(List.of("asset", "amount")), "v2 group by: projections " + scan.projections());
        check(!scan.filtered(), "v2 group by: no filters");
        check(Integer.valueOf(6).equals(scan.filesRead()), "v2 group by: files read");
        check(scan.fileNames().size() == 5 && scan.fileListTruncated(), "v2 group by: 5 names and ...");
    }

    private static void v2TextPlainExplain() throws IOException {
        List<QueryGuardPlan.Scan> scans = QueryGuardPlan.parseText(read("v2-explain-groupby.txt"));
        check(scans.size() == 1 && "lake.main.transfers".equals(scans.get(0).table()), "v2 explain: Ducklake Scan box");
        check(scans.get(0).projections().equals(List.of("asset", "amount")), "v2 explain: projections");

        List<QueryGuardPlan.Scan> join = QueryGuardPlan.parseText(read("v2-explain-join.txt"));
        check(join.size() == 2, "v2 explain join: two side-by-side scans, got " + join.size());
        check("lake.main.small".equals(join.get(1).table()) && join.get(1).projections().equals(List.of("id", "name")),
            "v2 explain join: right box " + join.get(1));
    }

    /** 2.0 lists skipped files too: one file read, five skipped, five names printed. */
    private static void v2TextJoinSkippedFiles() throws IOException {
        List<QueryGuardPlan.Scan> scans = QueryGuardPlan.parseText(read("v2-analyze-join.txt"));
        check(scans.size() == 2, "v2 analyzed join: two scans, got " + scans.size());
        QueryGuardPlan.Scan scan = scans.get(0);
        check(Integer.valueOf(1).equals(scan.filesRead()) && scan.filtered(), "v2 analyzed join: 1 file, dynamic filter");

        QueryGuardEstimate.ScanEstimate e = QueryGuardEstimate.estimate(scan, TRANSFERS,
            new QueryGuardEstimate.TableFiles(6, TRANSFER_BYTES, 0, TRANSFER_FILES));
        check(e.files() == 1 && e.bytes() == Math.round(TRANSFER_BYTES / 6.0 * 2 / 6.0),
            "v2 analyzed join: sized by files read, not by the names, got " + e);
    }

    private static void v2JsonQualifiedNames() throws IOException {
        List<QueryGuardPlan.Scan> groupBy = QueryGuardPlan.parseJson(read("v2-groupby.json"));
        check(groupBy.size() == 1 && "lake.main.transfers".equals(groupBy.get(0).table()), "v2 json: qualified table");

        List<QueryGuardPlan.Scan> limit = QueryGuardPlan.parseJson(read("v2-limit.json"));
        check(limit.size() == 2 && limit.get(0).limited() && limit.get(1).limited(), "v2 json: LIMIT scans stop early");

        List<QueryGuardPlan.Scan> topN = QueryGuardPlan.parseJson(read("v2-topn.json"));
        check(topN.size() == 2 && topN.get(0).limited() && !topN.get(1).limited(), "v2 json: top-n late materialization");
    }

    private static void qualifiedNames() {
        check(QueryGuardEstimate.splitQualified("trades").equals(List.of("trades")), "split: bare");
        check(QueryGuardEstimate.splitQualified("lake.main.trades").equals(List.of("lake", "main", "trades")), "split: dotted");
        check(QueryGuardEstimate.splitQualified("\"lake.public\".main.trades").equals(List.of("lake.public", "main", "trades")),
            "split: quoted catalog with a dot");
        check(QueryGuardEstimate.splitQualified("\"a\"\"b\".c").equals(List.of("a\"b", "c")), "split: doubled quote");

        QueryGuardEstimate.Candidate a = new QueryGuardEstimate.Candidate("lake.public", "main", "trades", 8);
        QueryGuardEstimate.Candidate b = new QueryGuardEstimate.Candidate("sales", "main", "trades", 8);
        List<QueryGuardEstimate.Candidate> both = List.of(a, b);
        check(QueryGuardEstimate.matching(both, List.of("trades")).size() == 2, "matching: bare keeps all");
        check(QueryGuardEstimate.matching(both, QueryGuardEstimate.splitQualified("\"lake.public\".main.trades")).equals(List.of(a)),
            "matching: qualified picks one");
        check(QueryGuardEstimate.matching(both, List.of("other", "trades")).isEmpty(), "matching: wrong schema");
    }

    // ------------------------------------------------------------------ estimates

    private static void estimateFractionOfColumns() throws IOException {
        QueryGuardPlan.Scan scan = QueryGuardPlan.parseJson(read("groupby.json")).get(0);
        QueryGuardEstimate.ScanEstimate e = QueryGuardEstimate.estimate(scan, TRANSFERS,
            new QueryGuardEstimate.TableFiles(6, TRANSFER_BYTES, 0, null));
        check(e.files() == 6, "fraction: 6 files");
        check(e.bytes() == Math.round(TRANSFER_BYTES * 2 / 6.0), "fraction: 2 of 6 columns, got " + e.bytes());
        check(e.projected() == 2 && e.columns() == 6 && !e.upperBound(), "fraction: fields");
    }

    private static void estimateListedFilesWithTruncation() throws IOException {
        QueryGuardPlan.Scan scan = QueryGuardPlan.parseText(read("analyze-filtered.txt")).get(0);
        QueryGuardEstimate.ScanEstimate e = QueryGuardEstimate.estimate(scan, TRANSFERS,
            new QueryGuardEstimate.TableFiles(6, TRANSFER_BYTES, 0, TRANSFER_FILES));
        // 5 listed files sized exactly, the sixth (cut off by "...") at the average of the rest.
        check(e.files() == 6, "listed: 6 files read");
        check(Math.abs(e.bytes() - TRANSFER_BYTES * 2 / 6.0) < 2, "listed: all six files, 2 of 6 columns, got " + e.bytes());
        check(e.upperBound(), "listed: filtered scan is an upper bound");

        // Only two files listed and no "...": only those two count.
        List<String> two = new ArrayList<>(TRANSFER_FILES.keySet()).subList(0, 2);
        QueryGuardPlan.Scan partial = new QueryGuardPlan.Scan("transfers", List.of("id", "amount"), true, 2, two, false, false);
        QueryGuardEstimate.ScanEstimate p = QueryGuardEstimate.estimate(partial, TRANSFERS,
            new QueryGuardEstimate.TableFiles(6, TRANSFER_BYTES, 0, TRANSFER_FILES));
        long twoBytes = 16_659_431L + 16_658_778L;
        check(p.files() == 2 && p.bytes() == Math.round(twoBytes * 2 / 6.0), "listed: two files only, got " + p.bytes());

        // Relative names (as an older catalog might store them) still match by suffix.
        QueryGuardPlan.Scan relative = new QueryGuardPlan.Scan("transfers", List.of("id"), false, null,
            List.of("main/transfers/ducklake-01a16391-d3d0-7372-bbe4-2efbb4516f29.parquet"), false, false);
        QueryGuardEstimate.ScanEstimate r = QueryGuardEstimate.estimate(relative, TRANSFERS,
            new QueryGuardEstimate.TableFiles(6, TRANSFER_BYTES, 0, TRANSFER_FILES));
        check(r.files() == 1 && r.bytes() == Math.round(16_659_431L / 6.0), "listed: suffix match, got " + r.bytes());
    }

    private static void estimateFilesReadWithoutNames() throws IOException {
        QueryGuardPlan.Scan scan = new QueryGuardPlan.Scan("transfers", List.of("id", "amount", "asset"), true, 3,
            List.of(), false, false);
        QueryGuardEstimate.ScanEstimate e = QueryGuardEstimate.estimate(scan, TRANSFERS,
            new QueryGuardEstimate.TableFiles(6, TRANSFER_BYTES, 0, null));
        check(e.files() == 3 && e.bytes() == Math.round(TRANSFER_BYTES / 2.0 * 3 / 6.0),
            "files read: half the files, half the columns, got " + e.bytes());
    }

    private static void estimateLimitedScan() throws IOException {
        QueryGuardPlan.Scan scan = QueryGuardPlan.parseJson(read("limit.json")).get(1);
        QueryGuardEstimate.ScanEstimate e = QueryGuardEstimate.estimate(scan, TRANSFERS,
            new QueryGuardEstimate.TableFiles(6, TRANSFER_BYTES, 0, null));
        check(e.limited() && e.bytes() == 0, "limited: nothing counted");
    }

    private static void estimateDeleteFiles() {
        QueryGuardPlan.Scan scan = new QueryGuardPlan.Scan("transfers", List.of("id"), false, null, List.of(), false, false);
        QueryGuardEstimate.ScanEstimate e = QueryGuardEstimate.estimate(scan, TRANSFERS,
            new QueryGuardEstimate.TableFiles(6, 600, 50, null));
        check(e.bytes() == 150, "delete files are read whole, got " + e.bytes());
    }

    private static void chooseCandidate() {
        QueryGuardEstimate.Candidate salesMain = new QueryGuardEstimate.Candidate("sales", "main", "deals", 4);
        QueryGuardEstimate.Candidate salesEmea = new QueryGuardEstimate.Candidate("sales", "emea", "deals", 4);
        QueryGuardEstimate.Candidate lakeMain = new QueryGuardEstimate.Candidate("lake", "main", "deals", 4);
        List<QueryGuardEstimate.Candidate> all = List.of(salesMain, salesEmea, lakeMain);

        check(QueryGuardEstimate.choose(List.of(lakeMain), "SELECT * FROM deals", "x", "y") == lakeMain, "choose: single");
        check(QueryGuardEstimate.choose(all, "SELECT * FROM sales.emea.deals", "lake", "main") == salesEmea, "choose: fully qualified");
        check(QueryGuardEstimate.choose(all, "SELECT * FROM \"sales\".\"emea\".\"deals\"", "lake", "main") == salesEmea, "choose: quoted");
        check(QueryGuardEstimate.choose(all, "SELECT * FROM emea.deals", "lake", "main") == salesEmea, "choose: schema.table");
        check(QueryGuardEstimate.choose(all, "SELECT * FROM deals", "lake", "main") == lakeMain, "choose: current catalog and schema");
        check(QueryGuardEstimate.choose(all, "SELECT * FROM deals", "sales", "other") == null, "choose: tie left to the caller");
        check(QueryGuardEstimate.choose(all, "SELECT * FROM mydeals", "lake", "main") == lakeMain, "choose: no false mention");
        check(QueryGuardEstimate.choose(List.of(salesMain, lakeMain), "SELECT * FROM sales.deals", "lake", "main") == salesMain,
            "choose: catalog.table shorthand");
        QueryGuardEstimate.Candidate small = new QueryGuardEstimate.Candidate("small", "main", "events", 3);
        QueryGuardEstimate.Candidate large = new QueryGuardEstimate.Candidate("large", "main", "events", 3);
        check(QueryGuardEstimate.choose(List.of(small, large),
                "SELECT * FROM small.main.events a JOIN large.main.events b USING (id)", "small", "main") == null,
            "choose: two named tables stay ambiguous, the caller keeps the largest");

        // DuckDB 1.5 prints "events" for both scans; the qualified mention must not win for both.
        check(QueryGuardEstimate.choose(List.of(small, large),
                "SELECT * FROM small.main.events s JOIN events e USING (id)", "large", "main") == null,
            "choose: qualified plus unqualified mention stays ambiguous");
        check(QueryGuardEstimate.choose(List.of(small, large),
                "SELECT * FROM \"small\".main.\"events\" s JOIN \"events\" e USING (id)", "large", "main") == null,
            "choose: quoted qualified plus quoted unqualified mention stays ambiguous");
        check(QueryGuardEstimate.choose(List.of(small, large),
                "SELECT events.id FROM small.main.events", "large", "main") == small,
            "choose: a column qualifier is not an unqualified table mention");
        check(QueryGuardEstimate.choose(List.of(small, large),
                "SELECT * FROM small.main . events", "large", "main") == small,
            "choose: spaces around the dot keep the mention qualified");

        check(QueryGuardEstimate.mentionsBare("SELECT * FROM events", "events"), "bare: plain");
        check(QueryGuardEstimate.mentionsBare("SELECT * FROM \"Events\" e", "events"), "bare: quoted, other case");
        check(!QueryGuardEstimate.mentionsBare("SELECT * FROM a.events", "events"), "bare: qualified");
        check(!QueryGuardEstimate.mentionsBare("SELECT events.id FROM a.events", "events"), "bare: column qualifier");
        check(!QueryGuardEstimate.mentionsBare("SELECT * FROM my_events", "events"), "bare: part of another name");
    }

    /**
     * Logging is global to a DuckDB instance, which several connections may share: the guard
     * recognises its own logging by the marker, and counts only one connection's entries.
     */
    private static void loggingOwnership() {
        String marker = QueryGuardEstimate.LOGGING_MARKER;

        check(QueryGuardEstimate.loggingState(false, "", "") == QueryGuardEstimate.LoggingState.OFF, "logging: off");
        check(QueryGuardEstimate.loggingState(false, "HTTP", marker) == QueryGuardEstimate.LoggingState.OFF,
            "logging: switched off after the guard had it");
        check(QueryGuardEstimate.loggingState(true, "HTTP", marker) == QueryGuardEstimate.LoggingState.GUARD,
            "logging: on with the marker, from any connection of the instance, is the guard's");
        check(QueryGuardEstimate.loggingState(true, "HTTP", "") == QueryGuardEstimate.LoggingState.USER,
            "logging: CALL enable_logging('HTTP') by the user clears the marker");
        check(QueryGuardEstimate.loggingState(true, "HTTP,QueryLog", marker) == QueryGuardEstimate.LoggingState.USER,
            "logging: more types than HTTP are the user's");
        check(QueryGuardEstimate.loggingState(true, "", "") == QueryGuardEstimate.LoggingState.USER, "logging: user, all types");
        check(QueryGuardEstimate.loggingState(true, null, null) == QueryGuardEstimate.LoggingState.USER, "logging: nulls");

        String sql = QueryGuardEstimate.httpStatsSql(7, 1_792_678_837_163_549L);
        check(sql.contains("WHERE connection_id = 7 AND epoch_us(timestamp) >= 1792678837163549"),
            "logging: stats filtered to one connection and the statement's time\n" + sql);
        check(sql.contains("duckdb_logs_parsed('HTTP')") && sql.contains("request.type = 'GET'"), "logging: stats source\n" + sql);
    }

    /** SELECT count(*) ... WHERE asset = 'A7': DuckDB projects nothing, but the filter reads asset. */
    private static void filterOnlyColumns() {
        QueryGuardEstimate.Candidate named = new QueryGuardEstimate.Candidate("lake", "main", "transfers", 6,
            List.of("id", "ts", "account", "counterparty", "amount", "asset"));
        QueryGuardPlan.Scan count = new QueryGuardPlan.Scan("transfers", List.of(), true, null, List.of(), false, false,
            "asset='A7'");
        check(QueryGuardEstimate.readColumns(count, named) == 1, "filter-only: asset counts");

        QueryGuardPlan.Scan both = new QueryGuardPlan.Scan("transfers", List.of("id"), true, null, List.of(), false, false,
            "ts>='2026-01-03 00:00:00'::TIMESTAMP AND amount>5.5");
        check(QueryGuardEstimate.readColumns(both, named) == 3, "filter-only: id + ts + amount");

        check(QueryGuardEstimate.readColumns(count, TRANSFERS) == 1, "filter-only: no column names, still one column");

        QueryGuardEstimate.ScanEstimate e = QueryGuardEstimate.estimate(count, named,
            new QueryGuardEstimate.TableFiles(6, TRANSFER_BYTES, 0, null));
        check(e.bytes() == Math.round(TRANSFER_BYTES / 6.0), "filter-only: estimate is one column, got " + e.bytes());

        check(QueryGuardPlan.parseText(read0("v2-analyze-filtered.txt")).get(0).filterText().contains("asset"),
            "filter text kept from the text plan");
    }

    private static String read0(String name) {
        try {
            return read(name);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void guardableStatements() {
        for (String sql : List.of(
            "SELECT 1", "  select * from t", "WITH x AS (SELECT 1) SELECT * FROM x", "FROM t",
            "-- comment\nSELECT 1", "/* a */ (SELECT 1)", "TABLE t", "PIVOT t ON a USING sum(b)")) {
            check(QueryGuardEstimate.isGuardable(sql), "guardable: " + sql);
        }

        for (String sql : List.of(
            "EXPLAIN SELECT 1", "EXPLAIN ANALYZE SELECT 1", "SET threads = 4", "ATTACH 'x' AS y", "USE lake",
            "CALL ducklake_merge_adjacent_files('lake')", "COPY (SELECT 1) TO 'x.parquet'", "INSERT INTO t SELECT 1",
            "CREATE TABLE t AS SELECT 1", "DESCRIBE t", "", "   ", "-- only a comment")) {
            check(!QueryGuardEstimate.isGuardable(sql), "not guardable: " + sql);
        }
    }

    private static void formatting() {
        check(QueryGuardEstimate.formatBytes(553_634_061L).equals("528 MB"), QueryGuardEstimate.formatBytes(553_634_061L));
        check(QueryGuardEstimate.formatBytes(4_404_019L).equals("4.2 MB"), QueryGuardEstimate.formatBytes(4_404_019L));
        check(QueryGuardEstimate.formatBytes(12_288L).equals("12 KB"), QueryGuardEstimate.formatBytes(12_288L));
        check(QueryGuardEstimate.formatBytes(0).equals("0 KB"), QueryGuardEstimate.formatBytes(0));
        check(QueryGuardEstimate.formatBytes(3L * 1024 * 1024 * 1024).equals("3.0 GB"), "GB");

        String message = QueryGuardEstimate.dialogMessage(List.of(
            new QueryGuardEstimate.ScanEstimate("lake.main.transfers", 6, 200L * 1024 * 1024, 2, 6, true, false),
            new QueryGuardEstimate.ScanEstimate("lake.main.small", 0, 0, 2, 2, false, true)), 100L * 1024 * 1024);
        check(message.contains("about 200 MB") && message.contains("threshold 100 MB"), "dialog: totals\n" + message);
        check(message.contains("lake.main.transfers: 6 files, up to 200 MB (2 of 6 columns)"), "dialog: scan line\n" + message);
        check(message.contains("lake.main.small: stops early (LIMIT)"), "dialog: limited line\n" + message);
        check(message.contains("cache"), "dialog: cache note");

        check(QueryGuardEstimate.measurementMessage(114, 47_710_208L, 0.188)
                .equals("Query guard: read 46 MB from object storage (114 requests) in 0.19 s"),
            QueryGuardEstimate.measurementMessage(114, 47_710_208L, 0.188));
        check(QueryGuardEstimate.measurementMessage(0, 0, 0.01).contains("nothing read"), "measurement: cached");
    }

    // ------------------------------------------------------------------ helpers

    private static String read(String name) throws IOException {
        return Files.readString(resources.resolve(name), StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            System.out.println("FAILED: " + what);
            System.exit(1);
        }

        passed++;
    }
}
