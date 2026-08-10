#!/usr/bin/env bash
# Launch helpers for Digicom USSDGW ↔ jSS7 USSD client lab + USSD CLI.
# Real MAP peer lives in jSS7 (coral-valley). This tree ships lab XML, CLI, load JSON.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
CFG="${CONFIG:-$ROOT/config.example.json}"
# Override for dedicated pull-lab pair (8024→8023): LAB_XML=…/ussdgw_lab_pull_client.xml
LAB_XML="${LAB_XML:-$ROOT/data/ussdgw_lab_client.xml}"
CLI_JAR="$ROOT/cli/ussd-cli.jar"
LOAD_JAR="$ROOT/cli/ussd-load.jar"
LOAD_JSON="${LOAD_JSON:-$ROOT/ss7-ussd-client-ussdgw.json}"
LOAD_JSON_PULL="${LOAD_JSON_PULL:-$ROOT/ss7-ussd-client-ussdgw-pull.json}"
# Digicom L3-LAB-SIM (SCCP networkId=1, :8024→:8023, PC 2→1470, RC 101)
LOAD_JSON_DIGICOM_LAB="${LOAD_JSON_DIGICOM_LAB:-$ROOT/ss7-ussd-client-digicom-lab.json}"
MAP_LOAD_HOME="${MAP_LOAD_HOME:-}"

JSS7_SIM_HOME="${JSS7_SIM_HOME:-}"
candidates=(
  "$JSS7_SIM_HOME"
  "$ROOT/../../../jSS7/coral-valley/jSS7/tools/simulator"
  "$ROOT/../../../../jSS7/coral-valley/jSS7/tools/simulator"
  "$HOME/Desktop/ethiopia-working-dir/worktrees/jSS7/coral-valley/jSS7/tools/simulator"
  "$HOME/Desktop/ethiopia-working-dir/tools/jss7-simulator"
)

resolve_sim() {
  for c in "${candidates[@]}"; do
    [[ -z "$c" ]] && continue
    if [[ -d "$c" ]]; then
      echo "$c"
      return 0
    fi
  done
  return 1
}

resolve_sim_dist() {
  local sim
  sim="$(resolve_sim)" || return 1
  # Prefer packaged bootstrap dist (bin/run.sh + lib), else source tree bootstrap
  if [[ -x "$sim/bin/run.sh" ]]; then
    echo "$sim"
    return 0
  fi
  if [[ -x "$sim/bootstrap/src/main/config/run.sh" ]]; then
    # Source checkout — look for Maven assembly output
    local d
    for d in \
      "$sim/bootstrap/target/simulator-ss7" \
      "$sim/bootstrap/target/ss7-simulator" \
      "$sim/target/ss7-simulator" \
      "$sim/target/simulator-ss7" \
      "$sim"
    do
      if [[ -x "$d/bin/run.sh" ]]; then
        echo "$d"
        return 0
      fi
    done
  fi
  echo "$sim"
}

DIGITS="${USSD_SIM_AUTO_DIGITS:-1,2,3,4}"
DELAY="${DIGIT_DELAY_MS:-400}"
MSISDNS="${USSD_SIM_MSISDNS:-251911000001,251911000002,251911000003}"
RMI_PORT="${USSD_SIM_RMI_PORT:-9999}"
SIM_NAME="${USSD_SIM_NAME:-main}"

java25() {
  local java_bin="" ver=""
  for cand in \
    "${HOME}/.local/share/mise/installs/java/zulu-25" \
    "${HOME}/.local/share/mise/installs/java/25" \
    "${JAVA_HOME:-}"
  do
    [[ -z "$cand" || ! -x "$cand/bin/java" ]] && continue
    ver="$("$cand/bin/java" -version 2>&1 | head -1 || true)"
    case "$ver" in
      *'"'25*|*version\ 25*) java_bin="$cand/bin/java"; break ;;
    esac
  done
  if [[ -z "$java_bin" ]]; then
    echo "Java 25 required for ussd-cli. Set JAVA_HOME to zulu-25." >&2
    return 1
  fi
  "$java_bin" "$@"
}

