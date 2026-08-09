# Agent skills — ussdgw-jainslee compress

What to load before packaging, admin UI, or AS plane work. Prefer these over re-deriving from chat.

## Before you edit

1. Read root [`AGENTS.md`](../../AGENTS.md) (Java 25, MAP/HTTP/gRPC, **dist layout**, link truth).
2. Footguns → [`lessons.md`](lessons.md) (OTA checklist adapted).
3. Packaging / deploy → this file § Dist + OTA peer [packaging.md](../../../../ota-service/ota-sim-push/docs/agents/packaging.md).
4. Admin HTMX / planes → [`AGENTS.md`](../../AGENTS.md) + this file.

| If you touch… | Read first |
|---------------|------------|
| Dist / `package-dist` / Digicom redeploy / `run.sh` | **this file § Dist** (+ Digicom compile + redeploy) · [AGENTS.md](../../AGENTS.md) · [lessons.md](lessons.md) |
| H2 / PostgreSQL / Flyway | [schema.md](schema.md) |
| Logging | [logging.md](logging.md) |
| `app/html/*` admin shell | [AGENTS.md](../../AGENTS.md) — UI files only under `app/html/` |
| HTTP Sync/Async/Callback / Monitor Hub HTTP | [AGENTS.md](../../AGENTS.md) + `AdminHttpAsModeHandler` + [classic-xml.md](../as-contract/classic-xml.md) |
| Lab MO / SMPP NI | [AGENTS.md](../../AGENTS.md) Access |
| HLR face / SRI-SM inbound | [ss7-lab-pair.md](ss7-lab-pair.md) · `HlrFaceService` / `HlrResponderSbb` |
| Parity vs classic | [../parity-matrix.md](../parity-matrix.md) |

## Dist — Quarkus fast-jar (non-negotiable)

Same Digicom-ET / OTA push standard. **Never** ship a single uber-jar.

### Footgun — “dist chỉ có app + configs”

| In **git** (scaffold) | **Not** in git (`.gitignore`) — must build |
|-----------------------|--------------------------------------------|
| `dist/app/html/`, `dist/configs/`, `dist/run.sh`, README | `dist/lib/`, `dist/*.jar`, `dist/quarkus/`, `dist/data/`, `dist/logs/` |

Fresh clone / new worktree **looks empty of libs** — that is intentional. **Copy-and-run** needs the full fast-jar tree (same idea as OTA `ota-sim-push/dist/` with `lib/`). Agents must **not** treat scaffold-only `dist/` as shippable or “package broken”.

```bash
./build/package-dist.sh   # REQUIRED → fills lib/ + jars + quarkus/
./dist/run.sh             # start (JDK 25); refuses incomplete layout
```

Prove before scp: `test -d dist/lib/main && test -f dist/quarkus-run.jar && test -f dist/ussdgw-app.jar` and `find dist/app -name '*.jar'` empty.

| Path | Role |
|------|------|
| `quarkus-run.jar` | Thin launcher — **only** start via `./run.sh` / `java -jar quarkus-run.jar` |
| `ussdgw-app.jar` | Application classes at APP_HOME **root** (moved out of Quarkus `app/`) |
| `lib/boot/` · `lib/main/` | Dependencies — replaceable jars (not fat-jar); **gitignored** |
| `quarkus/` | Generated model; `quarkus-application.dat` must reference **root** `ussdgw-app.jar` |
| `app/html/` | Admin UI (`*.html` …) — **never jars under `app/`** |
| `configs/` · `data/` · `logs/` | Config + runtime (`data/`/`logs/` gitignored) |

### Hard rules

1. **No uber-jar.** `quarkus.package.jar.type=fast-jar` only. Refuse `java -jar ussdgw-app.jar` alone.
2. **`app/` = UI only.** After package, `find dist/app -name '*.jar'` must be empty. Fail the package script if not.
3. **App jar at APP_HOME root.** Quarkus builds `target/quarkus-app/app/ussdgw-app.jar` → `package-dist.sh` copies to `dist/ussdgw-app.jar` and rewrites `quarkus/quarkus-application.dat` (`app/ussdgw-app.jar` → `ussdgw-app.jar`).
4. **Ship `dist/` only** — but only **after** `package-dist.sh`. Ops get `lib/` + html + configs + run.sh — not a mystery single jar. **Never commit** built jars/`lib/` into git.
5. **JDK 25** (bytecode major **69**) for `ussdgw-app.jar`.
6. **Incomplete dist = stop.** Missing `lib/main` or root jars → fix by re-running package, not by inventing uber-jar or copying `target/` by hand.
7. **Digicom ship:** build-time `quarkus.datasource.db-kind=postgresql` for `./build/package-dist.sh`, then **restore local `h2`** in `build/application.properties`. Shadow **`ProfileAccessorInvoker`** into `jainslee-api` (script must do this). Rsync **never** touches Digicom `configs/`.

