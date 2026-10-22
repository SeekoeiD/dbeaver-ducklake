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
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
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
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
 *     really fetched, and queue a line for the SQL editor's Output panel. The guard turns HTTP
 *     logging on for its own connections, and stays out of the way (no measurement) when the user
 *     already had DuckDB logging on, because it truncates the log between statements.</li>
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

    private static final String HTTP_STATS_SQL =
        "SELECT count(*), coalesce(sum(TRY_CAST(coalesce(response.headers['content-length'],"
            + " response.headers['Content-Length']) AS BIGINT)) FILTER (WHERE request.type = 'GET'), 0)"
            + " FROM duckdb_logs_parsed('HTTP')";

    /** JDBC connections on which the guard switched DuckDB's HTTP logging on itself. */
    private static final Map<Connection, Boolean> OWNED_LOGGING = Collections.synchronizedMap(new WeakHashMap<>());

    private static final int MAX_PENDING = 50;

    /** Lines waiting for the Output panel, per execution context. */
    private static final Map<DBCExecutionContext, List<String>> PENDING = Collections.synchronizedMap(new WeakHashMap<>());

    private QueryGuard() {
    }

    /**
     * State carried from {@link #beforeExecute} to {@link #afterClose} for one execution.
     *
     * @param measure whether the HTTP log was reset for this statement and should be read after it
     */
    public record Token(Connection connection, DBCExecutionContext context, long startNanos, boolean measure) {
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

        if (!isEnabled(cfg) || !QueryGuardEstimate.isGuardable(sql)) {
            return null;
        }

        DBCExecutionContext context = session.getExecutionContext();
        Connection connection;

        try {
            connection = session.getOriginal();
        } catch (SQLException e) {
            return null;
        }

        try {
            // Any failed statement aborts an open DuckDB transaction, and the pre-flight can fail
            // (or be cancelled at its deadline) for reasons that have nothing to do with the
            // user's query. In manual-commit mode the guard therefore stays out of the way.
            if (!connection.getAutoCommit()) {
                return null;
            }
        } catch (SQLException e) {
            return null;
        }

        long threshold = thresholdBytes(cfg);
        List<QueryGuardEstimate.ScanEstimate> estimates = null;

        try {
            estimates = preflight(connection, sql);
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

        if (estimates != null && !estimates.isEmpty()) {
            long total = QueryGuardEstimate.total(estimates);
            log.debug("Query guard estimate " + QueryGuardEstimate.formatBytes(total) + " for " + estimates);

            if (total > threshold) {
                confirm(container, estimates, threshold);
            }
        }

        boolean measure = resetHttpLog(connection);
        return new Token(connection, context, System.nanoTime(), measure);
    }

    /** EXPLAIN the statement and size its DuckLake scans. Empty when the plan reads no DuckLake table. */
    static List<QueryGuardEstimate.ScanEstimate> preflight(Connection connection, String sql) throws SQLException {
        long deadline = System.currentTimeMillis() + BUDGET_MS;
        StringBuilder plan = new StringBuilder();

        query(connection, "EXPLAIN (FORMAT json) " + sql, deadline, rs -> plan.append(rs.getString(2)));

        List<QueryGuardPlan.Scan> scans = QueryGuardPlan.parseJson(plan.toString());

        if (scans.isEmpty()) {
            return Collections.emptyList();
        }

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

            for (QueryGuardEstimate.Candidate candidate : chosen != null ? List.of(chosen) : found) {
                boolean needNames = !scan.fileNames().isEmpty();
                String key = candidate.qualifiedName() + (needNames ? "#names" : "");
                QueryGuardEstimate.TableFiles tableFiles = files.get(key);

                if (tableFiles == null) {
                    tableFiles = listFiles(connection, candidate, needNames, deadline);
                    files.put(key, tableFiles);
                }

                QueryGuardEstimate.ScanEstimate estimate = QueryGuardEstimate.estimate(scan, candidate, tableFiles);

                if (best == null || estimate.bytes() > best.bytes()) {
                    best = estimate;
                }
            }

            estimates.add(best);
        }

        return estimates;
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

        if (withNames) {
            Map<String, Long> sizes = new LinkedHashMap<>();
            long[] deletes = new long[1];
            query(connection, "SELECT data_file, data_file_size_bytes, coalesce(delete_file_size_bytes, 0) FROM " + source,
                deadline, rs -> {
                    sizes.put(rs.getString(1), rs.getLong(2));
                    deletes[0] += rs.getLong(3);
                });
            long total = 0;

            for (long size : sizes.values()) {
                total += size;
            }

            return new QueryGuardEstimate.TableFiles(sizes.size(), total, deletes[0], sizes);
        }

        long[] totals = new long[3];
        query(connection,
            "SELECT count(*), coalesce(sum(data_file_size_bytes), 0), coalesce(sum(delete_file_size_bytes), 0) FROM " + source,
            deadline, rs -> {
                totals[0] = rs.getLong(1);
                totals[1] = rs.getLong(2);
                totals[2] = rs.getLong(3);
            });
        return new QueryGuardEstimate.TableFiles(totals[0], totals[1], totals[2], null);
    }

    /** Ask the user. Returns normally for Run, throws for Cancel. */
    private static void confirm(
        DBPDataSourceContainer container,
        List<QueryGuardEstimate.ScanEstimate> estimates,
        long threshold
    ) throws DBCException {
        long total = QueryGuardEstimate.total(estimates);

        if (DBWorkbench.getPlatform().getApplication().isHeadlessMode()) {
            log.warn("Query guard: running a query estimated at " + QueryGuardEstimate.formatBytes(total) + " (no UI to ask)");
            return;
        }

        DBPPlatformUI.UserChoiceResponse response = DBWorkbench.getPlatformUI().showUserChoice(
            "DuckLake query guard",
            QueryGuardEstimate.dialogMessage(estimates, threshold),
            List.of("Run", "Cancel"),
            List.of("Don't ask again for this connection"),
            null,
            1);

        if (response != null && response.forAllChoiceIndex != null) {
            disable(container);
        }

        if (response == null || response.choiceIndex != 0) {
            throw new DBCException("Cancelled by the DuckLake query guard: the query would read about "
                + QueryGuardEstimate.formatBytes(total) + " from object storage. Raise \"Warn above (MB)\" or"
                + " turn the guard off on the connection's DuckLake storage tab.");
        }
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
     * Turn HTTP logging on if nobody has, and empty the log, so that what is in it after the
     * statement is the statement's own traffic. False when the user runs DuckDB logging themselves
     * (the guard then leaves the log alone and measures nothing) or when it fails.
     */
    private static boolean resetHttpLog(Connection connection) {
        try (Statement stmt = connection.createStatement()) {
            if (!OWNED_LOGGING.containsKey(connection)) {
                boolean on;

                try (ResultSet rs = stmt.executeQuery("SELECT current_setting('enable_logging')")) {
                    on = rs.next() && rs.getBoolean(1);
                }

                if (on) {
                    OWNED_LOGGING.put(connection, Boolean.FALSE);
                } else {
                    stmt.execute("CALL enable_logging('HTTP')");
                    OWNED_LOGGING.put(connection, Boolean.TRUE);
                }
            }

            if (!Boolean.TRUE.equals(OWNED_LOGGING.get(connection))) {
                return false;
            }

            stmt.execute("CALL truncate_duckdb_logs()");
            return true;
        } catch (Exception e) {
            log.debug("Query guard: HTTP logging unavailable: " + e.getMessage());
            OWNED_LOGGING.put(connection, Boolean.FALSE);
            return false;
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

            try (ResultSet rs = stmt.executeQuery(HTTP_STATS_SQL)) {
                if (!rs.next()) {
                    return;
                }

                requests = rs.getLong(1);
                bytes = rs.getLong(2);
            }

            stmt.execute("CALL truncate_duckdb_logs()");
            String message = QueryGuardEstimate.measurementMessage(requests, bytes, seconds);
            log.info(message);
            addMessage(token.context(), message);
        } catch (Exception e) {
            log.debug("Query guard: could not read HTTP stats: " + e.getMessage());
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

    private interface RowHandler {
        void row(ResultSet rs) throws SQLException;
    }

    /** Raised when the pre-flight runs out of {@link #BUDGET_MS}. */
    static final class GuardTimeout extends SQLException {
        GuardTimeout() {
            super("query guard budget exceeded");
        }
    }

    /** Run one query, cancelling it when the pre-flight deadline passes. */
    private static void query(Connection connection, String sql, long deadline, RowHandler handler) throws SQLException {
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
