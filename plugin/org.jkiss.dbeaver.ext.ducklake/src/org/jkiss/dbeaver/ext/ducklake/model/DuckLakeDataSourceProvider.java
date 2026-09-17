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

/**
 * DuckLake data source provider. Builds the JDBC URL itself: it writes a DuckDB
 * {@code session_init_sql_file} that loads the extensions and creates an S3 secret (only if S3
 * storage is used), then returns a {@code jdbc:duckdb:} URL referencing that file. The DuckDB
 * driver runs the file on every physical connection (SQL editor and the navigator's metadata
 * connection), so every connection has the extensions and the secret in place.
 *
 * <p>Only the values the user actually entered are used — empty fields are omitted, so this works
 * for a local S3 (RustFS/MinIO), real AWS S3 (endpoint blank → credential chain when no key), or a
 * local-filesystem lake (no S3 secret at all).
 *
 * <p>The init file deliberately contains no ATTACH and no USE. A statement that fails in the init
 * file fails the whole connection, and a catalog the Postgres role cannot read is exactly the case
 * that must not do that. So every catalog, the primary one included, is attached one at a time by
 * {@link DuckLakeCatalogDiscovery} from {@link DuckLakeDataSource}, where a failure becomes a
 * warning instead. That also means a connection with nothing filled in under "DuckLake catalog"
 * still opens: discovery picks the first catalog the role can actually read.
 */
public class DuckLakeDataSourceProvider extends DuckDBDataSourceProvider {

    private static final Log log = Log.getLog(DuckLakeDataSourceProvider.class);

    @Override
    public String getConnectionURL(DBPDriver driver, DBPConnectionConfiguration cfg) {
        String s3endpoint = CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_S3_ENDPOINT));
        String s3key = CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_S3_KEY));
        String s3secret = CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_S3_SECRET));
        String s3region = CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_S3_REGION));
        String s3style = CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_S3_URL_STYLE));
        boolean s3ssl = CommonUtils.getBoolean(cfg.getProviderProperty(DuckLakeConstants.PROP_S3_USE_SSL), false);
        String dataPath = CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_DATA_PATH));

        boolean useS3 = dataPath.startsWith("s3://") || !s3endpoint.isEmpty() || !s3key.isEmpty();
        String initSql = buildInitSql(useS3, s3endpoint, s3key, s3secret, s3region, s3style, s3ssl);

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

    /**
     * Name a catalog after where it lives, {@code <database>.<metadata schema>}, so every node in the
     * tree says which Postgres database and schema it comes from. Without a database setting it is
     * just the schema name.
     */
    public static String defaultAlias(String db, String metadataSchema) {
        return CommonUtils.isEmpty(db) ? metadataSchema : db + "." + metadataSchema;
    }

    /**
     * The whole init file: load the extensions every DuckLake connection needs and, when the lake
     * lives on S3, create the secret that reads it. Nothing here can fail because of a Postgres
     * permission or a stale DATA_PATH, which is why the ATTACH and the USE live in
     * {@link DuckLakeCatalogDiscovery} instead.
     *
     * <p>There is no {@code DUCKDB_CONNECTION_INIT_BELOW_MARKER}: for an in-memory database
     * ({@code jdbc:duckdb:} with no path) the DuckDB driver runs the part above the marker on every
     * connection anyway, and INSTALL / LOAD / CREATE OR REPLACE SECRET are all idempotent.
     */
    private static String buildInitSql(
        boolean useS3, String s3endpoint, String s3key, String s3secret,
        String s3region, String s3style, boolean s3ssl
    ) {
        StringBuilder b = new StringBuilder(512);
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

        return b.toString();
    }

    /**
     * Build the space-joined libpq connection string (e.g. {@code dbname=… host=… port=… user=…
     * password=…}), omitting any empty field. Shared by the init file and by the catalog-discovery
     * code so both use an identical connection string.
     */
    public static String buildPostgresConnString(String host, String port, String db, String user, String pass) {
        List<String> pg = new ArrayList<>();
        if (!CommonUtils.isEmpty(db)) pg.add("dbname=" + libpqValue(db));
        if (!CommonUtils.isEmpty(host)) pg.add("host=" + libpqValue(host));
        if (!CommonUtils.isEmpty(port)) pg.add("port=" + libpqValue(port));
        if (!CommonUtils.isEmpty(user)) pg.add("user=" + libpqValue(user));
        if (!CommonUtils.isEmpty(pass)) pg.add("password=" + libpqValue(pass));

        return String.join(" ", pg);
    }

    /**
     * Quote a libpq connection-string value that contains whitespace, a quote or a backslash, such as
     * a database named {@code sales archive} or a password with a space. Other values stay bare, so
     * the generated init file (and its name) doesn't change for them.
     */
    static String libpqValue(String value) {
        if (value.chars().noneMatch(c -> Character.isWhitespace(c) || c == '\'' || c == '\\')) {
            return value;
        }

        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static String writeInitFile(String sql) throws IOException {
        File f = initFile(sql);
        writeAtomically(f, sql);
        return f.getAbsolutePath().replace('\\', '/');
    }

    /**
     * Init files are named after a hash of their generated content, so connections that differ in
     * any S3 setting never share a file. Connections whose storage settings match share one, which
     * is harmless: the file is never modified after it is written, and it no longer holds anything
     * specific to one catalog.
     */
    private static File initFile(String sql) throws IOException {
        File dir = new File(System.getProperty("java.io.tmpdir"), "dbeaver-ducklake");

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8));
            return new File(dir, "init-" + HexFormat.of().formatHex(digest, 0, 8) + ".sql");
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

    /** Quote a SQL string literal, escaping single quotes. */
    static String q(String s) {
        return "'" + (s == null ? "" : s.replace("'", "''")) + "'";
    }

    /** Quote a SQL identifier with double quotes. */
    static String id(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
    }
}
