# Agent skills — ussdgw-jainslee compress

What to load before packaging, admin UI, or AS plane work. Prefer these over re-deriving from chat.

## Before you edit

1. Read root [`AGENTS.md`](../../AGENTS.md) (Java 25, MAP/HTTP/gRPC, **dist layout**, link truth).
2. Footguns → [`lessons.md`](lessons.md) (OTA checklist adapted).
3. Packaging / deploy → this file § Dist + OTA peer [packaging.md](../../../../ota-service/ota-sim-push/docs/agents/packaging.md).
4. Admin HTMX / planes → [`AGENTS.md`](../../AGENTS.md) + this file.

| If you touch… | Read first |
|---------------|------------|
| Dist / `package-dist` / `run.sh` / ship layout | **this file § Dist** + [AGENTS.md](../../AGENTS.md) + [lessons.md](lessons.md) |
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

Peer: OTA [`docs/agents/packaging.md`](../../../../ota-service/ota-sim-push/docs/agents/packaging.md) · micro-jainslee [AGENTS § DIST](../../../../jain-slee/jain-slee/AGENTS.md).

## Admin UX (OTA shell → USSD)

- **UI brand:** Digicom-ET USSDGW (titles, headers, login, nav). Package names stay `et.restlink.*`.
- Disk templates under `app/html/admin/` + `partials/` + `static/` via `AdminPageRenderer` (`ussd.admin.ui-dir`). Sync to `dist/app/html/admin/` when editing.
- Copy **shell** from ota-sim-push (Alpine theme, ink/signal amber `#e8a317`, DM Sans / JetBrains Mono) — never invent purple/cream skins.
- **Plane pages = Routing shell:** header title (**no** `hx-live-badge` / visible HTMX chrome); progressive `form-card` + `hx-post` → `#plane-notice` where editable; Directory / live status below uses **ink-panel** surfaces (never nested pure-black `bg-ink` status holes).
- **No hub redirect:** `/admin/ss7|hlr|smpp|http|grpc|diameter|sip` serve the plane shell directly. `/admin/*/config` aliases the same panel for POST/HTMX. Monitor Hub (`/telemetry/`) = metrics only.
- **SS7 / SMPP = JSON only:** no host/port/OPC/systemId field grids. SS7 = `mapEnabled` + `configFile` + `stackJson` textarea (ADMIN/OPS); TENANT = LIVE/DOWN. SMPP = single `smppJson` via `SmppConfigSupport.activeJsonOrLab()` + Save/Apply/Start/Stop.
- **HLR face:** `/admin/hlr` form (mode, fake IMSI/MSC GT, upper GT, Diameter dest host/realm) — **not** on SS7 JSON page. Persist `ussd.hlr.*` KV; hot-read at SRI time. Outbound SRI-SM CalledParty = resolved `ussd.hlr.upper-gt` (admin overlay, else props); blank overlay ≠ fail if props set; fail-closed only if resolved GT blank or == local USSD GT. ADMIN/OPS edit; TENANT read-only. Default PROXY_MAP fail-closed.
- **HTTP / gRPC = status only:** live badges/cards + read-only NI push URL / callback path on HTTP; no Apply config forms. Poll `/admin/links*` / plane status endpoints.
- **Diameter / SIP = form + status:** enabled + host/port/realm/origin (Diameter) or tcp/udp/fromUri/requestUri (SIP); Persist KV in `RuntimeConfigStore` then Apply via `DiameterApplyService` / `SipApplyService`.
- **SS7 role gate:** `stackJson` + Apply editable for **ADMIN/OPS** only (`Principal.isAdminOrOps`). TENANT sees LIVE/DOWN (`linkStatus`) — not editable stack fields.
- **HTTP:** show **Push USSD NI** clearly — read-only `POST {{NI_PUSH_URL}}` from `ussd.http.ni-path` (default `/ussd`) + listen host:port; also callback path.
- **Bridge:** `asyncWaitMessage` / `asyncHardFailMessage` are UTF-8 textareas (no alphabet dropdown); parseForm UTF-8; `VirtualSessionBridge.onGateExpired` → `MapDialogHelper.replyAndEnd` (AUTO→UCS-2).
- **Dashboard Planes:** status-first `form-card` rows (`bg-ink-panel`); Open → `/admin/ss7|hlr|smpp|http|grpc|diameter|sip|lab-mo`; secondary Monitor Hub link.
- **USSD pages only:** routing, bridge, campaigns, CDR, tenants, users, lab-mo, http sync/async/callback, grpc, diameter, sip, hlr, ss7/smpp/http — **no** fleet/CAP/sendota.
- Always seed `{{NAV_LINKS}}`, `{{NOTICE}}`, banners; never leave raw mustache. → [lessons.md](lessons.md)
- Keep `htmx.min.js` for AJAX; **remove** all visible `hx-live-badge` / “HTMX” badges.

## Compress — remember these

- **Product = 3GPP USSD pull/push** — oracle `nhanth87/ussdgw` core; not SIM OTA.
- **Dist:** git scaffold ≠ runnable — `package-dist.sh` before copy-and-run (`lib/` gitignored). → § Dist
- **DB:** file H2 lab or PostgreSQL prod — never `h2:mem` for ship. → [schema.md](schema.md)
- **`AGENTS.md` stays thin** — durable rules live here / linked docs.
- **HTTP AS modes:** Sync / Async / Callback = admin HTMX + Monitor Hub hooks; TENANT lab only. Pull carries `correlationId` (push-back key), `sessionId`/`virtualBridgeId`, `adaptiveTimeoutMs` — same JSON fields on gRPC.
- **AS HTTP wire (dual-mode):** default **XML** (`text/xml`, classic `<dialog>`) + opt-in **JSON**; per-tenant `http_as_wire_format` / `ussd.as.http.wire-format`. NI sync path `/ussd` + `JSESSIONID`; park with `AdaptiveTimeout`, never `Thread.sleep`. → [classic-xml.md](../as-contract/classic-xml.md)
- **Routing Mark:** `ussd_short_code.mark=true` = prefix key (classic `exactMatch=false`): `*100*` matches `*100*123456#`. Exact non-mark rules win on equality; else longest mark prefix. Admin `/admin/routing` field **Mark**.
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
- **Adaptive EWMA:** keyed by **`networkId`** (never MSISDN — unbounded cardinality), shared pull+push. Samples clamped to `[FLOOR_MS, dialogTimeoutMs]`, decayed back toward the configured gate while idle, dropped when stale, and resettable (`reset` / `resetAll`) after an AS redeploy. Durations use `System.nanoTime()`; wall clock is only for the durable gate deadline and CDR timestamps.
- **Bridge CDR:** `gate_ms` + `observed_ewma_ms` are real `ussd_cdr` columns (Flyway **V5**, registered in `UssdSchemaInitializer.MIGRATIONS`) — not free-text `detail`. Never edit V1–V4 (checksum break).
- **Logging:** Log4j2 → `ussd.log.dir` / `dist/logs/`; SLEE boundary = `SleeEventTrace` only.
- **Commits:** nhanth87 / Tran Nhan only — no AI co-author trailers.
