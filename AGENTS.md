# AGENTS.md — Digicom-ET USSDGW (ussdgw-jainslee)

**JDK: Java 25 only** (`maven.compiler.release=25`, mise **zulu-25**). Never Java 8/11/17/21.

Thin index for agents. Durable detail → [`docs/agents/`](docs/agents/) — start at [`docs/agents/README.md`](docs/agents/README.md). **Before admin/UI/packaging edits:** [`lessons.md`](docs/agents/lessons.md) + [`skills.md`](docs/agents/skills.md).

Greenfield **Digicom-ET USSDGW** (code packages `et.restlink.*`) — 3GPP **pull/MO** + **push/NI** gateway that **replaces** classic WildFly [`ussdgateway`](../../../ussdgateway) / [`nhanth87/ussdgw`](https://github.com/nhanth87/ussdgw) `core/`. HTTP AS wire = **dual-mode** (classic XmlMAPDialog-compatible **XML default** + greenfield **JSON**, per-tenant); gRPC JSON/proto as documented. **Not** SIM OTA / CAP / fleet / `/sendota`.

### Migration law (non-negotiable)

| Layer | Oracle / source | Never |
|-------|-----------------|-------|
| **Behavior** | Classic `ussdgw/core` (Parent/Child/Http/Grpc/Sip/Sri + session-bridge) + 3GPP USSD | Conflate with ota-sim-push product semantics |
| **Admin UX shell** | OTA [`app/html/admin/`](../../ota-service/ota-sim-push/app/html/admin/) + `AdminPageRenderer` **layout only** | Fleet / CAP / portal OTA campaigns / Ki |
| **AS wire** | [`docs/as-contract/`](docs/as-contract/) — XML default + JSON; [`classic-xml.md`](docs/as-contract/classic-xml.md); 3GPP [`ussd-3gpp-notes.md`](docs/as-contract/ussd-3gpp-notes.md) | Assume JSON-only; invent non-classic XML tags; rip AdaptiveTimeout/bridge for “raw MAP” |
| **Access planes** | MAP + Diameter + SIP/USSI + SMPP live (stubs only when peer down) | Leave Diameter/SIP as permanent STUB_QUEUED |


### Public GitHub vs Digicom configs (non-negotiable) — dual push

**Keep** Digicom carrier seeds in the project for Digicom deploys (local worktree + private remote). **Push always splits in 2** via [`./build/push-dual.sh`](build/push-dual.sh):

| Branch / remote | Visibility | SS7 / runtime configs |
|-----------------|------------|------------------------|
| **`main` → `origin` (`nhanth87/ussdgw-micro-jainslee`)** | **PUBLIC** | **Lab/test only** — `build/ss7-lab.json` / `dist/configs/ss7-lab.json`. Dual license: [`LICENSE`](LICENSE) + [`COMMERCIAL_LICENSE.md`](COMMERCIAL_LICENSE.md). **No** Digicom carrier JSON/props. |
| **`digicom` → `digicom-et` `main` (`digicom-et/ussdgw-micro-jainslee`)** | **PRIVATE** | **Same as public + Digicom overlay** — `build/ss7-digicom-balance.json`, `build/application-digicom.properties`, `build/systemd/install-on-digicom.sh`. Digicom host `configs/` remains operator SoT for live secrets. |

```
main (lab)  ──push──►  origin/main          (nhanth87 PUBLIC)
digicom = main + Digicom seeds  ──push──►  digicom-et/main  (PRIVATE)
```

- **Local:** Digicom paths may exist on disk (gitignored on `main`) for Digicom package/rsync.
- **Agents:** never `git push origin` alone when Digicom overlay also needs updating — run **`./build/push-dual.sh`**. Never force-add Digicom files onto `main` / never push them to nhanth87. Digicom tip **merges** `main` (FF-friendly); use **`--force-digicom`** only if digicom-et/main rejects a non-FF (private lease).
- Legacy branch `private/digicom-carrier-seeds` is a seed backup; prefer **`digicom-et/main`** as the Digicom-inclusive tree.

## Topic index

| Topic | Doc |
|-------|-----|
| Index | [`docs/agents/README.md`](docs/agents/README.md) |
| Agent compress | [`skills.md`](docs/agents/skills.md) |
| Lessons / footguns | [`lessons.md`](docs/agents/lessons.md) |
| Log4j2 ONLY | [`logging.md`](docs/agents/logging.md) |
| Admin UX (OTA shell → USSD) | [`skills.md`](docs/agents/skills.md) § Admin · `app/html/admin/` |
| Fast-jar dist (OTA peer) | OTA [`packaging.md`](../../ota-service/ota-sim-push/docs/agents/packaging.md) · [`skills.md` § Dist](docs/agents/skills.md) |
| Compile + Digicom redeploy | [`skills.md` § Digicom compile + redeploy](docs/agents/skills.md) — **copy-paste commands** (JDK 25 → PG package→H2 restore → rsync jars only → restart → wait `:8088`); footguns [`lessons.md`](docs/agents/lessons.md) |
| Schema H2 / PostgreSQL | [`schema.md`](docs/agents/schema.md) |
| SS7 lab + HLR face | [`ss7-lab-pair.md`](docs/agents/ss7-lab-pair.md) · admin `/admin/hlr` |
| Operator Digicom / live carrier | [`ss7-lab-pair.md` § Operator Digicom](docs/agents/ss7-lab-pair.md) — dual push [`push-dual.sh`](build/push-dual.sh); public = `ss7-lab.json`; Digicom overlay on digicom-et `main` |
| Ethiopia MO pull `*101…` | [`ss7-lab-pair.md` § Ethiopia MO pull](docs/agents/ss7-lab-pair.md) — mark `*101` → as-node `:8090/ussd/pull`; MAP `processUnstructuredSS-Request` → AS XML gen0 |
| Parity vs classic | [`docs/parity-matrix.md`](docs/parity-matrix.md) |
| AS contract / 3GPP USSD | [`docs/as-contract/`](docs/as-contract/) · [`ussd-3gpp-notes.md`](docs/as-contract/ussd-3gpp-notes.md) |

## Do-not-miss checklist

- **Java 25 only** — mise `zulu-25`; never downgrade. False alarms: `bcprov-jdk18on` ≠ Java 8; APT `RELEASE_8` is upstream metadata. → OTA [packaging](../../ota-service/ota-sim-push/docs/agents/packaging.md)
- **Ship only `dist/`** — after `./build/package-dist.sh`, copy **`dist/` alone** → `dist/run.sh`. Do **not** scp the worktree, `build/`, `src/`, or repo-root `app/`. UI = `app/html/` files — **never WAR / uber-jar**. → [skills § Dist](docs/agents/skills.md)
- **Git ≠ runnable dist** — clone/worktree may show only `dist/app/` + `dist/configs/` (+ `run.sh` scaffold). **`lib/` · `*.jar` · `quarkus/` are gitignored** (OTA parity). That is **not** a complete ship tree. Always `./build/package-dist.sh` before copy-and-run; `run.sh` must refuse incomplete layout. Never commit jars into `dist/`. → [skills § Dist](docs/agents/skills.md) · [lessons](docs/agents/lessons.md)
- **Dist layout** — `quarkus-run.jar` + **`ussdgw-app.jar` at APP_HOME root** + `lib/{boot,main}/` + `quarkus/` + `app/html/` (UI only) + `configs/` + `data/` + `logs/`. Never `java -jar ussdgw-app.jar` alone. Never jars under `app/`. Rewrite `quarkus-application.dat` when moving the app jar.
- **Prove the artifact, not the source** — before debugging runtime: artifact mtime vs source, `jar tf ussdgw-app.jar | grep <NewClass>`, classpath of the *running* PID. Green `mvn test` ≠ deployed. Never trust `mvn -q test` alone (`Tests run: 0` looks green). → [lessons](docs/agents/lessons.md)
- **Log4j2 ONLY** — `log4j-core` + `log4j2.xml` → `dist/logs/` (`ussd.log.dir`); never `/tmp`; never dual `SleeEventTrace`+`LOG.info`; never `log4j2-jboss-logmanager` / `quarkus.log.file*`. → [logging](docs/agents/logging.md)
- **Link status truth** — see section below; `ss7.live` / `smpp.live` via `LinkStatusService` only (SCTP+M3UA ACTIVE / bound ESME). Never LISTEN-alone, Apply-once, or UI badge fixes.
- **SCTP** — verify with `ss -ln --sctp` / `/proc/net/sctp/{eps,assocs}`, **not** `netstat` (empty netstat ≠ down; `map.enabled=false` ⇒ no listen 8013). Lab seed listens **8013**. Live Digicom peer topology stays on Digicom host — [ss7-lab-pair](docs/agents/ss7-lab-pair.md). → [lessons](docs/agents/lessons.md)
- **Sim persist XML** — never leave corrupt `*sccp*.xml` (`<1>` keys). Validate Jackson parse; quarantine + replace seed; smoke Start. → [ss7-lab-pair](docs/agents/ss7-lab-pair.md) · jSS7 AGENTS
- **SBB handlers** — catch **`Throwable`** in every `onEvent`; end/cancel MAP dialogs; `IN SBB=` count must match `OUT SBB=`.
- **Bridge idempotency** — a MAP reply / NI push may only follow a **won CAS**, never a read-only state check: `claimForAsResponse` (`AWAITING_AS|S1_RELEASED → RESPONDING`) and `onGateExpired` (returns `false` when it loses). Never `get()`+full `put()` after a CAS — it reverts concurrent single-field writes. Gate tick = `ConcurrentExecution.SKIP` + per-session `catch (Throwable)` + O(due) deadline index; one bad session must never starve every parked dialog. → [skills.md](docs/agents/skills.md)
- **Bridge + AdaptiveTimeout auto-run** — no admin Start. Boot arms `BridgeGateScheduler` (`@Scheduled` gate tick) + `ClassicNiHttpPark` + `AdaptiveTimeout`. `ussd.bridge.enabled` default **true** (arm vs hard-fail only). Prove: `scheduler.gateTicks` in `/admin/status.json` climbing; boot log `BridgeGateScheduler first gate tick`. → [lessons](docs/agents/lessons.md)
- **HTTP/gRPC** — RA **callbacks** only — never 50ms timer poll. Pull body = raw wire (XML default or JSON per tenant) — **not** `CallbackRequest` envelope. Classic **NI sync** on `ussd.http.ni-path` (default `/ussd`) with **JSESSIONID**; park HTTP via async + **`AdaptiveTimeout`** — never `Thread.sleep`.
- **HLR face** — inbound SRI-SM: `ussd.hlr.mode` default **PROXY_MAP fail-closed**; no silent FAKE; outbound SRI CalledParty = resolved **`ussd.hlr.upper-gt`** (never MSISDN); blank/self-loop fail-closed; never `PendingSri`/`HLR takeAny`. → [ss7-lab-pair](docs/agents/ss7-lab-pair.md) · [lessons](docs/agents/lessons.md)
- **Tenant `network_id` ≡ SCCP `networkId`** — live Digicom GTT is typically **networkId=0** only; mismatch → SCCP `no matching Rule` → 0 SCTP DATA + `SRI_TIMEOUT` despite `SRI-SM sent`. → [lessons](docs/agents/lessons.md)
- **NI push after SRI** — SCCP dest = SRI **`networkNodeNumber` (MSC)** + MAP destReference **IMSI**; never MSISDN as CalledParty when live. Carry MSC/IMSI on `NiPushReadyEvent.fromSri` (do not rely on profile round-trip alone). Dual SCTP: SRI answer may arrive on a non-ACTIVE ASP while AS stays ACTIVE — m3ua must still deliver PayloadData (else `SRI_TIMEOUT` despite pcap). Live fail-closed: no MSC → `SRI_NO_MSC` / `NI_NO_MSC`. → [lessons](docs/agents/lessons.md)
- **NI one-shot vs menu (3GPP)** — **one message / no digits** → AS `unstructuredSSNotify_Request` → MAP **`unstructuredSS-Notify`**. **Menu / expect UE input** → AS `unstructuredSSRequest_Request` → MAP **`unstructuredSS-Request`** (then UE `unstructuredSS-Response`). Never use Notify as interactive menu. → [ussd-3gpp-notes.md](docs/as-contract/ussd-3gpp-notes.md)
- **NI prove floor** — Notify path = `ni-parked` → `sri-ok msc=…` → `ni-sent notify` + `USSD NI sent … mscGt=… imsi=…`; pcap CalledParty = **MSC GT** (not MSISDN). Peer Notify RESULT ≠ handset UI. Wireshark **E.164 (MSISDN)** on Notify often shows **Calling GW GT** — ignore; use SCCP **Called Party Digits** = MSC. Prefer live prove with **Request** when handset must display a menu. → [ss7-lab-pair](docs/agents/ss7-lab-pair.md)
- **Notify ≠ full NI push** — Peer **Notify RESULT** settles parked HTTP (`onNotifyResponse` / `completeParkedEncoded`; AdaptiveTimeout still ontop). **Same-dialog continue** (JSESSIONID + live MSC): `continueOnDialog` → `niContinue` / `MapUnstructuredSsContinue`. **Release:** empty/`mapMessagesSize=0` → `niClose`; abort → `abort` (corr reverse-map). Specs: **22.090 / 23.090**; MAP **29.002** — **22.002 ≠ USSD**. → [ussd-3gpp-notes.md](docs/as-contract/ussd-3gpp-notes.md) · [lessons](docs/agents/lessons.md)
- **AdaptiveTimeout / Virtual bridge on top of MAP NI** — `ClassicNiHttpPark` + EWMA gate + `ussdTx` / `claimForAsResponse` / `onGateExpired` own AS park; MAP Notify/Request/continue/TC-END sit **under** that. Never replace bridge/adaptive with raw MAP-only. → [ussd-3gpp-notes.md](docs/as-contract/ussd-3gpp-notes.md) §6
- **Per-MSISDN profiles** — `ussdTx` PK = **correlationId** (not MSISDN); each in-flight user gets its own row. Never reuse/overwrite a corr bound to another MSISDN (`VirtualSessionStore.put` fail-closed; NI `/ussd` → **409**). Concurrent users = concurrent corr rows; registries (`AsPullStateRegistry`, `PendingSri*`) stay keyed by corr — never `takeAny`. → [lessons](docs/agents/lessons.md)
- **5k TPS honesty** — shared-host heap often **2g/4g**; pool knobs ×10 (`sbb-pool-max=40960`, `buffer-size=16384`) are **BUILD_TIME**. Runtime targets for sync AS: HTTP worker **512**, client pool **8192**, JDBC **128/16**, CDR queue **100k**/batch **2k**. Dual live SCTP links help share, not automatic 5k. **5k not measured** without dedicated load host (≥8g) + map/load + AS sim. → [lessons](docs/agents/lessons.md)
- **Quarkus Digicom ship** — CDI eager bridge/adaptive on boot (`BridgeGateScheduler`); build-time **`db-kind=postgresql`** for Digicom package then restore local **h2**; rsync jars/`lib`/`quarkus`/`app/html` only; after restart wait **`:8088`** `/admin/status.json` (systemd active ≠ ready); NI park = async + AdaptiveTimeout (**never** `Thread.sleep`); status truth = `/admin/status.json` `ss7.live` only. → [skills § Digicom redeploy](docs/agents/skills.md) · [lessons](docs/agents/lessons.md) · [schema](docs/agents/schema.md)
- **AS pull state** — `@ApplicationScoped` **`AsPullStateRegistry`** (not SBB instance maps); else EWMA never seeds under load. → [lessons](docs/agents/lessons.md)
- **NI `/ussd` auth** — default **required**; lab opt-out `ussd.http.ni.auth-required=false`. NI header **`X-USSD-Api-Key`** (admin key or tenant/app-user key); admin UI automation stays **`X-USSD-Admin-Key`**. Secrets fail-closed unless `ussd.lab.allow-default-secrets=true`; bcrypt; package-dist never clobbers configs. → [lessons](docs/agents/lessons.md)
- **NI Digicom 500 / `ussdTx`** — Quarkus can load **api stub** `ProfileAccessorInvoker` (UOE) before core; `package-dist` must shadow core class into `jainslee-api` jar. Also re-bind `ProfileFieldStoreLocator` in `VirtualSessionStore.put` or CMP writes hit empty facility (`No profile table: ussdTx`). Catch must log **exception message**. → [lessons](docs/agents/lessons.md)
- **TENANT login** — **username === tenantId**. UI brand = **Digicom-ET USSDGW** (packages stay `et.restlink.*`).
- **Admin UX** — plane pages match **Routing** shell (seeded form-card; **no** `hx-live-badge`). **SS7/SMPP = JSON only** (no field grids); **HLR face** at `/admin/hlr` (mode/fake/upper/Diameter dest — not in SS7 JSON); **HTTP/gRPC = status only** (read-only NI URL on HTTP); **Diameter/SIP = enabled + listen/peer forms**. Canonical shells at `/admin/ss7|hlr|smpp|http|grpc|diameter|sip` (**no** hub redirect). SS7 `stackJson` editable **ADMIN/OPS** only — TENANT sees LIVE/DOWN. Status tables use **ink-panel** (not nested black `bg-ink`). Bridge wait/fail = UTF-8 + AUTO→UCS-2. Dashboard Planes Open → `/admin/ss7` etc. → [skills § Admin](docs/agents/skills.md)
- **Digicom host = prod-bound** — live carrier peer + **PostgreSQL** dedicated DB **`ussdgw`** (never OTA’s `ota`). Not a disposable toy lab. Package with build-time **`db-kind=postgresql`**, then restore local **H2** for the public/dev tree. Rsync **jars/`lib`/`quarkus`/`app/html` only** — **never** overwrite Digicom `configs/` (operator SoT; not in nhanth87). → [schema](docs/agents/schema.md) · [ss7-lab-pair](docs/agents/ss7-lab-pair.md)
- **Digicom / prod DB = operator SoT** — agents must **NOT** arbitrarily `UPDATE`/`DELETE` Digicom (or any prod-bound) routing rules (`ussd_short_code`), tenants, users, `network_id`, or other ops data. Config + PG on Digicom are operator-owned; **ask the user before any DB mutation**. Read/`SELECT` for diagnose is fine; silent “fix” of `as_url` / enable flags is not. → [lessons](docs/agents/lessons.md)
- **Flyway / DB** — local dev **file H2** (`./data/ussdgw`, PG-mode); Digicom/prod **PostgreSQL** via server `configs` / `QUARKUS_DATASOURCE_*`. Dedicated DB **`ussdgw`**; `db-kind` is **build-time** (H2→PG needs rebuild). Single `V1__ussdgw_baseline.sql`; wipe **local** H2 / reset history after squash — never wipe Digicom PG casually. Boot guard `UssdSchemaInitializer`. Never `h2:mem` for ship. → [schema](docs/agents/schema.md) · [lessons](docs/agents/lessons.md)
- **Lab tools** — `tools/as-node/` menus; `tools/ss7-simulator/` CLI JMX dial/dt; `ss7-lab.json` needs HLR **`ssn:6`**. Load test: align map/load SSN/PC/ports; don’t measure adaptive until pull registry is fixed. → [lessons](docs/agents/lessons.md)
- **Ethiopia MO pull (`*101xxxxxx`)** — Digicom mark rule **`*101`** (not `*101*`) → `http://127.0.0.1:8090/ussd/pull`; as-node must listen. Wire: MAP `processUnstructuredSS-Request` → `MapUssdParentSbb.onProcessUnstructured` → AS XML `processUnstructuredSSRequest_Request` → AdaptiveTimeout/bridge → MAP reply. Exact `*100#`/`*123#` remain. → [ss7-lab-pair § Ethiopia MO](docs/agents/ss7-lab-pair.md)
- **MO Called SSN 147 (gsmSCF)** — some peers address the GW with SSN **147**; live seed `services` must include `gsmscf:147` (+8/+6) or SCCP UDTS. Prove boot `Registered SCCP listener with extra ssn 147`. → [ss7-lab-pair](docs/agents/ss7-lab-pair.md) · [lessons](docs/agents/lessons.md)
- **AS empty body ≠ AdaptiveTimeout** — HTTP 200 + empty → `AS_EMPTY_BODY` (not EWMA gate). Point short-code at as-node / real XmlMAPDialog AS; HTTPS AS → Wireshark TLS/SNI. → [lessons](docs/agents/lessons.md)
- **Lab heap** — `run-dist.sh` defaults **`-Xms2g -Xmx4g`** for shared hosts; **AlwaysPreTouch** only if `USSD_ALWAYS_PRETOUCH=1`; override to **8g** via `USSD_XMS`/`USSD_XMX` on bigger hosts. Do **not** co-force OTA **8G** + ussdgw **8G+PreTouch** on ~15 GiB. → [lessons](docs/agents/lessons.md)
- **Commits** — **nhanth87 / Tran Nhan** only. No AI `Co-authored-by:` / trailers (Cursor injects — strip / clean `commit-tree`). Hooks reject; never `--no-verify`.

## Link status truth (non-negotiable)

**Source of truth = runtime plane state** (`LinkStatusService`, ra-jss7 / SMPP callbacks) — never UI badges, never “config applied”, never local LISTEN / RA started alone.

1. **`ss7.live` / `smpp.live`** — peer can carry traffic **now**: SS7 = SCTP up **and** M3UA AS **ACTIVE**; SMPP = ≥1 bound session or outbound client. **Not** `isActive()`, **not** stack started, **not** LISTEN with zero peer.
2. **Operator Stop ≠ peer disconnect** — both set `live=false` with honest detail. Killing jSS7 sim / ESME unbind must flip `live=false` without Stop and without UI hacks.
3. **Admin HTML** — display API fields only; never invent ACTIVE/UP in partials.
4. **Delivery gates** — NI push / campaigns / HLR PROXY use the **same** SS7 peer truth; no parallel optimistic flag.

## Logging style (non-negotiable)

**Exactly one style:** Apache **Log4j2** → **`dist/logs/`**. Never Quarkus file handlers, never `log4j2-jboss-logmanager`, never `/tmp`.

| Plane | API | Never |
|-------|-----|-------|
| SBB `onEvent` / RA fire / RA command out | **`SleeEventTrace`** only | Also `LOG.info` for the same boundary |
| Services, schedulers, admin, RA lifecycle | **`LogManager.getLogger(Class)`** | `SleeEventTrace` for non-SLEE work |

Detail: [logging.md](docs/agents/logging.md).

## Hard constraints

- **Java 25 / Quarkus / micro-jainslee** — host = `adapter-quarkus`. No `main()`, no springboot/jakartaee adapters for Digicom deploy.
- App = CDI `@ApplicationScoped` + `@Inject MicroSleeContainer` + startup observer.
- **Persistence** — Quarkus JDBC + Panache + Flyway; **no** Postgres-RA.
- **MAP** — `ra-jss7` + access adapters; HLR face = inbound SRI-SM (`HlrResponderSbb`).
- **Diameter / SIP** — `ra-diameter` / `ra-sip-servlet` (micro-jainslee); app adapters MO/NI **live** when peer ready.
- **SMPP** — **in-tree** local RA (intentional); optional `ussd.smpp.ussd.enabled`.
- **Admin UI** — disk templates `app/html/admin/` via `AdminPageRenderer` (`ussd.admin.ui-dir`); Monitor Hub for RA planes.
- **Telemetry** — Monitor Hub `/telemetry/`; scrape metrics on HTTP RA port (default **8088**).

## Do not

- Attribute commits to any AI/agent — **nhanth87 / Tran Nhan** only; never `--no-verify`.
- Downgrade Java / invent uber-jar / WAR for Digicom lab/prod.
- Report link UP from LISTEN, Apply-once, `isActive()`, or UI-only badges.
- Dual-log SLEE boundaries (`SleeEventTrace` **and** `LOG.info`).
- Leave corrupt jSS7 sim persist XML or hand-edit illegal tags.
- Ship Digicom without shadowing **`ProfileAccessorInvoker`** into `jainslee-api` (NI `/ussd` → UOE) or without locator re-bind on `ussdTx` put.
- Treat repo-root `app/` / `build/` as runtime — runtime is **`dist/`** only.
- Assume git `dist/app`+`configs` alone is copy-and-run — **missing `lib/`** until `./build/package-dist.sh`.
- Use `CallbackRequest` for AS **pull**; poll HTTP/gRPC with timers; assume JSON-only AS wire; `Thread.sleep` on NI HTTP park.
- Silent FAKE HLR when mode is PROXY_*; point `upper-gt` at self.
- Port OTA fleet/CAP/`/sendota` into this USSD GW.
- Leave raw `{{TOKEN}}` in browser HTML — always seed vars (OTA admin lesson).
- **Mutate Digicom / prod-bound DB ops data without asking** — no silent `UPDATE`/`DELETE` of short-code `as_url`, tenants, users, `network_id`, or routing/enable flags. Operator config/DB is SoT; ask first. → [lessons](docs/agents/lessons.md)
- Push Digicom carrier SS7/props to **nhanth87** — keep them for Digicom (local + digicom-et via **`./build/push-dual.sh`**); public `main` stays lab (`ss7-lab.json`) only.
- Bloat this file — put detail in `docs/agents/*`.

## Dist / run

**In git:** scaffold only (`app/html/`, `configs/`, `run.sh`, README). **Not in git:** `lib/`, `quarkus/`, `*.jar`, `data/`, `logs/` (see `.gitignore`).

| Command | Does |
|---------|------|
| `./build/package-dist.sh` | Maven fast-jar → **self-contained `./dist/`** with `lib/{boot,main}/` (JDK 25) — **required** before ship |
| Digicom compile + redeploy | Exact shell copy-paste → [`skills.md` § Digicom compile + redeploy](docs/agents/skills.md) |
| `./run.sh` / `dist/run.sh` | Start packaged app (errors if jars/`lib` missing) |
| **Server** | Copy **complete `dist/`** (after package) → `./run.sh` (host JDK 25) |
| Bare bootstrap | [`dist-package-script.sh`](dist-package-script.sh) (sctp → jss7 → jain-slee → package) |

Lab AS sims: **`tools/as-node/`** (Fastify — prefer), `tools/as-http-sim.py`, `tools/as-grpc-json-sim.py`.

## Scope (short)

- Access PULL/PUSH: MAP + Diameter + SIP/USSI live when peer ready (stub only when down); SMPP lab MO + optional `submit_sm` NI.
- AS modes: **SYNC** / **ASYNC_ACK** / **BRIDGE** (adaptive EWMA gate); HTTP wire **XML|JSON** (default XML); classic NI sync + parked HTTP gated by `AdaptiveTimeout`.
- Admin forms at `/admin/ss7|hlr|smpp|http` (Routing shell; no hub redirect); Monitor Hub = metrics only; HTTP Sync/Async/Callback panels.
- HLR face: `FAKE|PROXY_MAP|PROXY_DIAMETER|FAKE_THEN_RESOLVE` (default PROXY_MAP fail-closed).
- Saga: `ProfileFacility` table `ussdTx`; campaigns; TenantGuard; CDR async flusher.
- Alphabets: **AS-driven** (`ucs7`|`ucs8`|`unicode`|`auto`) → MAP CBS DCS.

Detail tables (admin paths, AS modes, tenant, saga): keep in chat only if needed — prefer [`skills.md`](docs/agents/skills.md) and source under `src/main/java/et/restlink/ussdgw/`.
