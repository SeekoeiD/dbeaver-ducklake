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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Finds and ATTACHes the DuckLake catalogs hosted on a Postgres server. Works on a plain JDBC
 * connection to a DuckDB instance, so it can be exercised outside DBeaver.
 *
 * <p>A DuckLake catalog is a set of {@code ducklake_*} metadata tables in one Postgres schema, so a
 * catalog is identified by (database, schema). Two scopes are searched:
 * <ul>
 *   <li>the other schemas of the connection's own database;</li>
 *   <li>every other database on the server the user may connect to.</li>
 * </ul>
 *
 * <p>Every catalog, the primary one included, is attached here and one at a time, so a catalog the
 * Postgres role cannot read is skipped with a warning instead of failing the connection. Only the
 * read-only Postgres passthrough used to ask which schemas hold a catalog has to succeed: if that
 * fails, the host, port or credentials are wrong and nothing else could work either.
 *
 * <p>Catalogs are tried in a fixed order: the configured metadata schema (or {@code public} when
 * that field is blank), then the other schemas of this database alphabetically, then the other
 * databases. The first one that attaches becomes the active catalog, the one a new SQL editor
 * starts in. Every catalog is named {@code <database>.<schema>}, except the active one when a
 * catalog alias is configured.
 *
 * <p>Nothing here ever creates a catalog by accident. DuckLake's ATTACH creates one when the schema
 * has no {@code ducklake_metadata} table, so only schemas that already hold one are attached. The
 * single exception is a configured DATA_PATH, which is how the user says a new catalog is wanted;
 * without it, a schema with no catalog is reported and skipped.
 */
public final class DuckLakeCatalogDiscovery {

    /** Temporary Postgres passthrough to the connection's own database. */
    public static final String META_ALIAS = "__ducklake_meta";

    /** Temporary Postgres passthrough used to probe each other database in turn. */
    public static final String PROBE_ALIAS = "__ducklake_probe";

    /**
     * Names no catalog may be attached as: DuckDB's built-in databases and the two passthroughs.
     * {@code ATTACH IF NOT EXISTS} silently does nothing when the name is already in use, so a
     * collision would look like a success while selecting an empty built-in database.
     */
    private static final Set<String> RESERVED_NAMES = reservedNames();

    private static final String DUCKLAKE_SCHEMAS_SQL =
        "SELECT n.nspname::text FROM pg_catalog.pg_class c "
            + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
            + "WHERE c.relname = 'ducklake_metadata' AND c.relkind = 'r' ORDER BY 1";

    /**
     * A libpq {@code password=} value, either single-quoted with backslash escapes or bare. The bare
     * branch stops at a quote as well as at whitespace: the connection string is usually the last
     * thing inside a SQL string literal, and {@code \S+} would swallow that literal's closing quote
     * and leave the logged statement looking malformed. A password that contains a quote is always
     * quoted by {@link DuckLakeDataSourceProvider#libpqValue}, so the first branch catches it.
     */
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("password=(?:'(?:[^'\\\\]|\\\\.)*'|[^\\s']+)");

    private static final String DATABASES_SQL =
        "SELECT datname::text FROM pg_catalog.pg_database "
            + "WHERE datallowconn AND NOT datistemplate "
            + "AND has_database_privilege(datname, 'CONNECT') ORDER BY 1";

    /** DuckDB's wording when the DATA_PATH option contradicts what the catalog already stores. */
    private static final String DATA_PATH_MISMATCH = "does not match existing data path";

    /**
     * Outcome of a discovery run.
     *
     * @param attachStatements catalog name to the ATTACH statement that worked, for replaying on
     *                         other connections
     * @param activeCatalog    the catalog to run {@code USE} on, or null when none could be attached
     * @param attached         catalogs attached on this DuckDB instance
     * @param skipped          catalogs that could not be attached
     * @param warnings         catalogs or databases that were skipped, and why
     */
    public record Result(
        Map<String, String> attachStatements,
        String activeCatalog,
        int attached,
        int skipped,
        List<String> warnings
    ) {
    }

    /**
     * A catalog to try, in the order it should be tried. {@code name} is the name it is attached as
     * unless it turns out to be the active one and an alias is configured; {@code preferred} marks
     * the one the DATA_PATH setting applies to.
     */
    private record Candidate(String pgConn, String schema, String name, String label, boolean preferred) {
    }

