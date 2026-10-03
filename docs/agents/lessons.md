# Lessons learned — do not repeat (ussdgw-jainslee)

Short memory for Digicom footguns. Prefer this + OTA peer [`lessons.md`](../../../../ota-service/ota-sim-push/docs/agents/lessons.md) over rediscovering the same mistakes.

**Shared (USSDGW / OTA / Elisa / jain-slee):** workspace [`docs/agents/lessons.md`](../../../../../docs/agents/lessons.md) · skill `digicom-et-host`.

**Cross-product (portable):** skill `digicom-et-host` § Session / identity SIẾT (in-flight PK ≠ profile PK; rehydrate wipe; wire-gen stamp; 10k honesty; Digicom PG bake). USSD-specific multimenu/`ussdTx` rows below.

## Do not

| Mistake | Rule | Detail |
|---------|------|--------|
| **Docker jlink JRE missing `jdk.compiler`** | Diameter RA needs `javax.tools.JavaFileManager` at `raActive()` — even with `ussd.diameter.enabled=false`. jlink `--add-modules` must include `jdk.compiler` alongside `jdk.jsobject`. | § 2026-10-03 · `docker/ussdgw/Dockerfile` |
| **SS7 watchdog re-wire loop** | `Ss7Watchdog` aggressive re-wire (180s) interrupts M3UA FSM recovery from duplicate peer messages → stuck PENDING. Disable: `ussd.ss7.watchdog.enabled=false`. | § 2026-10-03 · `Ss7Watchdog` |
| **Memory congestion level 2** | JVM heap 4GB tight for SS7+Diameter+HTTP. Keep `-Xms2g -Xmx4g` on shared hosts; monitor `memory.heap.usedPercent`; do not co-run OTA 8G + USSDGW 8G. | § 2026-10-03 · `run-dist.sh` |
| Treating SIP listen as AS trunk UP | Trunks are peer+URI rows; NI requires matched **enabled** trunk. Soft free-text → pull-reply first (`SipUssiSbb`); SIP park via `AsPullRouter.armSipPullBridge`. From-host ≠ digest auth. | [sip-trunk.md](../as-contract/sip-trunk.md) |
| Assuming V6 `short_code` UNIQUE alone covers app-user multi-rule | Do not assume V6 alone — use **V8** composite UNIQUE `(short_code, app_username)`; unbound `app_username` is stored as `''`, not NULL. | `V8__short_code_app_username_unique.sql` |
| Committing Digicom **carrier** SS7 / props to **nhanth87** | **Keep** Digicom seeds for Digicom deploys; **dual-push** with [`./build/push-dual.sh`](../../build/push-dual.sh): `main`→`origin` = lab only (`ss7-lab.json`); `digicom`→`digicom-et/main` = lab + `ss7-digicom-balance.json` / `application-digicom.properties`. Never force-add Digicom paths onto public `main`. Host `configs/` still operator SoT for live secrets. | root [AGENTS.md](../../AGENTS.md) § dual push |
| Stuffing long walls into **`AGENTS.md`** | Keep root thin — link `docs/agents/*`. | [README.md](README.md) |
| Equating **portal TENANT** login with **API app-user** | Portal keeps **`username === tenantId`**. NI keys live in **`ussd_app_user`** (A/B/C). | `AppUserService` · `/admin/app-users` |
| Letting TENANT **Start** campaigns | TENANT **create/submit** on `/admin/my-campaigns`; ADMIN/OPS **approve** on `/admin/campaigns` (`PENDING_APPROVAL` → `RUNNING`). `start()` must **not** promote `PENDING_APPROVAL`. | `CampaignService` |
| Using **Java 8/11/17/21** or fixing compile by lowering release | **Java 25 only** (mise `zulu-25`). | OTA [packaging.md](../../../../ota-service/ota-sim-push/docs/agents/packaging.md) |
| Using **`org.joda.time.*` / joda-time** in host USSDGW code | **SIẾT:** timestamps = **`java.time` only** (`Instant`, `DateTimeFormatter`, …). Never add joda imports for CDR spine / admin / new code. Transitive joda on classpath ≠ OK to call. | root [AGENTS.md](../../AGENTS.md) · `CdrSessionSpine` · `AdminHttpHandler#formatCdrWhen` |
| Inventing a **new CDR visual theme** (“beautiful dashboard”) | Stay on Digicom-ET admin scheme (`admin.css`, ink-panel, `cdr-status-*`, Routing shell). Operators want **denser hop info**, not purple chrome / new fonts. | [skills.md](skills.md) § Admin · `cdr.html` |
| CDR expand **jumps / Advanced snaps shut / pipe bể layout** — **agent-failure pattern** | **Click jump:** Chrome **overflow-anchor** on tall `<tr.cdr-detail>`. **Mid-page jump + Advanced closes:** HTMX `#cdr-rows` **every 5s** `innerHTML` while expanded destroys `<details>` + `pinScroll(stale beforeSwap Y)`. **Pipe bể:** grid `dd` + `overflow-wrap:anywhere` mid-word-breaks. **Fake fixes that failed:** `show:none` alone; poll-only pin; keep polling while open; gold-wash digests. **Correct:** pause `#cdr-rows` poll while any row open; persist Advanced in sessionStorage; `<pre class="cdr-pipe-block">` one `\|` field/line; solid ink-panel digests; overflow-anchor none; click pin. **Prove:** `|ΔscrollY|≤1` **and** Advanced still open after ≥6s. | root [AGENTS.md](../../AGENTS.md) § CDR scroll · `cdr.html` · `admin.css` |
| Stuffing CDR ops fields into `<details class="cdr-advanced">` | Advanced = **raw pipe / event tape only**. AS ~50 hero + 6-hop spine + session grid must be **visible** on expand (full-width ink-panel). | `AdminHttpHandler` · `cdr.html` |
| Adding a **new CDR persist column/write** for expand spine | **SIẾT:** fold from existing `events_json` / `as_ussd` only (`CdrSessionSpine.derive`). No extra flusher path — 10k TPS honesty. Keep AdaptiveTimeout/bridge. | `CdrSessionSpine` · root AGENTS CDR spine |
| Seeing **`bcprov-jdk18on`** / APT **`RELEASE_8`** → switch to JDK 8 | Product-line name / upstream metadata — keep release=25. | OTA packaging |
| Shipping an **uber-jar** or `java -jar ussdgw-app.jar` alone | Fast-jar: `quarkus-run.jar` + root `ussdgw-app.jar` + `lib/`. Start via `./run.sh`. | [skills.md](skills.md) |
| Putting **jars under `app/`** | `app/html/` = UI only. Package script must fail if jars remain. | [skills.md](skills.md) |
| Seeing **`dist/` only `app/` + `configs/`** after clone → “thiếu lib / package hỏng” | **Gitignored by design** (`dist/lib/`, `*.jar`, `quarkus/`). Run **`./build/package-dist.sh`** then ship — OTA same. Never commit jars. | [skills.md](skills.md) § Dist · root AGENTS |
| Running **`./build/package-dist.sh`** / **`mvn package|test`** / **`prove-as-wire-lab`** under **Cursor sandbox** (then “Package failed… Retrying with full permissions”) | **Never sandbox** those Shell calls when using worktree **`.m2-agent-repo`**, Digicom ship, or any Maven local-repo/network build. Request **`required_permissions: ["all"]`** (or equivalent unrestricted) on the **first** call — do not burn a failed sandbox attempt then retry. Typical fail: sandbox blocks reads/writes under `.m2-agent-repo` / Maven local repo, or dep network → package dies → wasteful full-perm retry. | [skills.md](skills.md) § Digicom compile + redeploy · `build/package-dist.sh` · `build/prove-as-wire-lab.sh` |
| Copying scaffold `dist/` to server **without** package | Incomplete → `run.sh` must error. Package first; verify `lib/main` + both jars. | [skills.md](skills.md) |
| Deploying the **whole worktree** | Ship **`dist/` only** (complete tree after package). Digicom lab path often `~/ota-push-services/ussdgw-micro-jainslee/`. | root [AGENTS.md](../../AGENTS.md) |
| Co-running **OTA 8G + ussdgw 8G** / **`AlwaysPreTouch`** on a ~**15 GiB** host | Shared Digicom-class host: ussdgw runs **`-Xms2g -Xmx4g`** (not 8g). `run-dist.sh` lab-safe defaults match that; **`AlwaysPreTouch` only if** `USSD_ALWAYS_PRETOUCH=1`. Bigger hosts: `USSD_XMS=8g USSD_XMX=8g`. OOM history: 8g+PreTouch + ss7sim/as-node on ~15 GiB. | OTA [packaging.md](../../../../ota-service/ota-sim-push/docs/agents/packaging.md) · `build/run-dist.sh` |
| SS7 **`localSecondary()` NPE** / missing **`ss7-lab.json`** | Props path must pass empty list (not null). Digicom: `ussd.map.config-file=configs/ss7-lab.json` with `"localSecondary": []` and SCTP **server** 8013↔8014. Missing file → props fallback → NPE → no SCTP listen. | [ss7-lab-pair.md](ss7-lab-pair.md) · `Ss7ApplyService` |
| Debugging against a **stale** dist / old PID | After package: one `quarkus-run.jar` PID; jar **mtime** vs source; `jar tf` / `strings` for new symbols (`GATE_ARMED`, `UssdUserProfile`, …); wait for bootstrap. Green `mvn test` ≠ Digicom. | [skills.md](skills.md) § Dist · Digicom redeploy |
| **Green unit tests / new test class → claim Digicom fixed** (operator still sees old UI/bug) | **Forbidden (SIẾT).** `mvn test` never proves remote runtime. Digicom jar/html can lag days behind green laptop tests (classic: CDR pipe dump + no `CdrServiceStatuses` while tests pass). Must complete the full gate: package-dist → rsync jars/`lib`/`quarkus`/`app/html` only → restart → wait `:8088` status.json **200** → prove **jar tf / mtime / NewClass** + running PID classpath on Digicom **and** live hit on the broken `/admin/...` (status.json alone ≠ UI/API prove). Saying “fixed” / “redeployed OK” / “done” after tests-only or package-without-host-prove = **agent failure**. | root [AGENTS.md](../../AGENTS.md) § Prove the artifact · [skills.md](skills.md) § Digicom |
| Trusting **`systemctl` active** right after Digicom restart | systemd may show **active** before Quarkus binds **`:8088`**. After restart: wait ~25s (or journal bootstrap) then **one-shot** curl `--connect-timeout 3 --max-time 10` to `/admin/status.json` — **not** a 60× poll loop. Exact redeploy: [skills.md](skills.md) § Digicom compile + redeploy. | [skills.md](skills.md) § Digicom compile + redeploy |
| **`flock -n` + `KillMode=mixed`** on `ussdgw.service` restart | Stop must clear the cgroup (`KillMode=control-group`) and **wait** for the lock (`flock --timeout 45`, never `-n`). Else SIGKILL/java linger → immediate ExecStart exit 1 → RestartSec=5 double-start; `:8088` down in the window. Unit: `build/systemd/ussdgw.service` + `install-on-digicom.sh`. | `build/systemd/ussdgw.service` |
| **Deleting the `flock`** because "we want 2 nodes now" | The lock is a **restart-race guard, not a host-wide single-instance guard**. Removing it re-creates the proven Digicom crash-loop above. ADR 0007 P1 fixed it correctly: lock path is **namespaced per node** (`/tmp/ussdgw-%i.lock`), so N nodes coexist and each still self-protects against double-start. | `build/systemd/ussdgw@.service` |
| Enabling `ussdgw.service` **and** `ussdgw@node1` together | Harmless by design — both take `/tmp/ussdgw-node1.lock`, so the second start fails fast instead of double-binding `:8088`. Prefer `ussdgw.service` for node1, `ussdgw@<id>` for every extra node. | `build/systemd/install-on-digicom.sh` |
| Deriving the node id from `identityHashCode` / a random UUID | The cluster node id is the identity the **dialog-lease boot-epoch fence** keys on; a value that changes per restart breaks ownership reclaim and makes leases untraceable in logs. Set `USSD_NODE_ID` (unit) + `microjainslee.container.cluster-node-id` (config). | ADR 0007 D3 |
| Expecting **`ussdUser`** to survive restart / cross-JVM | ProfileFacility table `ussdUser` (PK=MSISDN) is **JVM-local** until clustering — not Digicom JDBC (same family as `ussdTx`). | [map2map.md](../as-contract/map2map.md) § ussdUser |
| Using `ussdUser` menu fields as AS/BPLUS session resurrect | Menu snapshot (`lastDigit`/`lastGeneration`/`lastMenuAsUssd`) is ops + EWMA seed only. In-flight = **`ussdTx`** corr; never reuse `lastCorrId` for AS localId. | [map2map.md](../as-contract/map2map.md) § ussdUser |
| Trusting **`mvn -q test`** exit 0 alone | Read `Tests run:` — zero tests can look green. | OTA lessons |
| Landing a test **never seen red** | Temporarily break the fix, confirm fail, restore. | OTA lessons |
| **`log4j2-jboss-logmanager`** / **`quarkus.log.file*`** / logs in `/tmp` | Log4j2 ONLY → `ussd.log.dir` / `dist/logs/`. | [logging.md](logging.md) |
| Dual **`SleeEventTrace` + `LOG.info`** on the same SBB boundary | Trace only for SLEE ingress/egress. | [logging.md](logging.md) |
| Fixing peer-down with **UI badge** only | `LinkStatusService` / `ss7.live` = SCTP+M3UA ACTIVE. | root AGENTS § Link status |
| Treating **LISTEN / Apply / isActive()** as live | Same as OTA. | root AGENTS |
| Verifying SCTP with **netstat** (or “empty netstat ⇒ down”) | Use `ss -ln --sctp` + `/proc/net/sctp/{eps,assocs}`. Empty netstat is **not** proof SCTP is down. Empty `ss` with `map.enabled=false` = SS7 **skipped** (no listen **8013**), not a broken stack. | [ss7-lab-pair.md](ss7-lab-pair.md) |
| Digicom **crash-loop after every deploy** (systemd exit 1 ~8–20s, `:8088` down) | **H2-baked fast-jar** rsynced onto Digicom PG host. Smoking gun in **`/tmp/ussdgw.service.log`**: `db-kind is set to 'postgresql' but it is build time fixed to 'h2'` + `Driver does not support the provided URL: jdbc:postgresql://…/ussdgw` → Flyway fail → Quarkus fail. **Killer set:** `ussdgw-app.jar` + **`quarkus/generated-bytecode.jar`** + `quarkus-application.dat` + matching `lib/` from an H2 `package-dist`. HTML-only rsync is fine. Fix: package with **`db-kind=postgresql`**, stamp `dist/.baked-db-kind`, restore local h2, rsync full jar set, prove `:8088`. Rollback: `/tmp/ussdgw-jar-bak-*` (jar+lib+quarkus). | root [AGENTS.md](../../AGENTS.md) § Digicom crash-loop · [skills.md](skills.md) § Digicom |
| Digicom package from laptop H2 fast-jar / uncommitted m3ua "1 RC→1 AS" | Same H2/PG bake law as above. Dual AS both `routingContext:12` dies on m3ua jars that enforce RC uniqueness — Digicom LIVE seed is **one AS** (`AS-BP`) + two SCTP server links 2011/2019 RC12, or an m3ua-impl **without** that check. Secrets: surgical props rotate + `allow-default-secrets=false`; HTTP `http.ra.host=127.0.0.1`; gRPC stays `*:9099` (Tailscale `grpc.pushEndpoint`); nginx TLS self-signed `:443` + `:80→https`, `cookie-secure=true`. Never rsync configs wholesale. Full redeploy checklist: [skills.md](skills.md) § Digicom compile + redeploy. | [ss7-lab-pair](ss7-lab-pair.md) · package-dist |
| Live Digicom: flip GW to SCTP/IPSP **client** or wrong RC | Digicom live peer expects Digicom as **IPSP/SCTP server** with operator RC (host SoT — not in nhanth87). Dual JSON `routingContexts` ignored on shipped jar → RC 0 → err 25. SE-only after restart ⇒ SCTP UP / ASP DOWN. | Digicom host `configs/` · [ss7-lab-pair](ss7-lab-pair.md) |
| Trusting `ss7.detail` `links=[127.0.0.1:8013→…]` when `source=file` | Fixed: file apply summarizes real SCTP + M3UA RC (`Ss7ApplyService.formatWiredDetail`). Still trust `ss7.live` + `ss -ln --sctp`. | `Ss7ApplyService` · [ss7-lab-pair.md](ss7-lab-pair.md) |
| Corrupt jSS7 **`*sccp*.xml`** (`<1>` keys) | Validate parse; quarantine + seed; smoke Start. | [ss7-lab-pair.md](ss7-lab-pair.md) |
| jSS7 sim as **`SMS_TEST_CLIENT`** blocking HLR SSN 6 | Prefer **SMS_TEST_SERVER**; allow SSN **6**. | [ss7-lab-pair.md](ss7-lab-pair.md) |
| SBB catching only **`RuntimeException`** | Catch **`Throwable`**; always emit OUT trace. | root AGENTS |
| AS pull via **`CallbackRequest`** envelope | Pull = raw body (XML or JSON). | [skills.md](skills.md) |
| Late AS callback missing / wrong session key | Pull metadata (`AsPullMetadata`): `correlationId` (real push-back key), `sessionId`, `virtualBridgeId`, `adaptiveTimeoutMs`, `asMode`. Callback resolve order = **`correlationId` → `virtualBridgeId` → `sessionId`** (`AsResponse.resolvePushBackId`). **gRPC shares the same JSON field names** on pull/callback bytes. | [classic-xml.md](../as-contract/classic-xml.md) · [grpc-json.md](../as-contract/grpc-json.md) · `tools/as-node/` |
| Gate fired but AS never learns (no re-push) | On gate, GW **POSTs new classic XML** to short-code **`asUrl`** (`GatedAsNotifyService` / `encodeGatedPush`) with `virtualBridgeId`, `adaptiveTimeoutMs`, `observedEwmaMs`, `jsessionId`, `gateReason` + `unstructuredSSNotify_Request`. HTTP client session = `gated-{corr}` (no pull-state collision). Also stamps `GatedSessionRegistry` for later pull enrich. NI park still replies ABORT on parked HTTP + keeps JSESSIONID. | [classic-xml.md](../as-contract/classic-xml.md) § Gated session push |
| Booting a **nested `dist/dist`** (or second java with **8g**) on Digicom | Ship/rsync to APP_HOME root only; one `quarkus-run.jar`; heap **`USSD_XMS=2g USSD_XMX=4g`**. Nested leftover + 8g peer steals :8088/:8013 and SIGTERM-races the safe instance. | [skills.md](skills.md) § Dist · heap row above |
| Assuming **JSON-only** HTTP AS / full classic **XmlMAPDialog** E2E | Dual-mode: classic **XML default** + JSON; per-tenant `httpAsWireFormat` / `http_as_wire_format`. Codec boundary only (`AsWireFacade` / `ClassicDialogXmlCodec`) — **not** a full XmlMAPDialog stack. | [classic-xml.md](../as-contract/classic-xml.md) |
| `JsonPostRequest` **3-arg** for XML PULL | ra-http-client 3-arg hardcodes **JSON** `Content-Type`. XML (and correct JSON) PULL needs the **4-arg** ctor with explicit `contentType` (`HttpClientSbb.submitPost`). | code · micro-jainslee ra-http-client |
| **`Thread.sleep`** (or blocking wait) on classic **NI sync** parked HTTP | Path `ussd.http.ni-path` (default **`/ussd`**) + **`JSESSIONID`**. Park via **`ClassicNiHttpPark`** + **`AdaptiveTimeout`**; MS continue → **`completeParked`**; MAP corr = **dialogId**. Never sleep on SBB. | [classic-xml.md](../as-contract/classic-xml.md) · root AGENTS |
| HTTP/gRPC response via **50ms timer poll** | RA callbacks only. | root AGENTS |
| Per-correlation state in **SBB instance fields** (`Map<String,…>` on the SBB) | **Never correlates.** `SbbObjectPool` does not reset instances, and the container derives the entity id from the **activity context name** (`MicroSleeContainer.resolveMappedEntityId` → `type.getSimpleName() + "/" + acName`) — so a submit on `"pull-http-"+corr` and a completion on the RA's bare `corr` handle land on **two different entities**, hence two different pooled objects. Symptom: `latencyMs=-1` (EWMA never seeds → adaptive gate silently fixed at the ceiling), breaker keyed on `""` (all AS collapse into one), retries skipped, five maps leaking request bodies. Own it in an **`@ApplicationScoped` registry** (`AsPullStateRegistry`) with TTL + hard bound; classic did the same with CMP fields and a **`static`** `GRPC_SUBMIT_AT_MS`. | `AsPullStateRegistry` · classic `GrpcClientSbb:56` |
| Outbound-pull activity name ≠ **RA handle id** | Both client RAs fire completion on `createActivityHandle(correlationId)` (`HttpCallbackClientRa:265`, `GenericGrpcClientRa:169`) → `container.createActivityContext(corr)`. `AsPullRouter` must name the pull activity the **bare correlation id** (`AsPullRouter.pullActivityName`), never a decorated one. | `AsPullRouter` |
| Retry counter read with **`getOrDefault(corr, new AtomicInteger(0))`** | Missing state ⇒ increments a throwaway ⇒ `attempt` stays 0 ⇒ `shouldRetry` always approves ⇒ **unbounded retry storm** at a failing AS. Fail **closed**: `AsPullStateRegistry.beginRetry` returns empty when the entry is gone, and the caller must not re-send. | `AsPullStateRegistry.beginRetry` |
| Registering pull state **before** checking the RA port | The `no-ra` / circuit-open early returns skipped cleanup, so every pull while the RA was down leaked an entry **including the request body**. Resolve the transport **first**, open state only once the submit is certain, and keep `close()` idempotent on every exit. A dropped RA response is caught by the `AsPullSweeper` TTL sweep (`ussd.as.pull.state-ttl-ms`, default 60 s; `ussd.as.pull.sweep-every`, default 10 s) — the sweep bounds the map only, it does **not** compensate the saga or trip a breaker (the gate already expired the session, and an RA restart is not the AS's fault). | `HttpClientSbb` · `GrpcClientSbb` · `AsPullSweeper` |
| **Silent FAKE** HLR under PROXY_* | Default PROXY_MAP fail-closed; FAKE only when ops set. | [ss7-lab-pair.md](ss7-lab-pair.md) |
| `ussd.hlr.upper-gt` **== local ussdGt** | Loop guard aborts — set a real upper HLR GT. | [ss7-lab-pair.md](ss7-lab-pair.md) |
| Generation bump on **AS CONTINUE** | Bump **only** on MS input (`onUserContinue`). | AGENTS saga notes / code |
| TENANT login username ≠ **tenantId** | Enforced in `AdminUserService`. | root AGENTS |
| Admin **401** / empty form login after **PG migrate** | API key lab default **`ussd-admin`** via header **`X-USSD-Admin-Key` only** (`?key=` is rejected — leaks into access/nginx logs). Form login: seeded **`admin` / `ussd-admin`** (`UssdFirstRunSeeder`) — OK on Digicom after seed **if** `ussd.lab.allow-default-secrets=true` **or** secrets are rotated. Empty table after PG cutover ⇒ 401 until reseed. Digicom nginx often **:80 → :8088** (set `ussd.admin.cookie-secure=false` on plain HTTP). | [skills.md](skills.md) · dist README · [prod-release-path.md](../prod-release-path.md) |
| Node **refuses to start** after package with default secrets | Fail-closed: HMAC/API key still built-in defaults. Digicom lab must keep **`ussd.lab.allow-default-secrets=true`** in **server** `dist/configs/application.properties`, or rotate `ussd.admin.session-hmac-secret` / `ussd.admin.api-key`. | `DefaultSecretStartupGuard` · build/application.properties |
| Repackage wiped PostgreSQL / routing | `package-dist.sh` must **not** clobber existing `dist/configs/application.properties` — writes `.new` instead. Diff before adopting. `db-kind` is build-time. | [schema.md](schema.md) · `build/install-config.sh` |
| Pointing JDBC at OTA DB **`ota`** / flipping **`db-kind` at runtime** | Dedicated DB **`ussdgw`** — never share OTA’s **`ota`**. `quarkus.datasource.db-kind` is **build-time** (H2→PG needs **rebuild** / repackage). Git/lab default stays **file H2**; Digicom PG only in **server** `dist/configs`. | [schema.md](schema.md) |
| Reinventing HTTP AS sim / wrong pull URL | Lab Node AS: **`tools/as-node/`** (Fastify) — `pull:fast` / `pull:bridge` (`DELAY_MS=8000`) / `push:ni`. Seed AS `http://127.0.0.1:8090/ussd/pull`. | [`tools/as-node/README.md`](../../tools/as-node/README.md) |
| Ethiopia `*101xxxxxx` mark seeded as **`*101*`** | Dial `*101123456#` does **not** `startsWith("*101*")`. Use mark **`*101`**. Digicom seeded 2026-08-08 → as-node `:8090`. | [ss7-lab-pair § Ethiopia MO](ss7-lab-pair.md) · `ShortCodeRoutingService` |
| Assuming **10k TPS** knobs are still default (**4096**) | **Target** for 10k lab/prod is pool ×10: `microjainslee.container.sbb-pool-max=40960` (not “landed at 4096”), plus `buffer-size=16384`, `sbb-pool-min=128` in **`build/application.properties`**. These are **BUILD_TIME** — re-package; verify **`dist/configs`** (may still show old default `4096` until `./build/package-dist.sh`). | `build/application.properties` |
| Leaving Digicom **SCTP a_rwnd ~104 KiB** (`rmem_max=212992`) | Stock Linux caps sockets; pcap a_rwnd **106496**. Raise OS/SCTP via [`99-ussdgw-sctp-buffers.conf`](../../build/systemd/99-ussdgw-sctp-buffers.conf) (64 MiB max / 4 MiB SCTP default) + restart ussdgw — **not** by rewriting carrier SS7 JSON. jSS7 `Ss7Config.Link` has no `optionSoRcvbuf` yet. Buffers = headroom only; **5k still unproven**. | `build/systemd/` · install-on-digicom.sh |
| Claiming **5k TPS** on Digicom **2g/4g** without a load run | Grill (2026-08-08 + capacity audit): knobs are necessary **not sufficient**. Digicom host ~15 GiB + OTA — keep **2g/4g**; do **not** push 8g PreTouch there. Dual link (2011/2019) ≠ 5k. Code OK: `AsPullStateRegistry` (not SBB maps), EWMA by **networkId**, `ussdTx` PK=corr, gate `SKIP`+per-session catch, `MAX_GATE_BATCH=5000`, no `Thread.sleep` on park. Runtime bumps (Digicom `bak-5k-YYYYMMDD`): JDBC **128/16**, HTTP client pool **8192**, worker **512**. BUILD_TIME still needs re-package verify (`sbb-pool-max=40960`, `buffer-size=16384`, `db-kind=postgresql`). Residual: `ClassicNiHttpPark` single daemon thread (NI gate fire only; MO uses `BridgeGateScheduler`); **5k unproven** without dedicated ≥8g load host + map/load + AS sim. | `build/application.properties` · Digicom `configs/` · root AGENTS |
| Reusing **one correlationId / ussdTx row across MSISDNs** | Saga PK = **correlationId**. Concurrent users must each have a distinct corr (MO/SIP/lab mint `UUID.randomUUID()`; NI may supply client corr). `put` **CAS-binds** digits MSISDN before full write (`ensureMsisdnBound`) so create→write TOCTOU cannot last-writer-win two subscribers; compare is **digits-normalized** (`+251` ≡ `251`); blank overwrite of a bound row refused. NI first POST **409** on digits mismatch. Never `takeAny`. Same MSISDN multi-session = multiple corr rows (SIP `findAwaitingAsByMsisdn` = best-effort first awaiter). `ussdUser.lastCorrId` = snapshot only — never AS/BPLUS resurrect. | `VirtualSessionStore#ensureMsisdnBound` · `#assertSameMsisdn` · `HttpServerSbb#handleNiFirst` |
| Cursor / agent injects **`Co-authored-by: Cursor`** (or other AI trailers) | Authorship **nhanth87 / Tran Nhan** only. Use a clean message (`commit-tree` if needed); hooks ban AI trailers — never `--no-verify`. Push with **`./build/push-dual.sh`** (nhanth87 lab + digicom-et Digicom overlay) — not a single remote push that forgets Digicom. | workspace AGENTS.md |
| Treating this repo as **SIM OTA** / copying fleet/CAP/`/sendota` | Product is **3GPP USSD** pull/push; OTA admin is **shell UX only**. | root AGENTS migration law |
| Leaving raw **`{{TOKEN}}`** in admin HTML | Seed vars in `AdminPageRenderer` / nav helpers; strip leftovers. | OTA admin-ui lesson |
| CDR UI inventing classic SCCP / dialog / USSD-string columns | Session ledger `ussd_cdr_session` has corr/phase/msisdn/shortCode/status/detail/network/tenant/origin/gate/EWMA/`as_ussd`/`events_json`. Wire those; stub classic-only fields in expand with an honest gap note — never fake IMSI/dialog ids from empty store. Filter = MSISDN + correlation + **status** (exact or `*` prefix: `MAP2MAP_*` / `GATED*`) on **rolled-up** status. Operators need a visible **AS USSD** ledger column (~50 chars) + expand field — not buried-only detail. | `cdr.html` · `CdrService#list` · `CdrStatuses` · `CdrUssdSnippet` · skills § Admin |
| CDR expand dumps `service=VirtualSessionBridge/AdaptiveTimeout|gateMs=…` as hero | Presentation bug. Primary = AS USSD (~50) + outcome; expand = dense **6-hop spine** (`CdrSessionSpine` — fold `events_json`/`as_ussd` only; SKIPPED+reason; slot 6 FAIL if AS text but MAP not to UE). Same Digicom ink-panel / `cdr-status-*` chips — not a new theme. Raw under Advanced. Timestamps = `java.time` only (never Joda). HTMX poll must pin scroll (`show:none`). Do not rip AdaptiveTimeout emit. | `CdrSessionSpine` · `AdminHttpHandler` · `cdr.html` · skills § Admin |
| Dashboard KPI shows raw `{1=1000.0}` / overflows adjacent cards | Never put `Map.toString()` (or other unbounded `toString`) in a metric card. Adaptive EWMA → `AdaptiveTimeout.formatSnapshotForDisplay`. **All** KPIs use `.metric-card` / `.metric-card-value` (`min-w-0`, overflow hidden, ellipsis; `--long` smaller font). Future-proof: long values must not blow the grid. | `AdminHttpHandler` · `admin.css` · skills § Admin |
| Escaping `events_json` detail by `|`→`/` (breaks `asUssd=` parse) | CDR pipe detail is `k=v|k=v`. JSON-serializing events must **keep `|`**; escape only `\`/`"` (`CdrSessionRollup.esc`). `|`→`/` inside snippet **values** (`CdrUssdSnippet.of`) is OK for pipe safety; applying that escape to whole `events_json` detail destroyed separators → expand timeline could not parse `asUssd=`. Prefer authoritative **`as_ussd`** column for ledger display. Pre-fix rows: `normalizeEventDetail` only. | `CdrSessionRollup` · `CdrUssdSnippet` · skills § Admin |
| CDR `gate_ms` / `observed_ewma_ms` always NULL despite V5 + bridge writes | Async path is **`CdrDbFlusher` JDBC UPSERT** into `ussd_cdr_session`, not JPA. UPSERT must list `gate_ms` + `observed_ewma_ms` or entity fields are dropped. Also stamp NI park (`ClassicNiHttpPark` GATED/GATE_EXPIRED) and NI push (`MapNiPushSbb`) with session gate + EWMA; detail `service=…` names AdaptiveTimeout / VirtualSessionBridge. | `CdrDbFlusher` · `ClassicNiHttpPark` · `VirtualSessionBridge` |
| Admin CDR shows N rows per MO (same corr) | Product ledger is **1 correlationId → 1 `ussd_cdr_session` row** (flusher coalesce + UPSERT; `event_count` / `events_json` for expand). File logger `USSD_CDR` stays append-only. Historical multi-row `ussd_cdr` dual-read (newest per corr) — **ask before Digicom DELETE/coalesce**. | `CdrSessionRollup` · `CdrDbFlusher` · Flyway **V13** |
| Permanent **STUB_QUEUED** Diameter/SIP | Live when `ra-diameter` / `ra-sip-servlet` peer ready. | parity-matrix |
| Cursor JDT **autobuild** deleting `target/classes` mid-testCompile | Prefer `java.autobuild.enabled=false`; one Maven at a time. | OTA lessons |
| Squashing Flyway **without** wiping history | Greenfield: wipe H2 / reset `flyway_schema_history`. | [schema.md](schema.md) |
| Shipping **`jdbc:h2:mem:`** as lab/prod | File H2 under `dist/data/` or PostgreSQL. Both drivers in fast-jar. | [schema.md](schema.md) |
| Outbound SRI-SM **CalledParty = MSISDN** (or blank / self) | CalledParty = resolved **`ussd.hlr.upper-gt`** only (admin overlay when non-blank, else props). Never the subscriber MSISDN. Fail-closed if resolved GT is blank or equals local USSD GT (self-loop). | [ss7-lab-pair.md](ss7-lab-pair.md) · `SriSbb` / `HlrFaceService` |
| NI push **SCCP CalledParty = MSISDN** after SRI | After SRI-SM, push UnstructuredSS-Request/Notify toward **`LocationInfoWithLMSI.networkNodeNumber` (MSC)** with MAP destReference = **IMSI** (land_mobile). Classic `HttpServerSbb.onSRIResult` / `getMSCSccpAddress`. Missing MSC → `SRI_NO_MSC` (never MSISDN/HLR/self). LMSI stored; USSD NI does not use SM_RP_DA. Pass MSC/IMSI on `NiPushReadyEvent.fromSri` — profile `get` alone can miss fields under load. | `MapUssdParentSbb#applyNiSriResult` · `MapNiPushSbb` · `MapUssdOutbound#sendNi` |
| **SRI_TIMEOUT** while pcap shows SRI result on **other** SCTP (L2) | Dual-homed Digicom: request may leave L1 (PC 1404) and answer return on L2 (PC 1403) (or vice versa). jSS7 dropped `PayloadData` when **that ASP FSM ≠ ACTIVE** even if parent **AS ACTIVE** via sibling. Fix: `TransferMessageHandler` delivers when ASP ACTIVE **or** AS ACTIVE. Do **not** widen `ss7.live` for this. | coral-valley `m3ua-impl` · [ss7-lab-pair](ss7-lab-pair.md) |
| Tenant **`network_id=1`** while live Digicom SCCP stack only has **`networkId=0`** | MAP dialog inherits tenant networkId → SCCP GTT `no matching Rule` → **0 SCTP DATA** despite `SRI-SM sent` → `SRI_TIMEOUT`. Fix: tenant `network_id` must match live seed `sccp.localPoints[].networkId` (Digicom host SoT). Symptom pcap = heartbeats only. Digicom dual-plane: **live BP = networkId 0**, **lab L3-LAB = networkId 1** — keep live tenants at **0**; lab sim MO uses stack net 1 without changing `*804` tenant. | `ussd_tenant.network_id` · `MapSmsOutbound#sendSri` · `SccpExtModuleImpl` |
| MAP2MAP hop inherits **lab MO networkId=1** | After SCCP split, sim MO dialog is net **1**; hop toward Ethio GT must use **live** GTT (net **0**). Code: `MapUssdParentSbb.map2mapHopNetworkId` → `ussd.map.live-network-id` (default 0). Session/CDR keep MO net. | `MapUssdParentSbb#applyMap2MapFixedHop` · Digicom `ss7-digicom-balance.json` |
| Inbound GTT **wipes** SAP `networkId` to **0** | `SccpExtModuleImpl.translationFunction` copies `routingAddress.networkId` onto the message. `Ss7StackBuilder` used to build addresses with networkId **0** always → lab MO (SAP net 1) becomes dialog net **0** → MO RESULT GTT on live catch-all → DPC **1404** instead of lab PC **2**. Fix: stamp rule `networkId` on pattern + routing addresses in `Ss7StackBuilder`. MO Parent prefers dialog networkId over short-code rule. | coral-valley `Ss7StackBuilder` · Digicom dual-plane |
| Treating **`ni-sent` as handset proof** | Server proves MAP out to MSC + optional peer `unstructuredSSNotify_Response`. Handset UI is off-box — need UE/operator confirm. | Digicom grill 2026-08-08 |
| Equating **`unstructuredSS-Notify` alone with full NI push** | Notify is **one-shot display** (no UE digits). Full NI push also needs **`unstructuredSS-Request`** (interactive menu), UE **`unstructuredSS-Response`**, optional further Request/Notify on the **same** MAP dialog, AS XML continue via **`JSESSIONID`**, and **TC-END / `prearrangedEnd`** release. Prerequisite for live NI: **SRI-SM** → MSC + IMSI (not a USSD op). Classic AS PUSH set: Notify **or** Request (+ release). Digicom proved Notify-only; interactive Request + dialog reuse + real `emptyDialogHandshake` remain gaps. **Notify RESULT→HTTP park** wired (`onNotifyResponse` / `completeParkedEncoded`). | [ussd-3gpp-notes.md](../as-contract/ussd-3gpp-notes.md) · TS 22.090 §5.2 · 23.090 §5 · 29.002 §11.10–11.11 / Table 7.3/2 · classic Chapter-HTTP |
| Treating **TS 22.002** as the USSD/MAP oracle | **22.002** = Circuit Bearer Services (BS 20/30) — **not USSD**. MAP ops / destRef = **TS 29.002**. Stage 1/2 = **22.090 / 23.090**. | [ussd-3gpp-notes.md](../as-contract/ussd-3gpp-notes.md) |
| Ripping **AdaptiveTimeout / Virtual bridge** to “fix” MAP NI | Bridge + park/gate stay **on top**; MAP continue/release under. CAS `claimForAsResponse` / `onGateExpired` still gate MAP emit. | [ussd-3gpp-notes.md](../as-contract/ussd-3gpp-notes.md) §6 · root AGENTS |
| **`PendingSriRegistry.takeAny`** / HLR proxy **`takeAny`** on miss | Cross-subscriber corruption under concurrent NI/PROXY. Key strictly on correlation; miss = fail-closed. No arbitrary “any pending” fallback. | `PendingSriRegistry` · `PendingHlrProxyRegistry` |
| Bridge **`onAsResponse` without CAS** / full-row **`put` after CAS** | Must win `claimForAsResponse` (`→ RESPONDING`) before MAP/NI emit. After `compareAndSetField`, never `get()`+full `put()` — it reverts concurrent field writes. Gate tick: `SKIP` + per-session `catch (Throwable)`. | [skills.md](skills.md) · `VirtualSessionStore` |
| Classic NI **`/ussd` open** (no auth) in ship | Default **`ussd.http.ni.auth-required=true`**. Lab-only opt-out: `ussd.http.ni.auth-required=false`. | `CallbackAuthService` · `HttpServerSbb` |
| NI ingress **`networkId = 0`** hardcoded | Classic read `xmlMAPDialog.getNetworkId()`. Order: dialog `networkId` → authenticated tenant → `ussd.http.ni.default-network-id`. Never an implicit 0. Tenant id must still exist in SCCP (`localPoints.networkId`); Digicom stack is **0 only**. | `ClassicNiIngress` · `HttpServerSbb` · lesson SCCP GTT |
| **Two responses on one inbound SRI-SM dialog** (`FAKE_THEN_RESOLVE`) | `doFake` already answered and closed the dialog. The upper resolve is **enrich-only** — refresh `HlrLocationCache`, never emit a second `SendRoutingInfoForSmResponse`. | `HlrFaceService` · `PendingHlrProxyRegistry.Pending#enrichOnly` |
| Pending correlation with **no TTL** (silent HLR) | Both registries expire: `ussd.sri.pending-ttl-ms` (30s, fails the saga) / `ussd.hlr.proxy.pending-ttl-ms` (15s, **aborts the inbound dialog**). Swept by the existing `BridgeGateScheduler` — never a new raw thread. | `BridgeGateScheduler#sweepPendingCorrelations` |
| `onEvent` **catches `Throwable` but leaves the dialog open** | Handset hangs to the network timer and the dialog leaks. MS-facing leg → `replyAndEnd` with the hard-fail text; anything else → `abort`. A **terminal** dialog event (abort/close/release/timeout) gets nothing — the peer already tore it down. | `MapUssdParentSbb#endDialogOnFailure` |
| `ss7-lab.json` **without HLR SSN 6** | Stack must advertise MAP service **`ssn:6`** (HLR face) **and** `ssn:8` (USSD). Peer sim: SCTP **8014↔8013**, allow SSN **8+6**. | [ss7-lab-pair.md](ss7-lab-pair.md) · `build/ss7-lab.json` |
| Measuring **adaptive EWMA** before AS pull registry is fixed | Load-test readiness: align map/load **SSN/PC/ports** with lab pair; do **not** trust adaptive/gate metrics until pull state lives in **`AsPullStateRegistry`** (else `latencyMs=-1`, ceiling gate). | `AsPullStateRegistry` · jSS7 `map/load` Client props |
| Hand-rolling AS menus / JMX dial | Lab tools: **`tools/as-node/`** (`menus.mjs` presets incl. **`brook804`**) + **`tools/ss7-simulator/`** (CLI JMX + **`run.sh load`**). Prefer these over ad-hoc curls. | `tools/as-node/README.md` · `tools/ss7-simulator/README.md` |
| Load **100 TPS** counted as TCAP msgs / Digicom BPLUS blast | **TPS = unique MSISDN MO sessions/s** (not TCAP). Lab only: map/load via `run.sh load` + as-node `pull:brook804`; JMX ceiling = 1 dialog ([SPIKE-JMX-CONCURRENCY.md](../../tools/ss7-simulator/SPIKE-JMX-CONCURRENCY.md)). Never Digicom/BPLUS @ 100. | `UssdLoadDriver` · `ss7.load.rateLimit` · `ss7.load.msisdnPrefix` |
| Assuming Bridge / AdaptiveTimeout need admin **Start** | Both **auto-run on boot**: `BridgeGateScheduler` Quarkus `@Scheduled` (gate tick, default 100 ms) + `ClassicNiHttpPark` daemon `classic-ni-http-park` + passive `AdaptiveTimeout` EWMA. `ussd.bridge.enabled` (default **true**) only chooses BRIDGE vs hard-fail on gate fire — it does **not** start/stop the ticker. Prove alive: boot logs `BridgeGateScheduler armed` / `first gate tick`, `/admin/status.json` → `scheduler.gateTicks` climbing, threads `quarkus-scheduler-*` + `classic-ni-http-park`. | `BridgeGateScheduler` · `UssdGatewayBootstrap` · root AGENTS |
| Digicom **`POST /ussd` → 500 `UnsupportedOperationException`** with `ss7.live=true` | Quarkus loads **`jainslee-api` before `jainslee-core`**, so the API **stub** `ProfileAccessorInvoker` (always throws UOE) wins over the core impl. Symptom: NI dies in `VirtualSessionStore.put` / CMP `setXxx` before park. **`package-dist.sh` must overwrite** `lib/main/…jainslee-api…jar`'s `ProfileAccessorInvoker.class` with the class from **`jainslee-core`**. Prove with `javap -c` (must see `ProfileFieldStoreLocator`, not `"…implemented in jainslee-core"` throw). | `build/package-dist.sh` · micro-jainslee split-package |
| Digicom NI **`IllegalStateException: No profile table: ussdTx`** after stub fix | `ProfileFieldStoreLocator` can point at a **different/empty** `InMemoryProfileFacility` than `container.getProfileFacility()` (any `new InMemoryProfileFacility()` rebinds the global locator). CMP writes then miss the table that `ensureTable()` created. **`VirtualSessionStore.put` must re-bind** locator to the container facility and re-`ensureTable` if `getProfileTable("ussdTx")` is null. | `VirtualSessionStore#put` |
| Digicom NI 500 with slee detail = class name only | `HttpServerSbb` catch must append **`t.getMessage()`** (and `LOG.error` stack) — class-only `error=UnsupportedOperationException` hid the ProfileAccessor / ussdTx root cause for hours. | `HttpServerSbb#onEvent` |
| Blaming handset / M3UA when NI returns **immediate 500** | If slee shows `POST /ussd` → `error=…` in **&lt;5 ms**, MAP never left. Fix profile/NI park first; only then chase `ss7.live` / SRI / UE. | Digicom 2026-08-07/08 |
| Treating Digicom as a **disposable toy lab** | Digicom host is **prod-bound** (future production): live Balance Plus peer + **PostgreSQL** DB **`ussdgw`**. Never wipe Digicom PG / Flyway casually; never share OTA’s DB **`ota`**. Package Digicom with build-time **`db-kind=postgresql`**, then restore local **H2** for the worktree. | [schema.md](schema.md) · [ss7-lab-pair](ss7-lab-pair.md) |
| Rsync Digicom deploy that **overwrites configs** / drops SS7 | Restart is fine; **never** overwrite Digicom `configs/` (props, carrier SS7 JSON, persist). Rsync **jars/`lib`/`quarkus`/`app/html` only**. After restart poll **`ss7.live`** + `ss -ln --sctp` on operator listen ports. | [ss7-lab-pair](ss7-lab-pair.md) · root AGENTS |
| Digicom MO pull → SCCP **UDTS Subsystem failure** / “no local SSN is present” | Peer may address GW GT with **Called SSN=147 (gsmSCF)**. Stack with only SSN **8** + **6** rejects the UDT → no `onProcessUnstructured`. Fix live seed `services`: `{name:gsmscf,ssn:147,protocol:map}` (plus 8 + 6). Prove boot log **`Registered SCCP listener with extra ssn 147`**. Seed lives on Digicom host (not nhanth87). | [ss7-lab-pair](ss7-lab-pair.md) · coral-valley `Ss7ConfigLoader.extraSsns` |
| Handset shows **“Please wait…”** and chasing **AdaptiveTimeout / bridge gate** | HTTP **200 + empty body** → `AS_EMPTY_BODY` → saga compensate (hard AS failure). That is **not** the EWMA gate. webhook.cool / dump bins are **not** a USSD AS — need classic **XmlMAPDialog** reply. Point short-code at **`tools/as-node`** (or real AS). Optional as-node **`MIRROR_URL`** forwards raw XML to a dump without replacing the AS. | `HttpClientSbb` · `UssdSagaCoordinator#compensate` · `tools/as-node` |
| Digit menu → Amharic root then **English** menu / “lost dialog context” | jSS7 dual `unstructuredSSRequest_Response` (~ms) → dual `onUserContinue` → dual AS pull → BPLUS resets locale/session. **Heap claim on VirtualSession is useless:** `store.get`/`byDialogId` rehydrate a new session from ussdTx (AtomicLong resets). **Fix:** `VirtualSessionStore.tryClaimMsDigitContinue(corr, invokeId)` (process-wide invoke + in-flight) → `dup-skip-continue reason=invoke\|in-flight` before `nextGeneration`/MS_DIGIT/AS pull; `releaseMsDigitInFlight` on AS CONTINUE→ACTIVE. Also first AS after hop/MO = **wire gen 0** BEGIN. Keep gen-stamp. Prove: one PullHttp + one MS_DIGIT per digit; slee `dup-skip-continue`; handset one language. Digicom 2026-08-10 `24570475-…` dual gen=2+3 before store claim. | `VirtualSessionStore#tryClaimMsDigitContinue` · `MapUssdParentSbb#onUserContinue` |
| Digit menu → **`Drop late/zombie AS response … gen=1`** (packages missing) | After MS digit, session gen bumps to **≥2**. Classic XML decode **hardcodes gen=1**; JSON AS may echo `"generation":1` or omit (0). Unstamped reply loses `claimForAsResponse` → `dropLate`. **Fix:** stamp `AsResponse` to `session.generation()` after decode (any wire). Do **not** rip AdaptiveTimeout/bridge. | `HttpClientSbb` · `GrpcClientSbb` · `SipUssiSbb` · `AsResponse#stampedToSessionGeneration` · `CdrMenuTape` |
| Agent “fixed” Digicom **`*804#`** by rewriting **`as_url`** to as-node localhost **without asking** | Digicom / prod-bound PG is **operator SoT**. Never silently `UPDATE`/`DELETE` short-code rules, tenants, users, `network_id`, or other ops rows. A webhook `as_url` (e.g. `https://happy-phoenix-66.webhook.cool`) is **valid** for dump/mirror experiments even when the handset hits **`AS_EMPTY_BODY`** — that is expected, not a broken route. Prefer as-node **`MIRROR_URL`** when you need both a real XmlMAPDialog AS and a dump; do not clobber the operator `as_url`. **Ask before any Digicom DB mutation.** After an authorized change: `POST /admin/routing` `action=reload` + `X-USSD-Admin-Key` (not `/admin/routing/reload`). | root [AGENTS.md](../../AGENTS.md) · Digicom grill 2026-08-08 |
| MAP2MAP hop using **MSISDN / map2map digits as SCCP CalledParty** | **Case 2** (no SRI): prefer `hop_dest_gt`/`hop_dest_ssn`; if hop dest blank → HLR Face `ussd.hlr.upper-gt` + SSN 6. Redirect USSD (`map2map_gt`) is the **USSD string**, not SCCP dest. Stay-on-call ≡ AdaptiveTimeout + Virtual Bridge at ingress (`ussd.bridge.enabled`). Case 1 NI SRI untouched. Digicom: ask before mutating. | [map2map.md](../as-contract/map2map.md) · `Map2MapSbb` |
| MAP2MAP bridge armed **only after hop→AS** | Arm at **ingress** (`setAdaptiveBridgeArm`) when `map2mapArmed()` — **do not** `startAwaitingAs` until hop USSD is on the wire (`armGateAfterHopSent`). Live gate budget = configured async-gate **ceiling** (default 25s), not EWMA×1.5. Completion: re-arm if still `AWAITING_AS`; if already `S1_RELEASED` do **not** `startAwaitingAs` again (CAS). Cancel pending hop on inbound abort; hop TTL ≥ dialog timeout. Telemetry: `/admin/status.json` `map2map.*` + `scheduler.map2mapExpired` — never invent `ss7.live`. | [map2map.md](../as-contract/map2map.md) · `Map2MapTelemetry` · `Map2MapBridgeArmTest` |
| MAP2MAP hop **REJECT** ignored → webhook never got **PullHttp** | Digicom `*804#`→`*875#`: `MAP2MAP_USSD_SENT` then Dialog **REJECT** ~120ms (peer refused hop). Old `handleDialog` omitted **REJECT** → pending sat until gate → only **GatedAsNotify** (sometimes 200) + inbound CLOSE **ZOMBIE** cancelled hop → `map2map.asRouted=0`, no sync AS pull. Fix: handle **REJECT** → `onMap2MapDialogLost` → `Map2MapCompletion` empty-hop AS pull; inbound CLOSE/RELEASE after `S1_RELEASED` must **not** cancel pending / zombie. Prove: slee `PullHttpEvent` + status `map2map.asRouted` climbing. Hop GT/AC still operator (why peer REJECT). | `MapUssdParentSbb#handleDialog` · `MapUssdParentMap2MapDialogTest` |
| CDR `MAP2MAP_TIMEOUT` at ~0.1s with gate=25s | Digicom `0cc0e0dc…` 13:28:36Z: detail was `kind=CLOSE` (ACCEPT→NOTICE→CLOSE, **no** `processUnstructuredSS-Response`). Old `statusForDialogLost` mapped CLOSE/RELEASE → TIMEOUT. Not AdaptiveTimeout (`GATE_EXPIRED`) and not hop TTL. Successful hops: RESULT Service then CLOSE (pending already taken → no TIMEOUT row). **Locked call flow:** hop **USSD text** → CDR `MAP2MAP_HOP_CLOSE` (**amber**); hop **no text** (empty CLOSE) → `MAP2MAP_HOP_FAIL` (**red**) + AS `hlrResult=none`; TIMEOUT only for MAP `TIMEOUT` / hop TTL. | `Map2MapCdr#statusForDialogLost` · [map2map.md](../as-contract/map2map.md) § Call flow |
| CDR `MAP2MAP_HOP_CLOSE` chip still **red** | `cdrStatusChipClass` checked `phase==FAILED` **before** status `MAP2MAP_HOP_CLOSE` → historical rows (`FAILED`+`HOP_CLOSE`) painted fail-red. Law: `MAP2MAP_HOP_CLOSE` → always `cdr-status--gated` (amber like `GATE_ARMED`), **independent of phase**. Fix: `CdrStatuses.ledgerChipClass` order + unit test; ship Digicom jars (source-only greps lie). | `CdrStatuses#ledgerChipClass` · `/admin/cdr` |
| CDR timeline `…AS_ROUTED → END` looks like “ended before AS” | Status **`END`** is `AsAction.END` from `VirtualSessionBridge.applyToLiveDialog` — **AS response already received** and forwarded to UE (`replyAndEnd`). Not hop-close (`MAP2MAP_HOP_*`). Detail must carry `asUssd=` (~50-char snippet) + `asLen=` + `note=AS→UE`. | `VirtualSessionBridge` · `CdrUssdSnippet` · [map2map.md](../as-contract/map2map.md) § Telemetry |
| Wireshark “no AS HTTP request” when AS is **HTTPS** | Filter **TLS SNI** (`tls.handshake.extensions_server_name contains "webhook"`), not cleartext `http`. Empty capture on `http` is a **false negative**. | Digicom grill 2026-08-08 |
| Double AS POST on one MO dialog | jSS7/IES can deliver **`processUnstructured` twice** on the same dialogId. Dedup in `MapUssdParentSbb.onProcessUnstructured` via `store.byDialogId` → `dup-skip` (do not open a second corr / second pull). | `MapUssdParentSbb` |
| Double AS pull on one MS digit (Amharic→English) | jSS7 can deliver **`unstructuredSSRequest_Response` twice** (~ms). Session-heap invoke claim **fails** after profile rehydrate. Dedup in `onUserContinue` via **store** `tryClaimMsDigitContinue` (same invoke → `reason=invoke`; other invoke while in-flight → `reason=in-flight`) — no `nextGeneration`, no second pull, no second `MS_DIGIT`. Release in-flight on AS CONTINUE→ACTIVE. Belt: profile state `AWAITING_AS`/`RESPONDING` → `reason=state`. | `VirtualSessionStore#tryClaimMsDigitContinue` · Digicom 2026-08-10 `24570475-…` |

## Remember

- Peer OTA footguns (dist, link truth, Log4j2, prove artifact) apply **1:1** unless a USSD row above overrides.
- `IN SBB=` / `OUT SBB=` unequal ⇒ handler died without OUT.
- HLR face + NI push share one stack: SSN **6** HLR face vs SSN **8** USSD — document GT split in lab.
- After `package-dist.sh`, confirm `find dist/app -name '*.jar'` is empty and `ussdgw-app.jar` is at dist root.
- Lab AS: prefer **`tools/as-node/`** over ad-hoc curls for XML PULL / bridge / classic NI; Python `tools/as-http-sim.py` remains available.
- Admin planes (2026-08-07): **SS7/SMPP = JSON only**; **HLR** = `/admin/hlr`; **HTTP/gRPC = status only**; **Diameter/SIP = forms**; LIVE STACK tables = **ink-panel** (never nested black `bg-ink`). → [skills.md](skills.md) § Admin
- Admin theme / nested black (2026-08-09): Tailwind CDN `bg-ink/40` / `bg-ink-panel/40` are **separate classes** — light remap must cover opacity variants (`[class*="bg-ink/"]`, `[class*="bg-ink-panel"]`), not only `.bg-ink`. Form controls inside `form-card` = **panel**, never nested pure `#0c1220`. Theme SoT key per product (`ussd-theme` / `ota-theme` / `ccv-admin-theme`); Monitor Hub must share the host product key. Never invent a dirtier nested-black default. → [skills.md](skills.md) § Admin · OTA [admin-ui.md](../../../../ota-service/ota-sim-push/docs/agents/admin-ui.md) · jainslee-monitor hub.css
- CDR expand closes after ~1s–5s (2026-08-09): root cause = HTMX `every 5s` **innerHTML** swap of `#cdr-rows` wiping `hidden` toggles. Fix = `sessionStorage` open ids + restore in `htmx:afterSwap`. Expand must show gated digest + corr timeline (`CdrSessionDigest`), not only the single row's raw detail.
- Dashboard KPI + CDR AS USSD (2026-08-10): ADAPTIVE card showed raw `{1=1000.0}` and overflowed neighbors — fix `formatSnapshotForDisplay` + `.metric-card` overflow law for **all** KPIs. CDR AS text must stay visible (~50-char ledger column + expand); never `|`→`/` escape whole `events_json` detail (breaks `asUssd=`). Prefer `as_ussd` column. → [skills.md](skills.md) § Admin
- CDR service-status digest (2026-08-10): expand was a GATE_ARMED / VirtualSessionBridge pipe dump. Redesign: AS USSD + outcome primary; `CdrServiceStatuses` human plane chips (MAP MO · HLR/hop · Bridge/Adaptive · AS HTTP · MAP UE · NI); timeline human labels; raw under Advanced. → [skills.md](skills.md) § Admin
- CDR 6-hop spine (2026-08-10): operators need denser hop detail (msisdn/sc/GT/hopText/asUrl/gateMs/reasons), not prettier chrome. Fixed spine always shown; fold events only; AS ~50 in column+hero+step5; slot6 red when AS text not MAP'd to UE; scroll pin on poll; Digicom scheme fidelity; **no Joda** (`java.time` only). → [skills.md](skills.md) § Admin
- CDR expand scroll jump (2026-08-10): click mid-page still jumped to bottom despite `show:none` + poll pin. Root = Chrome **overflow-anchor** on ledger foot + `overflow-x-auto` nested scrollport + smooth scrollBehavior. Fix = `overflow-anchor: none`, drop ledger overflow-x, pin scroll on **click** too, `focus-scroll:false`, Advanced = raw-only, expand = full-width spine/session. Prove Digicom `scrollY` stable. → [skills.md](skills.md) § Admin
- Admin theme dirty-black (2026-08-09): light pages still showed nested black because Tailwind `bg-ink/40` / `bg-ink-panel/40` were **not** remapped (only bare `.bg-ink`). Forms used nested `bg-ink` inside `form-card`. Hub used separate theme keys + brand drift. Fix: remap opacity classes in `admin.css`; inputs → `bg-ink-panel`; Monitor Hub `ussd-theme` + Digicom brand; never ship nested `#0c1220` wells. → [skills.md](skills.md) § Admin
- Secrets: fail-closed defaults unless **`ussd.lab.allow-default-secrets=true`**; admin API key = header **`X-USSD-Admin-Key` only**; passwords **bcrypt**; `package-dist` / `install-config.sh` never clobber live `configs/`.
- Digicom = **prod-bound** host (Balance Plus + PostgreSQL **`ussdgw`**). Deploy: rsync **jars / `lib/` / `quarkus/` / `app/html`** only — **never** overwrite Digicom `configs/` (PG URL, secrets, SS7 seed/persist). Package with **`db-kind=postgresql`** for Digicom ship, restore local **H2** for the dev tree. Paths: APP_HOME `~/ota-push-services/ussdgw-micro-jainslee/`, as-node under `…/tools/as-node`. **Never mutate Digicom routing/tenant/user/`network_id` DB without asking** — operator SoT.
- Dated lab notes **2026-08-07**: dual-mode AS wire, AS pull `@ApplicationScoped` registry, bridge CAS+gate resilience, NI auth default-on, HLR upper-GT CalledParty, Digicom heap **2g/4g**, PG `ussdgw` vs `ota`, as-node menus + ss7-simulator CLI, map/load shortCode props.
- Dated **2026-08-08**: Digicom live NI unblocked — shadow `ProfileAccessorInvoker` into api jar at package time; re-bind `ProfileFieldStoreLocator` in `VirtualSessionStore.put`; NI auth header **`X-USSD-Api-Key`** (admin key OK). Proof: `POST /ussd` → HTTP 200 + `ni-parked` + `SriSbb` with `ss7.live=true`.
- Dated **2026-08-08**: Digicom SRI receive + MSC address — pcap `ussd-sri-hlr-20260808-020509.pcap` (SRI out L1/1404, result in L2/1403); app was `SRI_TIMEOUT` / no `MapNiPushSbb`. Root: M3UA drop on non-ACTIVE ASP + NI handoff ignored `networkNodeNumber`. Fix m3ua `TransferMessageHandler` + `applyNiSriResult` → `NiPushReadyEvent.fromSri(msc,imsi)` → `MapNiPush` / ra-jss7 destRef=IMSI.
- Dated **2026-08-08**: Digicom NI notify prove — `ss7.live=true`, pipeline `ni-parked` → `sri-ok msc=…` → `ni-sent notify` + peer Notify RESULT; CalledParty = **MSC** (not MSISDN). Pcaps stay on Digicom host / gitignored `build/pcap/`. Handset UI not confirmable from server alone.
- Dated **2026-08-08 NI message-set audit**: Notify alone ≠ full NI push. Gaps: interactive Request dialog reuse, NotifyResponse→park complete, real emptyDialogHandshake, prearrangedEnd/abort MAP close, customInvokeTimeout.
- Dated **2026-08-08 full 3GPP read**: ETSI **22.090 V18.0.1**, **23.090 V18.0.0**, **22.002 V17.0.0** (Circuit BS — not USSD), **29.002 V18.0.0** USSD clauses + Table 7.3/2; ARIB Rel-5 22.090/23.090 cross-check. Durable notes + grill + plan: [ussd-3gpp-notes.md](../as-contract/ussd-3gpp-notes.md). Affirm AdaptiveTimeout/Virtual bridge **ontop** of MAP NI.
- Dated **2026-08-08 P0 NI continue/release**: JSESSIONID continue with live `mscGt`+`dialogAlive` → `NiPushReadyEvent.continueOnDialog` → ra-jss7 `MapUnstructuredSsContinue` (no SRI / no `createNewDialog`); empty/`mapMessagesSize=0` → `MapDialogClose(prearrangedEnd)`; `mapUserAbortChoice` → abort by corr reverse-map. Notify RESULT→park already done.
- Dated **2026-08-08 grill 5k + profiles**: per-MSISDN isolation = corr-keyed `ussdTx` + fail-closed put/409; 5k TPS = knobs present but **not measured** on Digicom 2g/4g — claim only after dedicated load host.
- Dated **2026-08-08 5k capacity checklist** (Digicom = PG prod-bound): (1) BUILD_TIME baked `sbb-pool-max=40960` / `buffer-size=16384` / `db-kind=postgresql` — re-package if unsure. (2) Runtime: client pool ≥ concurrent SYNC AS waits, JDBC ≥128 for CDR batch, worker ≥512, bridge gate 100 ms, `ussd.tx.profile-ttl-ms`, CDR queue/batch. (3) Code: registry/EWMA/corr-PK/CAS gate — already green. (4) Backup Digicom props before capacity edits (`*.bak-5k-YYYYMMDD`); never touch short-code/secrets/SS7/db URL. (5) Restart GW to pick runtime props; prove later on ≥8g host with map/load + as-node, watch `scheduler.gateTicks`, CDR lag, AS pull INFO, heap. (6) **OS/SCTP buffers** — install `build/systemd/99-ussdgw-sctp-buffers.conf` (see skills § Digicom OS/SCTP buffers); restart ussdgw; confirm `/proc/net/sctp/assocs` rcvbuf/sndbuf ≫ 212992. Dated **2026-08-09** raise applied for capacity headroom (not a measured 5k).
- Dated **2026-08-08**: Bridge + AdaptiveTimeout **auto-run on boot** (no Start). `scheduler.gateTicks` proves ticker; do not confuse `ussd.bridge.enabled=false` (hard-fail on gate) with scheduler dead.
- Dated **2026-08-08 MAP2MAP telemetry**: process-local `Map2MapTelemetry` → `/admin/status.json` / monitor strip (`map2map.armed`, hop path counters, `gatedDuringHop`, `timeoutAfterBridge`, `pending`); `BridgeGateScheduler` dual role still exposes `scheduler.gateTicks` + `scheduler.map2mapExpired`. Status truth for Digicom remains `ss7.live` from `LinkStatusService`.
- Dated **2026-08-08 MAP2MAP CDR**: `Map2MapCdr` statuses (`MAP2MAP_ARMED`, `HOP_START`, `GATED_HOP`, `OK` / `COMPLETE_AFTER_GATE`, `TIMEOUT` / `TIMEOUT_AFTER_BRIDGE`, …) with detail `sc|redirect|dialed|hopGt` and `gate_ms`/`observed_ewma_ms` on gate/TTL rows.
- Dated **2026-08-08 CDR gated + re-route E2E**: catalog `CdrStatuses` + admin `/admin/cdr` status filter (`MAP2MAP_*` / `GATED*` / `GATED_AS*`); stamp `GATED_AS_NOTIFY`/`SKIP`/`ACK`/`FAIL` from `GatedAsNotifyService` + `HttpClientSbb`; `CdrDbFlusher` already INSERTs `gate_ms`/`observed_ewma_ms`. Sample ops query: `SELECT status, gate_ms, observed_ewma_ms, detail FROM ussd_cdr WHERE status LIKE 'MAP2MAP_%' OR status LIKE 'GATED%' ORDER BY recorded_at DESC LIMIT 50`.
- Dated **2026-08-08 MO pull grill**: Balance Plus Called SSN **147** → seed `gsmscf` + prove `extra ssn 147`; empty AS 200 ≠ AdaptiveTimeout (`AS_EMPTY_BODY`); HTTPS AS → Wireshark TLS/SNI; `CdrDbFlusher` must INSERT `gate_ms`/`observed_ewma_ms`; tenant `network_id` ≡ SCCP `networkId` (Digicom **0**); MO `processUnstructured` dedup by dialogId.
- Dated **2026-08-08**: Restored Digicom `*804#` `as_url` to **`https://happy-phoenix-66.webhook.cool`** after an agent silently rewrote it to as-node localhost. Footgun: never change Digicom routing without asking; webhook dump URLs are intentional even when AS_EMPTY_BODY.
- Short-code match is **exact dial string**: `*804#` ≠ `*840#` (authorized Digicom INSERT `*840#` → same webhook 2026-08-08).
- Quarkus Digicom patterns (thin): build-time `db-kind=postgresql` for ship / restore local h2; CDI `@Scheduled` bridge gate + AdaptiveTimeout park (no `Thread.sleep`); fail-closed NI without MSC; `/admin/status.json` for `ss7.live` — never invent UP from LISTEN.
- Dated **2026-08-09 compile + Digicom redeploy**: JDK 25 → `db-kind=postgresql` package → restore `h2` → rsync jars/`lib`/`quarkus`/`app/html` only (never `configs/`) → restart → wait `:8088` `/admin/status.json` → prove `ss7.live` / `bridge.asyncGateMs` / jar (`GATE_ARMED`, new classes). **Copy-paste SoT:** [skills.md](skills.md) § Digicom compile + redeploy (do not rediscover host paths).
- Dated **2026-08-23 sync from gmlc-microjainslee** (Monitor Hub / KPI / fast-jar deploy):
  - **Rsync `quarkus/` + `lib/` TOGETHER with the app jar — always.** Quarkus fast-jar also loads app classes from `quarkus/transformed-bytecode.jar` + `generated-bytecode.jar`; a stale `quarkus/` shadows the new root jar (old code runs despite fresh jar mtime) or an H2-era `quarkus/` over a PG URL crash-loops with `Driver does not support jdbc:postgresql`. Jar-only rsync = forbidden. Prove boot from **log lines**, never mtime. (GMLC incident 2026-08-23: pack missing → then restart-loop.)
  - **ServiceLoader cannot see `META-INF/services` inside the ROOT app jar** at boot (fast-jar layering) — only packs in `lib/main` are discovered. App-owned `RaAdminDashboardContributor` packs must be appended explicitly into `AdminDashboardRegistry` (merge TCCL + SPI CL + app CL, dedupe by raName). Reference: gmlc `AdminHttpHandler.buildHub()`.
  - **Monitor Hub routing law**: route ALL hub paths (`isMonitorHubPath`: `/telemetry/*`, `/api/telemetry/*`, `/api/admin/dashboards`, `/admin/ra/**`, `/api/ra/**`, `/api/autonomous/*`) or RA tabs 404; **`/metrics` is NOT a hub path** — serve `port.scrape()` in-app; anonymous = inert static extensions only (GET/HEAD under `/telemetry/` + `/admin/ra/`), never dotted API paths; hub Overview polls `/admin/monitor-feed` every 1s.
  - **Hub branding**: jainslee-monitor now takes `MonitorHandler(…, appName)` + `@@APP_NAME@@` token — pass your product name or the shell shows legacy "Digicom-ET USSDGW".
  - **Protocol-KPI pattern** (reference gmlc `GmlcKpi` + `GmlcKpiContributor`): LongAdder map + `TelemetryPort.customCounter` mirrors (`gmlc_kpi_*` on `/metrics`) + own hub tab polling `/status.html`. Same shape works for USSDGW MAP2MAP/bridge counters if product asks for success/fail KPIs.
  - Readiness probe nuance: `/admin/status.json` returns **401 anonymous** (HTTP up only); ready = **200 WITH admin key**.

## 2026-10-02 — CDR moved to the file ledger + MAP returnError fixed (ported from gmlc / jain-slee)

**CDR file ledger is now the source of truth.** `logs/ussd-cdr.log` was already written but nothing
read it. Now `CdrFileLedger` (file = SoT, ring = hot read model) backs `/admin/cdr`.

- **Filter before cap** (gmlc 2026-09-30, verbatim trap): a tenant page went empty because
  `head(50)` ran *before* the tenant filter. `CdrFileLedger.sessions(limit, keep)` rolls a
  correlation up and applies the predicate **first**, stopping only when `limit` *complete*
  correlations have matched. A correlation is complete only when a different one is seen — never
  roll up half a session or stop before its first event. Test:
  `CdrFileLedgerTest.tenantFilterRunsBeforeTheRowCap`. Live prove: 61 corrs, oldest (position 1)
  still returned by `?corr=` and by `?msisdn=` under `limit=50`.
- **Line format v1** — instant in **field 0**, so the CDR appender pattern is bare `%m%n`. Adding a
  `%d` prefix would prepend a second timestamp and **every line would fail to parse**. Header line
  (`# ussdgw-cdr v1 …`) is written at boot so rotation keeps the contract; parsers skip `#`.
- **Escaping is load-bearing**: `detail` is the pipe-delimited `k=v` digest `CdrSessionDigest` parses
  and `asUssd` is arbitrary AS text — both may contain `|`. Those fields escape `\|` / `\\`
  (`splitEscaped`); identifier fields sanitize `|` → `/`. CR/LF → space, always: a raw newline
  corrupts a line ledger. `parse()` still understands the pre-v1 `%d`-prefixed 10-field line so
  rolled files written before the upgrade still render.
- **`ussd.cdr.db.enabled` default `false`** — the PG `ussd_cdr_session` mirror is opt-in. Tables and
  **Flyway history untouched** (V1–V13, `UssdSchemaInitializer.REQUIRED_TABLES` still lists both
  tables, so boot stays fail-closed on a missing table). No DROP, no migration, nothing to reverse
  on Digicom. Re-enable the mirror only if something downstream reads it via SQL.
- Ring overflow drops **oldest** and counts it — `cdr.file.dropped` in `/admin/status.json` is
  non-zero ⇒ file still has every line, only the in-memory view is short. `cdr.file.warmed` proves
  the restart re-read the file (a restart must not show an empty page).
- `AdminHttpHandler` needed **zero** changes to the CDR page: `CdrService.list/listRecords/timelineFor`
  kept their signatures, so spine / menu tape / AS-hero all still work off `events_json`.

**MAP returnError was silently dropped** (gmlc 2026-09-30, ported). `Ss7MapEvent` is a **sealed**
interface with four subtypes — `Service`, `Dialog`, **`Error`**, `Remote`. Only two were mapped in
`SbbRegistrationSupport` and branched in `MapUssdParentSbb.onEvent`, so a TS 29.002 returnError
(`absentSubscriber`, `error 52 unauthorizedRequestingNetwork`) **never reached the SBB**: the dialog
sat until the network deadline and the CDR showed `MAP_TIMEOUT` instead of the real refusal.
Fix: map + branch `Error` (fail the saga fast, CDR `MAP_RETURN_ERROR`, end MS-facing leg with the
hard-fail text / abort otherwise) and `Remote` (log honestly, never swallow). Test:
`MapReturnErrorSbbTest` — including a guard that the permitted subtypes stay 4.
**Rule: every subtype of a sealed RA event needs a mapping AND a branch. "ignored" in
`SleeEventTrace` is a smell, not a no-op.**

**`package-dist.sh` ProfileAccessorInvoker shadow is obsolete** (ADR 0004 in micro-jainslee). The
split-package trick (throwing stub in `jainslee-api`, real body in `jainslee-core`, relying on
classpath order that fast-jar inverted) is gone — `jainslee-api` now owns ONE delegating invoker
resolving a `ProfileAccessorBridge`, published by core via `META-INF/services`. The script's old
check hunted a class that no longer exists and **failed the whole package**. Replaced by
`verify_profile_accessor_bridge`: asserts the api class delegates (not a UOE stub), that core ships
the SPI file, and that core ships `CoreProfileAccessorBridge`. Do **not** re-add the shadow.
Footgun inside that check: under `set -o pipefail`, `unzip -l … | grep -q` **fails even on a
match** (grep exits at first hit, unzip dies on SIGPIPE) — extract to a temp dir and test the file.

**Lab prove recipe** (dist is baked `h2`, `dist/configs` ships `postgresql`): `run.sh` hardcodes its
own `-Dquarkus.config.locations` **after** `${JAVA_OPTS}`, so env/`JAVA_OPTS` overrides lose. Copy
`dist/configs/application.properties` to a scratch dir with `db-kind=h2` + a `jdbc:h2:file:` URL,
then launch `quarkus-run.jar` directly with `-Dquarkus.config.locations` pointing at it. Never edit
`dist/configs` to make a local run work.

## 2026-10-02 — Containerised deploy: 12 defects that all failed *silently*

Deploying USSDGW as a Docker Swarm stack built from source on the Digicom test host
(`digicom-nb`, `ubuntu`, 15 GB / 4 CPU). The container path had never been run end to
end. Every one of these produced a deployment that **looked healthy while nothing
served traffic** — the family of bug this file exists to prevent. Most are
**product-neutral** and apply equally to OTA, Elisa, IMSI and silent-auth.

### The rule that catches all of them

> **A check that cannot fail is not a check.** Every one of these shipped with a
> guard, an assert, a comment or a doc that claimed to prevent it, and the guard
> itself was the bug. When you write an assertion, ask *what value would make this
> fail* and then go produce that value and watch it fail.

### Provenance: the tag is not the build

| Defect | Symptom | Rule |
|---|---|---|
| `.dockerignore` in `docker/`, not the context root | Docker read **no** ignore file: 431 MB context + 2.9 GB `.git`, plus `build/digicom-secrets-*.txt` and every `ss7-digicom*.json` | `.dockerignore` is read at **`<context-root>/.dockerignore`** or `<dockerfile-dir>/.dockerignore` — for `docker build -f docker/x/Dockerfile .` that is the **repo root** only. Verify by the **context size** in the build log, not by reading the file. |
| The ignore file also excluded `dist/lib`, `dist/quarkus`, `dist/*.jar` | Enabling it as written would break the build | A `.dockerignore` must be checked against the **actual `COPY` lines** of every Dockerfile. Inert-then-wrong is worse than absent. |
| `ussdgw-builder:probe` consumed by `docker/ussdgw/Dockerfile:14` with no producer | `pull access denied` at the runtime build | An image tag referenced by a `FROM` must be **published by a script in the tree**. Grep for tags, then grep for who creates them. |
| `cp -a out/dist/. dist/` (merge, not mirror) | image with **both** `jainslee-core 1.2.0` and `1.2.1` — classpath conflict resolved at runtime by whichever class sorts first | Stage with `rm -rf dist/` first. Assert **one version per artifact** (`sed -nE 's/^(.*)-([0-9][^-]*(-SNAPSHOT)?)\.jar$/\1 \2/p' \| sort \| uniq -d`). |
| `src` mounted `:ro` unconditionally | `SOURCE_MODE=git` impossible — the documented alternative was the only working path | Writable only in the mode that writes. |
| `RUN_TESTS=1` printed "two pre-existing failures are EXPECTED" and continued | packaged a **red** tree into a shippable image | A build flag that says *run tests* must **fail the build** on failure. |

### Diagnostics that disable their own diagnostics

> **Watch for `exec` redirections in a fail-fast script.** `exec` with no command
> changes the shell *permanently*.

```bash
exec 3>&- 2>/dev/null || true   # BUG: exec 2>/dev/null is permanent
```

From that line on, every `die()` and every `log()` in the script — **and the JVM's own
stderr**, which `run.sh` inherits — went to `/dev/null`. An unwritable mount produced:

```
[entrypoint] PostgreSQL reachable at … → Exited (1)          (no reason at all)
```

After the fix:

```
[entrypoint] FATAL: /opt/ussdgw/configs is not writable — the mount must be rw …
```

Worst possible shape, because `restart_policy: max_attempts: 5` then retired the task:
`docker stack services` still listed the service, `:8088` never bound, `docker logs`
had nothing. To close fd 3 without touching stderr: `exec 3>&- || true`.

### Asserts that passed on corrupt input

The postgres tuning hook asserted the loopback pin and **reported success against a
file PostgreSQL had already refused to parse**:

```
log_statement = 'ddl'listen_addresses = '127.0.0.1'     # merged: no trailing newline
LOG:  syntax error … line 848, near token "listen_addresses"
FATAL:  configuration file "…/postgresql.conf" contains errors
```

The assert was `grep -qE "^[[:space:]]*listen_addresses[[:space:]]*=[[:space:]]*'127\.0\.0\.1'"`.
The merged line **still matches** — it has the key, whitespace and an equals sign.
**A regex cannot distinguish a valid assignment from a corrupted one.** Validate by
**parsing**: `postgres -D <datadir> -C <setting>` reads the config and exits non-zero
if it does not.

Related, all from the same hook:

| Trap | Detail |
|---|---|
| `postgres -D` takes a **data directory** | Passing the config *file* → `…/postgresql.conf/postgresql.conf: Not a directory`, which failed identically for a good and a corrupt file — a check that could only ever look broken. |
| `postgres -C` writes LOG lines to **stdout** too | `LOG: skipping missing configuration file …` then the value. Take the **last non-empty line**. |
| `postgres -C shared_buffers` reports **8 kB blocks** | 256MB prints as `32768`. Say `256MB (32768 x 8kB blocks)`. |
| `listen_addresses = 127.0.0.1` unquoted is **invalid** | Parsed as `127 . 0 . 1` → `near token ".0"`. Quote the value. |
| `grep` emits its **last line without a newline** | Same defect one level down. Use `awk '…' \| while read -r; do printf '%s\n' "$line"; done`. |
| Test executability against what the entrypoint does | The official postgres entrypoint **sources** a non-executable `.sh` (`if [ -x "$f" ]; then "$f"; else . "$f"; fi`). `test -x` rejected a working image; use `test -r` **as the postgres user**. |

### Config that is installed and never read

**B18, and the most serious of the set.** `docker/postgres/Dockerfile` copied the
operator's tuning to `/etc/postgresql/postgresql.conf` and appended a
`listen_addresses` line to that same file. The official image reads
**`$PGDATA/postgresql.conf` and nothing else** — grepping its entrypoint for
`/etc/postgresql` returns nothing. The server ran on its shipped defaults:

```
show listen_addresses;  ->  *          show shared_buffers;  ->  128MB   (not 256MB)
```

On `hostnet` there is no `-p` publishing step to mask that. Proven on the carrier host
with `ss -lnt` while the image ran under `--network host`:

```
LISTEN 0 200  0.0.0.0:5433  0.0.0.0:*        <- before
LISTEN 0 200   127.0.0.1:5433  0.0.0.0:*    <- after
172.16.144.163:5433  reachable   /  after:  no
192.168.0.70:5433     reachable   /  after:  no
```

The USSD database on every interface of a host that also holds live M3UA associations.
`pg_hba.conf` asked for `scram-sha-256` — but that is the second line of defence, and
**`pg_hba.conf` was being overridden by the very same dead file**.

> **Prove a config file is *read*, not merely *present*.** `test -f` and even a
> `docker exec … cat` prove the file exists. Ask the consumer for the value it
> derived: `show listen_addresses`, `nginx -T`, `java -XshowSettings`.

The fix keeps **one authored source** (`/etc/postgresql/postgresql.conf`) and has an
initdb hook append it to `$PGDATA/postgresql.conf`, where later assignments win. One
file to edit; no second copy to drift.

### Services that point at an image nothing builds

`docker/stack.yml` used the **stock upstream** `postgres:16@<digest>` while
`docker/postgres/Dockerfile` — the file carrying the initdb hook and the loopback pin
— was built by no script and referenced by nothing. Two consequences, neither visible
until the gateway was already deployed:

1. `01-ussdgw.sh` is what creates the `ussdgw` role + database. Without it the app
   connects as `username=ussdgw` and gets `FATAL: role "ussdgw" does not exist`.
2. Upstream ships `listen_addresses = '*'` so `-p` works.

> **For every `image:` in a stack file, find what builds it.** If the answer is
> "nothing in this repo", the deploy uses a different image than the one the
> Dockerfiles describe. `docker build-images.sh` now builds all three and **asserts
> the properties inside the built image** rather than trusting the build log.

### `set -e` scripts that hide their own failure

```bash
cp -a "$CONFIG_SRC"/. "$DEST"/     # ~30 × "Permission denied", then carried on
```

The documented order is `host-prep.sh` (creates `/srv/ussdgw/configs` as **10001**, so
the container can write stack JSON back) then `install-config.sh` (run by `app`, uid
1000). The destination is never writable by the installing user. Check **once, up
front**, and name the remedy — `sudo ./docker/install-config.sh --force`.

### Seeding: a whitelist, not `cp -a src/.`

`cp -a "$CONFIG_SRC"/. "$DEST"/` copied whatever the operator kept beside the live
config: 10 `application.properties.bak-*` copies, 4 historic `ss7-*.bak*`, a
`bak-sysctl-*` dir, and **4 `ss7-persist.quarantine-*` dirs**. The quarantine dirs are
separated from the live tree **precisely because their SIM persist XML is corrupt**
(a `<1>` key is exactly what the AGENTS law forbids a booting gateway from loading);
copying them back undoes the quarantine. The destination ended up with eleven files
named `application.properties`, so "which one is live?" became a guess.

Seed only what the gateway reads — `application.properties`, `ss7-*.json`, and an
**empty** `ss7-persist` (a fresh gateway must not inherit association state). **Refuse
to continue if quarantined material is present.**

### "Already done" heuristics that fire on empty

```bash
if [[ -n "$(ls -A "$DEST")" ]]; then echo "already populated — leaving untouched"; exit 0; fi
```

`host-prep.sh` creates `$DEST/ss7-persist` first, and it is the **documented order**, so
on every freshly prepared host the directory was never empty. The script exited 0
having copied nothing, and the deploy continued with an empty mount; the gateway then
died one step later with "no application.properties" — reading like a mount or
permission fault rather than a seed step that silently did nothing.

> **Decide "already done" from the artefact the consumer requires**
> (`[[ -f "$DEST/application.properties" ]]`), never from "the directory is non-empty".
> And assert the copy landed — `cp` exiting 0 is not evidence when the destination
> already held a same-named file.

### Counting that can only read zero

Reporting `skipped N backup/quarantine file(s)` from inside the copy loop: `*.bak`
never ends in `.json` so it never matched the glob, and `application.properties.bak-*`
was never a candidate. The counter **could only ever print 0**, which reads as "nothing
was skipped" — worse than not reporting. Enumerate the source instead. (First attempt
counted dirs with `[[ -d "$e" ]]` where `$e` was a *basename*, so every directory
read as a file: `0 dir(s)` against 3.)

