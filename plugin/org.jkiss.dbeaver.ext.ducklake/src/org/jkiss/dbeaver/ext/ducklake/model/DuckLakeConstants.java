/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.model;

/**
 * Provider-property keys and the few generic fallbacks for a DuckLake connection.
 *
 * <p>Host / Port / Database / User / Password on the standard connection page map to the catalog
 * database (Postgres). The storage (S3) settings below are entered on the "DuckLake storage" tab.
 * No environment-specific values are baked in — empty fields are simply omitted from the generated
 * ATTACH / CREATE SECRET statements.
 */
public final class DuckLakeConstants {

    // S3 / storage provider-property keys (see plugin.xml <provider-properties>)
    public static final String PROP_S3_ENDPOINT = "ducklake.s3.endpoint";
    public static final String PROP_S3_KEY = "ducklake.s3.key";
    public static final String PROP_S3_SECRET = "ducklake.s3.secret";
    public static final String PROP_S3_REGION = "ducklake.s3.region";
    public static final String PROP_S3_URL_STYLE = "ducklake.s3.url_style";
    public static final String PROP_S3_USE_SSL = "ducklake.s3.use_ssl";
    public static final String PROP_DATA_PATH = "ducklake.data_path";
    public static final String PROP_LAKE_ALIAS = "ducklake.alias";

    // Generic fallbacks only (not environment-specific).
    public static final String DEF_ALIAS = "lake";
    public static final String DEF_S3_REGION = "us-east-1";

    /** Marker understood by the DuckDB JDBC driver's session_init_sql_file:
     *  statements above run once per DB instance, statements below run on every connection. */
    public static final String INIT_MARKER = "/* DUCKDB_CONNECTION_INIT_BELOW_MARKER */";

    private DuckLakeConstants() {
    }
}