    private DuckLakeCatalogDiscovery() {
    }

    /**
     * Find every DuckLake catalog the role can reach and attach as many of them as possible.
     *
     * @param metadataSchema the configured preferred primary schema; blank means try {@code public}
     *                       first and fall back to whatever else is readable
     * @param catalogAlias   the configured name for the active catalog; blank means
     *                       {@code <database>.<schema>}
     * @param dataPath       the configured DATA_PATH, only offered to the preferred catalog and
     *                       dropped again when the catalog already records a different location.
     *                       Setting it is also the only thing that lets a schema with no catalog
     *                       in it be attached, which creates one
     * @throws SQLException when the Postgres passthrough cannot be opened, which means the server
     *                      or the credentials are wrong
     */
    public static Result discover(
        Connection con,
        String host, String port, String db, String user, String pass,
        String metadataSchema, String catalogAlias, String dataPath,
        boolean includeSchemas, boolean includeDatabases
    ) throws SQLException {
        String preferredSchema = trimmed(metadataSchema).isEmpty()
            ? DuckLakeConstants.DEF_METADATA_SCHEMA : trimmed(metadataSchema);

        String alias = trimmed(catalogAlias);
        String path = trimmed(dataPath);

        List<String> warnings = new ArrayList<>();

        if (RESERVED_NAMES.contains(alias)) {
            warnings.add("Ignored the catalog alias '" + alias + "': it is a DuckDB built-in database name");
            alias = "";
        }
        String pgConn = DuckLakeDataSourceProvider.buildPostgresConnString(host, port, db, user, pass);

        try {
            execute(con, "ATTACH IF NOT EXISTS " + q(pgConn) + " AS " + id(META_ALIAS) + " (TYPE POSTGRES, READ_ONLY)");
        } catch (SQLException e) {
            // Nothing below can work without this one, so it is the only fatal failure here.
            throw new SQLException("Cannot open the DuckLake catalog database: " + redact(e.getMessage()));
        }

        try {
            List<Candidate> candidates = collectCandidates(
                con, pgConn, host, port, db, user, pass, preferredSchema, path,
                includeSchemas, includeDatabases, warnings);

            return attachAll(con, candidates, path, alias, warnings);
        } finally {
            execute(con, "DETACH DATABASE IF EXISTS " + id(META_ALIAS));
        }
    }