Peer: OTA [`docs/agents/packaging.md`](../../../../ota-service/ota-sim-push/docs/agents/packaging.md) · micro-jainslee [AGENTS § DIST](../../../../jain-slee/jain-slee/AGENTS.md).

### Digicom compile + redeploy (durable — 2026-08-09)

Host shorthand: **`digicom-nb`**, APP_HOME **`/home/app/ota-push-services/ussdgw-micro-jainslee/`**. Do **not** invent Digicom secrets; host `configs/` is operator SoT.

**HARD LAW — prove Digicom artifact, not laptop tests (SIẾT):** Green `mvn test` / new unit tests / source greps **never** mean Digicom runs the fix. **Forbidden** to say “fixed” / “redeployed OK” / “done” until after **package → rsync jars/`lib`/`quarkus`/`app/html` → restart → `:8088` status.json 200** you show on Digicom: (1) jar+html **mtime** or `jar tf … | grep <NewClass>`, (2) running PID classpath, (3) live hit on the **broken** `/admin/...` (not status.json alone). Writing tests without this host prove = **incomplete / agent failure**. → root [AGENTS.md](../../AGENTS.md) § Prove the artifact · [lessons.md](lessons.md)

**Agent Shell:** always run `./build/package-dist.sh`, `mvn package|test`, and `./build/prove-as-wire-lab.sh` with **`required_permissions: ["all"]`** on the **first** call (worktree `.m2-agent-repo` / Maven local repo + deps) — never sandbox-fail then retry. → [lessons.md](lessons.md)

#### AS wire / AdaptiveTimeout ship gate (A∧B before rsync; C Digicom sim)

| Before rsync | After restart on Digicom |
|--------------|--------------------------|
| **A∧B must be green:** `./build/prove-as-wire-lab.sh` (`Map2MapAsWireContractExamplesTest` + `AsPullBeginContinueEndAndGateTest`) + `package-dist` | **C7 preflight** then **C1–C6** on **ss7-simulator `networkId=1`** + lab short-codes only |

- Live Brook / **`networkId=0`** stays up for **manual** prove — **not** part of automated C1–C6 (never dial live `*804` in that checklist).
- C fail ⇒ **not shipped**; rollback jars/`lib`/`quarkus` only — **never** Digicom `configs/`.
- Contract + checklist: [`map2map-as-xml.md` § Prove / ship gate](../as-contract/map2map-as-xml.md) · §4d N-step multimenu (hop-once, digit continue, CDR `asUssd`) · script [`build/prove-as-wire-lab.sh`](../../build/prove-as-wire-lab.sh) · as-node `MENU_PICK=multimenu` (`abc`→`2-dce`→`(xyz)`).

#### Digicom OS/SCTP buffers (5k headroom — not a measured 5k claim)

Stock Digicom had `net.core.rmem_max=wmem_max=212992` → SCTP `rcvbuf`/`sndbuf` 212992 → pcap **a_rwnd ≈ 106496** (~104 KiB). Raise via sysctl drop-in (no SS7 topology overwrite; `Ss7Config.Link` does not yet wire `optionSoRcvbuf`):

| Key | Value (bytes) | Why |
|-----|---------------|-----|
| `net.core.rmem_max` / `wmem_max` | **67108864** (64 MiB) | Caps `SO_*`; unlocks larger SCTP windows (OTA-lab class; shared host — not 1 GiB dedicated) |
| `net.core.rmem_default` / `wmem_default` | **4194304** (4 MiB) | Default for new sockets without explicit `SO_RCVBUF` |
| `net.sctp.sctp_rmem` / `sctp_wmem` | **4096 4194304 67108864** | min / default / max for SCTP; default 4 MiB ⇒ a_rwnd ≈ 2 MiB after assoc restart |

