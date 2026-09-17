# dbeaver-ducklake

DBeaver plugin that adds DuckLake (DuckDB lakehouse: a Postgres catalog plus Parquet files on S3) as a
connection type. Two OSGi bundles under `plugin/`, a local demo stack under `docker/`, and a no-plugin
reference setup under `quickstart/`. Public repo on GitHub, `SeekoeiD/dbeaver-ducklake` (personal
account, so the `seekoied` rules from the global CLAUDE.md apply: PRs straight to `main`, no issue
required, Codex review runs locally before the PR is opened).

## Layout

| Path | What |
|---|---|
| `plugin/org.jkiss.dbeaver.ext.ducklake/` | Model bundle. Data source provider, catalog discovery, meta model. All the logic lives here. |
| `plugin/org.jkiss.dbeaver.ext.ducklake.ui/` | UI bundle. The connection wizard page and the "DuckLake storage" tab. |
| `plugin/dist/` | Built jars (gitignored). `build.ps1` writes here, `install.ps1` reads from here. |
| `docker/` | Demo stack: Postgres 16 on host port 5433, RustFS (S3) on 9000/9001, a one-shot seed container. Five catalogs over two databases. |
| `docker/sql/seed.py`, `seed.sql` | Creates the demo databases and schemas, then attaches and populates the catalogs with DuckDB. |
| `quickstart/` | Same trick with the stock DuckDB driver and a hand-written `init.sql`. Kept for reference. |
| `build.ps1`, `install.ps1`, `install.sh`, `uninstall.*` | Build and install scripts. Windows is the primary dev platform. |
| `docs/images/` | README screenshots. |

## How the plugin works

Read `DuckLakeDataSourceProvider.java` first, then `DuckLakeDataSource.java`, then
`DuckLakeCatalogDiscovery.java`. The README's "How it works" section is accurate.

- The driver is plain DuckDB JDBC. The provider builds the URL itself: it writes a DuckDB
  `session_init_sql_file` under `%TEMP%/dbeaver-ducklake/init-<sha256 prefix>.sql` and returns
  `jdbc:duckdb:;session_init_sql_file=...;jdbc_pin_db=true;jdbc_stream_results=true;`.
- The init file only does INSTALL/LOAD (ducklake, postgres, httpfs) and CREATE OR REPLACE SECRET for
  S3 if any S3 field is set. No ATTACH, no USE, no `DUCKDB_CONNECTION_INIT_BELOW_MARKER`: the URL has
  no database path, and for an in-memory database the driver runs the whole file on every connection
  anyway (`DuckDBDriver.runSessionInitSQLFile` skips its once-per-instance check when the db name is
  `:memory:`). Nothing in the file can fail on a permission or a stale DATA_PATH, which matters
  because a failure there kills the connection where nothing can catch it.
- All the ATTACHes live in `DuckLakeCatalogDiscovery`, called from
  `DuckLakeDataSource.initializeContextState`. Discovery runs lazily on the first context (the Main
  one, opened inside the super constructor, so field initializers have not run yet: rely only on
  default null/false values and on `getContainer()`), guarded by `synchronized (this)`. It attaches a
  read-only Postgres passthrough, asks `pg_class` which schemas hold a `ducklake_metadata` table,
  probes the other databases the role can CONNECT to, then ATTACHes every candidate one at a time,
  each in its own try/catch. Candidate order: the configured metadata schema, or `public` when that
  field is blank, then the rest of this database alphabetically, then other databases. The first
  ATTACH that works becomes the active catalog and gets the `USE`. Only the Postgres passthrough is
  fatal (bad host or credentials); zero catalogs attached throws a DBException listing the warnings.
- Only schemas that already hold a `ducklake_metadata` table become candidates, the preferred one
  included. DuckLake's ATTACH CREATEs a catalog when the schema has none, so attaching the preferred
  schema blind let a role with CREATE rights leave a stray empty catalog behind on nothing worse than
  a typo in the Metadata schema field. A non-empty DATA_PATH is the only thing that lets a
  catalog-less schema be attached, and it warns that it is creating one. `DUCKLAKE_SCHEMAS_SQL` now
  runs against `META_ALIAS` even with "Discover all catalogs" off, because that list is what decides.
