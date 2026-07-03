-- DuckLake session-init for the STOCK DuckDB driver (Milestone 1 quick-start).
--
-- Point DBeaver's built-in DuckDB driver at this file via the JDBC URL:
--   jdbc:duckdb:;session_init_sql_file=C:/Users/User/Documents/dbeaver-ducklake/quickstart/init.sql;jdbc_pin_db=true;jdbc_stream_results=true;
--
-- The DuckDB driver runs everything ABOVE the marker once per DB instance and the
-- statement(s) BELOW the marker on EVERY physical connection. Because DBeaver opens a
-- separate connection for the navigator/metadata, running USE (and the ATTACH) on every
-- connection is what makes the lake's tables reliably appear in the tree.
--
-- Endpoints are localhost because DBeaver runs on the host (Postgres published on 5433).

INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
INSTALL httpfs;   LOAD httpfs;

CREATE OR REPLACE SECRET ducklake_s3 (
    TYPE s3, PROVIDER config,
    KEY_ID 'rustfsadmin',
    SECRET 'rustfsadmin',
    ENDPOINT 'localhost:9000',
    URL_STYLE 'path',
    USE_SSL false,
    REGION 'us-east-1'
);

ATTACH 'ducklake:postgres:dbname=ducklake_catalog host=localhost port=5433 user=postgres password=postgres'
    AS "lake" (DATA_PATH 's3://ducklake/data/');

/* DUCKDB_CONNECTION_INIT_BELOW_MARKER */
USE "lake";
