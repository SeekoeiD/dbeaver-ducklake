"""Create + populate the local DuckLake with generic demo data.

Runs inside the compose network (service-name endpoints). Executes seed.sql
statement-by-statement (robust across DuckDB versions), then prints row counts.
"""

import duckdb

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

print("--- SEED OK ---")

rows = con.execute(
    "SELECT 'customers' AS tbl, COUNT(*) AS n FROM lake.main.customers "
    "UNION ALL "
    "SELECT 'orders', COUNT(*) FROM lake.main.orders"
).fetchall()

print("row counts:", rows)

con.close()