SoT: [`build/systemd/99-ussdgw-sctp-buffers.conf`](../../build/systemd/99-ussdgw-sctp-buffers.conf). Apply: `sudo ./build/systemd/install-sctp-buffers.sh` (or Digicom `install-on-digicom.sh`, which also installs units) then **`sudo systemctl restart ussdgw.service`** so assocs recreate. Prove once: `sysctl net.core.rmem_max net.sctp.sctp_rmem` and header+rows for ports 2011/2019 in `/proc/net/sctp/assocs` (`rcvbuf`/`sndbuf` columns). **Buffers ≠ 5k measured.**


| Step | Do | Prove / never |
|------|----|---------------|
| 1. JDK | mise **`zulu-25`** only — never downgrade for compile | `java -version` → 25 |
| 2. Package for Digicom | Set **`quarkus.datasource.db-kind=postgresql`** → **`USSD_REQUIRE_PG_BAKE=1 ./build/package-dist.sh`** → confirm **`dist/.baked-db-kind` = `postgresql`** → **restore `h2`** for local/dev | H2 bake → Digicom **crash-loop** (`/tmp/ussdgw.service.log`: build-time fixed to `h2` + Driver does not support `jdbc:postgresql://…`). Killer = **`ussdgw-app.jar` + `quarkus/` + `lib/`** together |
| 3. Dist layout | `ussdgw-app.jar` + `quarkus-run.jar` at APP_HOME **root**; `lib/{boot,main}/` + `quarkus/` + `app/html/` | No jars under `app/`; `quarkus-application.dat` points at **root** app jar |
| 4. Shadow | `package-dist.sh` overwrites `ProfileAccessorInvoker` into **`jainslee-api`** from core | `javap -c` shows `ProfileFieldStoreLocator`, not stub UOE string |
| 5. Bytecode | App jar major **69** (Java 25) | `javap -verbose … \| grep major` or equivalent |
| 6. Rsync | **Only** `ussdgw-app.jar`, `quarkus-run.jar`, `lib/`, `quarkus/`, `app/html/` → Digicom APP_HOME | **Never** overwrite Digicom `configs/` (PG, secrets, SS7 seed/persist) |
| 7. Restart | `systemctl restart ussdgw.service` (or host equivalent) | systemd **active ≠ Quarkus ready** |
| 8. Ready gate | Wait ~25s once (or journal bootstrap), then **one-shot** curl `--connect-timeout 3 --max-time 10` to **`:8088`** `/admin/status.json` (`X-USSD-Admin-Key`) | **Not** 60× poll; systemd active ≠ Quarkus ready; unit uses `KillMode=control-group` + `flock --timeout` |
| 9. Artifact truth | Jar **mtime** vs source; `jar tf ussdgw-app.jar \| grep` / `strings` for new classes (`GATE_ARMED`, `UssdUserProfile`, …); running PID classpath | Green `mvn test` ≠ deployed; source-only greps lie |

#### Copy-paste (worktree root → Digicom)

Paths below match the live host shorthand (`digicom-nb`, APP_HOME as shown). Run from the **ussd-microjainslee** worktree root. Do **not** invent or paste Digicom secrets into chat/docs — read `X-USSD-Admin-Key` from Digicom host `configs/` only.

