# ussdgw-jainslee

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

**Digicom lab after this hardening:** keep `ussd.lab.allow-default-secrets=true` in server
`dist/configs/application.properties`, **or** rotate `ussd.admin.session-hmac-secret` /
`ussd.admin.api-key` before the next boot — otherwise startup fails closed. Checklist:
[docs/prod-release-path.md](docs/prod-release-path.md).

AS pull (HTTP): short-code rules → POST JSON to AS URL  
AS callback: `POST /as/callback`

## Database (H2 lab / PostgreSQL prod)

Same Digicom pattern as OTA. Default lab uses **file H2** under `dist/data/` (`MODE=PostgreSQL`). Both JDBC drivers ship in the fast-jar.

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

See `docs/as-contract/` and `AGENTS.md`.

## Digicom lab (test server)

Host: **`digicom-nb` / `100.110.205.176`**  
APP_HOME: `/home/app/ota-push-services/ussdgw-micro-jainslee/`  
Peers: as-node `:8090`; optional jSS7 lab sim SCTP **8014→GW 8013** (disable when on carrier Balance Plus).

### SS7 carrier — Balance Plus (example)

Old ussdgw lived on **`172.16.144.167`**. Micro-jainslee carrier face is **`172.16.144.163`** (eth1). Seed: [`build/ss7-digicom-balance.json`](build/ss7-digicom-balance.json) → server `configs/ss7-digicom-balance.json`.

| Ours (SCP / Digicom) | Peer (Balance Plus) |
|----------------------|---------------------|
| IP **172.16.144.163** · SPC **1470** · GT **251971200490** | |
| **IPSP client** · RC **101** · MAP SSN **8** (+ HLR face **6**) | |
| SCTP client **:2011** → | **10.177.55.241:2501** SPC **1404** |
| SCTP client **:2019** → | **10.177.54.241:2502** SPC **1403** |

Two separate M3UA AS (one link each) — not one AS with two links:

```json
{
  "sctp": {
    "links": [
      {
        "name": "L1-BP-1404",
        "type": "client",
        "channel": "sctp",
        "local": "172.16.144.163:2011",
        "peer": "10.177.55.241:2501",
        "localSecondary": []
      },
      {
        "name": "L2-BP-1403",
        "type": "client",
        "channel": "sctp",
        "local": "172.16.144.163:2019",
        "peer": "10.177.54.241:2502",
        "localSecondary": []
      }
    ]
  },
  "m3ua": {
    "as": [
      {
        "name": "AS-BP-1404",
        "mode": "loadshare",
        "functionality": "ipsp",
        "ipsp": "client",
        "routingContext": 101,
        "links": ["L1-BP-1404"]
      },
      {
        "name": "AS-BP-1403",
        "mode": "loadshare",
        "functionality": "ipsp",
        "ipsp": "client",
        "routingContext": 101,
        "links": ["L2-BP-1403"]
      }
    ],
    "routes": [
      { "to": { "dpc": 1404, "opc": 1470 }, "via": "AS-BP-1404" },
      { "to": { "dpc": 1403, "opc": 1470 }, "via": "AS-BP-1403" }
    ]
  }
}
```

Server props (do **not** clobber the rest of Digicom `application.properties`):

```properties
ussd.map.enabled=true
ussd.map.auto-apply-on-boot=true
ussd.map.config-file=configs/ss7-digicom-balance.json
ussd.map.ussd-gt=251971200490
ussd.map.ussd-ssn=8
ussd.map.hlr-ssn=6
```

```bash
# carrier face — stop lab MAP sim
sudo systemctl stop ussdgw-ss7sim && sudo systemctl disable ussdgw-ss7sim
# after editing configs/ss7-digicom-balance.json
sudo systemctl restart ussdgw
ss -ln --sctp   # expect client assoc toward .241:2501 / .241:2502 (not lab :8013)
curl -sS -H 'X-USSD-Admin-Key: ussd-admin' http://127.0.0.1:8088/admin/ss7/status
```

Lab loopback pair remains [`build/ss7-lab.json`](build/ss7-lab.json) / [`docs/agents/ss7-lab-pair.md`](docs/agents/ss7-lab-pair.md). Pcaps for RC/ASP debug: `build/pcap/`.

### Admin

