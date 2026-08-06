# ussdgw-jainslee

RestLink greenfield USSD gateway (Quarkus + micro-jainslee + ra-jss7). Java 25.

## Build

```bash
mise exec -- mvn -B -ntp package -DskipTests
./build/package-dist.sh
cd dist && ./run.sh
```

Admin: `http://127.0.0.1:8088/admin/?key=ussd-admin`  
AS pull (HTTP): short-code rules → POST JSON to AS URL  
AS callback: `POST /as/callback`

## Database (H2 lab / PostgreSQL prod)

Same Digicom pattern as OTA. Default lab uses **file H2** under `dist/data/` (`MODE=PostgreSQL`). Both JDBC drivers ship in the fast-jar.

**Postgres** — edit `dist/configs/application.properties` (replace H2 block) or set env:

```properties
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=ussdgw
quarkus.datasource.password=ussdgw
quarkus.datasource.jdbc.url=jdbc:postgresql://127.0.0.1:5432/ussdgw
```

```bash
export QUARKUS_DATASOURCE_DB_KIND=postgresql
export QUARKUS_DATASOURCE_USERNAME=ussdgw
export QUARKUS_DATASOURCE_PASSWORD=ussdgw
export QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/ussdgw
```

Never `jdbc:h2:mem:` for shipped dist. Schema: Flyway `V1__ussdgw_baseline.sql` + boot guard — see [`docs/agents/schema.md`](docs/agents/schema.md).

See `docs/as-contract/` and `AGENTS.md`.
