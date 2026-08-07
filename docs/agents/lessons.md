# Lessons learned — do not repeat (ussdgw-jainslee)

Short memory for Digicom footguns. Prefer this + OTA peer [`lessons.md`](../../../../ota-service/ota-sim-push/docs/agents/lessons.md) over rediscovering the same mistakes.

## Do not

| Mistake | Rule | Detail |
|---------|------|--------|
| Stuffing long walls into **`AGENTS.md`** | Keep root thin — link `docs/agents/*`. | [README.md](README.md) |
| Using **Java 8/11/17/21** or fixing compile by lowering release | **Java 25 only** (mise `zulu-25`). | OTA [packaging.md](../../../../ota-service/ota-sim-push/docs/agents/packaging.md) |
| Seeing **`bcprov-jdk18on`** / APT **`RELEASE_8`** → switch to JDK 8 | Product-line name / upstream metadata — keep release=25. | OTA packaging |
| Shipping an **uber-jar** or `java -jar ussdgw-app.jar` alone | Fast-jar: `quarkus-run.jar` + root `ussdgw-app.jar` + `lib/`. Start via `./run.sh`. | [skills.md](skills.md) |
| Putting **jars under `app/`** | `app/html/` = UI only. Package script must fail if jars remain. | [skills.md](skills.md) |
| Seeing **`dist/` only `app/` + `configs/`** after clone → “thiếu lib / package hỏng” | **Gitignored by design** (`dist/lib/`, `*.jar`, `quarkus/`). Run **`./build/package-dist.sh`** then ship — OTA same. Never commit jars. | [skills.md](skills.md) § Dist · root AGENTS |
| Copying scaffold `dist/` to server **without** package | Incomplete → `run.sh` must error. Package first; verify `lib/main` + both jars. | [skills.md](skills.md) |
| Deploying the **whole worktree** | Ship **`dist/` only** (complete tree after package). Digicom lab path often `~/ota-push-services/ussdgw-micro-jainslee/`. | root [AGENTS.md](../../AGENTS.md) |
| Co-running **OTA 8G + ussdgw 8G** / **`AlwaysPreTouch`** on a ~**15 GiB** host | **Live Digicom (`digicom-nb`)**: ussdgw runs **`-Xms2g -Xmx4g`** (not 8g). `run-dist.sh` Digicom-safe defaults match that; **`AlwaysPreTouch` only if** `USSD_ALWAYS_PRETOUCH=1`. Bigger hosts: `USSD_XMS=8g USSD_XMX=8g`. OOM history: 8g+PreTouch + ss7sim/as-node on ~15 GiB. 10k capacity knobs (`sbb-pool-max=40960`, …) stay applied — heap size ≠ pool size. | OTA [packaging.md](../../../../ota-service/ota-sim-push/docs/agents/packaging.md) · `build/run-dist.sh` |
| SS7 **`localSecondary()` NPE** / missing **`ss7-lab.json`** | Props path must pass empty list (not null). Digicom: `ussd.map.config-file=configs/ss7-lab.json` with `"localSecondary": []` and SCTP **server** 8013↔8014. Missing file → props fallback → NPE → no SCTP listen. | [ss7-lab-pair.md](ss7-lab-pair.md) · `Ss7ApplyService` |
| Debugging against a **stale** dist / old PID | After package: one `quarkus-run.jar` PID; `jar tf` the class; wait for bootstrap. | OTA lessons |
| Trusting **`mvn -q test`** exit 0 alone | Read `Tests run:` — zero tests can look green. | OTA lessons |
| Landing a test **never seen red** | Temporarily break the fix, confirm fail, restore. | OTA lessons |
| **`log4j2-jboss-logmanager`** / **`quarkus.log.file*`** / logs in `/tmp` | Log4j2 ONLY → `ussd.log.dir` / `dist/logs/`. | [logging.md](logging.md) |
| Dual **`SleeEventTrace` + `LOG.info`** on the same SBB boundary | Trace only for SLEE ingress/egress. | [logging.md](logging.md) |
| Fixing peer-down with **UI badge** only | `LinkStatusService` / `ss7.live` = SCTP+M3UA ACTIVE. | root AGENTS § Link status |
| Treating **LISTEN / Apply / isActive()** as live | Same as OTA. | root AGENTS |
| Verifying SCTP with **netstat** (or “empty netstat ⇒ down”) | Use `ss -ln --sctp` + `/proc/net/sctp/{eps,assocs}`. Empty netstat is **not** proof SCTP is down. Empty `ss` with `map.enabled=false` = SS7 **skipped** (no listen **8013**), not a broken stack. | [ss7-lab-pair.md](ss7-lab-pair.md) |
| Corrupt jSS7 **`*sccp*.xml`** (`<1>` keys) | Validate parse; quarantine + seed; smoke Start. | [ss7-lab-pair.md](ss7-lab-pair.md) |
| jSS7 sim as **`SMS_TEST_CLIENT`** blocking HLR SSN 6 | Prefer **SMS_TEST_SERVER**; allow SSN **6**. | [ss7-lab-pair.md](ss7-lab-pair.md) |
| SBB catching only **`RuntimeException`** | Catch **`Throwable`**; always emit OUT trace. | root AGENTS |
| AS pull via **`CallbackRequest`** envelope | Pull = raw body (XML or JSON). | [skills.md](skills.md) |
| Late AS callback missing / wrong session key | Pull metadata (`AsPullMetadata`): `correlationId` (real push-back key), `sessionId`, `virtualBridgeId`, `adaptiveTimeoutMs`, `asMode`. Callback resolve order = **`correlationId` → `virtualBridgeId` → `sessionId`** (`AsResponse.resolvePushBackId`). **gRPC shares the same JSON field names** on pull/callback bytes. | [classic-xml.md](../as-contract/classic-xml.md) · [grpc-json.md](../as-contract/grpc-json.md) · `tools/as-node/` |
| Booting a **nested `dist/dist`** (or second java with **8g**) on Digicom | Ship/rsync to APP_HOME root only; one `quarkus-run.jar`; heap **`USSD_XMS=2g USSD_XMX=4g`**. Nested leftover + 8g peer steals :8088/:8013 and SIGTERM-races the safe instance. | [skills.md](skills.md) § Dist · heap row above |
| Assuming **JSON-only** HTTP AS / full classic **XmlMAPDialog** E2E | Dual-mode: classic **XML default** + JSON; per-tenant `httpAsWireFormat` / `http_as_wire_format`. Codec boundary only (`AsWireFacade` / `ClassicDialogXmlCodec`) — **not** a full XmlMAPDialog stack. | [classic-xml.md](../as-contract/classic-xml.md) |
| `JsonPostRequest` **3-arg** for XML PULL | ra-http-client 3-arg hardcodes **JSON** `Content-Type`. XML (and correct JSON) PULL needs the **4-arg** ctor with explicit `contentType` (`HttpClientSbb.submitPost`). | code · micro-jainslee ra-http-client |
| **`Thread.sleep`** (or blocking wait) on classic **NI sync** parked HTTP | Path `ussd.http.ni-path` (default **`/ussd`**) + **`JSESSIONID`**. Park via **`ClassicNiHttpPark`** + **`AdaptiveTimeout`**; MS continue → **`completeParked`**; MAP corr = **dialogId**. Never sleep on SBB. | [classic-xml.md](../as-contract/classic-xml.md) · root AGENTS |
| HTTP/gRPC response via **50ms timer poll** | RA callbacks only. | root AGENTS |
| **Silent FAKE** HLR under PROXY_* | Default PROXY_MAP fail-closed; FAKE only when ops set. | [ss7-lab-pair.md](ss7-lab-pair.md) |
| `ussd.hlr.upper-gt` **== local ussdGt** | Loop guard aborts — set a real upper HLR GT. | [ss7-lab-pair.md](ss7-lab-pair.md) |
| Generation bump on **AS CONTINUE** | Bump **only** on MS input (`onUserContinue`). | AGENTS saga notes / code |
| TENANT login username ≠ **tenantId** | Enforced in `AdminUserService`. | root AGENTS |
| Admin **401** / empty form login after **PG migrate** | API key lab default **`ussd-admin`** via `?key=` or `X-USSD-Admin-Key`. Form login: seeded **`admin` / `ussd-admin`** (`UssdFirstRunSeeder` / `ussd_admin_user`) — OK on Digicom after seed. Empty table after PG cutover ⇒ 401 until reseed. Digicom nginx often **:80 → :8088**. | [skills.md](skills.md) · dist README |
| Pointing JDBC at OTA DB **`ota`** / flipping **`db-kind` at runtime** | Dedicated DB **`ussdgw`** — never share OTA’s **`ota`**. `quarkus.datasource.db-kind` is **build-time** (H2→PG needs **rebuild** / repackage). Git/lab default stays **file H2**; Digicom PG only in **server** `dist/configs`. | [schema.md](schema.md) |
| Reinventing HTTP AS sim / wrong pull URL | Lab Node AS: **`tools/as-node/`** (Fastify) — `pull:fast` / `pull:bridge` (`DELAY_MS=8000`) / `push:ni`. Seed AS `http://127.0.0.1:8090/ussd/pull`. | [`tools/as-node/README.md`](../../tools/as-node/README.md) |
| Assuming **10k TPS** knobs are still default (**4096**) | **Target** for 10k lab/prod is pool ×10: `microjainslee.container.sbb-pool-max=40960` (not “landed at 4096”), plus `buffer-size=16384`, `sbb-pool-min=128` in **`build/application.properties`**. These are **BUILD_TIME** — re-package; verify **`dist/configs`** (may still show old default `4096` until `./build/package-dist.sh`). | `build/application.properties` |
| Cursor / agent injects **`Co-authored-by: Cursor`** (or other AI trailers) | Authorship **nhanth87 / Tran Nhan** only. Use a clean message (`commit-tree` if needed); hooks ban AI trailers — never `--no-verify`. Push remotes: **nhanth87** + **digicom-et**. | workspace AGENTS.md |
| Treating this repo as **SIM OTA** / copying fleet/CAP/`/sendota` | Product is **3GPP USSD** pull/push; OTA admin is **shell UX only**. | root AGENTS migration law |
| Leaving raw **`{{TOKEN}}`** in admin HTML | Seed vars in `AdminPageRenderer` / nav helpers; strip leftovers. | OTA admin-ui lesson |
| Permanent **STUB_QUEUED** Diameter/SIP | Live when `ra-diameter` / `ra-sip-servlet` peer ready. | parity-matrix |
| Cursor JDT **autobuild** deleting `target/classes` mid-testCompile | Prefer `java.autobuild.enabled=false`; one Maven at a time. | OTA lessons |
| Squashing Flyway **without** wiping history | Greenfield: wipe H2 / reset `flyway_schema_history`. | [schema.md](schema.md) |
| Shipping **`jdbc:h2:mem:`** as lab/prod | File H2 under `dist/data/` or PostgreSQL. Both drivers in fast-jar. | [schema.md](schema.md) |

## Remember

- Peer OTA footguns (dist, link truth, Log4j2, prove artifact) apply **1:1** unless a USSD row above overrides.
- `IN SBB=` / `OUT SBB=` unequal ⇒ handler died without OUT.
- HLR face + NI push share one stack: SSN **6** HLR face vs SSN **8** USSD — document GT split in lab.
- After `package-dist.sh`, confirm `find dist/app -name '*.jar'` is empty and `ussdgw-app.jar` is at dist root.
- Lab AS: prefer **`tools/as-node/`** over ad-hoc curls for XML PULL / bridge / classic NI; Python `tools/as-http-sim.py` remains available.
- Dated lab notes **2026-08-07**: dual-mode AS wire, 4-arg `JsonPostRequest`, `ClassicNiHttpPark`, Digicom heap check (**ussdgw `-Xms2g -Xmx4g`**, PreTouch opt-in; 10k pool knobs still on; admin `admin`/`ussd-admin`), PG `ussdgw` vs `ota`, SCTP via `ss`, Node AS presets.
