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
| MAP2MAP Case 2 call flow | [`docs/as-contract/map2map.md`](docs/as-contract/map2map.md) § Call flow (locked) |

## Do-not-miss checklist

- **Java 25 only** — mise `zulu-25`; never downgrade. False alarms: `bcprov-jdk18on` ≠ Java 8; APT `RELEASE_8` is upstream metadata. → OTA [packaging](../../ota-service/ota-sim-push/docs/agents/packaging.md)
- **No Joda Time (SIẾT)** — host USSDGW code uses **`java.time` only** (`Instant`, `OffsetDateTime`, `DateTimeFormatter`, …). Never import/add `org.joda.time.*` / `joda-time` in new or changed app code (CDR spine timestamps included). Transitive joda on the classpath from legacy deps ≠ license to call it. → [lessons](docs/agents/lessons.md)
- **Ship only `dist/`** — after `./build/package-dist.sh`, copy **`dist/` alone** → `dist/run.sh`. Do **not** scp the worktree, `build/`, `src/`, or repo-root `app/`. UI = `app/html/` files — **never WAR / uber-jar**. → [skills § Dist](docs/agents/skills.md)
- **Never sandbox `package-dist` / `mvn package|test` / prove-as-wire-lab** — request Shell **`required_permissions: ["all"]`** on the **first** call (`.m2-agent-repo` / Digicom ship); do not fail-then-retry. → [lessons](docs/agents/lessons.md)
- **Git ≠ runnable dist** — clone/worktree may show only `dist/app/` + `dist/configs/` (+ `run.sh` scaffold). **`lib/` · `*.jar` · `quarkus/` are gitignored** (OTA parity). That is **not** a complete ship tree. Always `./build/package-dist.sh` before copy-and-run; `run.sh` must refuse incomplete layout. Never commit jars into `dist/`. → [skills § Dist](docs/agents/skills.md) · [lessons](docs/agents/lessons.md)
- **Dist layout** — `quarkus-run.jar` + **`ussdgw-app.jar` at APP_HOME root** + `lib/{boot,main}/` + `quarkus/` + `app/html/` (UI only) + `configs/` + `data/` + `logs/`. Never `java -jar ussdgw-app.jar` alone. Never jars under `app/`. Rewrite `quarkus-application.dat` when moving the app jar.
- **Prove the artifact, not the source (NON-NEGOTIABLE — SIẾT)** — Green `mvn test` / new unit tests / laptop source greps **NEVER** mean Digicom (or any remote/local host) runs the new code. **Forbidden** to report “fixed” / “done” / “redeployed OK” / “shipped” until **all** of the following are shown for the **running host** (tests-only or package-without-rsync = **incomplete / agent failure**):
  1. **Package** — `./build/package-dist.sh` (Digicom: build-time PG `db-kind` → restore local H2). Never sandbox this Shell call — `required_permissions: ["all"]` first.
  2. **Ship bits only** — rsync **`ussdgw-app.jar` + `quarkus-run.jar` + `lib/` + `quarkus/` + `app/html/`** — **never** Digicom `configs/`.
  3. **Restart + ready** — restart service → wait until `:8088` `/admin/status.json` **200** (systemd `active` ≠ Quarkus ready).
  4. **Artifact on host** — jar **and** html **mtime** vs the change, or `jar tf ussdgw-app.jar | grep <NewClass>` / `strings` for new symbols; running PID classpath loads that jar.
  5. **Live broken surface** — curl/hit the UI/API/log that failed (e.g. `/admin/cdr/partial`, Adaptive KPI) — **status.json alone does not prove** CDR/KPI/HTML. Never trust `mvn -q test` alone (`Tests run: 0` looks green). → [lessons](docs/agents/lessons.md) · [skills § Digicom](docs/agents/skills.md)
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
- **MAP2MAP call flow (locked)** — HLR/MSC **USSD text** → CDR `MAP2MAP_HOP_CLOSE` (**amber** chip, never red via `phase=FAILED`) + AS `string=`=hop text / `hlrResult=responded`. **No** hop text → CDR **FAIL** (red) + AS empty `string=` / `hlrResult=none` (never echo `hlr none` onto UE). AS reply → forward to UE; AS silent → Bridge page wait/hard-fail text (`ussd.bridge.async-wait-message` / hard-fail). → [map2map.md](docs/as-contract/map2map.md) · [lessons](docs/agents/lessons.md)
- **Per-MSISDN profiles** — `ussdTx` PK = **correlationId** (not MSISDN); each in-flight user gets its own row. Never reuse/overwrite a corr bound to another MSISDN (`VirtualSessionStore.put` fail-closed; NI `/ussd` → **409**). Concurrent users = concurrent corr rows; registries (`AsPullStateRegistry`, `PendingSri*`) stay keyed by corr — never `takeAny`. → [lessons](docs/agents/lessons.md)
- **5k TPS honesty** — shared-host heap often **2g/4g**; pool knobs ×10 (`sbb-pool-max=40960`, `buffer-size=16384`) are **BUILD_TIME**. Runtime targets for sync AS: HTTP worker **512**, client pool **8192**, JDBC **128/16**, CDR queue **100k**/batch **2k**. Dual live SCTP links help share, not automatic 5k. **5k not measured** without dedicated load host (≥8g) + map/load + AS sim. → [lessons](docs/agents/lessons.md)
- **Quarkus Digicom ship** — CDI eager bridge/adaptive on boot (`BridgeGateScheduler`); build-time **`db-kind=postgresql`** for Digicom package then restore local **h2**; rsync jars/`lib`/`quarkus`/`app/html` only; after restart wait **`:8088`** `/admin/status.json` (systemd active ≠ ready); NI park = async + AdaptiveTimeout (**never** `Thread.sleep`); status truth = `/admin/status.json` `ss7.live` only. → [skills § Digicom redeploy](docs/agents/skills.md) · [lessons](docs/agents/lessons.md) · [schema](docs/agents/schema.md)
- **Digicom crash-loop after deploy (SIẾT — H2-baked jar)** — see § below. **Never** rsync a laptop `package-dist` built with `quarkus.datasource.db-kind=h2` onto Digicom. Killer tree = whole fast-jar bake (`ussdgw-app.jar` + **`quarkus/`** + `lib/`), not “one bad class”.
- **AS pull state** — `@ApplicationScoped` **`AsPullStateRegistry`** (not SBB instance maps); else EWMA never seeds under load. → [lessons](docs/agents/lessons.md)
- **NI `/ussd` auth** — default **required**; lab opt-out `ussd.http.ni.auth-required=false`. NI header **`X-USSD-Api-Key`** (admin key or tenant/app-user key); admin UI automation stays **`X-USSD-Admin-Key`**. Secrets fail-closed unless `ussd.lab.allow-default-secrets=true`; bcrypt; package-dist never clobbers configs. → [lessons](docs/agents/lessons.md)
- **NI Digicom 500 / `ussdTx`** — Quarkus can load **api stub** `ProfileAccessorInvoker` (UOE) before core; `package-dist` must shadow core class into `jainslee-api` jar. Also re-bind `ProfileFieldStoreLocator` in `VirtualSessionStore.put` or CMP writes hit empty facility (`No profile table: ussdTx`). Catch must log **exception message**. → [lessons](docs/agents/lessons.md)
- **TENANT login** — **username === tenantId**. UI brand = **Digicom-ET USSDGW** (packages stay `et.restlink.*`).
- **Admin UX** — plane pages match **Routing** shell (seeded form-card; **no** `hx-live-badge`). **SS7/SMPP = JSON only** (no field grids); **HLR face** at `/admin/hlr` (mode/fake/upper/Diameter dest — not in SS7 JSON); **HTTP/gRPC = status only** (read-only NI URL on HTTP); **Diameter/SIP = enabled + listen/peer forms**. Canonical shells at `/admin/ss7|hlr|smpp|http|grpc|diameter|sip` (**no** hub redirect). SS7 `stackJson` editable **ADMIN/OPS** only — TENANT sees LIVE/DOWN. Status tables use **ink-panel** (not nested black `bg-ink`). Bridge wait/fail = UTF-8 + AUTO→UCS-2. Dashboard Planes Open → `/admin/ss7` etc. → [skills § Admin](docs/agents/skills.md)
- **Dashboard KPI** — human short format (never raw `Map.toString()` / `{1=1000.0}`); **all** `.metric-card`s overflow-safe (`AdaptiveTimeout.formatSnapshotForDisplay`). → [skills § Admin](docs/agents/skills.md)
- **CDR 6-hop spine (SIẾT — grilled)** — expand primary = **always 6 slots** via `CdrSessionSpine`: (1) UE USSD in (2) re-route HLR/MSC (3) HLR/MSC resp ~50 hop text (4) send AS (5) AS resp **~50** (6) gate→UE. Missing hop = **SKIPPED + reason**; SRI/NI fills slots 2–3 when present. Slot 6 **FAIL/`cdr-status--fail` (RED)** if AS had operator text but MAP END/CONTINUE not sent to UE. **AS ~50 visible** in ledger column + expand hero + step 5. Data = fold `events_json` / `as_ussd` only — **no new persist write path** (10k TPS honesty). Expand = **full-width** AS hero + spine + session grid (visible without hunting); **Advanced = raw pipe/tape only**. Keep AdaptiveTimeout/bridge CAS. Admin UX = **existing** Digicom scheme only. Never `|`→`/` escape of `events_json` detail. Prove live `/admin/cdr/partial` has `cdr-hop-list` + AS snip. → [skills § Admin/CDR](docs/agents/skills.md) · [lessons](docs/agents/lessons.md)
- **CDR click jumps to page bottom (SIẾT — agent-failure pattern)** — operators hit this across **many commits** while agents claimed “fixed” with `show:none` / poll-only scroll pin. That was **incomplete**. Exact mechanism + forbidden fake-fixes + correct pattern below (and [lessons](docs/agents/lessons.md)). **Forbidden** to close this bug without a **browser** prove: mid-page expand → `scrollY` unchanged on Digicom (or packaged `dist/` UI). Curl/status.json/html-grep alone ≠ prove.
- **Digicom host = prod-bound** — live carrier peer + **PostgreSQL** dedicated DB **`ussdgw`** (never OTA’s `ota`). Not a disposable toy lab. Package with build-time **`db-kind=postgresql`**, then restore local **H2** for the public/dev tree. Rsync **jars/`lib`/`quarkus`/`app/html` only** — **never** overwrite Digicom `configs/` (operator SoT; not in nhanth87). → [schema](docs/agents/schema.md) · [ss7-lab-pair](docs/agents/ss7-lab-pair.md)
- **Digicom / prod DB = operator SoT** — agents must **NOT** arbitrarily `UPDATE`/`DELETE` Digicom (or any prod-bound) routing rules (`ussd_short_code`), tenants, users, `network_id`, or other ops data. Config + PG on Digicom are operator-owned; **ask the user before any DB mutation**. Read/`SELECT` for diagnose is fine; silent “fix” of `as_url` / enable flags is not. → [lessons](docs/agents/lessons.md)
- **Flyway / DB** — local dev **file H2** (`./data/ussdgw`, PG-mode); Digicom/prod **PostgreSQL** via server `configs` / `QUARKUS_DATASOURCE_*`. Dedicated DB **`ussdgw`**; `db-kind` is **build-time** (H2→PG needs rebuild). Single `V1__ussdgw_baseline.sql`; wipe **local** H2 / reset history after squash — never wipe Digicom PG casually. Boot guard `UssdSchemaInitializer`. Never `h2:mem` for ship. → [schema](docs/agents/schema.md) · [lessons](docs/agents/lessons.md)
- **Lab tools** — `tools/as-node/` menus; `tools/ss7-simulator/` CLI JMX dial/dt; `ss7-lab.json` needs HLR **`ssn:6`**. Load test: align map/load SSN/PC/ports; don’t measure adaptive until pull registry is fixed. → [lessons](docs/agents/lessons.md)
- **Ethiopia MO pull (`*101xxxxxx`)** — Digicom mark rule **`*101`** (not `*101*`) → `http://127.0.0.1:8090/ussd/pull`; as-node must listen. Wire: MAP `processUnstructuredSS-Request` → `MapUssdParentSbb.onProcessUnstructured` → AS XML `processUnstructuredSSRequest_Request` → AdaptiveTimeout/bridge → MAP reply. Exact `*100#`/`*123#` remain. → [ss7-lab-pair § Ethiopia MO](docs/agents/ss7-lab-pair.md)
- **MO Called SSN 147 (gsmSCF)** — some peers address the GW with SSN **147**; live seed `services` must include `gsmscf:147` (+8/+6) or SCCP UDTS. Prove boot `Registered SCCP listener with extra ssn 147`. → [ss7-lab-pair](docs/agents/ss7-lab-pair.md) · [lessons](docs/agents/lessons.md)
- **AS empty body ≠ AdaptiveTimeout** — HTTP 200 + empty → `AS_EMPTY_BODY` (not EWMA gate). Point short-code at as-node / real XmlMAPDialog AS; HTTPS AS → Wireshark TLS/SNI. → [lessons](docs/agents/lessons.md)
- **Lab heap** — `run-dist.sh` defaults **`-Xms2g -Xmx4g`** for shared hosts; **AlwaysPreTouch** only if `USSD_ALWAYS_PRETOUCH=1`; override to **8g** via `USSD_XMS`/`USSD_XMX` on bigger hosts. Do **not** co-force OTA **8G** + ussdgw **8G+PreTouch** on ~15 GiB. → [lessons](docs/agents/lessons.md)
- **Commits** — **nhanth87 / Tran Nhan** only. No AI `Co-authored-by:` / trailers (Cursor injects — strip / clean `commit-tree`). Hooks reject; never `--no-verify`.

