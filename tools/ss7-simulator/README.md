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

## Load test (Brook — **TPS = MSISDN sessions/s**)

**Definition:** `--tps N` means **N unique MSISDN MO starts per second**, not N TCAP
messages. Each session = `processUnstructuredSS-Request` (`*804#`) + digit CONTINUE traffic;
digits do **not** inflate TPS.

### Digicom Brook (locked) — real BPLUS

Oracle: **[`BROOK-SCENARIO.md`](BROOK-SCENARIO.md)** (+ [`config-brook.json`](config-brook.json)).

| Rule | Detail |
|------|--------|
| Short code / digit | `*804#` + digit **`1`** (Balance path after MAP2MAP hop) |
| **ss7-sim SCCP** | **`networkId = 1`** (L3-LAB-SIM / AS-LAB) — **not** live BP 0 |
| Live handset `*804` | stays **`networkId = 0`** — do **not** flip Digicom routing DB |
| SCTP / PC | sim **`:8024`** → Digicom **`:8023`**; sim **PC=2**, GW **PC=1470**; M3UA **RC=101** |
| map JSON | [`ss7-ussd-client-digicom-lab.json`](ss7-ussd-client-digicom-lab.json) (selected by `--scenario brook`) |
| MAP2MAP hop | `ussd.map.live-network-id` default **0** (live GTT) even when MO dialog is nwid=1 |
| AS | **Real BPLUS** via Digicom routing `as_url` — **never as-node** |
| Smoke | `--scenario brook` → tps=1, duration=30, destPc=1470 |
| Gate | **Wait operator green light** before any Digicom run |
| Rate | **Never 100 TPS on Digicom** without explicit approval |

```bash
# After green light — Digicom host (AS=BPLUS, not as-node; simulator nwid=1):
./tools/ss7-simulator/run.sh load --scenario brook
./tools/ss7-simulator/run.sh load-jmx --scenario brook
# or:
java -jar tools/ss7-simulator/cli/ussd-load.jar --scenario brook --tps 1 --duration 30
```

Laptop pull-lab JSON (`ss7-ussd-client-ussdgw-pull.json`, **nwid=0** / destPc=1) is for local as-node only — never Digicom Brook.
### Lab ramp (as-node only — not Digicom)

JMX CLI is **1 concurrent dialog** — see [`SPIKE-JMX-CONCURRENCY.md`](SPIKE-JMX-CONCURRENCY.md).
For ≥~3 TPS use **map/load** via `run.sh load` (default engine when `--tps > 2`).

| Property / flag | Role |
|-----------------|------|
| `--scenario brook` | Digicom lock: `*804#` + digit 1 + smoke tps=1 / duration=30 + **nwid=1** digicom-lab JSON |
| `--tps` / `ss7.load.rateLimit` | MO / MSISDN sessions per second |
| `--duration` / `ndialogs≈tps×duration` | Run length |
| `--short-code '*804#'` | Brook short code |
| `--digits 1` | DT after root CONTINUE |
| `--msisdn-random` / `ss7.load.msisdnPrefix=25191` | Unique `25191`+7 digits per MO |
| as-node `npm run pull:brook804` | **Lab only** Amharic root → digit 1 (`MENU_PICK=brook804`) |
| `AS_DELAY_MS` / `pull:brook804:ewma` | Artificial AS delay for AdaptiveTimeout EWMA |

```bash
# Lab stack (pull-lab :8023↔:8024) — as-node, NOT Digicom/BPLUS
./tools/ss7-simulator/pull-lab.sh apply-ss7 && ./dist/run.sh
cd tools/as-node && npm run pull:brook804          # optional: npm run pull:brook804:ewma
./tools/ss7-simulator/pull-lab.sh reseed-brook     # *804# → :8090/ussd/pull  networkId=0
# map/load does NOT need jSS7 USSD_TEST_CLIENT RMI — own SCTP client on :8024
./tools/ss7-simulator/run.sh load --tps 100 --duration 60 --short-code '*804#' --digits 1

# Functional smoke (1 MSISDN session at a time via JMX — start sim first)
./tools/ss7-simulator/pull-lab.sh sim
./tools/ss7-simulator/run.sh load-jmx --tps 1 --duration 30 --short-code '*804#' --digits 1
```

Pass bar (lab): `ss7.live=true`, error rate &lt;1%, achieved MSISDN/s ≈ target, EWMA seeded when
`AS_DELAY_MS>0`. **Never Digicom/BPLUS at 100 TPS without approval.** Results:
[`RAMP-RESULTS.md`](RAMP-RESULTS.md) · Digicom oracle: [`BROOK-SCENARIO.md`](BROOK-SCENARIO.md).

Coral-valley `Client.java` also accepts `ss7.load.msisdnPrefix` (hot-patch jar if Maven
`map/load` rebuild fails on unrelated map-impl tests).

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
