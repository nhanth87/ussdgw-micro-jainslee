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
| Deploying the **whole worktree** | Ship **`dist/` only** (complete tree after package). | root [AGENTS.md](../../AGENTS.md) |
| Debugging against a **stale** dist / old PID | After package: one `quarkus-run.jar` PID; `jar tf` the class; wait for bootstrap. | OTA lessons |
| Trusting **`mvn -q test`** exit 0 alone | Read `Tests run:` — zero tests can look green. | OTA lessons |
| Landing a test **never seen red** | Temporarily break the fix, confirm fail, restore. | OTA lessons |
| **`log4j2-jboss-logmanager`** / **`quarkus.log.file*`** / logs in `/tmp` | Log4j2 ONLY → `ussd.log.dir` / `dist/logs/`. | [logging.md](logging.md) |
| Dual **`SleeEventTrace` + `LOG.info`** on the same SBB boundary | Trace only for SLEE ingress/egress. | [logging.md](logging.md) |
| Fixing peer-down with **UI badge** only | `LinkStatusService` / `ss7.live` = SCTP+M3UA ACTIVE. | root AGENTS § Link status |
| Treating **LISTEN / Apply / isActive()** as live | Same as OTA. | root AGENTS |
| Verifying SCTP with **netstat** | Use `ss` / `/proc/net/sctp/eps`. | [ss7-lab-pair.md](ss7-lab-pair.md) |
| Corrupt jSS7 **`*sccp*.xml`** (`<1>` keys) | Validate parse; quarantine + seed; smoke Start. | [ss7-lab-pair.md](ss7-lab-pair.md) |
| jSS7 sim as **`SMS_TEST_CLIENT`** blocking HLR SSN 6 | Prefer **SMS_TEST_SERVER**; allow SSN **6**. | [ss7-lab-pair.md](ss7-lab-pair.md) |
| SBB catching only **`RuntimeException`** | Catch **`Throwable`**; always emit OUT trace. | root AGENTS |
| AS pull via **`CallbackRequest`** envelope | Pull = raw body (XML or JSON). | [skills.md](skills.md) |
| Assuming **JSON-only** HTTP AS wire | Default is classic **XML** (`ussd.as.http.wire-format` / tenant `http_as_wire_format`); JSON is opt-in. | [classic-xml.md](../as-contract/classic-xml.md) |
| **`Thread.sleep`** (or blocking wait) on classic **NI sync** parked HTTP | Park with async/suspend + **`AdaptiveTimeout`**; keep `JSESSIONID` multi-turn. | [classic-xml.md](../as-contract/classic-xml.md) · root AGENTS |
| HTTP/gRPC response via **50ms timer poll** | RA callbacks only. | root AGENTS |
| **Silent FAKE** HLR under PROXY_* | Default PROXY_MAP fail-closed; FAKE only when ops set. | [ss7-lab-pair.md](ss7-lab-pair.md) |
| `ussd.hlr.upper-gt` **== local ussdGt** | Loop guard aborts — set a real upper HLR GT. | [ss7-lab-pair.md](ss7-lab-pair.md) |
| Generation bump on **AS CONTINUE** | Bump **only** on MS input (`onUserContinue`). | AGENTS saga notes / code |
| TENANT login username ≠ **tenantId** | Enforced in `AdminUserService`. | root AGENTS |
| AI **`Co-authored-by:`** / `--no-verify` | nhanth87 / Tran Nhan only; hooks reject. | workspace AGENTS.md |
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