| | |
|--|--|
| URL (nginx) | http://100.110.205.176/admin |
| URL (direct) | http://100.110.205.176:8088/admin/login |
| Username | `admin` |
| Password | `ussd-admin` (lab first-run; keep `ussd.lab.allow-default-secrets=true`) |
| API key | `ussd-admin` via header **`X-USSD-Admin-Key`** only (`?key=` rejected) |
| Public NI base | `ussd.admin.public-base-url=http://100.110.205.176:8088` (never advertise `0.0.0.0`) |
| Monitor feed | `/admin/monitor-feed` + dashboard strip (LinkStatusService + push URLs) |
| Campaigns | TENANT `/admin/my-campaigns` create/submit; ADMIN `/admin/campaigns` approve |
| App users | `/admin/app-users` — API keys for NI (portal TENANT login stays `username===tenantId`) |

### systemd (start / stop)

Units live in [`build/systemd/`](build/systemd/) — install once:

```bash
ssh digicom-nb
cd /home/app/ota-push-services/ussdgw-micro-jainslee
sudo cp tools/systemd/*.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now ussdgw-as-node ussdgw
# ussdgw-ss7sim: lab MAP peer only — disable when using Balance Plus carrier JSON
# sudo systemctl enable --now ussdgw-ss7sim
sudo systemctl status ussdgw ussdgw-as-node --no-pager
```

```bash
sudo systemctl restart ussdgw          # GW only
sudo systemctl restart ussdgw-as-node  # AS pull menus :8090
# sudo systemctl restart ussdgw-ss7sim   # lab MAP peer only
sudo journalctl -u ussdgw -f           # or: tail -f /tmp/ussdgw.service.log
```

**Never** overwrite live `configs/application.properties` on Digicom (PostgreSQL `ussdgw` DB). Re-package with `db-kind=postgresql`, rsync jars/`lib`/`quarkus`/`app/html` only.

### curl — status / planes / HLR

```bash
KEY='ussd-admin'
HOST='http://100.110.205.176:8088'

curl -sS -H "X-USSD-Admin-Key: $KEY" "$HOST/admin/status.json" | jq .
curl -sS -H "X-USSD-Admin-Key: $KEY" "$HOST/admin/links"
curl -sS -H "X-USSD-Admin-Key: $KEY" "$HOST/admin/hlr" | head
curl -sS -o /dev/null -w '%{http_code}\n' -H "X-USSD-Admin-Key: $KEY" "$HOST/admin/"
```

Form login (cookie session):

```bash
curl -sS -c /tmp/ussd.ck -b /tmp/ussd.ck -X POST "$HOST/admin/login" \
  -d 'username=admin&password=ussd-admin' -D - -o /dev/null | head
curl -sS -b /tmp/ussd.ck "$HOST/admin/" | head
```

### curl — AS pull smoke (as-node)

```bash
# as-node health / pull (menus)
curl -sS http://100.110.205.176:8090/health || true
curl -sS -X POST http://100.110.205.176:8090/ussd/pull \
  -H 'Content-Type: application/xml' \
  --data-binary @- <<'XML'
<dialog>
  <mapDialog><dialogId>lab-1</dialogId></mapDialog>
  <processUnstructuredSSRequest>
    <msisdn>251911000001</msisdn>
    <ussdString>*100#</ussdString>
  </processUnstructuredSSRequest>
</dialog>
XML
```

(Wire format follows tenant / global AS contract — XML default; see `docs/as-contract/`.)

### USSD CLI (short / long code + DT)

On Digicom (or laptop with JMX to sim `:9999`):

```bash
cd /home/app/ota-push-services/ussdgw-micro-jainslee/tools/ss7-simulator
./run.sh build-cli   # once
./run.sh cli         # REPL
```

```
ussd> connect
ussd> msisdn 251911000001
ussd> dial *100#
ussd> dt 1
ussd> dial *100*1234567890#
ussd> dial *519812345678901234#
ussd> quit
```

One-shot:

```bash
./run.sh cli dial '*100#' --msisdn 251911000001 --dt 1,2,3
./run.sh cli dial '*519812345678901234#' --manual
```

jSS7 sim GUI (optional): http://100.110.205.176:8089 — start **USSD_TEST_CLIENT**, then use CLI over JMX.

More detail: [`tools/ss7-simulator/README.md`](tools/ss7-simulator/README.md), [`tools/as-node/README.md`](tools/as-node/README.md).
