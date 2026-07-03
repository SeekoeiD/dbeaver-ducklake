-- Seed the local DuckLake with generic demo data.
-- Runs INSIDE the compose network, so endpoints use Docker SERVICE NAMES
-- (rustfs:9000, postgres:5432) — NOT localhost.
-- This runs BEFORE any DBeaver connection so the tables already exist on first browse.

INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
INSTALL httpfs;   LOAD httpfs;

-- S3 credentials for the local RustFS store (path-style, no TLS).
CREATE OR REPLACE SECRET rustfs (
    TYPE s3,
    KEY_ID 'rustfsadmin',
    SECRET 'rustfsadmin',
    ENDPOINT 'rustfs:9000',
    URL_STYLE 'path',
    USE_SSL false,
    REGION 'us-east-1'
);

-- Attach the DuckLake: Postgres catalog + Parquet data on S3. Alias = lake.
ATTACH 'ducklake:postgres:dbname=ducklake_catalog host=postgres port=5432 user=postgres password=postgres'
    AS lake (DATA_PATH 's3://ducklake/data/');

USE lake;

-- Generic demo tables.
CREATE TABLE IF NOT EXISTS customers (
    id      INTEGER,
    name    VARCHAR,
    country VARCHAR
);

-- Idempotent: reset to the demo rows so re-running `docker compose up` doesn't duplicate.
DELETE FROM customers;

INSERT INTO customers VALUES
    (1, 'Ada Lovelace',   'UK'),
    (2, 'Alan Turing',    'UK'),
    (3, 'Grace Hopper',   'US'),
    (4, 'Edsger Dijkstra','NL');

CREATE TABLE IF NOT EXISTS orders (
    id          INTEGER,
    customer_id INTEGER,
    total       DECIMAL(10,2),
    ts          TIMESTAMP
);

DELETE FROM orders;

INSERT INTO orders VALUES
    (100, 1, 42.00, TIMESTAMP '2026-06-01 10:00:00'),
    (101, 3,  7.50, TIMESTAMP '2026-06-02 11:30:00'),
    (102, 2, 19.99, TIMESTAMP '2026-06-03 09:15:00'),
    (103, 1,  5.25, TIMESTAMP '2026-06-04 14:45:00');

-- Sanity output (printed by the seed container).
SELECT 'customers' AS tbl, COUNT(*) AS n FROM customers
UNION ALL
SELECT 'orders', COUNT(*) FROM orders;