```bash
# JDK 25
export JAVA_HOME=$(ls -d ~/.local/share/mise/installs/java/zulu-25* 2>/dev/null | head -1)
export PATH="$JAVA_HOME/bin:$PATH"
java -version   # expect 25

# A∧B prove gate (fail ⇒ do not rsync)
./build/prove-as-wire-lab.sh

# Digicom build-time db-kind (backup → postgresql → package → restore h2)
cp -a build/application.properties "build/application.properties.bak-deploy-$(date +%Y%m%d%H%M%S)"
sed -i 's/^quarkus.datasource.db-kind=h2$/quarkus.datasource.db-kind=postgresql/' build/application.properties
USSD_REQUIRE_PG_BAKE=1 ./build/package-dist.sh
test "$(cat dist/.baked-db-kind)" = postgresql   # refuse H2 bake → Digicom crash-loop
sed -i 's/^quarkus.datasource.db-kind=postgresql$/quarkus.datasource.db-kind=h2/' build/application.properties
# NOTE: restoring h2 only edits source props for the next local package — dist/ stays PG-baked until you package again without USSD_REQUIRE_PG_BAKE

# Snapshot Digicom jars for rollback (C fail) — never configs/
ssh digicom-nb 'mkdir -p /tmp/ussdgw-jar-bak-$(date +%Y%m%d%H%M%S) && cd /home/app/ota-push-services/ussdgw-micro-jainslee && B=$(ls -dt /tmp/ussdgw-jar-bak-* | head -1) && cp -a ussdgw-app.jar quarkus-run.jar "$B/" && cp -a lib quarkus "$B/"'

# Rsync jars / lib / quarkus / UI only — never configs/
APP=digicom-nb:/home/app/ota-push-services/ussdgw-micro-jainslee
rsync -az dist/ussdgw-app.jar dist/quarkus-run.jar "$APP/"
rsync -az --delete dist/lib/ "$APP/lib/"
rsync -az --delete dist/quarkus/ "$APP/quarkus/"
rsync -az --delete dist/app/html/ "$APP/app/html/"

# Restart (systemd active ≠ Quarkus ready)
ssh digicom-nb 'sudo systemctl restart ussdgw.service'
# Then: export KEY=…; ./build/prove-as-wire-lab.sh --preflight → C1–C6 sim net1
```

**Ready + prove** (after restart). Read `ussd.admin.api-key` from Digicom host `configs/application.properties` (or `USSD_ADMIN_API_KEY` if set there) — **never invent** the value; do not paste live secrets into docs/chat.

```bash
# After restart: sleep ~25s once (or journal bootstrap), then ONE-SHOT curl — never 60× poll loops
# export KEY='…' from Digicom configs (ussd.admin.api-key); never commit / never invent
ssh digicom-nb 'sleep 25'
ssh digicom-nb "curl -sS --connect-timeout 3 --max-time 10 -o /tmp/ussdgw-status.json -w '%{http_code}\n' -H 'X-USSD-Admin-Key: $KEY' http://127.0.0.1:8088/admin/status.json"

# Flat status keys (not nested): ss7.live, bridge.asyncGateMs, scheduler.gateTicks
ssh digicom-nb 'python3 -c "import json; d=json.load(open(\"/tmp/ussdgw-status.json\")); print(\"ss7.live\", d.get(\"ss7.live\")); print(\"bridge.asyncGateMs\", d.get(\"bridge.asyncGateMs\")); print(\"scheduler.gateTicks\", d.get(\"scheduler.gateTicks\"))"'

# Artifact contains expected symbols (adapt to the change you shipped)
ssh digicom-nb 'strings /home/app/ota-push-services/ussdgw-micro-jainslee/ussdgw-app.jar | grep -E "GATE_ARMED|UssdUserProfile" | head'
```

Prove checklist: **`ss7.live`** from status JSON (not LISTEN / Apply); **`bridge.asyncGateMs`** present; **`scheduler.gateTicks`** climbing when bridge armed; jar **mtime** + symbols (`GATE_ARMED`, new classes); **systemd active ≠ Quarkus ready** — always wait for `:8088` `/admin/status.json` 200 before SS7/NI debug. **UI/bug ships:** also curl the broken admin surface — CDR: `/admin/cdr/partial` must contain **`cdr-hop-list`** / `data-cdr-hop=` + AS ~50 (`cdr-as-ussd-snip` / `asUssd=`); jar has `CdrSessionSpine`; Adaptive KPI ≠ `{1=1000.0}` — status.json alone does not prove CDR/KPI HTML.

**Optional note:** `ussdUser` ProfileFacility (PK=MSISDN) is **JVM-local** until clustering — same in-process family as `ussdTx`, not Digicom JDBC. → [map2map.md](../as-contract/map2map.md) § ussdUser · [lessons.md](lessons.md).

Footguns for this path: [lessons.md](lessons.md) (Digicom package / rsync / ProfileAccessor / systemd-before-HTTP).

## Admin UX (OTA shell → USSD)

