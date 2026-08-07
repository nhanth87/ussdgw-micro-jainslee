#!/usr/bin/env bash
# Launch helpers for Digicom USSDGW ↔ jSS7 USSD client lab + USSD CLI.
# Real MAP peer lives in jSS7 (coral-valley). This tree ships lab XML, CLI, load JSON.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
CFG="${CONFIG:-$ROOT/config.example.json}"
LAB_XML="$ROOT/data/ussdgw_lab_client.xml"
CLI_JAR="$ROOT/cli/ussd-cli.jar"
LOAD_JSON="$ROOT/ss7-ussd-client-ussdgw.json"

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
  if [[ ! -f "$CLI_JAR" ]]; then
    chmod +x "$ROOT/cli/build.sh"
    (cd "$ROOT/cli" && ./build.sh)
  fi
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
    # Canonical path — MainCore uses -DSIMULATOR_HOME + /data/<name>_simulator2.xml
    dist="$(readlink -f "$dist")"
    export SIMULATOR_HOME="$dist"
    # Force JDK 25 for the sim JVM (ignore ambient JAVA_HOME if it is not 25)
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
    # MainCore reads system property SIMULATOR_HOME (not env) → $SIMULATOR_HOME/data/<name>_simulator2.xml
    export JAVA_OPTS="${JAVA_OPTS:-} -DSIMULATOR_HOME=$dist -Dussd.sim.autoResponseSequence=$DIGITS -Dussd.sim.autoResponseDelayMs=$DELAY -Dussd.sim.msisdnList=$MSISDNS"
    echo "Starting sim core name=$SIM_NAME rmi=$RMI_PORT home=$dist JAVA_HOME=$JAVA_HOME"
    echo "Config XML: $dist/data/${SIM_NAME}_simulator2.xml"
    echo "Then: $0 cli"
    cd "$dist"
    exec bash bin/run.sh core -n "$SIM_NAME" -r "$RMI_PORT"
    ;;
  load-env)
    cat <<EOF
# MAP load Client against ussdgw lab (rebuild map/load after Client.java shortCode patch):
# Config: $LOAD_JSON
mise exec zulu-25 -- java \\
  -Dss7.load.shortCode='*100#' \\
  -Dss7.load.digits=1,2,3,4 \\
  -Dss7.load.msisdn=251911000001 \\
  -Dss7.load.origPc=2 -Dss7.load.destPc=1 -Dss7.load.ussdSsn=8 \\
  -Dss7.load.ndialogs=100 -Dss7.load.rateLimit=10 \\
  -cp '<map-load-classpath>' \\
  org.restcomm.protocols.ss7.map.load.ussd.Client \\
  $LOAD_JSON

# Or via ant (from jSS7 map/load) after aligning ports in ss7-ussd-client.json / this JSON.
# Interactive dial/DT: prefer '$0 cli' (JMX), not load Client.
EOF
    ;;
  help|*)
    cat <<EOF
Usage: $0 <http|cli|sim|build-cli|print-env|load-env|xml|path|help>

  cli [args…]  Interactive / one-shot USSD CLI (Java 25 → JMX → jSS7 sim)
  sim|core     Start jSS7 USSD_TEST_CLIENT core + RMI :$RMI_PORT (seeds lab XML)
  build-cli    Compile tools/ss7-simulator/cli → ussd-cli.jar
  http         Multi-user digit loop against as-node HTTP pull (no MAP)
  print-env    Print env / -D flags for jSS7 auto-digit + multi-MSISDN
  load-env     Print map/load Client -D example for ussdgw lab
  xml          Print path to lab USSD_TEST_CLIENT XML
  path         Resolve jSS7 tools/simulator directory

Lab XML: $LAB_XML
Config:  $CFG
CLI jar: $CLI_JAR
Load JSON: $LOAD_JSON

Functional PULL (interactive DT):
  1. ./dist/run.sh                          # ussdgw SS7 :8013
  2. cd tools/as-node && npm run pull:fast
  3. $0 sim                                 # jSS7 core + RMI :$RMI_PORT
  4. $0 cli                                 # REPL
       ussd> connect
       ussd> dial *100#
       ussd> dt 1
       ussd> dial *519812345678901234#

One-shot:
  $0 cli dial '*100#' --msisdn 251911000001 --dt 1,2,3
  $0 cli dial '*100*1234567890#' --manual

Short vs long / Mark routing:
  Exact short code *100#  → non-mark rule
  Mark prefix *100*       → *100*1234567890# (longest mark prefix)
  Long mark-style keys    → e.g. *5198…# as configured in /admin/routing
EOF
    ;;
esac