## Digicom crash-loop after deploy — H2-baked jar (SIẾT)

**Symptom:** every `systemctl restart` after rsync → exit **1** in ~8–20s, restart counter climbs, `:8088` never comes up. Digicom `configs/` still say PostgreSQL. Restoring previous `ussdgw-app.jar` + `lib/` + **`quarkus/`** from `/tmp/ussdgw-jar-bak-*` brings the host back.

**Exact mechanism (proven 2026-08-09 in `/tmp/ussdgw.service.log`):**
```
Build time property cannot be changed at runtime:
 - quarkus.datasource.db-kind is set to 'postgresql' but it is build time fixed to 'h2'
Datasource '<default>': Driver does not support the provided URL: jdbc:postgresql://127.0.0.1:5432/ussdgw
FlywaySqlUnableToConnectToDbException → Failed to start quarkus
```
Digicom **runtime** `configs/application.properties` has `db-kind=postgresql` + `jdbc:postgresql://…/ussdgw`. Quarkus **build-time** kind is baked into the fast-jar (`quarkus/generated-bytecode.jar` + `quarkus-application.dat` + companion `ussdgw-app.jar`). An **H2** package cannot drive a PostgreSQL JDBC URL — Agroal/Flyway die before Log4j app logs look “normal”.

**Which artifacts kill Digicom (all of them, together):**
| Artifact | Role |
|----------|------|
| **`quarkus/generated-bytecode.jar`** | Build-time datasource kind / Agroal recording |
| **`quarkus/quarkus-application.dat`** | Quarkus app model (also rewritten for root jar path) |
| **`ussdgw-app.jar`** | App classes from the same Maven package |
| **`lib/`** (esp. jdbc/flyway jars) | Must match that package |