- **UI brand:** Digicom-ET USSDGW (titles, headers, login, nav). Package names stay `et.restlink.*`.
- Disk templates under `app/html/admin/` + `partials/` + `static/` via `AdminPageRenderer` (`ussd.admin.ui-dir`). Sync to `dist/app/html/admin/` when editing.
- Copy **shell** from ota-sim-push (Alpine theme, ink/signal amber `#e8a317`, DM Sans / JetBrains Mono) — never invent purple/cream skins.
- **Plane pages = Routing shell:** header title (**no** `hx-live-badge` / visible HTMX chrome); progressive `form-card` + `hx-post` → `#plane-notice` where editable; Directory / live status below uses **ink-panel** surfaces (never nested pure-black `bg-ink` status holes).
- **Theme key = `ussd-theme` only** (`ussd-shell.js`). Never invent `ota-theme` / `mw-theme` as the SoT (Monitor Hub may *read* legacy keys as fallback, but must *write* `ussd-theme`). Light remap in `admin.css` must cover **opacity variants** (`bg-ink/40`, `bg-ink-panel/40`, …) — Tailwind emits separate classes; listing `.bg-ink` alone leaves black holes on light (CDR/dashboard). Form inputs inside `form-card` / filter bars = **`bg-ink-panel`** (or CSS token), never nested `bg-ink`. Monitor Hub jar (`jainslee-monitor`) shares the same ink/signal tokens + brand **Digicom-ET USSDGW**; chart canvases stay transparent / panel — no nested `#0c1220` wells.
- **Save must HTMX-swap the list (OTA parity):** catalog create/update/delete POSTs return **HTML row fragments** for `hx-target="#…-rows"` (`innerHTML`) plus `HX-Trigger` toast (`ussdToast`) and `ussdCatalogChanged` (re-GET `/admin/…/partial` into the same tbody — fleet-approvals pattern). Never empty body, JSON-only, or full shell page on mutate. `HX-Trigger` values must be **ASCII** (no em-dash) — HTTP headers are Latin-1. Do **not** put Alpine `x-data` on the same `<form>` as `hx-post` (wrap a parent `div`). App-user create notices use **OOB** (`#app-user-notice`), never a `<div>` prepended into `<tbody>`. Set `Vary: HX-Request`.
- **No hub redirect:** `/admin/ss7|hlr|smpp|http|grpc|diameter|sip` serve the plane shell directly. `/admin/*/config` aliases the same panel for POST/HTMX. Monitor Hub (`/telemetry/`) = metrics only.
- **SS7 / SMPP = JSON only:** no host/port/OPC/systemId field grids. SS7 = `mapEnabled` + `configFile` + `stackJson` textarea (ADMIN/OPS); TENANT = LIVE/DOWN. SMPP = single `smppJson` via `SmppConfigSupport.activeJsonOrLab()` + Save/Apply/Start/Stop.
- **HLR face:** `/admin/hlr` form (mode, fake IMSI/MSC GT, upper GT, Diameter dest host/realm) — **not** on SS7 JSON page. Persist `ussd.hlr.*` KV; hot-read at SRI time. Outbound SRI-SM CalledParty = resolved `ussd.hlr.upper-gt` (admin overlay, else props); blank overlay ≠ fail if props set; fail-closed only if resolved GT blank or == local USSD GT. ADMIN/OPS edit; TENANT read-only. Default PROXY_MAP fail-closed.
- **HTTP / gRPC = status only:** live badges/cards + read-only NI push URL / callback path on HTTP; no Apply config forms. Poll `/admin/links*` / plane status endpoints.
- **Diameter / SIP = form + status:** enabled + host/port/realm/origin (Diameter) or tcp/udp/fromUri/requestUri (SIP); Persist KV in `RuntimeConfigStore` then Apply via `DiameterApplyService` / `SipApplyService`.
- **SS7 role gate:** `stackJson` + Apply editable for **ADMIN/OPS** only (`Principal.isAdminOrOps`). TENANT sees LIVE/DOWN (`linkStatus`) — not editable stack fields.
- **HTTP:** show **Push USSD NI** clearly — read-only `POST {{NI_PUSH_URL}}` from `ussd.http.ni-path` (default `/ussd`) + listen host:port; also callback path.
- **Bridge:** `asyncWaitMessage` / `asyncHardFailMessage` are UTF-8 textareas (no alphabet dropdown); parseForm UTF-8; `VirtualSessionBridge.onGateExpired` → `MapDialogHelper.replyAndEnd` (AUTO→UCS-2).
- **Dashboard Planes:** status-first `form-card` rows (`bg-ink-panel`); Open → `/admin/ss7|hlr|smpp|http|grpc|diameter|sip|lab-mo`; secondary Monitor Hub link.
- **Dashboard KPI metric cards (layout law):** every KPI uses `.metric-card` + `.metric-card-value` (+ `.metric-card-value--long` when the string is long). CSS SoT in `admin.css`: `min-width: 0`, `overflow: hidden`, value = single-line ellipsis, smaller font for long values; detail may wrap. **Never** dump Java `Map`/`Object.toString()` into a card — AdaptiveTimeout ADAPTIVE uses `AdaptiveTimeout.formatSnapshotForDisplay` (empty → `—`; one net → `1000 ms`; multi → `n0:900ms · n1:1.2k` capped). Apply the same overflow-safe card shell to **all** dashboard metrics so future long values cannot blow adjacent cards. Renderer: `AdminHttpHandler` metric helpers.
- **USSD pages only:** routing, bridge, campaigns, CDR, tenants, users, lab-mo, http sync/async/callback, grpc, diameter, sip, hlr, ss7/smpp/http — **no** fleet/CAP/sendota.
- Always seed `{{NAV_LINKS}}`, `{{NOTICE}}`, banners; never leave raw mustache. → [lessons.md](lessons.md)
- **CDR ledger** (`/admin/cdr`): **1 correlationId → 1 row** (`ussd_cdr_session` UPSERT; optional `events=N` badge). File `USSD_CDR` = append-only event tape. Filter MSISDN + **correlation** + **status** (exact or `*` prefix: `MAP2MAP_*` / `GATED*` / `GATED_AS*`) on **rolled-up** status + limit → HTMX `#cdr-rows` (`/admin/cdr/partial`, auto every 5s; `hx-swap` `innerHTML settle:0 show:none focus-scroll:false` + click/poll scroll pin — **`scrollY` must not change**). Seed `{{ROWS}}`, `{{MSISDN}}`, `{{CORR}}`, `{{STATUS}}`, `{{STATUS_OPTIONS}}`, `{{LIMIT}}`, `{{ROW_COUNT}}`. Phase bar = ledger phase spine (not `hx-live-badge`). Status chips: gated (`GATE_ARMED` / `MAP2MAP_HOP_CLOSE` → amber `cdr-status--gated` — **HOP_CLOSE never red even if phase=FAILED**) / map2map / **fail** (`TIMEOUT` / `AS_EMPTY_BODY` / `*FAIL*` / `MAP2MAP_HOP_FAIL` / `MAP2MAP_HOP_ABORT` → `cdr-status--fail`). MAP2MAP law: hop text → `HOP_CLOSE` amber + AS text; no text → fail + `hlrResult=none`; AS silent → Bridge wait text. → [map2map.md](../as-contract/map2map.md). **Primary signals:** (1) flat **AS USSD** column ~50 (`CdrUssdSnippet.resolveForDisplay` — prefer `as_ussd`); (2) outcome via `CdrServiceStatuses.primaryOutcome` — END/CONTINUE/fail/HOP_CLOSE beat `GATE_ARMED`. **Expand (full-width card under row, same Digicom scheme):** AS hero → fixed **6-hop spine** (`CdrSessionSpine.derive` from `events_json`/`as_ussd` only — **no new persist**): (1) Receive USSD (msisdn/dialed/sc) (2) Re-route HLR/MSC (gt/ssn; SRI/NI fills 2–3) (3) HLR/MSC response (~50 hopText) (4) Send AS (asUrl/sc) (5) AS response (`asUssd=` ~50) (6) Gate→UE (MAP END/CONTINUE + gateMs; **FAIL/`cdr-status--fail` if AS text but MAP not sent**). Missing hops = SKIPPED + `reason=…`. Then **visible** This session grid (incl. hop/AS answers) → `<details class="cdr-advanced">` = **raw pipe / event tape only** (never bury spine/session there). **Scroll law:** `overflow-anchor: none` on ledger/detail/foot; **no** ledger `overflow-x-auto`; pin `scrollY` on expand click + `htmx:afterSwap`/`afterSettle`; `htmx.config.scrollBehavior=instant` + `defaultFocusScroll=false`. Timestamps = **`java.time` only**. UI tokens: ink-panel / `cdr-digest` / `cdr-status-*` — no new theme. Prove: `/admin/cdr/partial` has `cdr-hop-list` + AS snip; mid-page expand keeps `scrollY`; Adaptive KPI ≠ `{1=1000.0}`. Catalog: `CdrSessionSpine` · `CdrServiceStatuses` · `CdrUssdSnippet` · `CdrSessionRollup`.
- Keep `htmx.min.js` for AJAX; **remove** all visible `hx-live-badge` / “HTMX” badges.

