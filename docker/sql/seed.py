"""Create + populate the local DuckLake catalogs with generic demo data.

Runs inside the compose network (service-name endpoints). First creates the extra
Postgres database and the metadata schemas the catalogs live in, then executes
seed.sql statement-by-statement (robust across DuckDB versions) and prints row counts.
"""

import duckdb
import psycopg

PG = dict(host="postgres", port=5432, user="postgres", password="postgres")

# database -> non-public metadata schemas it holds (see the table at the top of seed.sql)
LAYOUT = {
    "ducklake_catalog": ["sales", "finance"],
    "analytics": ["ml"],
}

TABLES = [
    "lake.main.customers",
    "lake.main.orders",
    "sales.main.deals",
    "sales.emea.leads",
    "finance.main.invoices",
    "analytics.main.events",
    "analytics_ml.main.features",
]

# CREATE DATABASE cannot run inside a transaction, hence autocommit.
with psycopg.connect(dbname="ducklake_catalog", autocommit=True, **PG) as pg:
    for database in LAYOUT:
        if not pg.execute("SELECT 1 FROM pg_database WHERE datname = %s", (database,)).fetchone():
            pg.execute(f'CREATE DATABASE "{database}"')

for database, schemas in LAYOUT.items():
    with psycopg.connect(dbname=database, autocommit=True, **PG) as pg:
        for schema in schemas:
            pg.execute(f'CREATE SCHEMA IF NOT EXISTS "{schema}"')

with open("/sql/seed.sql", "r", encoding="utf-8") as fh:
    script = fh.read()

con = duckdb.connect()  # in-memory coordinator; DuckLake persists to Postgres + S3

# extract_statements splits the script safely; fall back to a single execute().
try:
    statements = [s.query for s in duckdb.extract_statements(script)]
except Exception:
    statements = [script]

for stmt in statements:
    con.execute(stmt)

# lake.main.blocks: a chain-shaped table with many small files, for the plugin's query guard and
# its "newest file" default filter. Every INSERT writes one Parquet file, so BLOCK_FILES inserts of
# BLOCK_ROWS rows each, in height order, give BLOCK_FILES files whose height ranges do not overlap,
# the same layout as the production bronze tables (partitioned by height_bucket, sorted by height).
# The payload is two md5 strings per row so the files are about 2 MB and do not compress away.
# CREATE OR REPLACE keeps the seed idempotent; the old files stay on S3 as orphans, which only a
# DuckLake cleanup would remove and nothing here reads.
BLOCK_FILES = 40
BLOCK_ROWS = 25_000

con.execute("""
    CREATE OR REPLACE TABLE lake.main.blocks (
        height_bucket BIGINT,
        height        BIGINT,
        hash          VARCHAR,
        payload       VARCHAR
    )
""")
con.execute("ALTER TABLE lake.main.blocks SET PARTITIONED BY (height_bucket)")

for i in range(BLOCK_FILES):
    start = i * BLOCK_ROWS
    con.execute(f"""
        INSERT INTO lake.main.blocks
        SELECT h // 1000000, h, md5(h::VARCHAR), md5((h * 7)::VARCHAR) || md5((h * 13)::VARCHAR)
        FROM range({start}, {start + BLOCK_ROWS}) t(h)
    """)

TABLES.append("lake.main.blocks")

print("--- SEED OK ---")

for table in TABLES:
    print(f"{table}: {con.execute(f'SELECT COUNT(*) FROM {table}').fetchone()[0]} rows")

con.close()
