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
| HTTP Sync/Async/Callback / Monitor Hub HTTP | [AGENTS.md](../../AGENTS.md) + `AdminHttpAsModeHandler` |
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

- Disk templates under `app/html/admin/` + `partials/` + `static/` via `AdminPageRenderer` (`ussd.admin.ui-dir`).
- Copy **shell** from ota-sim-push (Alpine theme, login, nav, mustache `{{TOKEN}}`) — rebrand RestLink USSD.
- **USSD pages only:** routing, bridge, campaigns, CDR, tenants, users, lab-mo, http sync/async/callback, grpc, diameter, sip — **no** fleet/CAP/sendota.
- Always seed `{{NAV_LINKS}}`, `{{NOTICE}}`, banners; never leave raw mustache. → [lessons.md](lessons.md)

## Compress — remember these

- **Product = 3GPP USSD pull/push** — oracle `nhanth87/ussdgw` core; not SIM OTA.
- **Dist:** git scaffold ≠ runnable — `package-dist.sh` before copy-and-run (`lib/` gitignored). → § Dist
- **DB:** file H2 lab or PostgreSQL prod — never `h2:mem` for ship. → [schema.md](schema.md)
- **`AGENTS.md` stays thin** — durable rules live here / linked docs.
- **HTTP AS modes:** Sync / Async / Callback = admin HTMX + Monitor Hub hooks; TENANT lab only.
- **HLR face:** inbound SRI-SM → `HlrResponderSbb`. Default PROXY_MAP fail-closed. → [ss7-lab-pair.md](ss7-lab-pair.md)
- **Diameter / SIP:** live MO/NI when RA peer ready; stub only when down.
- **AS pull:** `JsonPostRequest` raw body — never `CallbackRequest` envelope for pull.
- **Logging:** Log4j2 → `ussd.log.dir` / `dist/logs/`; SLEE boundary = `SleeEventTrace` only.
- **Commits:** nhanth87 / Tran Nhan only — no AI co-author trailers.