ensure_cli_jar() {
  if [[ ! -f "$CLI_JAR" ]] || [[ ! -f "$LOAD_JAR" ]]; then
    chmod +x "$ROOT/cli/build.sh"
    (cd "$ROOT/cli" && ./build.sh)
  fi
}

resolve_map_load_home() {
  local c
  for c in \
    "$MAP_LOAD_HOME" \
    "$ROOT/../../../jSS7/coral-valley/jSS7/map/load" \
    "$ROOT/../../../../jSS7/coral-valley/jSS7/map/load" \
    "$HOME/Desktop/ethiopia-working-dir/worktrees/jSS7/coral-valley/jSS7/map/load"
  do
    [[ -z "$c" ]] && continue
    if [[ -f "$c/target/map-load-9.2.8-j25.jar" ]] || [[ -f "$c/ant-classpath.txt" ]]; then
      echo "$(readlink -f "$c")"
      return 0
    fi
  done
  return 1
}

seed_lab_xml() {
  local dist="$1"
  local data="$dist/data"
  mkdir -p "$data"
  local dest="$data/${SIM_NAME}_simulator2.xml"
  if [[ ! -f "$dest" ]] || [[ "${FORCE_LAB_XML:-0}" == "1" ]]; then
    cp -f "$LAB_XML" "$dest"
    echo "Seeded $dest from lab XML"
  else
    echo "Using existing $dest (FORCE_LAB_XML=1 to overwrite)"
  fi
}

case "${1:-help}" in
  http)
    exec node "$ROOT/mo-http-digit-loop.mjs"
    ;;
  print-env)
    cat <<EOF