- The ATTACH + USE run before `super.initializeContextState`, because that is where DBeaver reads
  the context's active catalog and schema back: `GenericDataSource.initializeContextState` calls
  `refreshDefaults` on the Main context (DuckDB's context then runs `SELECT current_catalog()`) and
  `initDefaultsFrom` on later ones, which issues a JDBC `setCatalog` and resets DuckDB's schema to
  `main`. That last part is why `restoreDefaultSchema` still exists.
- Every later execution context replays the stored ATTACH statements one at a time, logs a warning
  for each that fails, and runs the same USE.
- DATA_PATH is only offered to the preferred primary, and an ATTACH that fails with "does not match
  existing data path" is retried without it plus a warning. Never use OVERRIDE_DATA_PATH: it rewrites
  the catalog instead of reading it.
- `DuckLakeGenericCatalog.isSystem` marks `memory`, `system`, `temp` and the two discovery
  passthrough aliases as system catalogs so DBeaver hides them without an object filter.
- Connection settings are DBeaver provider properties with keys in `DuckLakeConstants`. The same
  keys are declared in the model bundle's `plugin.xml` and read by the UI page. Add a setting in all
  three places.

## Build and install

No Maven or Tycho. `build.ps1` compiles with `javac --release 21` against every jar in a DBeaver
install's `plugins/` folder and packages two jars with `jar`.

```powershell
.\build.ps1                       # defaults: %LOCALAPPDATA%\DBeaver, C:\Program Files\Java\jdk-21
.\install.ps1                     # copies plugin\dist\*.jar into DBeaver and registers them in bundles.info
& "$env:LOCALAPPDATA\DBeaver\dbeaver.exe" -clean
```

- The build needs DBeaver 26.1.x installed. The plugin links against DBeaver's internal
  `org.jkiss.dbeaver.ext.duckdb` and `ext.generic` classes, so a DBeaver upgrade can break the
  compile. Fix the code, don't pin an older DBeaver.
- DBeaver does not load loose `dropins/`. The installers edit
  `configuration/org.eclipse.equinox.simpleconfigurator/bundles.info` and keep a `.orig` backup.
  Always restart DBeaver with `-clean` after install or uninstall.
- `install.ps1` writes `bundles.info` without a BOM on purpose. A BOM breaks Equinox.
- The DuckDB JDBC version is pinned in the model `plugin.xml` (`maven:/org.duckdb:duckdb_jdbc:1.5.4.0`)
  and the seed container pins `DUCKDB_VERSION` in `docker/.env.example`. Keep both on the same
  DuckDB minor line, otherwise the DuckLake catalog format the seed writes may not match what the
  plugin reads. DuckLake v1.0 catalogs need DuckDB 1.5.2 or newer.
- There are no automated tests. Verification is manual: build, install, restart with `-clean`,
  connect to the demo stack, expand the tree. The `DuckLakeCatalogDiscovery` class works on a plain
  JDBC connection and can be exercised outside DBeaver, and the DuckDB Python module reproduces
  what the init file does (see `docker/sql/seed.sql` for the SQL shape).

## Releasing

The version string lives in four files and must match: both `META-INF/MANIFEST.MF` files
(`Bundle-Version`), `install.ps1` (`$version`) and `install.sh` (`VERSION`). `build.ps1` reads the
model manifest to name the jars. A GitHub Release ships the two jars plus the install scripts in one
folder.

## Demo stack

```bash
cd docker && docker compose up -d
```

Postgres `localhost:5433`, user and password `postgres`, database `ducklake_catalog`. S3 endpoint
`localhost:9000`, key and secret `rustfsadmin`, URL style `path`, SSL off. `docker/.env` is local and
gitignored; `docker/.env.example` is the committed template. `docker/pg-data/` and
`docker/rustfs-data/` hold the stack's state and are gitignored. The seed is idempotent (DELETE then
INSERT), so `docker compose up` can be re-run.

## Things that bit before

- "Failed to probe DuckLake metadata ... permission denied for table ducklake_metadata" means the
  Postgres role cannot SELECT the `ducklake_*` tables in that schema. It used to fail the connection
  when it hit the primary catalog, which the default `public` made routine for a read-only role on a
  server whose real catalogs live in `bronze`, `silver`, ... Since 1.2.0 it is only a warning in the
  error log and the next readable catalog becomes active, so the fix is no longer mandatory. Setting
  "Metadata schema (Postgres)" still picks which catalog you land in. Superusers never see any of
  this, so test with the least-privileged role.
- A stale DATA_PATH gives "DATA_PATH parameter ... does not match existing data path in the catalog
  ...". The location stored in the catalog is the real one; the field is only for creating a new
  catalog. Discovery retries that ATTACH without DATA_PATH and warns. Do not reach for
  OVERRIDE_DATA_PATH, it rewrites the catalog's stored location.
- An empty `public` DuckLake catalog on a server whose real catalogs live elsewhere was almost
  certainly created by an earlier build of this plugin: it ATTACHed the metadata schema blind, and
  DuckLake CREATEs a catalog when the schema has none. Fixed in 1.2.0. If you find such a catalog,
  check whether it is empty before dropping it.
- Every ATTACH failure from DuckDB repeats the whole libpq connection string, password included.
  Route messages through `DuckLakeCatalogDiscovery.redact` before logging them.
- DuckDB identifiers compare case-insensitively even when quoted. Name comparisons in discovery use
  `String.CASE_INSENSITIVE_ORDER` for that reason.
- The `.gitattributes` forces LF for `*.sh` and CRLF for `*.ps1`. Don't fight it.
- `hs_err_pid*.log` and `replay_pid*.log` in the repo root are JVM crash dumps from DBeaver runs.
  They are noise; delete them.

## Style

Java follows DBeaver's conventions (4-space indent, `@NotNull` from `org.jkiss.code`, `Log` from
`org.jkiss.dbeaver`). Every class has a Javadoc that explains why it exists, not just what it does.
Keep that up. Blank line after every closing brace before the next statement, per the global rules.
