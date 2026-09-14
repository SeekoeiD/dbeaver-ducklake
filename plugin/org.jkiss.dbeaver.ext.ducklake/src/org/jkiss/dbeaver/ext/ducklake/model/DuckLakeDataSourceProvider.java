/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.model;

import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.duckdb.model.DuckDBDataSourceProvider;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.utils.CommonUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DuckLake data source provider. Builds the JDBC URL itself: it writes a DuckDB
 * {@code session_init_sql_file} that loads the extensions, creates an S3 secret (only if S3 storage
 * is used) and ATTACHes the DuckLake, then returns a {@code jdbc:duckdb:} URL referencing that file.
 * Because the DuckDB driver runs the init file on every physical connection (SQL editor and the
 * navigator's metadata connection), the attached catalog and its tables reliably appear in the tree.
 *
 * <p>Only the values the user actually entered are used — empty fields are omitted, so this works
 * for a local S3 (RustFS/MinIO), real AWS S3 (endpoint blank → credential chain when no key), or a
 * local-filesystem lake (no S3 secret at all).
 *
 * <p>The init file attaches ONE primary catalog (selected by {@code ducklake.metadata_schema},
 * default {@code public}) and makes it current, optionally with {@code ducklake.default_schema} as
 * the current schema. {@link DuckLakeDataSource} then discovers the other DuckLake catalogs on the
 * same Postgres server (other schemas of this database, and other databases) and attaches each as
 * its own top-level node — see {@link DuckLakeCatalogDiscovery}.
 */
public class DuckLakeDataSourceProvider extends DuckDBDataSourceProvider {

    private static final Log log = Log.getLog(DuckLakeDataSourceProvider.class);

    private static final Pattern INIT_FILE_PATTERN = Pattern.compile("session_init_sql_file=([^;]+)");

    @Override
    public String getConnectionURL(DBPDriver driver, DBPConnectionConfiguration cfg) {
        String host = CommonUtils.notEmpty(cfg.getHostName());
        String port = CommonUtils.notEmpty(cfg.getHostPort());
        String db = CommonUtils.notEmpty(cfg.getDatabaseName());
        String user = CommonUtils.notEmpty(cfg.getUserName());
        String pass = CommonUtils.notEmpty(cfg.getUserPassword());

        String s3endpoint = CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_S3_ENDPOINT));
        String s3key = CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_S3_KEY));
        String s3secret = CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_S3_SECRET));
        String s3region = CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_S3_REGION));
        String s3style = CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_S3_URL_STYLE));
        boolean s3ssl = CommonUtils.getBoolean(cfg.getProviderProperty(DuckLakeConstants.PROP_S3_USE_SSL), false);
        String dataPath = CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_DATA_PATH));

        String metadataSchema = CommonUtils.isEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_METADATA_SCHEMA))
            ? DuckLakeConstants.DEF_METADATA_SCHEMA : cfg.getProviderProperty(DuckLakeConstants.PROP_METADATA_SCHEMA);

        String alias = CommonUtils.isEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_LAKE_ALIAS))
            ? metadataSchema : cfg.getProviderProperty(DuckLakeConstants.PROP_LAKE_ALIAS);

        String defaultSchema = CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_DEFAULT_SCHEMA)).trim();

        boolean useS3 = dataPath.startsWith("s3://") || !s3endpoint.isEmpty() || !s3key.isEmpty();
        String initSql = buildInitSql(host, port, db, user, pass,
            useS3, s3endpoint, s3key, s3secret, s3region, s3style, s3ssl, dataPath, alias, metadataSchema, defaultSchema);

        try {
            return connectionURL(writeInitFile(initSql));
        } catch (IOException e) {
            log.error("Failed to write DuckLake init SQL file; falling back to plain DuckDB URL", e);
            return "jdbc:duckdb:";
        }
    }

    private static String connectionURL(String initFilePath) {
        return "jdbc:duckdb:;session_init_sql_file=" + initFilePath + ";jdbc_pin_db=true;jdbc_stream_results=true;";
    }

    /** The init file a URL from {@link #getConnectionURL} points at, or null for the plain fallback URL. */
    public static File initFileFromURL(String url) {
        String path = initFilePath(url);
        return path == null ? null : new File(path);
    }

    private static String initFilePath(String url) {
        Matcher m = INIT_FILE_PATTERN.matcher(url == null ? "" : url);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Copy the init file behind {@code url} to a file private to one connection profile, and return
     * the URL of the copy. Discovery edits the init file, and profiles with identical settings but
     * different discovery options would otherwise share (and clobber) one file. The shared original
     * is never edited. A URL without an init file is returned unchanged.
     */
    public static String privateConnectionURL(String url, String connectionId) throws IOException {
        String sharedPath = initFilePath(url);
        if (sharedPath == null || !new File(sharedPath).isFile()) {
            return url;
        }

        String sql = Files.readString(Path.of(sharedPath), StandardCharsets.UTF_8);
        File own = initFile(sql, connectionId);
        writeAtomically(own, sql);
        return url.replace(sharedPath, own.getAbsolutePath().replace('\\', '/'));
    }

    private static String buildInitSql(
        String host, String port, String db, String user, String pass,
        boolean useS3, String s3endpoint, String s3key, String s3secret, String s3region,
        String s3style, boolean s3ssl, String dataPath, String alias, String metadataSchema, String defaultSchema
    ) {
        StringBuilder b = new StringBuilder(1024);
        b.append("INSTALL ducklake; LOAD ducklake;\n");
        b.append("INSTALL postgres; LOAD postgres;\n");
        if (useS3) {
            b.append("INSTALL httpfs;   LOAD httpfs;\n");

            List<String> secret = new ArrayList<>();
            boolean hasKey = !s3key.isEmpty();
            secret.add("TYPE s3");
            secret.add("PROVIDER " + (hasKey ? "config" : "credential_chain"));
            if (hasKey) {
                secret.add("KEY_ID " + q(s3key));
                secret.add("SECRET " + q(s3secret));
            }
            if (!s3endpoint.isEmpty()) {
                secret.add("ENDPOINT " + q(s3endpoint));
            }
            secret.add("REGION " + q(s3region.isEmpty() ? DuckLakeConstants.DEF_S3_REGION : s3region));
            if (!s3style.isEmpty()) {
                secret.add("URL_STYLE " + q(s3style));
            }
            secret.add("USE_SSL " + s3ssl);
            b.append("CREATE OR REPLACE SECRET ducklake_s3 (\n    ")
                .append(String.join(",\n    ", secret))
                .append("\n);\n");
        }

        String pgConn = buildPostgresConnString(host, port, db, user, pass);

        List<String> options = new ArrayList<>();
        if (!dataPath.isEmpty()) {
            options.add("DATA_PATH " + q(dataPath));
        }
        options.add("METADATA_SCHEMA " + q(metadataSchema));

        b.append("ATTACH ").append(q("ducklake:postgres:" + pgConn))
            .append(" AS ").append(id(alias))
            .append(" (").append(String.join(", ", options)).append(")");
        b.append(";\n");
        b.append(DuckLakeConstants.INIT_MARKER).append("\n");

        // Below the marker, so every physical connection starts in the primary catalog (and schema).
        b.append("USE ").append(id(alias));
        if (!defaultSchema.isEmpty()) {
            b.append(".").append(id(defaultSchema));
        }

        b.append(";\n");
        return b.toString();
    }

    /**
     * Build the space-joined Postgres connection string (e.g. {@code dbname=… host=… port=… user=…
     * password=…}), omitting any empty field. Shared by the init file and by the catalog-discovery
     * code so both use an identical connection string.
     */
    public static String buildPostgresConnString(String host, String port, String db, String user, String pass) {
        List<String> pg = new ArrayList<>();
        if (!CommonUtils.isEmpty(db)) pg.add("dbname=" + db);
        if (!CommonUtils.isEmpty(host)) pg.add("host=" + host);
        if (!CommonUtils.isEmpty(port)) pg.add("port=" + port);
        if (!CommonUtils.isEmpty(user)) pg.add("user=" + user);
        if (!CommonUtils.isEmpty(pass)) pg.add("password=" + pass);

        return String.join(" ", pg);
    }

    private static String writeInitFile(String sql) throws IOException {
        File f = initFile(sql, null);
        writeAtomically(f, sql);
        return f.getAbsolutePath().replace('\\', '/');
    }

    /**
     * Init files are named after a hash of their generated content, plus the owning connection's id
     * for private copies. Connections that differ in any setting (metadata schema, default schema,
     * credentials, ...) never share a file, even with the same alias.
     */
    private static File initFile(String sql, String connectionId) throws IOException {
        File dir = new File(System.getProperty("java.io.tmpdir"), "dbeaver-ducklake");

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(sql.getBytes(StandardCharsets.UTF_8));
            if (connectionId != null) {
                md.update(("\n#" + connectionId).getBytes(StandardCharsets.UTF_8));
            }

            return new File(dir, "init-" + HexFormat.of().formatHex(md.digest(), 0, 8) + ".sql");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
    }

    /** Replace {@code f} in one step, so a DuckDB instance opening concurrently never reads half a file. */
    private static void writeAtomically(File f, String content) throws IOException {
        File dir = f.getParentFile();
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new IOException("Cannot create init dir: " + dir);
        }

        Path tmp = Files.createTempFile(dir.toPath(), "init-", ".tmp");

        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);

            try {
                Files.move(tmp, f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    static final String DISCOVERY_BEGIN = "-- DUCKLAKE_DISCOVERY_BEGIN";
    static final String DISCOVERY_END = "-- DUCKLAKE_DISCOVERY_END";

    /**
     * Rewrite the generated init file so the discovered catalogs are ATTACHed by every future
     * physical connection. DBeaver opens separate physical connections (navigator metadata, each
     * SQL editor), and despite {@code jdbc_pin_db} they can end up on separate DuckDB instances —
     * an ATTACH performed on one instance is invisible to the others, so the discovered ATTACH
     * statements must live in the init file, which every new instance replays.
     *
     * <p>The statements go into the once-per-instance section (above the marker), inside a
     * begin/end comment block that is replaced wholesale on each rediscovery. Only ATTACHes that
     * succeeded during discovery are written, and all use IF NOT EXISTS, so replaying them is safe.
     */
    public static void updateInitFileDiscoveries(File f, List<String> attachStatements) throws IOException {
        if (!f.isFile()) {
            return;
        }

        List<String> out = new ArrayList<>();
        boolean inOldBlock = false;

        for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
            if (line.equals(DISCOVERY_BEGIN)) {
                inOldBlock = true;
                continue;
            }
            if (line.equals(DISCOVERY_END)) {
                inOldBlock = false;
                continue;
            }
            if (inOldBlock) {
                continue;
            }

            if (line.equals(DuckLakeConstants.INIT_MARKER) && !attachStatements.isEmpty()) {
                out.add(DISCOVERY_BEGIN);
                out.addAll(attachStatements);
                out.add(DISCOVERY_END);
            }

            out.add(line);
        }

        writeAtomically(f, String.join("\n", out) + "\n");
    }

    /**
     * Put the discovery block back if the file was regenerated without it, e.g. by a Test Connection
     * on the same connection profile.
     */
    public static void restoreInitFileDiscoveries(File f, List<String> attachStatements) throws IOException {
        if (attachStatements.isEmpty() || !f.isFile()
            || Files.readAllLines(f.toPath(), StandardCharsets.UTF_8).contains(DISCOVERY_BEGIN)) {
            return;
        }

        updateInitFileDiscoveries(f, attachStatements);
    }

    /** Quote a SQL string literal, escaping single quotes. */
    static String q(String s) {
        return "'" + (s == null ? "" : s.replace("'", "''")) + "'";
    }

    /** Quote a SQL identifier with double quotes. */
    static String id(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
    }
}