# Export before starting jSS7 simulator JVM (or pass as -D):
export USSD_SIM_AUTO_DIGITS='$DIGITS'
export DIGIT_DELAY_MS='$DELAY'
export USSD_SIM_MSISDNS='$MSISDNS'
# JVM example:
#   -Dussd.sim.autoResponseSequence=$DIGITS
#   -Dussd.sim.autoResponseDelayMs=$DELAY
#   -Dussd.sim.msisdnList=$MSISDNS
# Load-test Client (map/load) — after rebuild coral-valley map/load:
#   -Dss7.load.shortCode='*100#'
#   -Dss7.load.digits=1,2,3,4
#   -Dss7.load.msisdn=251911000001
#   -Dss7.load.origPc=2 -Dss7.load.destPc=1 -Dss7.load.ussdSsn=8
EOF
    ;;
  xml)
    echo "$LAB_XML"
    ;;
  path)
    if sim="$(resolve_sim)"; then
      echo "$sim"
    else
      echo "jSS7 simulator not found; set JSS7_SIM_HOME" >&2
      exit 1
    fi
    ;;
  build-cli)
    chmod +x "$ROOT/cli/build.sh"
    (cd "$ROOT/cli" && ./build.sh)
    ;;
  cli)
    shift || true
    ensure_cli_jar
    java25 -jar "$CLI_JAR" --config "$CFG" "$@"
    ;;
  sim|core)
    # Start jSS7 USSD_TEST_CLIENT core with RMI for the CLI
    if ! dist="$(resolve_sim_dist)"; then
      echo "jSS7 simulator not found; set JSS7_SIM_HOME" >&2
      exit 1
    fi
    if [[ ! -x "$dist/bin/run.sh" ]]; then
      echo "No packaged sim at $dist (need bin/run.sh). Build tools/simulator bootstrap, or point JSS7_SIM_HOME at a dist." >&2
      exit 1
    fi
    seed_lab_xml "$dist"
    dist="$(readlink -f "$dist")"
    export SIMULATOR_HOME="$dist"
    JAVA_HOME_25=""
    for cand in \
      "${HOME}/.local/share/mise/installs/java/zulu-25" \
      "${HOME}/.local/share/mise/installs/java/25" \
      "${JAVA_HOME:-}"
    do
      [[ -z "$cand" || ! -x "$cand/bin/java" ]] && continue
      if "$cand/bin/java" -version 2>&1 | grep -qE 'version "25'; then
        JAVA_HOME_25="$(readlink -f "$cand" 2>/dev/null || echo "$cand")"
        break
      fi
    done
    if [[ -z "$JAVA_HOME_25" ]]; then
      echo "Java 25 required for jSS7 sim (class file 69). Install mise java@zulu-25." >&2
      exit 1
    fi
    export JAVA_HOME="$JAVA_HOME_25"
    export JAVA="$JAVA_HOME/bin/java"
    export JAVA_OPTS="${JAVA_OPTS:-} -DSIMULATOR_HOME=$dist -Dussd.sim.autoResponseSequence=$DIGITS -Dussd.sim.autoResponseDelayMs=$DELAY -Dussd.sim.msisdnList=$MSISDNS"
    echo "Starting sim core name=$SIM_NAME rmi=$RMI_PORT home=$dist JAVA_HOME=$JAVA_HOME"
    echo "Config XML: $dist/data/${SIM_NAME}_simulator2.xml"
    echo "Then: $0 cli"
    cd "$dist"
    exec bash bin/run.sh core -n "$SIM_NAME" -r "$RMI_PORT"
    ;;
  load)
    # Brook Digicom / lab load: TPS = MSISDN sessions/s (MO starts), not TCAP message count.
    # Digicom locked scenario: --scenario brook  (*804# digit 1, smoke tps=1) — see BROOK-SCENARIO.md
    # Default engine=mapload for --tps>2 (see SPIKE-JMX-CONCURRENCY.md).
    shift || true
    ensure_cli_jar
    MAP_HOME=""
    MAP_HOME="$(resolve_map_load_home)" || true
    MAP_JAR=""
    MAP_CP=""
    if [[ -n "$MAP_HOME" ]]; then
      MAP_JAR="$MAP_HOME/target/map-load-9.2.8-j25.jar"
      MAP_CP="$MAP_HOME/ant-classpath.txt"
    fi
    EXTRA=()
    if [[ -f "$MAP_JAR" ]]; then
      EXTRA+=(--map-jar "$MAP_JAR")
    fi
    if [[ -f "$MAP_CP" ]]; then
      EXTRA+=(--map-cp "$MAP_CP")
    fi
    HAS_JSON=0
    HAS_SCENARIO=0
    for a in "$@"; do
      [[ "$a" == "--map-json" ]] && HAS_JSON=1
      [[ "$a" == "--scenario" ]] && HAS_SCENARIO=1
    done
    if [[ "$HAS_JSON" -eq 0 ]]; then
      if [[ "$HAS_SCENARIO" -eq 1 ]] && [[ -f "$LOAD_JSON_DIGICOM_LAB" ]]; then
        # Digicom Brook / --scenario * → L3-LAB nwid=1 JSON (driver also sets this)
        EXTRA+=(--map-json "$LOAD_JSON_DIGICOM_LAB")
      else
        EXTRA+=(--map-json "$LOAD_JSON_PULL")
      fi
    fi
    # Pass JDK 25 java path into child ProcessBuilder
    JBIN="$JAVA_HOME/bin/java"
    for cand in \
      "${HOME}/.local/share/mise/installs/java/zulu-25/bin/java" \
      "${HOME}/.local/share/mise/installs/java/25/bin/java"
    do
      [[ -x "$cand" ]] && JBIN="$cand" && break
    done
    java25 -jar "$LOAD_JAR" --java "$JBIN" "${EXTRA[@]}" "$@"
    ;;
  load-jmx)
    # Digicom Brook smoke: $0 load-jmx --scenario brook  (wait green light; AS=real BPLUS)
    shift || true
    ensure_cli_jar
    java25 -jar "$LOAD_JAR" --engine jmx "$@"
    ;;
  load-env)
    cat <<EOF
