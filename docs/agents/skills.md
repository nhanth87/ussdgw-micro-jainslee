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
| Logging | [logging.md](logging.md) |
| `app/html/*` admin shell | [AGENTS.md](../../AGENTS.md) — UI files only under `app/html/` |
| HTTP Sync/Async/Callback / Monitor Hub HTTP | [AGENTS.md](../../AGENTS.md) + `AdminHttpAsModeHandler` |
| Lab MO / SMPP NI | [AGENTS.md](../../AGENTS.md) Access |
| HLR face / SRI-SM inbound | [ss7-lab-pair.md](ss7-lab-pair.md) · `HlrFaceService` / `HlrResponderSbb` |
| Parity vs classic | [../parity-matrix.md](../parity-matrix.md) |

## Dist — Quarkus fast-jar (non-negotiable)

Same Digicom-ET / OTA push standard. **Never** ship a single uber-jar.

```bash
./build/package-dist.sh   # → ./dist/
./dist/run.sh             # start (JDK 25)
```

| Path | Role |
|------|------|
| `quarkus-run.jar` | Thin launcher — **only** start via `./run.sh` / `java -jar quarkus-run.jar` |
| `ussdgw-app.jar` | Application classes at APP_HOME **root** (moved out of Quarkus `app/`) |
| `lib/boot/` · `lib/main/` | Dependencies — replaceable jars (not fat-jar) |
| `quarkus/` | Generated model; `quarkus-application.dat` must reference **root** `ussdgw-app.jar` |
| `app/html/` | Admin UI (`*.html` …) — **never jars under `app/`** |
| `configs/` · `data/` · `logs/` | Config + runtime |

### Hard rules

1. **No uber-jar.** `quarkus.package.jar.type=fast-jar` only. Refuse `java -jar ussdgw-app.jar` alone.
2. **`app/` = UI only.** After package, `find dist/app -name '*.jar'` must be empty. Fail the package script if not.
3. **App jar at APP_HOME root.** Quarkus builds `target/quarkus-app/app/ussdgw-app.jar` → `package-dist.sh` copies to `dist/ussdgw-app.jar` and rewrites `quarkus/quarkus-application.dat` (`app/ussdgw-app.jar` → `ussdgw-app.jar`).
4. **Ship `dist/` only.** Ops get `lib/` + html + configs + run.sh — not a mystery single jar.
5. **JDK 25** (bytecode major **69**) for `ussdgw-app.jar`.

Peer: OTA [`docs/agents/packaging.md`](../../../../ota-service/ota-sim-push/docs/agents/packaging.md) · micro-jainslee [AGENTS § DIST](../../../../jain-slee/jain-slee/AGENTS.md).

## Compress — remember these

- **`AGENTS.md` stays thin** — durable rules live here / linked docs.
- **HTTP AS modes:** Sync / Async / Callback = admin HTMX + Monitor Hub hooks (`HttpServerAdminBindings.bindAppPanels`); TENANT lab only, no plane config mutate.
- **HLR face:** inbound SRI-SM → `InboundSriSmEvent` → `HlrResponderSbb`. Modes FAKE / PROXY_MAP (default fail-closed) / PROXY_DIAMETER / FAKE_THEN_RESOLVE. Never silent FAKE. Upper GT must not loop to local. → [ss7-lab-pair.md](ss7-lab-pair.md)
- **AS pull:** `JsonPostRequest` raw body — never `CallbackRequest` envelope for pull.
- **Logging:** Log4j2 → `ussd.log.dir` / `dist/logs/`; SLEE boundary = `SleeEventTrace` only.
- **Commits:** nhanth87 / Tran Nhan only — no AI co-author trailers.