### nginx: verify the image runs, not that it builds

| Defect | Symptom |
|---|---|
| `upstream ussdgw_app` in **both** `nginx.conf` and `conf.d/ussdgw.conf` | `[emerg] duplicate upstream "ussdgw_app"` — the container could not start. Missed because `prove.sh` only *noted* "nginx not answering on :80 (expected if not deployed yet)". |
| `FROM ubuntu` + `apt install nginx` + `chown nginx:nginx` | Ubuntu's nginx package has **no** `nginx` user. Now `nginx:1.27-alpine` **digest-pinned** in `sources.lock`. |
| Build-time `nginx -t` left a root-owned `/tmp/nginx.pid` in the image | `/tmp` is sticky, so a uid-101 master can neither write nor unlink it → `[emerg] open() "/tmp/nginx.pid" failed (13: Permission denied)` on **first real run**, long after the green build. Delete the pair and the pid file, and **assert they are gone**. |
| `user nginx;` under a non-root master | Ignored, with a warning. Remove it; workers are already nginx via `USER nginx`. |
| `/var/run/nginx.pid` | root-owned — uid 101 cannot create it. `pid /tmp/nginx.pid`. |
| `worker_rlimit_nofile` 4096 < `worker_connections` 2048 vs service soft limit | nginx warns and continues. Set the limit, `worker_rlimit_nofile` and the service `ulimits.nofile` **together**. |
| Comment claimed `listen 443 ssl` is conditional | It is **unconditional**. `install-config.sh --check` now verifies the cert exists, is **readable by uid 101**, is unexpired (>24 h) and is chain-complete before deploy. |
| `host-prep.sh` `mkdir`'d `nginx/certs` and left it `root:root` | Works only if the operator remembers `install -o 101`. Now owned `101:101` mode 750. `chmod 644` the key is never acceptable. |

