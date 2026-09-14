-- Seed the local DuckLake stack with generic demo data.
-- Runs INSIDE the compose network, so endpoints use Docker SERVICE NAMES
-- (rustfs:9000, postgres:5432) — NOT localhost.
-- This runs BEFORE any DBeaver connection so the tables already exist on first browse.
--
-- One Postgres server hosts five DuckLake catalogs. A catalog is the set of ducklake_* metadata
-- tables in one Postgres schema, so it is identified by (database, METADATA_SCHEMA):
--
--   database          METADATA_SCHEMA  seed alias    DBeaver plugin node
--   ducklake_catalog  public           lake          public (primary)
--   ducklake_catalog  sales            sales         sales
--   ducklake_catalog  finance          finance       finance
--   analytics         public           analytics     analytics.public
--   analytics         ml               analytics_ml  analytics.ml
--
-- seed.py creates the analytics database and the metadata schemas before this script runs.

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

-- Attach the catalogs: Postgres metadata + Parquet data on S3, one DATA_PATH prefix each.
ATTACH 'ducklake:postgres:dbname=ducklake_catalog host=postgres port=5432 user=postgres password=postgres'
    AS lake (DATA_PATH 's3://ducklake/data/', METADATA_SCHEMA 'public');

ATTACH 'ducklake:postgres:dbname=ducklake_catalog host=postgres port=5432 user=postgres password=postgres'
    AS sales (DATA_PATH 's3://ducklake/sales/', METADATA_SCHEMA 'sales');

ATTACH 'ducklake:postgres:dbname=ducklake_catalog host=postgres port=5432 user=postgres password=postgres'
    AS finance (DATA_PATH 's3://ducklake/finance/', METADATA_SCHEMA 'finance');

ATTACH 'ducklake:postgres:dbname=analytics host=postgres port=5432 user=postgres password=postgres'
    AS analytics (DATA_PATH 's3://ducklake/analytics/', METADATA_SCHEMA 'public');

ATTACH 'ducklake:postgres:dbname=analytics host=postgres port=5432 user=postgres password=postgres'
    AS analytics_ml (DATA_PATH 's3://ducklake/analytics_ml/', METADATA_SCHEMA 'ml');

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

-- sales: a second DuckLake schema (emea) next to main, to try the plugin's "Default schema".
CREATE TABLE IF NOT EXISTS sales.main.deals (
    id       INTEGER,
    customer VARCHAR,
    amount   DECIMAL(10,2),
    stage    VARCHAR
);

DELETE FROM sales.main.deals;

INSERT INTO sales.main.deals VALUES
    (1, 'Ada Lovelace', 1200.00, 'won'),
    (2, 'Grace Hopper',  800.00, 'open'),
    (3, 'Alan Turing',   450.00, 'lost');

CREATE SCHEMA IF NOT EXISTS sales.emea;

CREATE TABLE IF NOT EXISTS sales.emea.leads (
    id      INTEGER,
    company VARCHAR,
    country VARCHAR
);

DELETE FROM sales.emea.leads;

INSERT INTO sales.emea.leads VALUES
    (1, 'Analytical Engines Ltd', 'UK'),
    (2, 'Bletchley Systems',      'UK');

CREATE TABLE IF NOT EXISTS finance.main.invoices (
    id      INTEGER,
    deal_id INTEGER,
    amount  DECIMAL(10,2),
    due     DATE
);

DELETE FROM finance.main.invoices;

INSERT INTO finance.main.invoices VALUES
    (9001, 1, 1200.00, DATE '2026-07-01');

CREATE TABLE IF NOT EXISTS analytics.main.events (
    id   INTEGER,
    kind VARCHAR,
    ts   TIMESTAMP
);

DELETE FROM analytics.main.events;

INSERT INTO analytics.main.events VALUES
    (1, 'page_view', TIMESTAMP '2026-06-01 10:00:00'),
    (2, 'signup',    TIMESTAMP '2026-06-01 10:05:00'),
    (3, 'page_view', TIMESTAMP '2026-06-02 08:30:00');

CREATE TABLE IF NOT EXISTS analytics_ml.main.features (
    entity_id INTEGER,
    feature   VARCHAR,
    value     DOUBLE
);

DELETE FROM analytics_ml.main.features;

INSERT INTO analytics_ml.main.features VALUES
    (1, 'orders_30d', 2.0),
    (3, 'orders_30d', 1.0);
