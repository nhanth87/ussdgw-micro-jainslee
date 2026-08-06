# Schema / Flyway — H2 lab + PostgreSQL prod

OTA peer: [`ota-sim-push/docs/agents/schema.md`](../../../../ota-service/ota-sim-push/docs/agents/schema.md).

## Where SQL lives

| Path | Role |
|------|------|
| `src/main/resources/db/migration/V1__ussdgw_baseline.sql` | **Baseline:** full schema (`CREATE IF NOT EXISTS`) + indexes. PostgreSQL + H2 `MODE=PostgreSQL`. |

Config: `quarkus.flyway.locations=classpath:db/migration` (`build/application.properties` → packaged `dist/configs/`).
Startup guard: [`UssdSchemaInitializer`](../../src/main/java/et/restlink/ussdgw/persist/UssdSchemaInitializer.java) — `flyway.repair` + `migrate`, then verify `REQUIRED_TABLES` / `REQUIRED_COLUMNS`; classpath fallback if incomplete. Toggle: `ussd.db.schema-init.enabled`.

## Datasource switch (OTA same pattern)

| Mode | `db-kind` | JDBC URL |
|------|-----------|----------|
| **Lab (default)** | `h2` | `jdbc:h2:file:./data/ussdgw;…;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;…` |
| **Prod** | `postgresql` | `jdbc:postgresql://HOST:5432/ussdgw` |

Both drivers are on the classpath (`quarkus-jdbc-h2` + `quarkus-jdbc-postgresql`). Edit **`dist/configs/application.properties`** (or set `QUARKUS_DATASOURCE_*` env) — **no** Quarkus `%prod` profile required.

```properties
# Prod — replace H2 block
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=ussdgw
quarkus.datasource.password=ussdgw
quarkus.datasource.jdbc.url=jdbc:postgresql://127.0.0.1:5432/ussdgw
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

## Squash policy

| Rule | Detail |
|------|--------|
| On disk | Single `V1__ussdgw_baseline.sql` until an additive `Vn__` is needed |
| `MIGRATIONS` | Must list every `db/migration/*.sql` — pinned by `UssdSchemaInitializerTest` |
| Existing **H2** lab | Wipe `dist/data/ussdgw*` when V1 checksum changes |
| Existing **PostgreSQL** | Operator dump → wipe / reset `flyway_schema_history` → migrate |
| New table/column | Entity + fold into V1 (greenfield) or additive `Vn__` + update `REQUIRED_*` + test |

## Agent checklist

1. New table/column → SQL + Panache entity + `REQUIRED_TABLES` / `REQUIRED_COLUMNS` if boot-critical.
2. Do not use `hibernate.orm.database.generation=update` for Digicom ship.
3. Prove artifact after package: boot log `[ussd-schema] OK`.