# MAP load Client against ussdgw pull-lab (8024→8023).
# TPS = MSISDN sessions/s (MO starts), NOT TCAP messages.
# Digicom Brook (real BPLUS, wait green light — never as-node; ss7-sim nwid=1):
#   $0 load --scenario brook
#   $0 load-jmx --scenario brook
# Digicom map JSON: $LOAD_JSON_DIGICOM_LAB  (networkId=1, :8024→:8023, destPc=1470, RC=101)
# Lab as-node ramp only (not Digicom): $0 load --tps 100 --duration 60 --short-code '*804#' --digits 1
# Oracle: $ROOT/BROOK-SCENARIO.md

MAP_HOME=\$(…/jSS7/…/map/load)
# Digicom L3-LAB example (after green light):
mise exec zulu-25 -- java \\
  -Dss7.load.shortCode='*804#' \\
  -Dss7.load.digits=1 \\
  -Dss7.load.msisdn= \\
  -Dss7.load.msisdnPrefix=25191 \\
  -Dss7.load.origPc=2 -Dss7.load.destPc=1470 -Dss7.load.ussdSsn=8 \\
  -Dss7.load.ndialogs=30 -Dss7.load.rateLimit=1 \\
  -cp "\$MAP_HOME/target/map-load-9.2.8-j25.jar:\$(cat \$MAP_HOME/ant-classpath.txt)" \\
  org.restcomm.protocols.ss7.map.load.ussd.Client \\
  $LOAD_JSON_DIGICOM_LAB
EOF
    ;;
  help|*)
    cat <<EOF
Usage: $0 <http|cli|sim|build-cli|load|load-jmx|print-env|load-env|xml|path|help>

  cli [args…]  Interactive / one-shot USSD CLI (Java 25 → JMX → jSS7 sim)
  sim|core     Start jSS7 USSD_TEST_CLIENT core + RMI :$RMI_PORT (seeds lab XML)
  build-cli    Compile tools/ss7-simulator/cli → ussd-cli.jar + ussd-load.jar
  load […]     Brook load driver — TPS = MSISDN sessions/s (map/load default)
                 Digicom: --scenario brook  (*804# digit 1; ss7-sim nwid=1; smoke tps=1)
  load-jmx […] Sequential JMX smoke (1 concurrent dialog)
                 Digicom: --scenario brook  (wait green light; AS=real BPLUS, never as-node)
  http         Multi-user digit loop against as-node HTTP pull (no MAP)
  print-env    Print env / -D flags for jSS7 auto-digit + multi-MSISDN
  load-env     Print raw map/load Client -D example
  xml          Print path to lab USSD_TEST_CLIENT XML
  path         Resolve jSS7 tools/simulator directory

Lab XML: $LAB_XML
Config:  $CFG
CLI jar: $CLI_JAR
Load jar: $LOAD_JAR
Load JSON (default pair): $LOAD_JSON
Load JSON (pull-lab nwid=0): $LOAD_JSON_PULL
Load JSON (Digicom L3-LAB nwid=1): $LOAD_JSON_DIGICOM_LAB
Brook Digicom:            $ROOT/BROOK-SCENARIO.md  (+ config-brook.json)

Digicom Brook (real BPLUS — wait green light; ss7-sim networkId=1; live *804 stays nwid=0):
  $0 load --scenario brook
  $0 load-jmx --scenario brook

Lab PULL with as-node (:8013↔:8014) — not Digicom:
  1. ./dist/run.sh                          # ussdgw SS7 :8013
  2. cd tools/as-node && npm run pull:brook804
  3. $0 sim                                 # jSS7 core + RMI :$RMI_PORT (JMX only)
  4. $0 load --tps 100 --duration 60 --short-code '*804#' --digits 1

Dedicated PULL pair (:8023↔:8024) — prefer:
  ./tools/ss7-simulator/pull-lab.sh help

TPS definition: 100 TPS = 100 unique MSISDN MO sessions/second (not 100 TCAP msgs).
JMX concurrency ceiling = 1 dialog — see SPIKE-JMX-CONCURRENCY.md
EOF
    ;;
esac
