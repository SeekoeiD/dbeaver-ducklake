/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.model.guard;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.ducklake.model.DuckLakeCatalogDiscovery;
import org.jkiss.dbeaver.ext.ducklake.model.DuckLakeConstants;
import org.jkiss.dbeaver.ext.ducklake.model.DuckLakeDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.ui.DBPPlatformUI;
import org.jkiss.utils.CommonUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The query guard. DuckLake keeps its data on object storage and runs the query in the user's own
 * DuckDB, so an innocent aggregate over a fact table pulls every projected column of every file over
 * the network, and nothing in DBeaver says so. The guard does two things around each read query a
 * user runs on a DuckLake connection:
 *
 * <ul>
 *     <li>Before: {@code EXPLAIN (FORMAT json)} the statement (planning only), size every DuckLake
 *     scan in the plan from the catalog ({@code ducklake_list_files}) and the share of columns it
 *     projects, and ask before running a statement whose estimate is above the connection's
 *     threshold. The whole pre-flight gets {@link #BUDGET_MS}; past that it gives up and lets the
 *     statement run.</li>
 *     <li>After: read DuckDB's HTTP log ({@code duckdb_logs_parsed('HTTP')}) for what the statement
 *     really fetched, and queue a line for the SQL editor's Output panel. Logging is per DuckDB
 *     instance, which DBeaver's execution contexts may share: the guard switches HTTP logging on
 *     with a marker in {@code disabled_log_types} so every connection of the instance can tell it
 *     is the guard's, counts only the log entries of the statement's own connection since it
 *     started, and empties the log when no measurement is running. It stays out of the way (no
 *     measurement) when the user runs DuckDB logging themselves.</li>
 * </ul>
 *
 * <p>Everything here runs on the raw JDBC connection of the statement being guarded, so none of it
 * goes back through {@link QueryGuardJdbcFactory}, and nothing runs while the user's result set is
 * still open: the pre-flight runs before the statement, the measurement after it is closed. A
 * failure anywhere in the guard is logged and the statement runs as if the guard were off.
 */
public final class QueryGuard {

    private static final Log log = Log.getLog(QueryGuard.class);

    /** Time the pre-flight may take (EXPLAIN plus the catalog lookups) before it gives up. */
    static final long BUDGET_MS = 3000;

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DuckLake query guard timer");
        t.setDaemon(true);
        return t;
    });

    private static final String LOGGING_STATE_SQL =
        "SELECT current_setting('enable_logging'), current_setting('enabled_log_types'),"
            + " current_setting('disabled_log_types')";

    /**
     * Switches HTTP logging on for the whole DuckDB instance. The marker goes in first, so another
     * connection of the same instance never sees the guard's logging without it.
     */
    private static final String[] ENABLE_LOGGING_SQL = {
        "SET GLOBAL disabled_log_types = '" + QueryGuardEstimate.LOGGING_MARKER + "'",
        "SET GLOBAL enabled_log_types = 'HTTP'",
        "SET GLOBAL logging_mode = 'ENABLE_SELECTED'",
        "SET GLOBAL logging_level = 'DEBUG'",
        "SET GLOBAL enable_logging = true",
    };

    /**
     * JDBC connections the guard never measures on: the user runs DuckDB logging there, or logging
     * is not available. Logging settings belong to the DuckDB instance, which several connections
     * (DBeaver's execution contexts) may share, so who owns the log is read from the instance each
     * time; this only remembers the connections that gave up.
     */
    private static final Map<Connection, Boolean> NO_MEASUREMENT = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Measurements started and not read yet, on any connection. The log is shared by every
     * connection of an instance, so it is only emptied when none is left. Weak, so a statement
     * that is never closed does not block that forever.
     */
    private static final Set<Token> IN_FLIGHT = Collections.newSetFromMap(new WeakHashMap<>());

    private static final int MAX_PENDING = 50;

    /** JDBC connections on which the user ran BEGIN and has not yet ended the transaction. */
    private static final Map<Connection, Boolean> IN_TRANSACTION = Collections.synchronizedMap(new WeakHashMap<>());

    /** Lines waiting for the Output panel, per execution context. */
    private static final Map<DBCExecutionContext, List<String>> PENDING = Collections.synchronizedMap(new WeakHashMap<>());

    private QueryGuard() {
    }

    /**
     * State carried from {@link #beforeExecute} to {@link #afterClose} for one execution.
     *
     * @param measure      whether the HTTP log should be read after the statement
     * @param connectionId DuckDB's id of the connection, which the log records per entry
     * @param sinceMicros  DuckDB's clock (epoch microseconds) just before the statement
     * @param rewrittenSql the statement to run instead of the user's, when the user chose "Newest
     *                     file only" in the dialog; null to run the user's statement
     */
    public record Token(Connection connection, DBCExecutionContext context, long startNanos, boolean measure,
                        long connectionId, long sinceMicros, @Nullable String rewrittenSql) {
    }

    /**
     * What the pre-flight found.
     *
     * @param estimates  one per DuckLake scan; empty when the plan reads no DuckLake table
     * @param plainScan  the plan's scan when the plan is a plain read of one table
     *                   ({@link QueryGuardPlan#plainScan}), else null
     * @param candidate  the catalog table that scan reads, when it is the only scan and resolved to
     *                   one table, else null
     * @param deadline   the pre-flight's deadline (epoch milliseconds), which the "Newest file only"
     *                   lookups share
     */
    record Preflight(List<QueryGuardEstimate.ScanEstimate> estimates, @Nullable QueryGuardPlan.Scan plainScan,
                     @Nullable QueryGuardEstimate.Candidate candidate, long deadline) {
    }

    /**
     * The "Newest file only" choice of the dialog.
     *
     * @param sql      the user's statement with the newest file's range added as a WHERE
     * @param where    that range without the table qualifier, for the dialog
     * @param estimate what the statement reads with it
     */
    record NewestFileOffer(String sql, String where, QueryGuardEstimate.ScanEstimate estimate) {
    }

    // ------------------------------------------------------------------ settings

    public static boolean isEnabled(@NotNull DBPConnectionConfiguration cfg) {
        return CommonUtils.getBoolean(cfg.getProviderProperty(DuckLakeConstants.PROP_GUARD_ENABLED), true);
    }

    public static long thresholdBytes(@NotNull DBPConnectionConfiguration cfg) {
        double mb = parseThresholdMb(cfg.getProviderProperty(DuckLakeConstants.PROP_GUARD_THRESHOLD_MB));
        return Math.round(mb * QueryGuardEstimate.MB);
    }

    /** The stored threshold in MB, or the default when it is blank, unreadable or not positive. */
    public static double parseThresholdMb(@Nullable String value) {
        try {
            double mb = Double.parseDouble(CommonUtils.notEmpty(value).trim());

            if (mb > 0 && !Double.isInfinite(mb)) {
                return mb;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the default
        }

        return DuckLakeConstants.DEF_GUARD_THRESHOLD_MB;
    }

    // ------------------------------------------------------------------ before

    /**
     * Run the pre-flight for a statement the user is about to execute. Returns null when the guard
     * does not apply (not a user query, guard off, not a read query).
     *
     * @throws DBCException when the user cancels in the confirmation dialog
     */
    @Nullable
    public static Token beforeExecute(@NotNull JDBCSession session, @Nullable String sql) throws DBCException {
        if (sql == null || !session.getPurpose().isUser()) {
            return null;
        }

        DBPDataSourceContainer container = session.getDataSource().getContainer();
        DBPConnectionConfiguration cfg = container.getActualConnectionConfiguration();

        if (!isEnabled(cfg)) {
            return null;
        }

        DBCExecutionContext context = session.getExecutionContext();
        Connection connection;

        try {
            connection = session.getOriginal();
        } catch (SQLException e) {
            return null;
        }

        trackExplicitTransaction(connection, sql);

        if (!QueryGuardEstimate.isGuardable(sql)) {
            return null;
        }

        try {
            // Any failed statement aborts an open DuckDB transaction, and the pre-flight can fail
            // (or be cancelled at its deadline) for reasons that have nothing to do with the
            // user's query. Inside a transaction (manual-commit mode, or a BEGIN typed in the
            // editor, which DuckDB JDBC does not report) the guard therefore stays out of the way.
            if (!connection.getAutoCommit() || IN_TRANSACTION.containsKey(connection)) {
                return null;
            }
        } catch (SQLException e) {
            return null;
        }

        long threshold = thresholdBytes(cfg);
        Preflight preflight = null;
        List<QueryGuardEstimate.ScanEstimate> estimates = null;
        Function<String, String> metadataSchemas = session.getDataSource() instanceof DuckLakeDataSource lake
            ? lake::getMetadataSchema : catalog -> null;

        try {
            preflight = preflight(connection, sql, metadataSchemas);
            estimates = preflight.estimates();
        } catch (GuardTimeout e) {
            addMessage(context, "Query guard: planning took longer than " + (BUDGET_MS / 1000) + " s, estimate skipped");
        } catch (Exception e) {
            String reason = DuckLakeCatalogDiscovery.redact(String.valueOf(e.getMessage()));
            log.debug("Query guard pre-flight skipped", e);
            addMessage(context, "Query guard: estimate skipped (" + firstLine(reason) + ")");
        }

        if (estimates != null && estimates.isEmpty()) {
            addMessage(context, "Query guard: no DuckLake table scan in the plan, nothing to estimate");
        }

        String rewrittenSql = null;

        if (estimates != null && !estimates.isEmpty()) {
            long total = QueryGuardEstimate.total(estimates);
            log.debug("Query guard estimate " + QueryGuardEstimate.formatBytes(total) + " for " + estimates);
            addMessage(context, QueryGuardEstimate.estimateMessage(estimates));

            if (total > threshold) {
                NewestFileOffer offer = newestFileOffer(session, connection, sql, preflight, metadataSchemas);

                if (confirm(container, estimates, threshold, offer)) {
                    rewrittenSql = offer.sql();
                    log.info("Query guard: ran on the newest file: " + rewrittenSql);
                    addMessage(context, "Query guard: ran on the newest file: " + rewrittenSql);
                }
            }
        }

        long[] mark = startMeasurement(connection);

        if (mark == null) {
            return new Token(connection, context, System.nanoTime(), false, 0, 0, rewrittenSql);
        }

        Token token = new Token(connection, context, System.nanoTime(), true, mark[0], mark[1], rewrittenSql);

        synchronized (IN_FLIGHT) {
            IN_FLIGHT.add(token);
        }

        return token;
    }

    /** Note BEGIN / COMMIT / ROLLBACK typed by the user, which auto-commit mode does not show. */
    private static void trackExplicitTransaction(Connection connection, String sql) {
        String keyword = QueryGuardEstimate.firstKeyword(sql);

        if (keyword == null) {
            return;
        }

        switch (keyword) {
            case "BEGIN", "START" -> IN_TRANSACTION.put(connection, Boolean.TRUE);
            case "COMMIT", "ROLLBACK", "END", "ABORT" -> IN_TRANSACTION.remove(connection);
            default -> {
                // not a transaction statement
            }
        }
    }

    /**
     * EXPLAIN the statement and size its DuckLake scans. Empty when the plan reads no DuckLake table.
     *
     * @param metadataSchemas catalog name to its Postgres metadata schema, or null when unknown
     *                        (a catalog attached by hand); without it a filtered scan is not
     *                        pruned by the catalog's statistics
     */
    static Preflight preflight(
        Connection connection,
        String sql,
        Function<String, String> metadataSchemas
    ) throws SQLException {
        long deadline = System.currentTimeMillis() + BUDGET_MS;
        StringBuilder plan = new StringBuilder();

        query(connection, "EXPLAIN (FORMAT json) " + sql, deadline, rs -> plan.append(rs.getString(2)));

        List<QueryGuardPlan.Scan> scans = QueryGuardPlan.parseJson(plan.toString());

        if (scans.isEmpty()) {
            return new Preflight(Collections.emptyList(), null, null, deadline);
        }

        QueryGuardPlan.Scan plainScan = scans.size() == 1 ? QueryGuardPlan.plainScan(plan.toString()) : null;
        QueryGuardEstimate.Candidate plainTable = null;

        String[] current = new String[2];
        query(connection, "SELECT current_database(), current_schema()", deadline, rs -> {
            current[0] = rs.getString(1);
            current[1] = rs.getString(2);
        });

        Map<String, List<QueryGuardEstimate.Candidate>> candidates = new LinkedHashMap<>();
        Map<String, QueryGuardEstimate.TableFiles> files = new LinkedHashMap<>();
        List<QueryGuardEstimate.ScanEstimate> estimates = new ArrayList<>();

        for (QueryGuardPlan.Scan scan : scans) {
            if (scan.table() == null) {
                continue;
            }

            List<String> nameParts = QueryGuardEstimate.splitQualified(scan.table());
            String tableName = nameParts.get(nameParts.size() - 1);
            List<QueryGuardEstimate.Candidate> sameName = candidates.get(tableName.toLowerCase(Locale.ROOT));

            if (sameName == null) {
                sameName = findTables(connection, tableName, deadline);
                candidates.put(tableName.toLowerCase(Locale.ROOT), sameName);
            }

            List<QueryGuardEstimate.Candidate> found = QueryGuardEstimate.matching(sameName, nameParts);

            if (found.isEmpty()) {
                // A plain DuckDB table or something else that is not on object storage.
                continue;
            }

            QueryGuardEstimate.Candidate chosen = QueryGuardEstimate.choose(found, sql, current[0], current[1]);
            QueryGuardEstimate.ScanEstimate best = null;

            if (plainScan != null) {
                plainTable = chosen;
            }

            for (QueryGuardEstimate.Candidate candidate : chosen != null ? List.of(chosen) : found) {
                boolean needNames = !scan.fileNames().isEmpty();
                String key = candidate.qualifiedName() + (needNames ? "#names" : "");
                QueryGuardEstimate.TableFiles tableFiles = files.get(key);

                if (tableFiles == null) {
                    tableFiles = listFiles(connection, candidate, needNames, deadline);
                    files.put(key, tableFiles);
                }

                QueryGuardEstimate.TableFiles pruned = pruneByStatistics(connection, scan, candidate,
                    metadataSchemas.apply(candidate.catalog()), deadline);
                QueryGuardEstimate.ScanEstimate estimate = QueryGuardEstimate.estimate(scan, candidate, tableFiles, pruned);

                if (best == null || estimate.bytes() > best.bytes()) {
                    best = estimate;
                }
            }

            estimates.add(best);
        }

        return new Preflight(QueryGuardEstimate.countFootersOnce(estimates), plainScan, plainTable, deadline);
    }

    /**
     * The "Newest file only" choice for a plain read of one table, or null when the statement is
     * not one (by its plan and by its text, {@link NewestFileQuery}), the table has no newest file
     * with a usable integer column, or the lookups fail or run past the pre-flight's deadline. The
     * newest file is found as the data editor finds it ({@code DuckLakeTable.defaultWhere}): the
     * connection's preferred columns, {@link CatalogStats#newestFile}, {@link NewestFileFilter#choose}.
     * The cost shown with it comes from the same min/max pruning the guard applies to a filtered
     * scan, for the range it adds.
     */
    @Nullable
    private static NewestFileOffer newestFileOffer(
        JDBCSession session,
        Connection connection,
        String sql,
        @Nullable Preflight preflight,
        Function<String, String> metadataSchemas
    ) {
        if (preflight == null || preflight.plainScan() == null || preflight.candidate() == null
            || preflight.estimates().size() != 1) {
            return null;
        }

        NewestFileQuery.Parsed parsed = NewestFileQuery.parse(sql);
        QueryGuardEstimate.Candidate table = preflight.candidate();

        // The statement must name the table the plan scans; a view over it would not take the WHERE.
        if (parsed == null || QueryGuardEstimate.matching(List.of(table), parsed.nameParts()).isEmpty()) {
            return null;
        }

        String metadataSchema = metadataSchemas.apply(table.catalog());

        if (metadataSchema == null) {
            return null;
        }

        try {
            List<String> preferred = NewestFileFilter.parseColumns(session.getDataSource().getContainer()
                .getConnectionConfiguration().getProviderProperty(DuckLakeConstants.PROP_NEWEST_FILE_COLUMNS));
            List<CatalogStats.ColumnStats> columns = CatalogStats.newestFile(connection, table.catalog(), metadataSchema,
                table.schema(), table.table(), preferred, preflight.deadline());
            NewestFileFilter.Choice choice = NewestFileFilter.choose(columns, preferred);

            if (choice == null) {
                return null;
            }

            Map<String, QueryGuardEstimate.Range> bounds = Map.of(choice.column().toLowerCase(Locale.ROOT),
                new QueryGuardEstimate.Range(choice.min(), choice.max()));
            CatalogStats.Pruned pruned = CatalogStats.prunedFiles(connection, table.catalog(), metadataSchema,
                table.schema(), table.table(), bounds, preflight.deadline());
            QueryGuardEstimate.ScanEstimate estimate = QueryGuardEstimate.newestFileEstimate(preflight.plainScan(), table,
                choice, new QueryGuardEstimate.TableFiles(pruned.files(), pruned.dataBytes(), pruned.deleteBytes(), null));
            UnaryOperator<String> quote = name -> DBUtils.getQuotedIdentifier(session.getDataSource(), name);
            return new NewestFileOffer(NewestFileQuery.rewrite(sql, parsed, choice, quote),
                NewestFileFilter.where(choice, quote), estimate);
        } catch (Exception e) {
            log.debug("Query guard: no newest-file choice for " + table.qualifiedName() + ": "
                + DuckLakeCatalogDiscovery.redact(String.valueOf(e.getMessage())));
            return null;
        }
    }

    /**
     * The files a filtered scan can still touch once DuckLake has compared its integer bounds with
     * each file's min/max statistics, or null when the filter has no such bound or the metadata
     * schema is unknown. Without this, the data editor's default filter on a table's newest file
     * ({@code height BETWEEN a AND b}, see {@code DuckLakeTable}) was estimated as the whole table:
     * 220 GB for a 2,815-file table, against one file actually read.
     */
    @Nullable
    private static QueryGuardEstimate.TableFiles pruneByStatistics(
        Connection connection,
        QueryGuardPlan.Scan scan,
        QueryGuardEstimate.Candidate table,
        @Nullable String metadataSchema,
        long deadline
    ) throws SQLException {
        if (!scan.filtered() || scan.limited() || metadataSchema == null) {
            return null;
        }

        Map<String, QueryGuardEstimate.Range> bounds = QueryGuardEstimate.filterBounds(scan.filterText());

        if (bounds.isEmpty()) {
            return null;
        }

        CatalogStats.Pruned pruned = CatalogStats.prunedFiles(connection, table.catalog(), metadataSchema,
            table.schema(), table.table(), bounds, deadline);
        return new QueryGuardEstimate.TableFiles(pruned.files(), pruned.dataBytes(), pruned.deleteBytes(), null);
    }

    private static List<QueryGuardEstimate.Candidate> findTables(Connection connection, String table, long deadline)
        throws SQLException {
        List<QueryGuardEstimate.Candidate> result = new ArrayList<>();
        // Column names travel as one string joined by U+001F, which no identifier contains in practice.
        query(connection,
            "SELECT c.database_name, c.schema_name, c.table_name, count(*), string_agg(c.column_name, chr(31))"
                + " FROM duckdb_columns() c JOIN duckdb_databases() d ON d.database_name = c.database_name"
                + " WHERE d.type = 'ducklake' AND lower(c.table_name) = lower(" + literal(table) + ")"
                + " GROUP BY c.database_name, c.schema_name, c.table_name",
            deadline,
            rs -> result.add(new QueryGuardEstimate.Candidate(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                List.of(CommonUtils.notEmpty(rs.getString(5)).split("")))));
        return result;
    }

    private static QueryGuardEstimate.TableFiles listFiles(
        Connection connection,
        QueryGuardEstimate.Candidate table,
        boolean withNames,
        long deadline
    ) throws SQLException {
        String source = "ducklake_list_files(" + literal(table.catalog()) + ", " + literal(table.table())
            + ", schema => " + literal(table.schema()) + ")";

        // A footer read is the footer plus its 8-byte trailer; files without a recorded footer size
        // count nothing for it.
        String footer = "CASE WHEN data_file_footer_size IS NULL THEN 0 ELSE data_file_footer_size + "
            + QueryGuardEstimate.FOOTER_TRAILER + " END";

        if (withNames) {
            Map<String, Long> sizes = new LinkedHashMap<>();
            long[] extra = new long[2];
            query(connection, "SELECT data_file, data_file_size_bytes, coalesce(delete_file_size_bytes, 0), " + footer
                    + " FROM " + source,
                deadline, rs -> {
                    sizes.put(rs.getString(1), rs.getLong(2));
                    extra[0] += rs.getLong(3);
                    extra[1] += rs.getLong(4);
                });
            long total = 0;
            long largest = 0;

            for (long size : sizes.values()) {
                total += size;
                largest = Math.max(largest, size);
            }

            return new QueryGuardEstimate.TableFiles(sizes.size(), total, extra[0], sizes, extra[1], largest);
        }

        long[] totals = new long[5];
        query(connection,
            "SELECT count(*), coalesce(sum(data_file_size_bytes), 0), coalesce(sum(delete_file_size_bytes), 0),"
                + " coalesce(sum(" + footer + "), 0), coalesce(max(data_file_size_bytes), 0) FROM " + source,
            deadline, rs -> {
                for (int i = 0; i < totals.length; i++) {
                    totals[i] = rs.getLong(i + 1);
                }
            });
        return new QueryGuardEstimate.TableFiles(totals[0], totals[1], totals[2], null, totals[3], totals[4]);
    }

    /**
     * Ask the user. Returns false for Run, true for "Newest file only" (offered only when
     * {@code offer} is not null), throws for Cancel.
     */
    private static boolean confirm(
        DBPDataSourceContainer container,
        List<QueryGuardEstimate.ScanEstimate> estimates,
        long threshold,
        @Nullable NewestFileOffer offer
    ) throws DBCException {
        long total = QueryGuardEstimate.total(estimates);

        if (DBWorkbench.getPlatform().getApplication().isHeadlessMode()) {
            log.warn("Query guard: running a query estimated at " + QueryGuardEstimate.formatBytes(total) + " (no UI to ask)");
            return false;
        }

        List<String> buttons = offer == null ? List.of("Run", "Cancel") : List.of("Run", "Newest file only", "Cancel");
        String newestFileLine = offer == null ? null : QueryGuardEstimate.newestFileLine(offer.where(), offer.estimate());
        DBPPlatformUI.UserChoiceResponse response = DBWorkbench.getPlatformUI().showUserChoice(
            "DuckLake query guard",
            QueryGuardEstimate.dialogMessage(estimates, threshold, newestFileLine),
            buttons,
            List.of("Don't ask again for this connection"),
            null,
            buttons.size() - 1);

        if (response != null && response.forAllChoiceIndex != null) {
            disable(container);
        }

        if (response != null && offer != null && response.choiceIndex == 1) {
            return true;
        }

        if (response == null || response.choiceIndex != 0) {
            throw new DBCException("Cancelled by the DuckLake query guard: the query would read about "
                + QueryGuardEstimate.formatBytes(total) + " from object storage. Raise \"Warn above (MB)\" or"
                + " turn the guard off on the connection's DuckLake storage tab.");
        }

        return false;
    }

    /** "Don't ask again": switch the guard off for this connection and save that. */
    private static void disable(DBPDataSourceContainer container) {
        try {
            container.getConnectionConfiguration().setProviderProperty(DuckLakeConstants.PROP_GUARD_ENABLED, "false");
            container.getActualConnectionConfiguration().setProviderProperty(DuckLakeConstants.PROP_GUARD_ENABLED, "false");
            container.persistConfiguration();
            log.info("Query guard turned off for connection '" + container.getName() + "'");
        } catch (Exception e) {
            log.warn("Could not save the query guard setting: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ after

    /**
     * Make sure the DuckDB instance logs HTTP for the guard and note where the statement's own
     * traffic will start in the log: this connection's id and the current time. Null when the user
     * runs DuckDB logging themselves (the guard then leaves the log alone and measures nothing) or
     * when logging is not available.
     */
    @Nullable
    private static long[] startMeasurement(Connection connection) {
        if (NO_MEASUREMENT.containsKey(connection)) {
            return null;
        }

        try (Statement stmt = connection.createStatement()) {
            QueryGuardEstimate.LoggingState state = loggingState(stmt);

            if (state == QueryGuardEstimate.LoggingState.OFF) {
                for (String sql : ENABLE_LOGGING_SQL) {
                    stmt.execute(sql);
                }

                state = loggingState(stmt);
            }

            // Logging the user set up, or reconfigured since the guard switched it on: theirs.
            if (state != QueryGuardEstimate.LoggingState.GUARD) {
                NO_MEASUREMENT.put(connection, Boolean.TRUE);
                return null;
            }

            try (ResultSet rs = stmt.executeQuery("SELECT current_connection_id(), epoch_us(get_current_timestamp())")) {
                if (!rs.next()) {
                    return null;
                }

                return new long[] {rs.getLong(1), rs.getLong(2)};
            }
        } catch (Exception e) {
            log.debug("Query guard: HTTP logging unavailable: " + e.getMessage());
            NO_MEASUREMENT.put(connection, Boolean.TRUE);
            return null;
        }
    }

    private static QueryGuardEstimate.LoggingState loggingState(Statement stmt) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(LOGGING_STATE_SQL)) {
            if (!rs.next()) {
                return QueryGuardEstimate.LoggingState.USER;
            }

            return QueryGuardEstimate.loggingState(rs.getBoolean(1), rs.getString(2), rs.getString(3));
        }
    }

    /** Read what the statement fetched from object storage and queue it for the Output panel. */
    public static void afterClose(@NotNull Token token) {
        if (!token.measure()) {
            return;
        }

        double seconds = (System.nanoTime() - token.startNanos()) / 1e9;

        try (Statement stmt = token.connection().createStatement()) {
            long requests;
            long bytes;

            try (ResultSet rs = stmt.executeQuery(
                QueryGuardEstimate.httpStatsSql(token.connectionId(), token.sinceMicros()))) {
                if (!rs.next()) {
                    return;
                }

                requests = rs.getLong(1);
                bytes = rs.getLong(2);
            }

            String message = QueryGuardEstimate.measurementMessage(requests, bytes, seconds);
            log.info(message);
            addMessage(token.context(), message);
        } catch (Exception e) {
            log.debug("Query guard: could not read HTTP stats: " + e.getMessage());
        } finally {
            finishMeasurement(token);
        }
    }

    /**
     * Drop the measurement and, when no other one is running, empty the log so it does not grow in
     * memory. Only while the guard still owns the instance's logging; a user's log stays.
     */
    private static void finishMeasurement(Token token) {
        synchronized (IN_FLIGHT) {
            IN_FLIGHT.remove(token);

            if (!IN_FLIGHT.isEmpty()) {
                return;
            }

            try (Statement stmt = token.connection().createStatement()) {
                if (loggingState(stmt) == QueryGuardEstimate.LoggingState.GUARD) {
                    stmt.execute("CALL truncate_duckdb_logs()");
                }
            } catch (Exception e) {
                log.debug("Query guard: could not empty the HTTP log: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ output panel

    static void addMessage(DBCExecutionContext context, String message) {
        if (context == null) {
            return;
        }

        synchronized (PENDING) {
            List<String> lines = PENDING.computeIfAbsent(context, c -> new ArrayList<>());

            // Bounded, in case nothing ever drains this context (no SQL editor Output panel).
            if (lines.size() >= MAX_PENDING) {
                lines.remove(0);
            }

            lines.add(message);
        }
    }

    /** Take the queued lines for a context; the Output panel reader calls this. */
    public static List<String> drainMessages(DBCExecutionContext context) {
        synchronized (PENDING) {
            List<String> messages = PENDING.remove(context);
            return messages == null ? Collections.emptyList() : messages;
        }
    }

    // ------------------------------------------------------------------ JDBC helpers

    interface RowHandler {
        void row(ResultSet rs) throws SQLException;
    }

    /** Raised when the pre-flight runs out of {@link #BUDGET_MS}. */
    static final class GuardTimeout extends SQLException {
        GuardTimeout() {
            super("query guard budget exceeded");
        }
    }

    /** Run one query, cancelling it when the deadline (epoch milliseconds) passes. */
    static void query(Connection connection, String sql, long deadline, RowHandler handler) throws SQLException {
        long remaining = deadline - System.currentTimeMillis();

        if (remaining <= 0) {
            throw new GuardTimeout();
        }

        try (Statement stmt = connection.createStatement()) {
            ScheduledFuture<?> cancel = TIMER.schedule(() -> {
                try {
                    stmt.cancel();
                } catch (Exception ignored) {
                    // the statement finished or is already closed
                }
            }, remaining, TimeUnit.MILLISECONDS);

            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    handler.row(rs);
                }
            } catch (SQLException e) {
                if (System.currentTimeMillis() >= deadline) {
                    throw new GuardTimeout();
                }

                throw e;
            } finally {
                cancel.cancel(false);
            }
        }
    }

    private static String firstLine(String message) {
        int eol = message.indexOf('\n');
        String line = eol < 0 ? message : message.substring(0, eol);
        return line.length() > 200 ? line.substring(0, 200) + "..." : line;
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
