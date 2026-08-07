# Agent docs — ussdgw-jainslee

Start at root [AGENTS.md](../../AGENTS.md). Compress: **[skills.md](skills.md)**. Footguns: **[lessons.md](lessons.md)**.

Pattern peer: OTA [`docs/agents/`](../../../../ota-service/ota-sim-push/docs/agents/).

| Topic | Notes |
|-------|--------|
| Skills (compress) | [skills.md](skills.md) — dist fast-jar / admin OTA-shell / HLR / Diameter+SIP |
| Lessons / footguns | [lessons.md](lessons.md) — do not repeat (OTA + USSD) |
| Logging | [logging.md](logging.md) — Log4j2 ONLY |
| Admin UX | `app/html/admin/` + `AdminPageRenderer` (shell from ota-sim-push; USSD pages only) |
| SS7 lab / HLR face | [ss7-lab-pair.md](ss7-lab-pair.md) |
| Schema / H2+Postgres | [schema.md](schema.md) — Flyway V1 + `UssdSchemaInitializer` |
| Bridge | Adaptive EWMA + Virtual Session Bridge |
| AS contract | [../as-contract/](../as-contract/) dual-mode XML default + JSON · [classic-xml.md](../as-contract/classic-xml.md) |
| Parity | [../parity-matrix.md](../parity-matrix.md) |
| Cutover | [../cutover.md](../cutover.md) |