`app/html/` alone is safe (UI-only). **Never** blame a single “bad class” inside ussdgw-app while leaving an H2 `quarkus/` tree in place.

**What NOT to do:**
- `./build/package-dist.sh` with `build/application.properties` still `db-kind=h2` → rsync to Digicom
- Rsync **only** `ussdgw-app.jar` and leave Digicom’s old PG `quarkus/` (or the reverse) — mixed bake
- Edit Digicom `configs` `db-kind` hoping to override bake (Quarkus warns; kind stays fixed)
- Declare “deploy OK” from systemd `active` while `:8088` is down / restart loop

**Correct Digicom package (copy-paste SoT in skills):**
1. `sed` **`db-kind=postgresql`** in `build/application.properties`
2. `./build/package-dist.sh` → writes `dist/.baked-db-kind` = `postgresql`
3. **Restore** local `db-kind=h2` immediately
4. **Preflight before rsync:** `test "$(cat dist/.baked-db-kind)" = postgresql` (and/or `grep` the bake warn will not apply)
5. Rsync `ussdgw-app.jar` + `quarkus-run.jar` + `lib/` + **`quarkus/`** + `app/html/` (never `configs/`)
6. Restart → wait `:8088` 200 → prove. On loop: `tail` **`/tmp/ussdgw.service.log`** (systemd stdout/stderr), restore bak.