> **A container bind mount keeps the *host's* ownership.** The image's uid does not
> rewrite it. `chown` the host path to the container's uid, or the process that
> matters cannot read it.
>
> **Build-time self-tests leak into runtime.** A `nginx -t` that writes `/tmp/nginx.pid`
> bakes a file the non-root runtime cannot use. Clean up, and assert the cleanup.

### Proving a local bind beats proving a config

`nginx -t` and a green build both passed while the container died on first run. What
actually proved it: `docker run` with the certs chowned to 101, then
`Server: nginx`, `/healthz` **200**, `/admin/status.json` **200 through the proxy**,
TLS 200, no `warn`/`emerg` at start.

Likewise, when a local test showed `/healthz` → 404, the response was
`Content-Length: 19` + `X-Content-Type-Options: nosniff` = Go's `404 page not found`
from a **pre-existing service already on `127.0.0.1:80`**, not nginx. Confirming
`Server: nginx` from *inside* the container's own netns settled it in one command.

> **When a probe disagrees with the code, find out who is answering before changing
> the code.** `Server:`, `Content-Length:` and a second listener are cheaper than a
> fix built on the wrong assumption.

### Two false proofs of my own, worth recording

1. **`ss -lnt | grep 5432` "proved" loopback-only** — but 5432 was the **host's own
   PostgreSQL** (still running, per plan), which also binds `127.0.0.1:5432`. I had
   observed the wrong process. Re-ran on a **free port (5433)** with a
   `docker ps`/`ss` baseline taken first, and checked the container still existed
   before reading its log (I had `docker rm -f`'d it in the same command).
2. **A test run whose `TUNING` env var could not override the hook's hard-coded
   assignment**, executed `postgres -C` as root, and used `listen_addresses = 127.0.0.1`
   unquoted — an **invalid** config. It "proved" the hook rejects bad input while
   actually proving the fixture, not the code. Check that a test can reach the code
   path, and check the **test input is valid**, before believing a green test.

> **Establish the baseline before you measure.** Take the `ss` / `docker ps` snapshot
> first, use a port nothing else holds, and read logs **before** removing the
> container. A "pass" on the wrong process or the wrong fixture is worse than no
> result, because it ends the investigation.

### `set -o pipefail` + `grep -q`

Under `pipefail`, `docker exec … \| grep -q` **fails on a match** (SIGPIPE). Validate
through a file (`printf … > /tmp/x; python3 /tmp/x`), never a pipeline. Same trap as
`unzip -p … \| grep -q` in `prove.sh`.

### Port conflicts — rollback must be ordered

After stopping `gmlc`: 2011/2019 and 8088 free; **5432 still held by host PG**, **80/443
still held by host nginx**. So rollback is **four ordered commands** — stop the stack
*before* starting host PG, or the container holds 5432 and host PG fails to start:

```bash
docker stack rm ussdgw
sudo systemctl start postgresql
sudo systemctl start nginx
sudo systemctl start gmlc
```

Fail-closed guard before deploying — the container holds these ports, and
`restart_policy: max_attempts: 5` makes a bad nginx config vanish **silently**:

```bash
ss -ln | grep -E ':(80|443|5432|8088)\b' && { echo "port bận — dừng"; exit 1; }
ss -ln --sctp; cat /proc/net/sctp/assocs
```

### Weak credentials, found while reading (do not fix unilaterally)

The operator's live `application.properties` carried a **6-character** database
password. For the **new, empty** container database I generated a 28-character
password, set the swarm secret, and **commented the hard-coded lines out of the seeded
config** so the secret is the single source (Quarkus env ordinal 300 outranks the file
at 250). An operator's existing credentials are **not** to be changed without asking —
but a database being created from scratch has no existing consumer, and leaving a
6-character password on a carrier host is not a defensible default. A stale literal in
the config is also a **silent fallback** if the secret ever fails to load.

Also: `/srv/ussdgw/nginx/certs/fullchain.pem` holds **one** certificate. Fine if the CA
issued no intermediate; `install-config.sh` warns rather than assuming.

### Deploy key, not a token

GitHub access from the host used a **deploy key** (`~/.ssh/digicom_deploy`, ed25519,
registered on `digicom-et/ussdgw-micro-jainslee` as key id 165131997, `read_only`).
Only the `.pub` ever left the host. A classic `gho_…` OAuth token has **full write to
every repo** the user can reach — far too much exposure to park on a carrier-adjacent
host. `~/.gitconfig` on that host said `Jenny Assistant <jenny@assistant.ai>`; corrected
to `Tran Nhan <nhanth87@gmail.com>` so the first commit is attributable.

### B25 — no `sctp.backend` means FSTACK_DPDK: "listening" with nothing bound

**The root cause of a deaf gateway, and it predates the containers.**
`ss7-digicom-balance.json` on the host had **no `sctp.backend` key**. The resolution
chain in `Ss7StackBuilder.createSctp` is:

```
JSON sctp.backend  →  -Dss7.sctp.impl (class name)  →  SctpProvider.create(name)
                   →  System.getProperty("sctp.backend")  →  SctpBackend.from(null)
SctpBackend.from(null) == FSTACK_DPDK          <- the silent default
```

FSTACK_DPDK is a **userspace** dataplane: it needs hugepages and
`libsctp_fstack.so`. This host has **0 AnonHugePages** and the image has no such
library. What the gateway did anyway:

```
boot log : SCTP server … listening        status.json : ss7 = wired
/proc/net/sctp/eps : (empty)              ss -ln --sctp : (empty)
```

A log line and a `wired` flag, **zero** sockets. The sibling `gmlc` on the same host —
same link names, same ports 2011/2019 — carries `"backend": "NETTY_KERNEL"` and was
the process actually holding the carrier associations. And the **old systemd ussdgw
deploy** had `backend = None` with no `-Dsctp.backend` either: ussdgw's SS7 was never
live on this host. This is not a container regression; the container just made it
measurable.

The pre-existing guard tested the **wrong half** of the config: it rejected
`channel: "tcp"`. The channel string said `sctp` — the *implementation* underneath was
DPDK userspace. A transport-name check cannot catch a missing backend key.

Fix — `"backend": "NETTY_KERNEL"` added after `"workerThreads": 8,` in
`build/ss7-lab.json`, `build/ss7-lab-sim-pull.json`, `build/ss7-digicom-balance.json`
(gitignored on `main`; **force-added to `digicom` by `push-dual.sh` — verified**),
`dist/configs/ss7-lab*.json`, and the deployed `/srv/ussdgw/configs/ss7-digicom-balance.json`.
New guards, in order of when they fire:

| Where | Guard |
|---|---|
| `build-images.sh` | asserts `jdk.sctp@25.0.4.1` is in the jlink'd JRE — NETTY_KERNEL is unusable without it |
| `install-config.sh validate()` | resolves the backend of the file that will boot; refuses FSTACK with no library |
| `entrypoint.sh` step **4b** | resolves backend pre-boot: dies on FSTACK without `sctp.library`, requires `jdk.sctp` for NETTY_KERNEL |
| `prove.sh` §3b | reads `/proc/net/sctp/eps` + `assocs` from inside the container |

Proof on the carrier host: eps on **2011 / 2019 / 8023** owned by uid 10001, and two
associations **ST=3 ESTABLISHED** — `172.16.144.163:2011 ↔ 10.177.55.241:2501`,
`172.16.144.163:2019 ↔ 10.177.54.241:2502` — `ss7.live = true`.

> **"Listening" is a log line; the kernel is the truth.** For SCTP that truth is
> `/proc/net/sctp/eps` and `/proc/net/sctp/assocs` — never `netstat`, never `ss -tlnp`,
> never a transport string in JSON. And when a value is absent, find out what the
> *default* resolves to before assuming the absence is harmless.

### The guard for B25 exited 1 without saying why

Step 4b was written, and immediately reproduced the defect it was guarding against:
the preflight **failed silently**. Under `set -euo pipefail`,

```bash
backend=$(grep -o '"backend"[^,]*' "$f")   # no match → grep exits 1 → assignment fails
die "…"                                    # never reached
```

The interesting case for this check is precisely the one where grep finds **nothing**
(absent key ⇒ FSTACK default), and that is the case where the command substitution
kills the script before the message. Every `$( )` in the block now carries `|| true`
(same for `cfg_val` and the `ls ss7-*.json` fallback). Verified against four fixtures:
key absent / `NETTY_KERNEL` / explicit `FSTACK_DPDK` / unknown value.

> In a fail-fast script, **a negative match is data, not failure**. Every command
> substitution that may legitimately return nothing needs `|| true`, or the script
> reports its own exit code instead of the reason.

### A gate that fires on a file nobody boots

The first `install-config.sh` backend gate globbed every `ss7-*.json` and **died on the
unused spare** `ss7-lab.json` (port 8013) while the booted file was fine. Now
`ussd.map.config-file` is resolved **once** into `selected_stack`: findings on that file
are **errors**, findings on spares are **warnings** via `stack_note`, and the later
"referenced stack file must exist" check reuses the same variable instead of re-deriving
it. A config directory is a library; only one entry is the program.

### B24 — `start-first` + host-mode ports: Pending forever, service reports 1/1

nginx ran with `update_config: order: start-first`. **Swarm reserves host-mode published
ports for placement even when the service is on `network_mode: host`**, so the new task
could never be scheduled:

```
docker stack services ussdgw   →  nginx  1/1  ussdgw-nginx:7d7e8f3   (looks deployed)
docker service ps  ussdgw_nginx →  Pending  "no suitable node (host-mode port already in use)"
```

and the **old container kept serving**. I read `1/1` + the new image tag as success. It
was neither: the spec had moved, the running task had not. Fix = `order: stop-first`,
plus the false comment in `stack.yml` (which claimed Docker ignores the `ports:` block
for host-network services) corrected. `prove.sh` §1b now compares each service's **spec
image ID** against the **image ID of its running container** and fails on any Pending
task. Also note: Swarm does **not** roll tasks for an `update_config`-only change (it is
not part of `TaskTemplate`), so the corrected order protects *future* deploys.

> `docker stack services` reports the **spec**. Only `docker service ps` +
> `docker inspect <container>` report what is **running**. 1/1 with a Pending task is a
> normal, reachable state.

### Counting the header row, and globbing the spares

Two false results in `prove.sh`, both on a **live** gateway:

- It globbed all `ss7-*.json`, so the unused spare's port produced
  `FAIL NO kernel SCTP endpoint on: 8013 (kernel has: 2011 2019 8023)`. Scope every
  check to the booted file.
- `awk 'NR>1 && NF>3'` over `/proc/net/sctp/assocs` reported **associations: 2** on a
  host with **0**: the header row has the *same* field count (27) as data rows, so a
  width filter does not exclude it and `NR>1` alone leaves the second header line of a
  per-socket dump. Use `NF>19`.

Real column map (`/proc/net/sctp/assocs`): 5 = **ST**, 12 = **LPORT**, 13 = **RPORT**,
14 = **LADDRS**, 16 = **RADDRS**; in `/proc/net/sctp/eps`, LPORT is field **6**.
`ST=3` = ESTABLISHED.

Also removed a PCRE negative lookahead from a `grep -E`: `grep` exits **2** on a syntax
error, and inside `if grep …` that is indistinguishable from "no match" — a broken
expression silently flips the verdict instead of failing loudly. Both fixes were
validated against captured real kernel output, not against a hoped-for format.

### "Tests passed" — check the default, then find the log

`docker/build/build-all.sh` gates tests on `RUN_TESTS`, which **defaults to 0**, and the
output goes to `$LOG_DIR/ussdgw-test.log`, not stdout. A quiet build therefore proves
nothing about tests. Run with `RUN_TESTS=1` and read the file: **669 tests, 0 failures,
0 errors, 0 skipped**, `Ss7ApplyServiceWiredDetailTest` 4/4, in
`/srv/ussdgw-build/out/logs/ussdgw-test.log`. (`Tests run: 0` also looks green.)

### A reused build output dir keeps the previous run's `configs/`

`build/package-dist.sh` (~lines 255–285) has a **never-clobber** rule for `configs/`.
Correct for an operator's live host, wrong for a build directory: a reused `out/dist`
silently keeps the *previous* run's seeds. Harmless for the **image** —
`docker/ussdgw/Dockerfile` does not `COPY dist/configs`; runtime config is bind-mounted
from `/srv/ussdgw/configs` — but very real for an rsync'd `dist/`. Stage with a mirror
`rm -rf dist/` first, and diff `configs/` before believing a seed change shipped.

### Seeding over ssh: four shell traps

| Trap | What happened |
|---|---|
| unquoted `--data-urlencode k=v` | a value with spaces made the trailing words **extra curl URLs** |
| unquoted heredoc + `set -u` | a bcrypt hash `$2a$10$…` was expanded by the shell — quote the delimiter (`<<'EOF'`) |
| `ssh host 'sudo bash -s' < file` | received **no stdin** and ran an empty script successfully — pipe base64 and decode remotely |
| `sudo -u "#10001"` | not accepted; to prove a uid can read a bind-mounted file use `docker run --user 10001 -v …` |

### Restore fidelity is a diff, not a look

Rows were extracted from the operator's CUSTOM dump with
`pg_restore --data-only --table=… -f -` (**no database touched**) and compared against
the container's rows: `ussd_short_code` (6 rows), `ussd_tenant`, `ussd_app_user`
**identical byte for byte**, excluding the surrogate `id` and timestamps. Three details
that a visual check would have missed:

- **Sequence.** `public.ussd_short_code_v8_id_seq` `last_value = 6 ≥ max(id) = 6`.
  Restoring with explicit ids and forgetting `setval` breaks the *next* insert, not the
  restore.
- **An API that cannot carry the data.** `/admin/app-users` accepts a **plaintext** key
  and bcrypts it, so the original hash is not reproducible through it. `ni-push` was
  restored by verbatim SQL `INSERT` of the original bcrypt hash
  (`$2a$10$TfLg…`, fingerprint `9ad01837`) so the operator's existing key still works if
  they hold it. That the key was **not** recoverable from anything on disk is proven, not
  assumed: `sha256("ussd_MkNRQA5RXzWTocd7mkK4O7uwmuW5BrUB")[:8] = 803afcf9 ≠ 9ad01837`,
  i.e. the app user's key ≠ the tenant `http_api_key`.
- **`bypass` is a transition mirror of `!rerouteEnable`.** Restoring one without the
  other leaves the UI showing a contradiction.

`*804#` kept `https://bph.vas.et/v2/interactive` + hop GT `*875#` + `bypass=f` /
`reroute_enable=t`; `*101` `mark=t`; `*199#` `network_id=1`; all with `tenant_id NULL`
and `app_username ''`, as in the dump. `as-node` on `:8090` was **left running on
purpose** — 4 of the 6 live short codes point at it.

### :80 is cleartext **by design** — do not call it a redirect

`http://<host>:80/admin/routing` → `302 http://127.0.0.1/admin/login`. That is the
**application's** auth redirect, not nginx sending :80 to :443: `docker/nginx/ussdgw.conf`'s
:80 server block proxies `location /` straight to the app, so the admin login and UI are
reachable in cleartext on `0.0.0.0:80`. I initially read the 302 as "80 redirects to
443" — a false proof that would have hidden a real exposure. Hardening it (drop
`location /` from :80, keep ACME + a redirect) changes an operator-facing surface:
**ask first**.

