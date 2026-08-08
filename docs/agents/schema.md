# Schema / Flyway — H2 lab + PostgreSQL prod

OTA peer: [`ota-sim-push/docs/agents/schema.md`](../../../../ota-service/ota-sim-push/docs/agents/schema.md).

## Where SQL lives

| Path | Role |
|------|------|
| `src/main/resources/db/migration/V1__ussdgw_baseline.sql` | **Baseline:** full schema (`CREATE IF NOT EXISTS`) + indexes. PostgreSQL + H2 `MODE=PostgreSQL`. |
| `V2__tenant_http_as_wire_format.sql` | Additive: `ussd_tenant.http_as_wire_format` (default `XML`). |
| `V3__short_code_mark.sql` | Additive: `ussd_short_code.mark` (default `FALSE` — prefix / Mark key). |
| `V4__config_value_unicode.sql` | Widen `ussd_config.config_value` to `VARCHAR(4096)` for Unicode bridge wait/fail messages. |
| `V9__short_code_map2map.sql` | Additive: `ussd_short_code.bypass` (default `TRUE`) + `map2map_gt` for MAP2MAP hop. |

Config: `quarkus.flyway.locations=classpath:db/migration` (`build/application.properties` → packaged `dist/configs/`).
Startup guard: [`UssdSchemaInitializer`](../../src/main/java/et/restlink/ussdgw/persist/UssdSchemaInitializer.java) — **migrate first**; `flyway.repair` only when migrate refuses (checksum drift). Then verify `REQUIRED_TABLES` / `REQUIRED_COLUMNS` (includes `ussd_short_code.mark`, `ussd_tenant.http_as_wire_format`); classpath fallback if incomplete. Toggle: `ussd.db.schema-init.enabled`. Keep `quarkus.flyway.repair-at-start=false`.

## Datasource switch (OTA same pattern)

| Mode | `db-kind` | JDBC URL |
|------|-----------|----------|
| **Lab (default)** | `h2` | `jdbc:h2:file:./data/ussdgw;…;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;…` |
| **Prod / Digicom server** | `postgresql` | `jdbc:postgresql://HOST:5432/ussdgw` |

**Hard rules (2026-08-07 lab):** DB name is **`ussdgw`** — **never** point at OTA’s PostgreSQL DB **`ota`**. `quarkus.datasource.db-kind` is **build-time** (Quarkus JDBC extension); switching H2→PG needs a **rebuild / `package-dist.sh`**, not only editing a running process. Local git default stays **file H2**; Digicom PG lives in **server** `dist/configs` (or `QUARKUS_DATASOURCE_*`).

**`package-dist.sh` never clobbers** an existing server `dist/configs/application.properties` — it writes `application.properties.new` via `build/install-config.sh`. Diff before adopting packaged defaults.

Both drivers are on the classpath (`quarkus-jdbc-h2` + `quarkus-jdbc-postgresql`). Edit **`dist/configs/application.properties`** (or set `QUARKUS_DATASOURCE_*` env) — **no** Quarkus `%prod` profile required.

```properties
# Prod — replace H2 block (password via env, not this file)
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=ussdgw
quarkus.datasource.jdbc.url=jdbc:postgresql://127.0.0.1:5432/ussdgw
# export QUARKUS_DATASOURCE_PASSWORD=…
```

Env equivalent: `QUARKUS_DATASOURCE_DB_KIND`, `_USERNAME`, `_PASSWORD`, `_JDBC_URL`.

**Never** `jdbc:h2:mem:` for shipped dist — routing / tenants / CDR / campaigns vanish on restart.

## Flyway keys (required)

```properties
quarkus.flyway.migrate-at-start=true
quarkus.flyway.baseline-on-migrate=true
quarkus.flyway.repair-at-start=true
quarkus.flyway.validate-on-migrate=true
quarkus.hibernate-orm.database.generation=none
```

## Squash / additive policy

| Rule | Detail |
|------|--------|
| On disk | `V1` baseline + additive `Vn__` for new columns (do **not** rewrite V1 after it has shipped — checksum breaks existing DBs) |
| `MIGRATIONS` | Must list every `db/migration/*.sql` — pinned by `UssdSchemaInitializerTest` |
| Existing **H2** lab | Next boot runs pending Flyway (`V2`/`V3`/`V4`). Wipe `dist/data/ussdgw*` only when V1 checksum itself changes |
| Existing **PostgreSQL** | Boot with migrate-at-start applies pending `Vn`. Full wipe / reset `flyway_schema_history` only on V1 squash |
| New table/column | Entity + additive `Vn__` + `REQUIRED_*` + test |

### Current additive columns

| Column | Migration | Default |
|--------|-----------|---------|
| `ussd_tenant.http_as_wire_format` | V2 | `XML` |
| `ussd_short_code.mark` | V3 | `FALSE` |
| `ussd_short_code.bypass` | V9 | `TRUE` (skip MAP2MAP; direct AS) |
| `ussd_short_code.map2map_gt` | V9 | null — redirect USSD e.g. `*875#` when reroute_enable |
| `ussd_short_code.hop_dest_gt` | V11 | null — fixed hop CalledParty GT (SP: `251971200201`) |
| `ussd_short_code.hop_dest_ssn` | V11 | null — fixed hop SSN (default 6 when GT set) |
| `ussd_config.config_value` → `VARCHAR(4096)` | V4 | widen for Unicode bridge msgs |
## Agent checklist

1. New table/column → SQL + Panache entity + `REQUIRED_TABLES` / `REQUIRED_COLUMNS` if boot-critical.
2. Do not use `hibernate.orm.database.generation=update` for Digicom ship.
3. Prove artifact after package: boot log `[ussd-schema] OK`.
