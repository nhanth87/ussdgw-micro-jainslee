# ussdgw-micro-jainslee

![Java 25](https://img.shields.io/badge/Java-25-orange) ![License](https://img.shields.io/badge/license-Dual_(GPLv3_|_Commercial)-blueviolet)

RestLink greenfield USSD gateway (Quarkus + micro-jainslee + ra-jss7). **Java 25.**

## At a glance

| | |
|--|--|
| **LOC in `src/`** | **~16.1k** Java (12.8k main + 3.3k test) across **152** `.java` files |
| Stack | Quarkus 3.37 · micro-jainslee · ra-jss7 / HTTP / gRPC / Diameter / SIP · Flyway |
| Ship | Fast-jar `dist/` — H2 lab or PostgreSQL prod (OTA parity) |
| Product | 3GPP USSD MO/NI — MAP + Diameter + SIP/USSI + SMPP lab; not SIM OTA |

One greenfield Quarkus tree replacing classic WildFly `ussdgateway` behavior — admin HTMX shell, AdaptiveTimeout bridge, campaigns, CDR, tenant RBAC — without the WF10 DU circus.

### Protocol references

| Domain | Specs |
|--------|--------|
| CS USSD (service / stage 2 / stage 3) | **TS 22.090** · **TS 23.090** · **TS 24.090** |
| MAP operations | **TS 29.002** |
| USSI / USSD over IMS (SIP INVITE + INFO) | **TS 24.390** |

Access planes: SS7/MAP USSD per **TS 22.090** / **TS 23.090** / **TS 24.090** and **TS 29.002**; SIP/USSI per **TS 24.390** (`application/vnd.3gpp.ussd+xml`). AS HTTP wire: classic XmlMAPDialog XML (default) + JSON — see [`docs/as-contract/`](docs/as-contract/). AS-facing SIP trunks (MESSAGE) are documented in [`docs/as-contract/sip-trunk.md`](docs/as-contract/sip-trunk.md); full INVITE/INFO menu machine is a follow-on to MESSAGE lab.

## Build

```bash
mise exec -- mvn -B -ntp package -DskipTests
./build/package-dist.sh
cd dist && ./run.sh
```

Admin: `http://127.0.0.1:8088/admin/login` (form login), or for automation
`curl -H 'X-USSD-Admin-Key: <ussd.admin.api-key>' http://127.0.0.1:8088/admin/status.json`.
`?key=` is no longer accepted — it leaks a full-ADMIN credential into access logs and history.

**Lab after secret hardening:** keep `ussd.lab.allow-default-secrets=true` in local
`dist/configs/application.properties`, **or** rotate `ussd.admin.session-hmac-secret` /
`ussd.admin.api-key` before the next boot — otherwise startup fails closed. Checklist:
[docs/prod-release-path.md](docs/prod-release-path.md).

AS pull (HTTP): short-code rules → POST **XML** (default) or **JSON** (per-tenant) to AS URL.
AS callback: `POST /as/callback`

## Database (H2 lab / PostgreSQL prod)

Same H2-lab / PostgreSQL-prod pattern as OTA. Default lab uses **file H2** under `dist/data/` (`MODE=PostgreSQL`). Both JDBC drivers ship in the fast-jar.

**Postgres** — edit `dist/configs/application.properties` (replace H2 block) or set env:

```properties
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=ussdgw
quarkus.datasource.jdbc.url=jdbc:postgresql://127.0.0.1:5432/ussdgw
# export QUARKUS_DATASOURCE_PASSWORD=…  (never commit the password)
```

```bash
export QUARKUS_DATASOURCE_DB_KIND=postgresql
export QUARKUS_DATASOURCE_USERNAME=ussdgw
export QUARKUS_DATASOURCE_PASSWORD=ussdgw
export QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/ussdgw
```

Never `jdbc:h2:mem:` for shipped dist. Schema: Flyway `V1__ussdgw_baseline.sql` + boot guard — see [`docs/agents/schema.md`](docs/agents/schema.md).

See `docs/as-contract/` and `AGENTS.md`. AS HTTP **dual-wire** guide (XML + JSON samples,
MO + MAP2MAP, session ids, multi-menu, final `processUnstructuredSSRequest_Response` /
`action":"END"`):
[`docs/as-contract/map2map-as-xml.md`](docs/as-contract/map2map-as-xml.md)
(Content-Type / tenant `httpAsWireFormat` · § Session identity · §2 CONTINUE · §3 END ·
§4 MAP2MAP · §4d multi-menu).

## Lab SS7 (public tree)

Public GitHub **`nhanth87/ussdgw-micro-jainslee`** ships **lab/test SS7 only**:
[`build/ss7-lab.json`](build/ss7-lab.json) → `dist/configs/ss7-lab.json` (loopback **8013↔8014**).

**Never** commit Digicom-ET / live carrier topology (peer IPs, SPC/GT, listen **2011/2019**, `ss7-digicom-balance.json`, Digicom `application-*.properties`) to **nhanth87**. Those live on the **Digicom host** (operator SoT) and optionally on private **`digicom-et/ussdgw-micro-jainslee`** (`private/digicom-carrier-seeds`). See [`AGENTS.md`](AGENTS.md) and [`docs/agents/ss7-lab-pair.md`](docs/agents/ss7-lab-pair.md).

### Admin (local after `./run.sh`)

| | |
|--|--|
| URL | http://127.0.0.1:8088/admin/login |
| Username | `admin` |
| Password | `ussd-admin` (lab first-run; keep `ussd.lab.allow-default-secrets=true`) |
| API key | `ussd-admin` via header **`X-USSD-Admin-Key`** only (`?key=` rejected) |

### systemd (lab units)

Units in [`build/systemd/`](build/systemd/) — install with [`build/systemd/install-lab-units.sh`](build/systemd/install-lab-units.sh).

**Never** overwrite live operator `configs/application.properties` on a prod-bound host. Re-package with `db-kind=postgresql` when shipping PG; rsync jars/`lib`/`quarkus`/`app/html` only.

### curl — status / AS pull smoke

```bash
KEY='ussd-admin'
HOST='http://127.0.0.1:8088'

curl -sS -H "X-USSD-Admin-Key: $KEY" "$HOST/admin/status.json" | jq .
curl -sS http://127.0.0.1:8090/health || true
```

### USSD CLI (lab sim)

```bash
cd tools/ss7-simulator
./run.sh build-cli   # once
./run.sh cli         # REPL: connect / msisdn / dial / dt
```

More: [`tools/ss7-simulator/README.md`](tools/ss7-simulator/README.md), [`tools/as-node/README.md`](tools/as-node/README.md).

## License

**Dual-licensed:** GPLv3 (Section A) for open-source use, or Commercial License (Section B) for proprietary deployment — same pattern as [micro-jainslee](https://github.com/nhanth87/micro-jainslee).

See [`LICENSE`](LICENSE), [`COMMERCIAL_LICENSE.md`](COMMERCIAL_LICENSE.md), and [`NOTICE`](NOTICE).

> Maintained by [Tran Nhan (nhanth87)](mailto:nhanth87@gmail.com)