    /**
     * List the catalogs to try, preferred one first, then the rest of this database alphabetically,
     * then the other databases. Nothing is attached yet: the order decides which catalog becomes
     * the active one, and that is only known once the ATTACHes have been tried.
     *
     * <p>Only schemas that already hold a {@code ducklake_metadata} table become candidates. DuckLake
     * CREATEs a catalog as a side effect of ATTACHing a schema that has none, so attaching the
     * preferred schema blind would let a browsing connection by a role with CREATE rights leave a
     * stray empty catalog behind, on nothing worse than a typo in the Metadata schema field. A
     * non-empty DATA_PATH is the one signal that creating a catalog is what the user asked for.
     */
    private static List<Candidate> collectCandidates(
        Connection con, String pgConn,
        String host, String port, String db, String user, String pass,
        String preferredSchema, String dataPath,
        boolean includeSchemas, boolean includeDatabases,
        List<String> warnings
    ) throws SQLException {
        List<Candidate> candidates = new ArrayList<>();

        // DuckDB identifiers are case-insensitive even when quoted, so every name comparison is too.
        Set<String> used = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        used.addAll(RESERVED_NAMES);

        // The Database setting may be blank (Postgres then uses the user name), so ask the server
        // which database the passthrough landed on instead of trusting the setting.
        String primaryDb = pgQuery(con, META_ALIAS, "SELECT current_database()::text").get(0);

        // One cheap query, and it runs even with discovery off: it is what says whether the
        // preferred schema holds a catalog or would have one created in it.
        List<String> ownSchemas = pgQuery(con, META_ALIAS, DUCKLAKE_SCHEMAS_SQL);

        // Candidates carry their default name here. The alias is applied at attach time, to
        // whichever catalog ends up active, so it must not reserve a name yet: with the preferred
        // catalog unreadable and the alias equal to another catalog's default name, that catalog is
        // exactly the one the alias should land on.
        String preferredName = DuckLakeDataSourceProvider.defaultAlias(db, preferredSchema);
        used.add(preferredName);

        boolean preferredExists = ownSchemas.contains(preferredSchema);

        if (!preferredExists) {
            warnings.add("No DuckLake catalog in schema '" + preferredSchema + "' of database '" + primaryDb + "'; "
                + (dataPath.isEmpty()
                    ? "nothing was created"
                    : "DATA_PATH is set, so a new catalog will be created there"));
        }

        if (preferredExists || !dataPath.isEmpty()) {
            candidates.add(new Candidate(pgConn, preferredSchema, preferredName,
                "schema '" + preferredSchema + "'", true));
        }

        // Same-database catalogs go next, so they win a name collision with another database's.
        if (includeSchemas) {
            for (String schema : ownSchemas) {
                if (schema.equals(preferredSchema)) {
                    continue;
                }

                add(candidates, used, warnings, new Candidate(pgConn, schema,
                    DuckLakeDataSourceProvider.defaultAlias(db, schema), "schema '" + schema + "'", false));
            }
        }

        if (includeDatabases) {
            for (String database : pgQuery(con, META_ALIAS, DATABASES_SQL)) {
                if (database.equals(primaryDb)) {
                    continue;
                }

                String dbConn = DuckLakeDataSourceProvider.buildPostgresConnString(host, port, database, user, pass);

                try {
                    execute(con, "ATTACH " + q(dbConn) + " AS " + id(PROBE_ALIAS) + " (TYPE POSTGRES, READ_ONLY)");

                    for (String schema : pgQuery(con, PROBE_ALIAS, DUCKLAKE_SCHEMAS_SQL)) {
                        add(candidates, used, warnings, new Candidate(dbConn, schema, database + "." + schema,
                            "schema '" + schema + "' of database '" + database + "'", false));
                    }
                } catch (SQLException e) {
                    // pg_hba rules or a missing role can still refuse a database we may CONNECT to.
                    warnings.add("Could not probe database '" + database + "': " + redact(e.getMessage()));
                } finally {
                    execute(con, "DETACH DATABASE IF EXISTS " + id(PROBE_ALIAS));
                }
            }
        }

        return candidates;
    }

    /**
     * ATTACH every candidate in turn, each in its own try/catch, and let the first one that works
     * be the active catalog. A configured alias names whichever catalog that turns out to be, so it
     * moves along with the fallback when the preferred catalog cannot be read. Names are checked
     * here, against what is attached by then, because {@code ATTACH IF NOT EXISTS} would otherwise
     * report success for a name that is already taken without attaching anything.
     */
    private static Result attachAll(
        Connection con, List<Candidate> candidates, String dataPath, String alias, List<String> warnings
    ) {
        Map<String, String> attachStatements = new LinkedHashMap<>();
        Set<String> taken = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        taken.addAll(RESERVED_NAMES);

        String active = null;
        String freeAlias = alias.isEmpty() ? null : alias;
        int attached = 0;
        int skipped = 0;

        for (Candidate candidate : candidates) {
            String name = active == null && freeAlias != null ? freeAlias : candidate.name();

            if (taken.contains(name)) {
                warnings.add("Skipped the DuckLake catalog in " + candidate.label() + ": the name '" + name
                    + "' is already taken");
                skipped++;
                continue;
            }

            String attachSql = attachOne(con, candidate, name, candidate.preferred() ? dataPath : "", warnings);

            if (attachSql == null) {
                // The name stays free: an alias the preferred catalog could not take goes to the fallback.
                skipped++;
                continue;
            }

            taken.add(name);
            attachStatements.put(name, attachSql);
            attached++;

            if (active == null) {
                active = name;
                freeAlias = null;
            }
        }

        return new Result(attachStatements, active, attached, skipped, warnings);
    }

