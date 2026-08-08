# SS7 simulator lab + USSD CLI (auto digits / interactive DT)

Handset / MSC peer for Digicom-ET USSDGW. **Real MAP** uses jSS7 `USSD_TEST_CLIENT`
(coral-valley). This folder ships lab XML, multi-user config, HTTP smoke, and a
**Java 25 CLI** that drives the sim over JMX.

## Where the MAP sim lives

| Path | Role |
|------|------|
| `worktrees/jSS7/coral-valley/jSS7/tools/simulator` | Source + build (patched auto-digit) |
| `tools/ss7-simulator/data/ussdgw_lab_client.xml` | Lab seed for ussdgw `ss7-lab.json` (8014→8013) |
| `tools/ss7-simulator/cli/` | Java 25 `ussd-cli` (JMX → dial / dt / msisdn) |
| `tools/ss7-simulator/ss7-ussd-client-ussdgw.json` | map/load Client stack JSON for ussdgw lab |
| `./run.sh path` | Resolves simulator home (`JSS7_SIM_HOME` override) |

ussdgw listens SCTP **8013** (PC 1). Sim is IPSP **client** on **8014** (PC 2). SSN **8**.

## Dedicated pull-lab SCTP pair (`:8023`↔`:8024`)

When `:8013` is busy (or you want an isolated pull prove), use the second stack:

| Role | Listen | PC | SSN |
|------|--------|----|-----|
| GW (`ss7-lab-sim-pull.json`) | **8023** server | 1 | **8 + 147 + 6** |
| jSS7 sim (`ussdgw_lab_pull_client.xml`) | **8024** client | 2 | 8 → remote 8 |

```bash
./tools/ss7-simulator/pull-lab.sh apply-ss7   # points dist configs at sim-pull JSON
./dist/run.sh
cd tools/as-node && npm run pull:fast
./tools/ss7-simulator/pull-lab.sh reseed-pull # *100# *123# *101 → :8090/ussd/pull
./tools/ss7-simulator/pull-lab.sh sim
./tools/ss7-simulator/pull-lab.sh dial-pull '*100#'
# or: ./tools/ss7-simulator/pull-lab.sh cli
```

Files: `build/ss7-lab-sim-pull.json`, `tools/ss7-simulator/data/ussdgw_lab_pull_client.xml`,
`config-pull.json`, `seed-ussd-pull.sql`, `pull-lab.sh`.

## USSD CLI (preferred operator UX)

JDK **25** only (`mise` → `zulu-25`). CLI does **not** embed a second SS7 stack — it
connects to a running jSS7 `core` with RMI and calls `TestUssdClientMan`.

```bash
# 1) GW + AS
./dist/run.sh
cd tools/as-node && npm install && npm run pull:fast
# seed routing pull URL → http://127.0.0.1:8090/ussd/pull

# 2) jSS7 USSD_TEST_CLIENT core (seeds lab XML, RMI :9999)
./tools/ss7-simulator/run.sh sim

# 3) Interactive CLI
./tools/ss7-simulator/run.sh cli
```

```
ussd> connect
ussd> msisdn 251911000001
ussd> dial *100#
  network: Welcome…
ussd> dt 1
ussd> dial *519812345678901234#
ussd> dial *100*1234567890#
ussd> auto 1,2,3,4
ussd> dial *125#
ussd> quit
```

One-shot / non-interactive:

```bash
./tools/ss7-simulator/run.sh build-cli
mise exec zulu-25 -- java -jar tools/ss7-simulator/cli/ussd-cli.jar \
  --config tools/ss7-simulator/config.example.json \
  dial '*100#' --msisdn 251911000001 --dt 1,2,3

# Long / mark-style code, manual DT:
./tools/ss7-simulator/run.sh cli dial '*519812345678901234#' --manual
./tools/ss7-simulator/run.sh cli dial '*100*1234567890#' --msisdn 251911000002
```

### CLI commands

| Command | Meaning |
|---------|---------|
| `connect [--nostart]` | JMX connect; start stack unless `--nostart` |
| `msisdn [n]` / `msisdns a,b` / `next-msisdn` | MSISDN selection |
| `dial <code>` | MO `ProcessUnstructuredSS-Request` (any short/long string) |
| `dt <text>` | `UnstructuredSS-Response` (digit or free text) |
| `auto [1,2,3,4]` / `manual` | Auto-digit vs interactive DT |
| `status` / `wait [ms]` / `close` | Dialog inspect / poll / tear down |
| `quit` | Exit |