Detail: [skills.md](docs/agents/skills.md) § Digicom · [lessons.md](docs/agents/lessons.md) · [schema.md](docs/agents/schema.md)

## CDR scroll jump — agent-failure pattern (SIẾT)

**Symptom:** click CDR timestamp (mid-page) → viewport jumps to **page bottom**. Also seen after HTMX 5s poll while a row is expanded.

**Exact mechanism (not htmx `show:` alone):**
1. **Chrome overflow-anchor (primary on click):** expanding a mid-list `<tr class="cdr-detail">` inserts a tall block **above** `.cdr-ledger-foot` / later rows. Scroll anchoring keeps that foot/later content in view → `scrollY` increases → looks like “jump to bottom”. Prior `show:none` + poll-only `pinScroll` **never ran on click**, so click stayed broken.
2. **Ledger `overflow-x-auto` (amplifier):** CSS spec: non-`visible` `overflow-x` forces `overflow-y` to `auto` → nested scrollport; focus/layout then scrolls that port.
3. **Bad “fix”:** `htmx.config.scrollBehavior = 'smooth'` fought any pin and made residual show/focus scroll worse.
4. **HTMX 5s poll while expanded (Advanced closes + mid-page jump):** `#cdr-rows` `innerHTML` every 5s **destroys** open `<details>` (Advanced snaps shut) and `pinScrollSoon(stale beforeSwap Y)` races the user’s current scroll → jump to **middle**. `sessionStorage` row-open restore alone does **not** keep Advanced open.
5. **Pipe “bể layout”:** long `events`/`detail` pipe in a grid `<dd>` with `overflow-wrap: anywhere` mid-word-breaks (`Brid`/`ge`, `no`/`te`).

