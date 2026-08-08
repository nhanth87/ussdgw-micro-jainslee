#!/usr/bin/env bash
# Local lab: dedicated SCTP/SCCP pair (8023↔8024) + classic USSD pull via jSS7-sim CLI.
# Does NOT touch Digicom carrier configs.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$ROOT/../.." && pwd)"
DIST="${USSD_DIST_DIR:-$APP/dist}"
CFG_PULL="$ROOT/config-pull.json"
SQL="$ROOT/seed-ussd-pull.sql"
SS7_PULL="$APP/build/ss7-lab-sim-pull.json"

die() { echo "error: $*" >&2; exit 1; }

cmd="${1:-help}"
shift || true

case "$cmd" in
  apply-ss7)
    # Point dist configs at the pull-lab stack (does not clobber Digicom host).
    [[ -f "$SS7_PULL" ]] || die "missing $SS7_PULL"
    mkdir -p "$DIST/configs"
    cp -a "$SS7_PULL" "$DIST/configs/ss7-lab-sim-pull.json"
    props="$DIST/configs/application.properties"
    [[ -f "$props" ]] || die "missing $props — run ./build/package-dist.sh first"
    if grep -q '^ussd.map.config-file=' "$props"; then
      sed -i 's|^ussd.map.config-file=.*|ussd.map.config-file=configs/ss7-lab-sim-pull.json|' "$props"
    else
      echo 'ussd.map.config-file=configs/ss7-lab-sim-pull.json' >> "$props"
    fi
    echo "Applied pull-lab SS7 → $DIST/configs/ss7-lab-sim-pull.json"
    grep -E '^ussd.map.config-file=' "$props"
    echo "Restart GW: $DIST/run.sh   then prove: ss -ln --sctp | grep 8023"
    ;;

  reseed-pull)
    # Re-seed classic MO pull short-codes → as-node :8090/ussd/pull (local H2 default).
    H2_URL="${USSD_H2_URL:-jdbc:h2:file:./data/ussdgw;MODE=PostgreSQL;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1}"
    if command -v psql >/dev/null 2>&1 && [[ -n "${USSD_PG_URL:-}" ]]; then
      echo "Seeding via psql $USSD_PG_URL"
      psql "$USSD_PG_URL" -f "$SQL"
    elif [[ -f "$DIST/lib/main/"*h2*.jar ]] || ls "$DIST"/lib/main/*h2*.jar >/dev/null 2>&1; then
      echo "Apply SQL via admin UI Routing, or:"
      echo "  # with H2 Console / your JDBC tool against $H2_URL"
      echo "  # file: $SQL"
      cat "$SQL"
    else
      echo "SQL ready at $SQL"
      echo "Apply against local DB (H2 file ./data/ussdgw or lab PG). Digicom: ask operator first."
      cat "$SQL"
    fi
    ;;

  sim)
    LAB_XML="$ROOT/data/ussdgw_lab_pull_client.xml" \
      CONFIG="$CFG_PULL" \
      FORCE_LAB_XML=1 \
      "$ROOT/run.sh" sim "$@"
    ;;

  cli)
    CONFIG="$CFG_PULL" "$ROOT/run.sh" cli "$@"
    ;;

  dial-pull)
    # One-shot classic pull prove
    code="${1:-*100#}"
    msisdn="${2:-251911000001}"
    CONFIG="$CFG_PULL" "$ROOT/run.sh" cli dial "$code" --msisdn "$msisdn" --dt 1,2,3
    ;;

  help|*)
    cat <<EOF
Usage: $0 <apply-ss7|reseed-pull|sim|cli|dial-pull|help>

Dedicated pull lab SCTP/SCCP (does not use :8013/:8014):
  GW  SCTP server 127.0.0.1:8023  PC=1  services SSN 8+147+6
  sim SCTP client 127.0.0.1:8024  PC=2  SSN 8 → remote GW SSN 8

Functional USSD pull (classic ussdgateway MO → AS):
  1. ./build/package-dist.sh && $0 apply-ss7
  2. $DIST/run.sh                         # listen :8023
  3. cd tools/as-node && npm run pull:fast # :8090/ussd/pull
  4. $0 reseed-pull                       # *100# *123# *101 → as-node
  5. $0 sim                               # jSS7 USSD_TEST_CLIENT + RMI
  6. $0 cli                               # or: $0 dial-pull '*100#'
       ussd> connect
       ussd> dial *100#
       ussd> dt 1
       ussd> dial *101123456#

Files:
  $SS7_PULL
  $ROOT/data/ussdgw_lab_pull_client.xml
  $ROOT/ss7-ussd-client-ussdgw-pull.json
  $CFG_PULL
  $SQL
EOF
    ;;
esac
