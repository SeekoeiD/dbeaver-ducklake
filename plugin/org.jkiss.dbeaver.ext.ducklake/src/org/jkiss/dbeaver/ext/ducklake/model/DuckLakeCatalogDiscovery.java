/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.model;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Finds and ATTACHes the DuckLake catalogs hosted on a Postgres server. Works on a plain JDBC
 * connection to a DuckDB instance, so it can be exercised outside DBeaver.
 *
 * <p>A DuckLake catalog is a set of {@code ducklake_*} metadata tables in one Postgres schema, so a
 * catalog is identified by (database, schema). Two scopes are searched:
 * <ul>
 *   <li>the other schemas of the connection's own database, attached as {@code <schema>};</li>
 *   <li>every other database on the server the user may connect to, attached as
 *       {@code <database>.<schema>}.</li>
 * </ul>
 * The primary catalog (the connection's database + metadata schema) is attached by the init file,
 * so it is skipped here.
 */
public final class DuckLakeCatalogDiscovery {

    /** Temporary Postgres passthrough to the connection's own database. */
    public static final String META_ALIAS = "__ducklake_meta";

    /** Temporary Postgres passthrough used to probe each other database in turn. */
    public static final String PROBE_ALIAS = "__ducklake_probe";

    private static final String DUCKLAKE_SCHEMAS_SQL =
        "SELECT n.nspname::text FROM pg_catalog.pg_class c "
            + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
            + "WHERE c.relname = 'ducklake_metadata' AND c.relkind = 'r' ORDER BY 1";

    private static final String DATABASES_SQL =
        "SELECT datname::text FROM pg_catalog.pg_database "
            + "WHERE datallowconn AND NOT datistemplate "
            + "AND has_database_privilege(datname, 'CONNECT') ORDER BY 1";

    /**
     * Outcome of a discovery run.
     *
     * @param initStatements ATTACH statements (with trailing {@code ;}) to replay from the init file
     * @param attached       catalogs newly attached on this DuckDB instance
     * @param warnings       catalogs or databases that were skipped, and why
     */
    public record Result(List<String> initStatements, int attached, List<String> warnings) {
    }

    /** A catalog to attach; {@code pgConn} is null for a name that is merely reserved. */
    private record Target(String pgConn, String schema, String label) {
    }

    private DuckLakeCatalogDiscovery() {
    }

    public static Result discover(
        Connection con,
        String host, String port, String db, String user, String pass,
        String primarySchema, String primaryAlias,
        boolean includeSchemas, boolean includeDatabases
    ) throws SQLException {
        List<String> initStatements = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int attached = 0;

        String pgConn = DuckLakeDataSourceProvider.buildPostgresConnString(host, port, db, user, pass);
        execute(con, "ATTACH IF NOT EXISTS " + q(pgConn) + " AS " + id(META_ALIAS) + " (TYPE POSTGRES, READ_ONLY)");

        try {
            // DuckDB identifiers are case-insensitive even when quoted, so every name comparison is too.
            Set<String> alreadyAttached = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            alreadyAttached.addAll(queryStrings(con, "SELECT database_name FROM duckdb_databases()"));

            // Keyed by attach name (sorted, so the rewritten init file stays stable across runs),
            // seeded with the names that are already taken.
            Map<String, Target> targets = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (String builtin : List.of("memory", "system", "temp", META_ALIAS, PROBE_ALIAS)) {
                targets.put(builtin, new Target(null, null, "a DuckDB built-in database"));
            }

            targets.put(primaryAlias, new Target(null, null, "the primary catalog"));

            // Same-database catalogs go first, so they win a name collision with another database's.
            if (includeSchemas) {
                for (String schema : pgQuery(con, META_ALIAS, DUCKLAKE_SCHEMAS_SQL)) {
                    if (!schema.equals(primarySchema)) {
                        addTarget(targets, warnings, schema, new Target(pgConn, schema, "schema '" + schema + "'"));
                    }
                }
            }

            if (includeDatabases) {
                // The Database setting may be blank (Postgres then uses the user name), so ask the
                // server which database the passthrough landed on instead of trusting the setting.
                String primaryDb = pgQuery(con, META_ALIAS, "SELECT current_database()::text").get(0);

                for (String database : pgQuery(con, META_ALIAS, DATABASES_SQL)) {
                    if (database.equals(primaryDb)) {
                        continue;
                    }

                    String dbConn = DuckLakeDataSourceProvider.buildPostgresConnString(host, port, database, user, pass);

                    try {
                        execute(con, "ATTACH " + q(dbConn) + " AS " + id(PROBE_ALIAS) + " (TYPE POSTGRES, READ_ONLY)");

                        for (String schema : pgQuery(con, PROBE_ALIAS, DUCKLAKE_SCHEMAS_SQL)) {
                            addTarget(targets, warnings, database + "." + schema,
                                new Target(dbConn, schema, "schema '" + schema + "' of database '" + database + "'"));
                        }
                    } catch (SQLException e) {
                        // pg_hba rules or a missing role can still refuse a database we may CONNECT to.
                        warnings.add("Could not probe database '" + database + "': " + e.getMessage());
                    } finally {
                        execute(con, "DETACH DATABASE IF EXISTS " + id(PROBE_ALIAS));
                    }
                }
            }

            for (Map.Entry<String, Target> entry : targets.entrySet()) {
                String name = entry.getKey();
                Target target = entry.getValue();

                if (target.pgConn() == null) {
                    continue;
                }

                String attachSql = "ATTACH IF NOT EXISTS " + q("ducklake:postgres:" + target.pgConn())
                    + " AS " + id(name) + " (METADATA_SCHEMA " + q(target.schema()) + ")";

                if (alreadyAttached.contains(name)) {
                    // A repeat run on the same instance: keep the statement for the init file so
                    // other DuckDB instances (SQL editors) still get the attach.
                    initStatements.add(attachSql + ";");
                    continue;
                }

                try {
                    execute(con, attachSql);
                    initStatements.add(attachSql + ";");
                    attached++;
                } catch (SQLException e) {
                    // A catalog might be permission-restricted or on unreachable storage.
                    warnings.add("Could not attach DuckLake catalog '" + name + "': " + e.getMessage());
                }
            }
        } finally {
            execute(con, "DETACH DATABASE IF EXISTS " + id(META_ALIAS));
        }

        return new Result(initStatements, attached, warnings);
    }

    /** Add a catalog unless its name (ignoring case) is taken; a skipped catalog becomes a warning. */
    private static void addTarget(Map<String, Target> targets, List<String> warnings, String name, Target target) {
        Target existing = targets.putIfAbsent(name, target);

        if (existing != null) {
            warnings.add("Skipped DuckLake catalog in " + target.label() + ": the name '" + name
                + "' is already used by " + existing.label());
        }
    }

    /** Run {@code sql} on an attached Postgres database via {@code postgres_query}; returns column 1. */
    private static List<String> pgQuery(Connection con, String pgAlias, String sql) throws SQLException {
        return queryStrings(con, "SELECT * FROM postgres_query(" + q(pgAlias) + ", " + q(sql) + ")");
    }

    private static List<String> queryStrings(Connection con, String sql) throws SQLException {
        List<String> out = new ArrayList<>();

        try (Statement stmt = con.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String value = rs.getString(1);
                if (value != null) {
                    out.add(value);
                }
            }
        }

        return out;
    }

    private static void execute(Connection con, String sql) throws SQLException {
        try (Statement stmt = con.createStatement()) {
            stmt.execute(sql);
        }
    }

    private static String q(String s) {
        return DuckLakeDataSourceProvider.q(s);
    }

    private static String id(String s) {
        return DuckLakeDataSourceProvider.id(s);
    }
}