## Compress — remember these

- **Product = 3GPP USSD pull/push** — oracle `nhanth87/ussdgw` core; not SIM OTA.
- **Dist:** git scaffold ≠ runnable — `package-dist.sh` before copy-and-run (`lib/` gitignored). → § Dist
- **DB:** file H2 lab or PostgreSQL prod — never `h2:mem` for ship. → [schema.md](schema.md)
- **`AGENTS.md` stays thin** — durable rules live here / linked docs.
- **HTTP AS modes:** Sync / Async / Callback = admin HTMX + Monitor Hub hooks; TENANT lab only. Pull carries `correlationId` (push-back key), `sessionId`/`virtualBridgeId`, `adaptiveTimeoutMs` — same JSON fields on gRPC.
- **AS HTTP wire (dual-mode):** default **XML** (`text/xml`, classic `<dialog>`) + opt-in **JSON**; enable from **Routing** `HTTP AS wire` (tenant `http_as_wire_format`) or Tenants / `ussd.as.http.wire-format`. NI sync path `/ussd` + `JSESSIONID`; park with `AdaptiveTimeout`, never `Thread.sleep`. → [map2map-as-xml.md](../as-contract/map2map-as-xml.md) · [classic-xml.md](../as-contract/classic-xml.md)
- **AdaptiveTimeout / Virtual bridge on top of MAP NI:** park/gate/CAS own AS HTTP lifetime; MAP Notify/Request/continue/TC-END under that. Specs: [ussd-3gpp-notes.md](../as-contract/ussd-3gpp-notes.md) (22.090 / 23.090 / **29.002**; **22.002** is Circuit BS — not USSD).
- **Routing Mark:** `ussd_short_code.mark=true` = prefix key (classic `exactMatch=false`): `*100*` matches `*100*123456#`. Exact non-mark rules win on equality; else longest mark prefix. Admin `/admin/routing` field **Mark**.
- **MAP2MAP Case 2:** Type **`RE_ROUTE` / re-route** (or legacy `reroute_enable=true` + `map2map_gt`) → arm **AdaptiveTimeout / Virtual bridge at hop ingress** (stay-on-call); hop to **`hop_dest_gt`/`hop_dest_ssn`** or blank → **`ussd.hlr.upper-gt`** SSN 6 — **no SRI/FAKE**. Hop USSD = `resolveHopUssd` (mark long → preserve suffix after prefix; exact short → literal redirect) + **chain** fold across nested RE_ROUTE rules. After hop, **`AsPullRouter`** SLEE-routes AS by plane **HTTP|GRPC|SIP** (`asPullType` on RE_ROUTE form; persisted as `rule_type` + `reroute_enable` — never invent a non-SLEE wire). Routing UI shows Redirect USSD + Hop HLR + AS plane when TYPE=re-route. Case 1 NI SRI untouched. Telemetry `map2map.hopFixedGt` / `hopUpperGt`. Digicom long form: mark=`*804*` + redirect=`*875*` (ask before mutating live `*804#`); Flyway **V10**+**V11**. → [map2map.md](../as-contract/map2map.md)
- **HLR face:** inbound SRI-SM → `HlrResponderSbb`; NI/PROXY_MAP outbound SRI CalledParty = resolved `ussd.hlr.upper-gt` (overlay→props). Admin `/admin/hlr`. Default PROXY_MAP fail-closed. → [ss7-lab-pair.md](ss7-lab-pair.md)
- **Digicom ↔ Balance Plus:** SCTP/IPSP **server**, `exchangeType: DE`, single `routingContext: 12` (not dual list), listen **2011/2019**, SPC **1470**. LIVE 2026-08-07 after persist quarantine + restart — proof pcap `build/pcap/m3ua-aspac-rc12-20260807-135839.pcap`. File-source `ss7.detail` must not show props `8013`. → [ss7-lab-pair.md](ss7-lab-pair.md)
- **Pending correlation (non-negotiable):** classic gets this from its per-query `SriSbb` child — an answer that matches nothing resolves to nothing. Here both `PendingSriRegistry` and `PendingHlrProxyRegistry` key **strictly** on the outbound correlation id; a miss is `Optional.empty()`, never "any pending entry". TTL (`ussd.sri.pending-ttl-ms` 30s / `ussd.hlr.proxy.pending-ttl-ms` 15s) is swept by `BridgeGateScheduler` — expiry fails the NI saga, or **aborts** the inbound HLR dialog. → [parity-matrix.md](../parity-matrix.md)
- **One response per inbound SRI-SM dialog:** `FAKE_THEN_RESOLVE` answers from `doFake` and closes the dialog, so its upper resolve is registered `enrichOnly` — it refreshes `HlrLocationCache` for the next query and never emits a second `SendRoutingInfoForSmResponse`.
- **NI `/ussd` ingress:** authenticated by default (`ussd.http.ni.auth-required=true`, `X-USSD-Api-Key` tenant or admin key); 401 is rendered in the **request's own** wire format, then `TenantGuard.admit` (403 / 429). `networkId` = dialog → tenant → `ussd.http.ni.default-network-id`, never a hardcoded 0.
- **`onEvent` failure ends the dialog:** catching `Throwable` is not enough — `MapUssdParentSbb.endDialogOnFailure` sends `replyAndEnd` (hard-fail text) on an MS-facing leg, `abort` on any other, and nothing on an already-terminal dialog event.
- **Diameter / SIP:** live MO/NI when RA peer ready; stub only when down.
- **AS pull:** raw body (XML or JSON) — never `CallbackRequest` envelope for pull.
- **Bridge idempotency (non-negotiable):** every path that emits a MAP reply or an NI push must first win a **CAS**, never a read-only inspection. `VirtualSessionStore.claimForAsResponse` takes `AWAITING_AS|S1_RELEASED → RESPONDING` (classic `BridgeReconciler` `BRIDGED → PUSH_PENDING`); `onGateExpired` takes `AWAITING_AS → S1_RELEASED|COMPLETED` and returns `false` when it loses. `acceptAsResponse` is a **read-only** pre-check — never act on it alone.
- **CAS ≠ then rewrite the row:** after a successful `compareAndSetField`, never `get()`+`put()`. `UssdTxProfileMapper.write` republishes all CMP fields from a detached snapshot and silently reverts concurrent single-field writes (resurrecting `dialogAlive` on a dead dialog). Use `VirtualSessionStore.setDialogAlive` / `ProfileFacility.updateField` for caller-owned fields.
- **Gate tick:** `BridgeGateScheduler.tickGates` is `ConcurrentExecution.SKIP` with per-session `catch (Throwable)`. The due list is deadline-ordered, so one throwing session would otherwise sit first on every tick and **no gate would ever fire again** — every parked MAP dialog hangs to MSC timeout. Gate discovery walks an in-memory deadline index (O(due), not a full deserialize of every `AWAITING_AS` row at 10 Hz); the Profile table stays the source of truth and every index hit is re-validated.
- **Store reads never throw:** a row removed mid-read invalidates its `ProfileLocalObject` (micro-jainslee C8). `VirtualSessionStore.get` answers `Optional.empty()`; `put` retries once. Nothing may propagate out of a SLEE event handler.
- **AdaptiveTimeout:** live `GATE_ARMED` budget = configured
  `ussd.bridge.async-gate-timeout-ms` ceiling (default 25s) for MAP2MAP hop arm, MO AS wait,
  and NI HTTP park — **never** `clamp(EWMA×1.5, …)`. EWMA keyed by **`networkId`** (+ temporary
  per-MSISDN pull profile) stays for telemetry / CDR `observed_ewma_ms` / admin
  `adaptive.ewma` only. Samples clamped to `[FLOOR_MS, dialogTimeoutMs]`, decayed while idle,
  dropped when stale, resettable after AS redeploy. Durations use `System.nanoTime()`; wall
  clock is only for the durable gate deadline and CDR timestamps.
- **Bridge CDR:** `gate_ms` + `observed_ewma_ms` are real session columns (Flyway **V5** legacy tape + **V13** `ussd_cdr_session`) — not free-text `detail`. Never edit V1–V12 (checksum break).
- **Logging:** Log4j2 → `ussd.log.dir` / `dist/logs/`; SLEE boundary = `SleeEventTrace` only.
- **Commits:** nhanth87 / Tran Nhan only — no AI co-author trailers.