**What NOT to do (already failed across commits):**
- Only add `hx-swap … show:none` and declare fixed
- Only pin scroll on `htmx:afterSwap` (poll) — **click path untouched**
- Keep polling `#cdr-rows` every 5s **while a row is open** (closes Advanced; stale pin)
- Set `scrollBehavior = 'smooth'`
- Keep `overflow-x-auto` on `.cdr-ledger` / table-wrap that contains expand rows
- Render rolled pipe in `.cdr-detail-grid dd` with `overflow-wrap: anywhere`
- Gold-wash every `.cdr-digest` (signal mix) — looks tè le next to status chips
- Stuff ops spine/session into `<details class="cdr-advanced">` and call UX done
- Ship after `mvn test` / html grep **without** Digicom (or packaged UI) **browser** `scrollY` prove + Advanced stays open

**Correct pattern (this admin HTMX partial):**
- CSS: `overflow-anchor: none` on `.cdr-ledger`, `tbody`, `.cdr-detail`, `.cdr-ledger-foot`, `.cdr-advanced`; ledger wrap **`overflow: visible`** (no `overflow-x-auto`); digests = solid `ink-panel`; pipe = `<pre class="cdr-pipe-block">` one `|` field per line (`white-space: pre`, no mid-word break)
- JS: on expand **click**, capture `scrollY` **before** toggle, restore via rAF/`scrollTo`; **pause** `#cdr-rows` poll while any row open (`htmx:beforeRequest` preventDefault); persist Advanced open in sessionStorage; restore `details.open` after filter swaps
- HTMX: `hx-swap="innerHTML settle:0 show:none focus-scroll:false"`; `htmx.config.scrollBehavior='instant'`; `defaultFocusScroll=false`
- Markup: expand = full-width ink-panel (AS hero + 6-hop + session grid); Advanced = raw pipe/tape only
- Prove: mid-page click → `|scrollY_after - scrollY_before| ≤ 1`; open Advanced → still open after ≥6s; many commits without that prove = **agent failure**

