# AGENTS.md — RestLink USSD GW (ussdgw-jainslee)

**JDK: Java 25 only** (`maven.compiler.release=25`, mise **zulu-25**). Never Java 8/11/17/21.

Thin index for agents. Durable detail → [`docs/agents/`](docs/agents/) — start at [`docs/agents/README.md`](docs/agents/README.md). **Before admin/UI/packaging edits:** [`lessons.md`](docs/agents/lessons.md) + [`skills.md`](docs/agents/skills.md).

Greenfield RestLink **USSD** gateway (3GPP **pull/MO** + **push/NI**) that **replaces** classic WildFly [`ussdgateway`](../../../ussdgateway) / [`nhanth87/ussdgw`](https://github.com/nhanth87/ussdgw) `core/`. Wire contracts = **new** JSON/proto. **Not** SIM OTA / CAP / fleet / `/sendota`.

### Migration law (non-negotiable)

| Layer | Oracle / source | Never |
|-------|-----------------|-------|
| **Behavior** | Classic `ussdgw/core` (Parent/Child/Http/Grpc/Sip/Sri + session-bridge) + 3GPP USSD | Conflate with ota-sim-push product semantics |
| **Admin UX shell** | OTA [`app/html/admin/`](../../ota-service/ota-sim-push/app/html/admin/) + `AdminPageRenderer` **layout only** | Fleet / CAP / portal OTA campaigns / Ki |
| **AS wire** | [`docs/as-contract/`](docs/as-contract/) greenfield | Drop-in XmlMAPDialog |
| **Access planes** | MAP + Diameter + SIP/USSI + SMPP live (stubs only when peer down) | Leave Diameter/SIP as permanent STUB_QUEUED |

## Topic index

| Topic | Doc |
|-------|-----|
| Index | [`docs/agents/README.md`](docs/agents/README.md) |
| Agent compress | [`skills.md`](docs/agents/skills.md) |
| Lessons / footguns | [`lessons.md`](docs/agents/lessons.md) |
| Log4j2 ONLY | [`logging.md`](docs/agents/logging.md) |
| Admin UX (OTA shell → USSD) | [`skills.md`](docs/agents/skills.md) § Admin · `app/html/admin/` |
| Fast-jar dist (OTA peer) | OTA [`packaging.md`](../../ota-service/ota-sim-push/docs/agents/packaging.md) · [`skills.md` § Dist](docs/agents/skills.md) |
| Schema H2 / PostgreSQL | [`schema.md`](docs/agents/schema.md) |
| SS7 lab + HLR face | [`ss7-lab-pair.md`](docs/agents/ss7-lab-pair.md) |
| Parity vs classic | [`docs/parity-matrix.md`](docs/parity-matrix.md) |
| AS contract | [`docs/as-contract/`](docs/as-contract/) |

## Do-not-miss checklist

- **Java 25 only** — mise `zulu-25`; never downgrade. False alarms: `bcprov-jdk18on` ≠ Java 8; APT `RELEASE_8` is upstream metadata. → OTA [packaging](../../ota-service/ota-sim-push/docs/agents/packaging.md)
- **Ship only `dist/`** — after `./build/package-dist.sh`, copy **`dist/` alone** → `dist/run.sh`. Do **not** scp the worktree, `build/`, `src/`, or repo-root `app/`. UI = `app/html/` files — **never WAR / uber-jar**. → [skills § Dist](docs/agents/skills.md)
- **Git ≠ runnable dist** — clone/worktree may show only `dist/app/` + `dist/configs/` (+ `run.sh` scaffold). **`lib/` · `*.jar` · `quarkus/` are gitignored** (OTA parity). That is **not** a complete ship tree. Always `./build/package-dist.sh` before copy-and-run; `run.sh` must refuse incomplete layout. Never commit jars into `dist/`. → [skills § Dist](docs/agents/skills.md) · [lessons](docs/agents/lessons.md)
- **Dist layout** — `quarkus-run.jar` + **`ussdgw-app.jar` at APP_HOME root** + `lib/{boot,main}/` + `quarkus/` + `app/html/` (UI only) + `configs/` + `data/` + `logs/`. Never `java -jar ussdgw-app.jar` alone. Never jars under `app/`. Rewrite `quarkus-application.dat` when moving the app jar.
- **Prove the artifact, not the source** — before debugging runtime: artifact mtime vs source, `jar tf ussdgw-app.jar | grep <NewClass>`, classpath of the *running* PID. Green `mvn test` ≠ deployed. Never trust `mvn -q test` alone (`Tests run: 0` looks green). → [lessons](docs/agents/lessons.md)
- **Log4j2 ONLY** — `log4j-core` + `log4j2.xml` → `dist/logs/` (`ussd.log.dir`); never `/tmp`; never dual `SleeEventTrace`+`LOG.info`; never `log4j2-jboss-logmanager` / `quarkus.log.file*`. → [logging](docs/agents/logging.md)
- **Link status truth** — see section below; `ss7.live` / `smpp.live` via `LinkStatusService` only (SCTP+M3UA ACTIVE / bound ESME). Never LISTEN-alone, Apply-once, or UI badge fixes.
- **SCTP** — verify with `ss` / `/proc/net/sctp/eps`, **not** `netstat`. → [ss7-lab-pair](docs/agents/ss7-lab-pair.md)
- **Sim persist XML** — never leave corrupt `*sccp*.xml` (`<1>` keys). Validate Jackson parse; quarantine + replace seed; smoke Start. → [ss7-lab-pair](docs/agents/ss7-lab-pair.md) · jSS7 AGENTS
- **SBB handlers** — catch **`Throwable`** in every `onEvent`; end/cancel MAP dialogs; `IN SBB=` count must match `OUT SBB=`.
- **HTTP/gRPC** — RA **callbacks** only — never 50ms timer poll. Pull body = `JsonPostRequest` raw JSON — **not** `CallbackRequest` envelope.
- **HLR face** — inbound SRI-SM: `ussd.hlr.mode` default **PROXY_MAP fail-closed**; no silent FAKE; upper GT must not loop to local. → [ss7-lab-pair](docs/agents/ss7-lab-pair.md)
- **TENANT login** — **username === tenantId**. RestLink = dist brand only.
- **Flyway / DB** — lab **file H2** (`./data/ussdgw`, PG-mode) or **PostgreSQL** via `configs` / `QUARKUS_DATASOURCE_*`. Single `V1__ussdgw_baseline.sql`; wipe lab H2 / reset `flyway_schema_history` after squash. Boot guard `UssdSchemaInitializer`. Never `h2:mem` for ship. → [schema](docs/agents/schema.md)
- **Commits** — **nhanth87 / Tran Nhan** only. No AI `Co-authored-by:` / trailers. Hooks reject; never `--no-verify`.

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
- Treat repo-root `app/` / `build/` as runtime — runtime is **`dist/`** only.
- Assume git `dist/app`+`configs` alone is copy-and-run — **missing `lib/`** until `./build/package-dist.sh`.
- Use `CallbackRequest` for AS **pull**; poll HTTP/gRPC with timers.
- Silent FAKE HLR when mode is PROXY_*; point `upper-gt` at self.
- Port OTA fleet/CAP/`/sendota` into this USSD GW.
- Leave raw `{{TOKEN}}` in browser HTML — always seed vars (OTA admin lesson).
- Bloat this file — put detail in `docs/agents/*`.

## Dist / run

**In git:** scaffold only (`app/html/`, `configs/`, `run.sh`, README). **Not in git:** `lib/`, `quarkus/`, `*.jar`, `data/`, `logs/` (see `.gitignore`).

| Command | Does |
|---------|------|
| `./build/package-dist.sh` | Maven fast-jar → **self-contained `./dist/`** with `lib/{boot,main}/` (JDK 25) — **required** before ship |
| `./run.sh` / `dist/run.sh` | Start packaged app (errors if jars/`lib` missing) |
| **Server** | Copy **complete `dist/`** (after package) → `./run.sh` (host JDK 25) |
| Bare bootstrap | [`dist-package-script.sh`](dist-package-script.sh) (sctp → jss7 → jain-slee → package) |

Lab AS sims: `tools/as-http-sim.py`, `tools/as-grpc-json-sim.py`.

## Scope (short)

- Access PULL/PUSH: MAP + Diameter + SIP/USSI live when peer ready (stub only when down); SMPP lab MO + optional `submit_sm` NI.
- AS modes: **SYNC** / **ASYNC_ACK** / **BRIDGE** (adaptive EWMA gate).
- Admin HTMX + Monitor Hub (`/admin/ss7|smpp|http` → hub tabs); HTTP Sync/Async/Callback panels.
- HLR face: `FAKE|PROXY_MAP|PROXY_DIAMETER|FAKE_THEN_RESOLVE` (default PROXY_MAP fail-closed).
- Saga: `ProfileFacility` table `ussdTx`; campaigns; TenantGuard; CDR async flusher.
- Alphabets: **AS-driven** (`ucs7`|`ucs8`|`unicode`|`auto`) → MAP CBS DCS.

Detail tables (admin paths, AS modes, tenant, saga): keep in chat only if needed — prefer [`skills.md`](docs/agents/skills.md) and source under `src/main/java/et/restlink/ussdgw/`.