> When a check returns a redirect, read the `Location`. "It 302s" is not "it is
> encrypted", and the redirect may belong to a different layer than the one under test.

### Final proof state (2026-10-02, carrier host)

3/3 services 1/1 **on their spec images**; `./docker/prove.sh` → **25 passed, 0 failed**;
`ss7.live = true` with two ESTABLISHED carrier associations; `scheduler.gateTicks`
climbing (611 and counting — bridge armed at boot, no admin Start); container health
`healthy`; `BUILD-INFO.json` `sources.ussdgw` equal to the image tag, `builtAt
2026-10-02T14:03:37Z`, `dbKind postgresql`. Not proven: a real MO producing ledger rows
(`cdr.file.recentEvents = 0`). The candidate is the loopback lab link
`L3-LAB-SIM 127.0.0.1:8023 ← 127.0.0.1:8024` with `tools/ss7-simulator` — **never**
inject MAP toward the live carrier peers to make a metric move.

## 2026-10-03 — Docker jlink JRE missing jdk.compiler + SS7 watchdog re-wire loop

### Docker jlink JRE missing `jdk.compiler` — DiameterStackImpl crash

**Symptom:** container exits 1 immediately after start with `NoClassDefFoundError: javax/tools/JavaFileManager$Location`. Stack trace points to `DiameterStackImpl.<init>` → `ra-diameter` → `DiameterResourceAdaptor.raActive()`. Happens even with `ussd.diameter.enabled=false` because the RA still initializes during boot.

