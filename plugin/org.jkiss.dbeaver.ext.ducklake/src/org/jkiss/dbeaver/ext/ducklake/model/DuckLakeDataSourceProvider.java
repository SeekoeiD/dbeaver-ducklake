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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

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
 */
public class DuckLakeDataSourceProvider extends DuckDBDataSourceProvider {

    private static final Log log = Log.getLog(DuckLakeDataSourceProvider.class);

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
        String alias = CommonUtils.isEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_LAKE_ALIAS))
            ? DuckLakeConstants.DEF_ALIAS : cfg.getProviderProperty(DuckLakeConstants.PROP_LAKE_ALIAS);

        boolean useS3 = dataPath.startsWith("s3://") || !s3endpoint.isEmpty() || !s3key.isEmpty();
        String initSql = buildInitSql(host, port, db, user, pass,
            useS3, s3endpoint, s3key, s3secret, s3region, s3style, s3ssl, dataPath, alias);

        try {
            String initFilePath = writeInitFile(initSql, host, port, db, alias);
            return "jdbc:duckdb:;session_init_sql_file=" + initFilePath
                + ";jdbc_pin_db=true;jdbc_stream_results=true;";
        } catch (IOException e) {
            log.error("Failed to write DuckLake init SQL file; falling back to plain DuckDB URL", e);
            return "jdbc:duckdb:";
        }
    }

    private static String buildInitSql(
        String host, String port, String db, String user, String pass,
        boolean useS3, String s3endpoint, String s3key, String s3secret, String s3region,
        String s3style, boolean s3ssl, String dataPath, String alias
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

        List<String> pg = new ArrayList<>();
        if (!db.isEmpty()) pg.add("dbname=" + db);
        if (!host.isEmpty()) pg.add("host=" + host);
        if (!port.isEmpty()) pg.add("port=" + port);
        if (!user.isEmpty()) pg.add("user=" + user);
        if (!pass.isEmpty()) pg.add("password=" + pass);

        b.append("ATTACH ").append(q("ducklake:postgres:" + String.join(" ", pg)))
            .append(" AS ").append(id(alias));
        if (!dataPath.isEmpty()) {
            b.append(" (DATA_PATH ").append(q(dataPath)).append(")");
        }
        b.append(";\n");
        b.append(DuckLakeConstants.INIT_MARKER).append("\n");
        b.append("USE ").append(id(alias)).append(";\n");
        return b.toString();
    }

    private static String writeInitFile(String sql, String host, String port, String db, String alias) throws IOException {
        File dir = new File(System.getProperty("java.io.tmpdir"), "dbeaver-ducklake");
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new IOException("Cannot create init dir: " + dir);
        }
        String key = Integer.toHexString((host + ":" + port + "/" + db + "#" + alias).hashCode());
        File f = new File(dir, "init-" + key + ".sql");
        Files.writeString(f.toPath(), sql, StandardCharsets.UTF_8);
        return f.getAbsolutePath().replace('\\', '/');
    }

    /** Quote a SQL string literal, escaping single quotes. */
    private static String q(String s) {
        return "'" + (s == null ? "" : s.replace("'", "''")) + "'";
    }

    /** Quote a SQL identifier with double quotes. */
    private static String id(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
    }
}