    /**
     * Attach one catalog and return the statement that worked, or null after adding a warning. A
     * DATA_PATH that contradicts the location the catalog already stores is dropped and the ATTACH
     * retried without it: the stored location is the true one, and OVERRIDE_DATA_PATH would rewrite
     * the catalog rather than read it.
     */
    private static String attachOne(
        Connection con, Candidate candidate, String name, String dataPath, List<String> warnings
    ) {
        String attachSql = attachSql(candidate, name, dataPath);

        try {
            execute(con, attachSql);
            return attachSql;
        } catch (SQLException e) {
            if (dataPath.isEmpty() || !String.valueOf(e.getMessage()).contains(DATA_PATH_MISMATCH)) {
                warnings.add(skippedMessage(candidate, name, e));
                return null;
            }

            warnings.add("Ignored the DATA_PATH setting for DuckLake catalog '" + name
                + "': it does not match the location stored in the catalog, which is the one that counts. "
                + redact(e.getMessage()));
        }

        String retrySql = attachSql(candidate, name, "");

        try {
            execute(con, retrySql);
            return retrySql;
        } catch (SQLException e) {
            warnings.add(skippedMessage(candidate, name, e));
            return null;
        }
    }

    private static String attachSql(Candidate candidate, String name, String dataPath) {
        StringBuilder b = new StringBuilder(256);
        b.append("ATTACH IF NOT EXISTS ").append(q("ducklake:postgres:" + candidate.pgConn()))
            .append(" AS ").append(id(name)).append(" (");

        if (!dataPath.isEmpty()) {
            b.append("DATA_PATH ").append(q(dataPath)).append(", ");
        }

        b.append("METADATA_SCHEMA ").append(q(candidate.schema())).append(")");
        return b.toString();
    }

    /** A catalog might be permission-restricted, gone, or on unreachable storage. */
    private static String skippedMessage(Candidate candidate, String name, SQLException e) {
        return "Skipped DuckLake catalog '" + name + "' (" + candidate.label() + "): " + redact(e.getMessage());
    }

    /**
     * Make {@code catalog} the current one, and {@code defaultSchema} the current schema when it is
     * set. A schema that does not exist only costs the schema: the catalog is still selected and the
     * connection still opens, which beats failing over a typo in an optional field.
     */
    public static List<String> useCatalog(Connection con, String catalog, String defaultSchema) {
        List<String> warnings = new ArrayList<>();
        String schema = trimmed(defaultSchema);

        if (!schema.isEmpty()) {
            try {
                execute(con, "USE " + id(catalog) + "." + id(schema));
                return warnings;
            } catch (SQLException e) {
                warnings.add("Could not open DuckLake schema '" + schema + "' of catalog '" + catalog
                    + "'; staying in the catalog's own default schema: " + redact(e.getMessage()));
            }
        }

        try {
            execute(con, "USE " + id(catalog));
        } catch (SQLException e) {
            warnings.add("Could not make DuckLake catalog '" + catalog + "' the current one: " + redact(e.getMessage()));
        }

        return warnings;
    }

    /**
     * Replay discovered ATTACH statements on another connection, one at a time. Each context runs on
     * its own DuckDB instance, where nothing is attached yet. A catalog that has become unreachable
     * since discovery only produces a warning, so it cannot stop the context from opening.
     */
    public static List<String> attachBestEffort(Connection con, Map<String, String> attachStatements) {
        List<String> warnings = new ArrayList<>();

        for (Map.Entry<String, String> entry : attachStatements.entrySet()) {
            try {
                execute(con, entry.getValue());
            } catch (SQLException e) {
                warnings.add("Could not attach DuckLake catalog '" + entry.getKey() + "': " + redact(e.getMessage()));
            }
        }

        return warnings;
    }

    /**
     * Mask libpq passwords in a message before it is logged. DuckDB's Postgres errors repeat the whole
     * connection string, password included.
     */
    public static String redact(String message) {
        return message == null ? null : PASSWORD_PATTERN.matcher(message).replaceAll("password=***");
    }

    /** Add a catalog unless its name (ignoring case) is taken; a skipped catalog becomes a warning. */
    private static void add(
        List<Candidate> candidates, Set<String> used, List<String> warnings, Candidate candidate
    ) {
        if (used.add(candidate.name())) {
            candidates.add(candidate);
            return;
        }

        warnings.add("Skipped the DuckLake catalog in " + candidate.label() + ": the name '"
            + candidate.name() + "' is already taken");
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static Set<String> reservedNames() {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(List.of("memory", "system", "temp", META_ALIAS, PROBE_ALIAS));
        return names;
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