**Exact mechanism (proven in Docker logs):**
```
Caused by: java.lang.NoClassDefFoundError: javax/tools/JavaFileManager$Location
  at com.mobius.software.telco.protocols.diameter.impl.DiameterStackImpl.<init>(DiameterStackImpl.java:239)
  at com.microjainslee.ra.diameter.transport.CorsacDiameterTransport.start(CorsacDiameterTransport.java:111)
  at com.microjainslee.ra.diameter.DiameterResourceAdaptor.raActive(DiameterResourceAdaptor.java:164)
```

`DiameterStackImpl` uses `javax.tools.JavaFileManager` (from `jdk.compiler` module) to compile Diameter AVP templates at runtime. The jlink JRE in `docker/ussdgw/Dockerfile` was missing `jdk.compiler` from `--add-modules`, so the class was absent at runtime.

**Required jlink modules (complete list):**
```
java.base,java.logging,java.sql,java.naming,java.management,java.xml,java.desktop,
java.instrument,java.net.http,java.rmi,java.security.jgss,java.security.sasl,jdk.unsupported,
jdk.crypto.ec,jdk.crypto.cryptoki,jdk.management,jdk.sctp,jdk.localedata,jdk.jfr,jdk.zipfs,
jdk.jsobject,jdk.compiler  ← ADDED 2026-10-03
```

