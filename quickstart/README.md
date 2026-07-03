# Quick-start: browse DuckLake with the stock DuckDB driver

This is the **no-plugin** path (Milestone 1). It gets you browsing the local DuckLake in a few
minutes using DBeaver's built-in DuckDB driver, and it uses the exact same idea the plugin
automates. Prefer the plugin (`../plugin/`) for a one-field "DuckLake" connection; use this to
sanity-check the stack or if you don't want to install the plugin.

## Prerequisites

The local stack must be running (see `../docker/`):

```powershell
cd ..\docker
docker compose up -d
```

## Steps in DBeaver

1. **Database → New Database Connection → DuckDB.** (First connect downloads the DuckDB JDBC
   driver — in *Driver Manager → DuckDB → Libraries*, make sure it's **1.5.2.0 or newer**, and
   **not 1.4.0.0**.)
2. On the connection page choose the **URL** tab ("Connect by: URL") and set:
   ```
   jdbc:duckdb:;session_init_sql_file=C:/Users/User/Documents/dbeaver-ducklake/quickstart/init.sql;jdbc_pin_db=true;jdbc_stream_results=true;
   ```
   (Forward slashes, even on Windows.)
3. **Connection settings → Main**: tick **Show all databases** so the attached `lake` catalog is
   visible in the tree.
4. **Test Connection**, then **Finish**.
5. In the navigator expand the connection → **`lake`** catalog → **`main`** schema → press **F5**.
   You'll see **`customers`** and **`orders`**. Double-click to browse data.

## Why the marker line matters

`init.sql` contains a `/* DUCKDB_CONNECTION_INIT_BELOW_MARKER */` line. The DuckDB driver runs
everything **above** it once per DB instance (the `ATTACH`), and everything **below** it on **every**
physical connection (the `USE`). DBeaver opens a *separate* connection for the navigator/metadata, so
running the init on every connection is what makes the tables reliably show up — without this,
the metadata connection never sees the `ATTACH` and the tree looks empty. Verified: a second
JDBC connection lists `customers`/`orders`, and a table created on one connection appears on the
other after refresh.

## Editing the target

`init.sql` points at the local stack (`localhost:5433` Postgres catalog, `localhost:9000` S3,
bucket `ducklake`, `DATA_PATH s3://ducklake/data/`). Edit those values to point elsewhere.
