# Logging — Log4j2 ONLY (one style)

Copied/adapted from OTA SMSC-GW so agents do not reintroduce Quarkus file logging or dual SLEE traces.

App / SLEE / CDR file logging uses **Apache Log4j2** (`log4j-api` + `log4j-core` +
`src/main/resources/log4j2.xml`). Logs go under **APP_HOME `logs/`** (`dist/logs/`
after package), never `/tmp`. Property: **`ussd.log.dir`** (not `ota.log.dir`).

## One style (non-negotiable)

| Plane | API | Never |
|-------|-----|-------|
| SBB `onEvent` / RA `fireEvent` / RA command out | **`SleeEventTrace`** only | Also `LOG.info` for the same boundary |
| Services, schedulers, admin, RA lifecycle | **`LogManager.getLogger(Class)`** | `SleeEventTrace` for non-SLEE work |
| CDR lines | logger **`USSD_CDR`** | Blocking DB on the MAP hot path when async CDR is on |

**Always:** put outcome in Trace `detail`; `LOG.error(..., ex)` only for stack dumps.
**Never:** `log4j2-jboss-logmanager`, `quarkus.log.file*`, `/tmp` for app logs.

Mirror: [AGENTS.md § Logging style](../../AGENTS.md) · OTA peer [logging.md](../../../../ota-service/ota-sim-push/docs/agents/logging.md).

## Dist paths

```text
dist/logs/          # after ./build/package-dist.sh
  ussdgw.log        # (names may vary — follow log4j2.xml)
  …-slee.log
  …-cdr.log
dist/configs/
  application.properties
  ss7-persist/
```