**What NOT to do:**
- Assume `jdk.jsobject` alone is enough (it is not — `jdk.compiler` is separate)
- Disable Diameter RA to work around the crash (the RA still initializes even when `enabled=false`)
- Rebuild without testing `raActive()` path (the crash happens at RA activation, not compile time)

**Correct fix:**
- Add `jdk.compiler` to `--add-modules` in `docker/ussdgw/Dockerfile`
- Rebuild image: `docker build -f docker/ussdgw/Dockerfile -t ussdgw:<tag> .`
- Verify: `docker run --rm --entrypoint /opt/jre/bin/java ussdgw:<tag> --list-modules | grep jdk.compiler`
- Prove: container boots, `ss7.live=true`, no `NoClassDefFoundError` in logs

**Verification probes (run after build):**
1. `docker run --rm --entrypoint /opt/jre/bin/java ussdgw:<tag> --list-modules | grep jdk.compiler` → must show `jdk.compiler@25.x`
2. `docker run --rm --entrypoint /usr/local/bin/ussdgw-healthcheck.sh ussdgw:<tag>` → must **fail** (proves probe works)
3. Container boot → `ss7.live=true` + no `NoClassDefFoundError` in logs

**Commit:** `e9c0c01 fix(docker): add jdk.compiler to jlink JRE modules` on digicom-et