Config: `config.example.json` (`jmx.url`, object names, `msisdns`, `digits`).

## Short vs long code / Routing Mark

| Dial | Routing |
|------|---------|
| `*100#`, `*125#` | Exact short-code rule (`mark=false`) |
| `*100*1234567890#` | Mark prefix `*100*` (`mark=true`, longest prefix) |
| `*5198123456789…#` | Long / mark-style key as configured in `/admin/routing` |

Admin: **Routing → Mark**. See `docs/agents/skills.md` (Routing Mark).

## Auto digits (per MAP dialog)

After each `UnstructuredSS-Request` (menu from GW), the client can reply with the **next**
digit in sequence `1,2,3,4,…` on **that dialog only** (multi-user safe). CLI `auto` toggles
the same MBean flags; or set before sim start:

| Knob | Example |
|------|---------|
| XML `autoResponseString` | `1,2,3,4` |
| XML flag | `autoResponseOnUnstructuredSSRequests="true"` |
| `-Dussd.sim.autoResponseSequence` | `1,2,3,4` |
| `USSD_SIM_AUTO_DIGITS` | `1,2,3,4` |
| `-Dussd.sim.autoResponseDelayMs` / `DIGIT_DELAY_MS` | `400` |
| `-Dussd.sim.msisdnList` / `USSD_SIM_MSISDNS` | `251911000001,251911000002` |

Rebuild jSS7 simulator after pulling the coral-valley patch (`TestUssdClientMan`).

Packaged sim required for `run.sh sim`: build `tools/simulator` bootstrap so
`bin/run.sh` exists (`bootstrap/target/simulator-ss7`), or set `JSS7_SIM_HOME`.
Lab XML (Jackson `ConfigurationData` format) is copied to
`$SIMULATOR_HOME/data/main_simulator2.xml` via `-DSIMULATOR_HOME=…`
(`FORCE_LAB_XML=1` to overwrite). Old attribute-style XML will not load — L1 stays NO.

## Load test (`map/load` Client)

Hardcoded `*125*…` USSD string is replaced by system properties (coral-valley `Client.java`):

| Property | Default | Role |
|----------|---------|------|
| `ss7.load.shortCode` | `*125*+3162%06d#` | USSD string; `%` → `String.format` with random |
| `ss7.load.digits` | `1` | DT sequence on UnstructuredSS-Request |
| `ss7.load.msisdn` | _(empty → random)_ | Fixed MSISDN |
| `ss7.load.origPc` / `destPc` / `ussdSsn` | `1` / `2` / `147` | MAP address PC/SSN — use `2`/`1`/`8` for ussdgw |

```bash
# Rebuild map/load after pulling Client.java patch
cd worktrees/jSS7/coral-valley/jSS7/map/load && mvn -q -DskipTests package

./tools/ss7-simulator/run.sh load-env   # prints full java example

mise exec zulu-25 -- java \
  -Dss7.load.shortCode='*100#' \
  -Dss7.load.digits=1,2,3,4 \
  -Dss7.load.msisdn=251911000001 \
  -Dss7.load.origPc=2 -Dss7.load.destPc=1 -Dss7.load.ussdSsn=8 \
  -Dss7.load.ndialogs=100 -Dss7.load.rateLimit=10 \
  -cp '…map-load classpath…' \
  org.restcomm.protocols.ss7.map.load.ussd.Client \
  tools/ss7-simulator/ss7-ussd-client-ussdgw.json
```

Interactive single dialogs → **CLI**, not load Client.

## HTTP smoke (no MAP)

```bash
cd tools/as-node && npm run pull:fast
./tools/ss7-simulator/run.sh http
```

## PUSH test (NI → GW → handset auto-digit → AS continues)

```bash
cd tools/as-node && npm run pull:fast
# ss7-sim running with auto digits (answers NI UnstructuredSS-Request)
cd tools/as-node && MSISDN=251911000001 npm run push:ni
```

## Multi-user

- **Sequential:** `msisdn` / `next-msisdn` in CLI; or HTTP driver `mode=sequential`.
- **Parallel:** MAP load + `msisdnList` / per-dialog digit index; or HTTP `MODE=parallel`.

Each MSISDN keeps its own MAP dialog / as-node menu session (`correlationId` / `localId`).
