# DuckLake plugin for DBeaver

Adds **DuckLake** (DuckDB's lakehouse format — a SQL catalog + Parquet data on object storage) as a
first-class connection type in [DBeaver](https://dbeaver.io), so you can browse a lake's schemas and
tables in the navigator like any other database.

It's built on top of DBeaver's bundled DuckDB support and adds:

- A dedicated **DuckLake** driver/connection type (with the DuckLake icon).
- A connection page: standard **Host/Port/Database/User/Password** for the **catalog** database
  (Postgres), plus a **DuckLake storage (S3)** tab for the object store.
- **Reliable table browsing** — the plugin generates a DuckDB `session_init_sql_file` so the
  `ATTACH` runs on *every* physical connection (SQL editor **and** the navigator's metadata
  connection). This is the fix for the well-known "DuckLake tables don't show up in DBeaver" problem.
- A clean tree — DuckDB's internal `memory`/`system`/`temp` databases are hidden, leaving just your
  lake(s).

Works with a local S3 (RustFS/MinIO), real **AWS S3** (leave the key blank to use the credential
chain), or a local-filesystem lake — empty fields are simply omitted from the generated SQL.

## Compatibility

- **DBeaver Community/PRO 26.1.x** (tested against 26.1.1 and 26.1.5). The plugin links against
  DBeaver's internal `org.jkiss.dbeaver.ext.duckdb` and `…ext.generic` APIs, which ship with
  DBeaver — so it needs a DBeaver version with compatible APIs. Other versions may require a rebuild
  (see [Build from source](#build-from-source)).
- Uses the DuckDB JDBC driver **1.5.4.0** (pulled from Maven on first connect). DuckLake v1.0 needs
  DuckDB ≥ 1.5.2.

## Install

1. Download the two jars **and** `install.ps1` (Windows) / `install.sh` (Linux/macOS) from the latest
   [Release](../../releases) into one folder.
2. Run the installer (it copies the jars into DBeaver's `plugins/` and registers them in Equinox
   `bundles.info` — this DBeaver build does **not** load loose `dropins/`):

   **Windows** (DBeaver Community default location):
   ```powershell
   .\install.ps1
   # or:  .\install.ps1 -DBeaverPath "C:\Program Files\DBeaver"
   ```
   **Linux/macOS** (pass the install dir containing `plugins/`):
   ```bash
   ./install.sh /opt/dbeaver
   # macOS: ./install.sh "/Applications/DBeaver.app/Contents/Eclipse"
   ```
3. **Restart DBeaver once with `-clean`** so Equinox rebuilds from `bundles.info`:
   ```
   <dbeaver>/dbeaver -clean
   ```

Uninstall with `uninstall.ps1` / `uninstall.sh` (same arguments), then restart with `-clean`.

## Usage

**Database → New Database Connection → DuckLake.**

| Field | Meaning |
|---|---|
| Host / Port / Database / User / Password | your **DuckLake catalog** (a Postgres database) |
| DuckLake storage (S3) → Endpoint | e.g. `localhost:9000`, or blank for AWS default |
| … Access key / Secret key | S3 credentials (blank key → AWS credential chain) |
| … Region / URL style / Use SSL | `path` style for MinIO/RustFS; SSL on for https endpoints |
| … DATA_PATH | storage location, e.g. `s3://bucket/prefix/` (only needed when the ATTACH creates a brand-new catalog; existing catalogs store it in their metadata) |
| DuckLake catalog → Metadata schema (Postgres) | Postgres schema holding the **primary** catalog's `ducklake_*` metadata tables (default `public`) |
| … Catalog alias | name the primary catalog is attached as (defaults to the metadata schema name, e.g. `public`) |
| … Default schema (DuckLake) | schema inside the primary catalog that unqualified table names resolve to (default `main`) |
| … Discover and attach all DuckLake catalogs in this database | on by default. Attaches every other DuckLake catalog in the database, one per Postgres schema |
| … Also discover DuckLake catalogs in other databases on this server | on by default. Attaches the catalogs of every other database you can connect to, as `<database>.<schema>` |

Connect, expand the lake → schema → **Tables**, and browse. To change what's hidden, see
**Connection settings → Filters** / the navigator's *Show system objects* toggle.

### Many catalogs, one connection

A DuckLake catalog is a set of `ducklake_*` metadata tables in one Postgres schema. DuckDB's
`METADATA_SCHEMA` option picks that schema and defaults to `public`. So one Postgres server can hold
many catalogs, as several schemas in one database, spread over several databases, or both.

A single DuckLake connection shows all of them:

- The primary catalog is the **Metadata schema (Postgres)** in the database you entered. It is
  attached under **Catalog alias** and is the current catalog in new SQL editors.
- Every other schema in that database that holds a DuckLake appears as its own top-level node, named
  after the schema, e.g. `sales` or `finance`.
- The plugin also probes every other database on the server you have `CONNECT` rights on. Their
  catalogs appear as `<database>.<schema>`, e.g. `analytics.public`. The dot is part of the name, so
  quote it in SQL: `SELECT * FROM "analytics.public".main.events`.

Discovery runs when you connect, so reconnect to see a catalog created later. A database or catalog
that can't be reached is skipped with a warning in DBeaver's error log. Untick both discovery options
to mount only the primary catalog.

**Default schema (DuckLake)** makes every connection start in that schema of the primary catalog. The
plugin runs `USE "<alias>"."<schema>"` on each connection, so `SELECT * FROM leads` works without a
prefix. If the schema doesn't exist, connecting fails with `No catalog + schema named ...`. Picking
another catalog in DBeaver's active catalog selector puts you in that catalog's `main` schema.

## Try it locally (optional demo stack)

`docker/` spins up a complete local DuckLake to test against: **RustFS** (S3) + **Postgres**
(catalog), seeded with five catalogs over two databases.

```bash
cd docker
docker compose up -d      # S3 on :9000 (console :9001), Postgres on host :5433
```

| Postgres database | Metadata schema | Appears in DBeaver as | Tables |
|---|---|---|---|
| `ducklake_catalog` | `public` | `public` | `main.customers`, `main.orders` |
| `ducklake_catalog` | `sales` | `sales` | `main.deals`, `emea.leads` |
| `ducklake_catalog` | `finance` | `finance` | `main.invoices` |
| `analytics` | `public` | `analytics.public` | `main.events` |
| `analytics` | `ml` | `analytics.ml` | `main.features` |

Make a DuckLake connection with Host `localhost`, Port `5433`, Database `ducklake_catalog` and
User/Password `postgres`. On the storage tab set Endpoint `localhost:9000`, key and secret
`rustfsadmin`, URL style `path`, SSL off, and leave the rest blank. All five catalogs show up. To start
SQL editors in `sales.emea` instead, set Metadata schema `sales` and Default schema `emea`.

`quickstart/` shows how to get the same result with the **stock** DuckDB driver (no plugin), for
reference.

## Build from source

Needs JDK 21 and a DBeaver install to compile against (no Maven/Tycho):

```powershell
.\build.ps1          # -> plugin/dist/*.jar
```

Then run `install.ps1` from `plugin/dist` (or copy the jars next to `install.ps1`).

## How it works

- The datasource extends DBeaver's `GenericDataSourceProvider`; `getConnectionURL` writes a
  `session_init_sql_file` (INSTALL/LOAD extensions → `CREATE SECRET` → `ATTACH 'ducklake:postgres:…'`
  → `USE`) and returns `jdbc:duckdb:;session_init_sql_file=…;jdbc_pin_db=true`. The DuckDB driver runs
  that file on every connection, so the attached catalog is visible to the navigator.
- On connect, the data source asks Postgres through DuckDB's `postgres_query` which schemas contain a
  `ducklake_metadata` table, first in the connection's database and then in each other database. It
  attaches every catalog it finds and adds those `ATTACH` statements to the init file. A SQL editor
  connection can run on a separate DuckDB instance that only knows what the init file tells it.
- The metadata model extends DBeaver's DuckDB model. It marks `memory`/`system`/`temp` as system
  catalogs (hidden via *Show system objects = off*) and lists a lake's tables via `duckdb_tables()`.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE). Builds on DBeaver's Apache-2.0
code (© DBeaver Corp).