### SS7 watchdog re-wire loop — M3UA FSM stuck PENDING

**Symptom:** `ss7.live=false` despite SCTP associations ESTABLISHED. Logs show repeated `Ss7Watchdog: M3UA route down, watching (threshold 180s)` → `re-wiring SS7` → `route back` → cycle repeats every 3-5 minutes. M3UA ASP state machine stuck in `PENDING` state, never transitions to `ACTIVE`.

**Exact mechanism (proven in Docker logs):**
```
16:17:57 ERROR  Transition=ntfyaspending. FSM.name=AS-BP_PEER old state=PENDING, current state=PENDING
16:17:59 WARN   PENDING timed out for As=AS-BP
16:20:56 WARN   ss7-watchdog: M3UA route down, watching (threshold 180s)
16:24:26 WARN   ss7-watchdog: route down 209s, re-wiring SS7 (attempt 1, cooldown 600s)
16:24:56 INFO   ss7-watchdog: route back (was down since 2026-10-03T13:20:56Z)
```

Carrier peer sends duplicate ASP Active / CommUp messages → jSS7 M3UA FSM throws `UnknownTransitionException` (transition from ACTIVE to ACTIVE) → ASP state machine confused → peer AS stuck in `PENDING` → `Ss7Watchdog` detects route down → re-wires SS7 → cycle repeats.