Detail: [lessons.md](docs/agents/lessons.md) · [skills.md](docs/agents/skills.md) § Admin/CDR · `app/html/admin/cdr.html` · `admin.css`

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
- Import **`org.joda.time.*`** or add **joda-time** for host USSDGW timestamps — use **`java.time` only**.
- Redesign CDR admin with a new visual theme (purple gradients, new fonts) — denser spine info inside **existing** ink-panel / `cdr-status-*` scheme only.
- Rip AdaptiveTimeout / bridge CAS to “simplify” CDR; add a new CDR persist write path for expand fields (fold `events_json`/`as_ussd` only).
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

## Cursor Cloud specific instructions

Durable, non-obvious notes for Cloud Agent VMs (toolchain refresh is handled by the startup update script — see below; do not re-run one-off installs here).

- **Toolchain = mise.** JDK 25 (`zulu-25`) + Maven pinned in [`mise.toml`](mise.toml) (`[tools]` format). `mise install` restores both; put them on PATH via `export PATH="$HOME/.local/share/mise/shims:$HOME/.local/bin:$PATH"` (or `mise exec -- …`). Build scripts also honor `JAVA_HOME` — set `export JAVA_HOME="$(mise where java)"` before `./build/package-dist.sh` / `mvn`.
- **Upstream deps are built from source, not published.** `com.microjainslee:*:1.2.0-SNAPSHOT` (+ jss7 `9.2.8-j25`, sctp `2.27.32`) come from cloning `sctp` (`java25-upgrade`), `jss7` (`j25`), `jain-slee` (`micro-jainslee-2`) and `mvn install` into `~/.m2`. The private `restlink` org is unreachable — use the public fallback: `GIT_BASE=https://github.com/nhanth87 ./dist-package-script.sh`. These artifacts persist in the VM snapshot, so a normal session does **not** rebuild them. First-time bootstrap gotchas (already applied once): jss7 and jain-slee reactors fail the initial POM scan because leaf modules declare a parent whose default `relativePath` points at the wrong pom — pre-install the parents with `mvn -N install` (jss7 root once; then jain-slee `jainslee-pom/` BOM first, then jain-slee root). Also the public `nhanth87/jss7` `j25` lagged `jain-slee` `micro-jainslee-2` — `ra-jss7` expects a `routingContexts` field on `Ss7Config.As`; add it to `~/ussdgw-build/jss7/ss7-config` and rebuild that module if a fresh bootstrap ever fails to compile `ra-jss7`.
- **No SCTP in this kernel ⇒ no SS7/MAP.** The Cloud VM kernel has no SCTP module (`checksctp` → "Protocol not supported"), so `ra-jss7` cannot activate. This is expected: boot logs `SS7 boot wire failed (lab may run without MAP)` and `status.json` shows `ss7.live=false` with honest detail. The app degrades gracefully and everything else runs. The `tools/ss7-simulator` MAP path and any real MO/NI-over-MAP flow **cannot be exercised in Cloud** — use the HTTP-drivable paths instead.
- **Database = PostgreSQL (server parity).** Local dev uses PostgreSQL 16 (role/db `ussdgw`/`ussdgw`). Start it with `sudo pg_ctlcluster 16 main start` (not auto-started on boot; the initialized cluster persists in the snapshot). `quarkus.datasource.db-kind` is **BUILD-TIME**: the shipped `dist/` here was packaged with `db-kind=postgresql` (temporarily flip `build/application.properties` db-kind→postgresql for the `package-dist` run, then restore it to the tracked H2 lab default; the committed `dist/configs/application.properties` already targets Postgres). The password is never in git — supply `QUARKUS_DATASOURCE_PASSWORD` at runtime.
- **Run / ready-gate.** `cd dist && QUARKUS_DATASOURCE_PASSWORD=ussdgw ./run.sh`. Ready only when `:8088` `/admin/status.json` returns 200 (process-started ≠ Quarkus ready — poll for it). AS simulator: `cd tools/as-node && npm install && npm run pull:fast` (`:8090`).
- **Exercise a real MO→AS pull without MAP (Lab MO).** Enable a stub plane (e.g. `curl -X POST :8088/admin/diameter -H 'X-USSD-Admin-Key: ussd-admin' --data-urlencode action=save --data-urlencode enabled=true`), then `POST /admin/lab/mo` (`plane=DIAMETER&msisdn=…&shortCode=*100#&ussd=*100#`). This drives the true gateway pipeline → `HttpClientSbb PullHttpEvent` → AS 200. Note: stub planes (Diameter/SMPP/SIP without a live peer) have **no return leg** to the UE, so the AS reply is recorded and dropped by design (CDR slot 6 = "AS text not sent to UE"); that is expected, not a bug.
- **Admin UI.** Browser login `admin` / `ussd-admin`. HTML pages require a browser session cookie; the `X-USSD-Admin-Key` header alone serves JSON/HTMX partials but 302s on full HTML pages. The product CDR ledger is **`ussd_cdr_session`** (one row per correlation) — `ussd_cdr` stays empty by design.
- **Test server (ussdgw).** `ssh app@100.110.205.176` — no password. APP_HOME `/home/app/ota-push-services/ussdgw-micro-jainslee/`, service `ussdgw.service`. It is a Tailscale host (`100.x`) using Tailscale SSH, so SSH works directly from any machine already on the tailnet. A Cloud Agent VM must first join the tailnet: `sudo tailscaled --tun=userspace-networking --socks5-server=localhost:1055 --outbound-http-proxy-listen=localhost:1054 &` then `sudo tailscale up` (approve the printed URL, or use a `TS_AUTHKEY`); then SSH/rsync through the SOCKS proxy, e.g. `ssh -o ProxyCommand='nc -X 5 -x localhost:1055 %h %p' app@100.110.205.176` and `rsync -e 'ssh -o ProxyCommand=…' …`. Deploy = rsync `ussdgw-app.jar`+`quarkus-run.jar`+`lib/`+`quarkus/`+`app/html/` only (never `configs/`), then `sudo systemctl restart ussdgw.service`, wait `:8088 /admin/status.json` 200. That host has SCTP, so full MO/NI + `ss7-simulator` can be proven there (unlike the Cloud VM). Focus this host on **ussdgw** — do not deploy OTA here.