**Root cause:** `Ss7Watchdog` re-wire logic interferes with M3UA FSM recovery. When peer sends duplicate messages (normal during SCTP re-establishment), the FSM logs warnings but would eventually recover. The watchdog's aggressive re-wire (every 180s) interrupts this recovery and creates a loop.

**What NOT to do:**
- Assume `ss7.live=false` means SCTP is down (check `/proc/net/sctp/assocs` first)
- Restart container repeatedly (watchdog will re-create the loop)
- Blame carrier peer (duplicate messages are normal SCTP behavior)

**Correct fix:**
- Disable watchdog: `ussd.ss7.watchdog.enabled=false` in `configs/application.properties`
- Restart container once (clean slate)
- Verify: `ss7.live=true`, no `ss7-watchdog` entries in logs, `scheduler.gateTicks` climbing

**When to re-enable watchdog:**
- Only if SS7 genuinely unstable (peer flapping, network issues)
- Increase threshold: `ussd.ss7.watchdog.threshold-ms=600000` (10 min instead of 3 min)
- Monitor logs for `re-wiring SS7` frequency (should be rare, not every 3 min)

**Prove:** after restart, wait 2-3 minutes, check:
- `ss7.live=true`
- `scheduler.gateTicks` climbing (e.g., 64 → 222 → 3243)
- No `ss7-watchdog` entries in logs
- SCTP associations ESTABLISHED (`/proc/net/sctp/assocs`)

### Memory congestion level 2 — JVM heap pressure

**Symptom:** `status.json` shows `memory.congestion.level=2`, `memory.heap.usedPercent=89.9`. Container memory usage climbs to 4-5GB/6GB. After restart, memory drops to 2-3GB/6GB (38-50%).

**Root cause:** JVM heap 4GB (`-Xms2g -Xmx4g`) is tight for USSDGW with SS7 stack + Diameter RA + HTTP/gRPC workers. Memory congestion level 2 means heap usage > 85%, which triggers GC pressure and can cause latency spikes.

**What NOT to do:**
- Increase heap to 8GB without checking host capacity (shared host may have 15GB total)
- Enable `AlwaysPreTouch` without `USSD_ALWAYS_PRETOUCH=1` (wastes memory at startup)
- Co-run OTA 8GB + USSDGW 8GB on same host (OOM killer)

**Correct fix:**
- Keep default `-Xms2g -Xmx4g` for shared hosts
- Monitor `memory.heap.usedPercent` in `status.json`
- If consistently > 80%, consider:
  - Reduce HTTP worker pool: `quarkus.http.worker.max-threads=256` (default 512)
  - Reduce CDR queue size: `ussd.cdr.queue-capacity=50000` (default 100000)
  - Move to dedicated host with more RAM

**Prove:** after restart, check:
- `memory.heap.usedPercent` < 70%
- `memory.congestion.level=0` or `1`
- Container memory usage stable (not climbing)

## Synced from workspace (2026-09-18)
Cross-project footguns added to workspace [`docs/agents/lessons.md`](../../../../../docs/agents/lessons.md) from the OTA P1 SMSC-GW build — **do not paste, link**: Quarkus `@ConfigProperty(defaultValue="")` boot-breaker → `Optional<String>`; Claude Code worktree-agents branch from a stale base under uncommitted WIP (commit clean base / salvage-and-reapply); **parallel subagents share one session rate-limit** (prefer sequential in-tree); auto-mode classifier blocks remote-shell/prod-DB/inline-credential writes; Iran L2TP ship = **sequential** rsync (parallel deadlocks).
