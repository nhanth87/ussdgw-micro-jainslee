#!/usr/bin/env bash
# Container entrypoint: fail fast on every condition that would otherwise show up
# as a confusing runtime error on the carrier host.
#
# Each check exists because its absence produced a specific, hard-to-diagnose
# failure documented in docs/agents/lessons.md:
#   - no sctp kernel module   → jdk.sctp: "Protocol not supported"
#   - H2-baked jar on PG      → Flyway "Driver does not support jdbc:postgresql"
#   - SS7 link channel tcp    → violates the SCTP-only mandate, silently broken SS7
#   - missing DB password     → boots, then every query fails
#   - PG not up yet           → no depends_on in swarm; races the database
set -euo pipefail

APP_HOME="${APP_HOME:-/opt/ussdgw}"
CONFIG_DIR="${CONFIG_DIR:-$APP_HOME/configs}"
PG_HOST="${PG_HOST:-127.0.0.1}"
PG_PORT="${PG_PORT:-5432}"
log() { echo "[entrypoint] $*"; }
die() { echo "[entrypoint] FATAL: $*" >&2; exit 1; }

# --- 1. host kernel SCTP ------------------------------------------------------
# The container cannot load kernel modules, so this MUST be prepared on the host
# (docker/host-prep.sh). Checking /proc/net/sctp is the honest test: if the host
# module is absent the file does not exist.
if [[ ! -e /proc/net/sctp ]]; then
  die "host kernel has no sctp module — run 'sudo docker/host-prep.sh' (modprobe sctp;
       a container cannot load kernel modules)."
fi
log "host SCTP available: $(awk 'END{print NR-1" endpoint(s)"}' /proc/net/sctp/eps 2>/dev/null || echo present)"

# --- 2. the artifact really is PG-baked ---------------------------------------
baked="$(cat "$APP_HOME/.baked-db-kind" 2>/dev/null || echo missing)"
[[ "$baked" == "postgresql" ]] \
  || die "image is baked '$baked', expected 'postgresql'. Shipping an H2 bake to a PG host is a
       crash-loop (build-time db-kind is fixed in the fast-jar and cannot be overridden)."

# --- 3. configs present and consistent ----------------------------------------
[[ -f "$CONFIG_DIR/application.properties" ]] \
  || die "no $CONFIG_DIR/application.properties — is the operator configs mount in place?"
cfg="$CONFIG_DIR/application.properties"

cfg_val() { grep -E "^$1=" "$cfg" 2>/dev/null | head -1 | cut -d= -f2- | tr -d ' '; }

cfg_kind="$(cfg_val 'quarkus.datasource.db-kind')"
[[ "$cfg_kind" == "postgresql" ]] \
  || die "config db-kind='$cfg_kind' does not match the PG-baked image."

cfg_url="$(cfg_val 'quarkus.datasource.jdbc.url')"
case "$cfg_url" in
  jdbc:postgresql://*) log "JDBC: $cfg_url" ;;
  *) die "JDBC url is '$cfg_url', expected jdbc:postgresql://..." ;;
esac

# --- 4. SS7 transport must be SCTP (never TCP) --------------------------------
# A tcp channel in the stack JSON looks like it works and silently breaks M3UA.
ss7_cfg_name="$(cfg_val 'ussd.map.config-file')"
ss7_cfg_path="$CONFIG_DIR/$(basename "${ss7_cfg_name:-ss7-lab.json}")"
[[ -f "$ss7_cfg_path" ]] || ss7_cfg_path="$(ls "$CONFIG_DIR"/ss7-*.json 2>/dev/null | head -1)"
if [[ -n "$ss7_cfg_path" && -f "$ss7_cfg_path" ]]; then
  bad_channels="$(grep -oE '"channel"[[:space:]]*:[[:space:]]*"[a-zA-Z]+"' "$ss7_cfg_path" \
                  | grep -viE '"(sctp)"' | sort -u || true)"
  [[ -z "$bad_channels" ]] \
    || die "non-SCTP SS7 channel found in $(basename "$ss7_cfg_path"):
       $bad_channels
       SS7 is SCTP-only (RFC 4666 §3). Fix the stack JSON."
  log "SS7 stack $(basename "$ss7_cfg_path"): all links use channel=sctp ✓"
fi

# --- 5. secrets ---------------------------------------------------------------
# Password comes from a swarm secret file, never from the stack YAML (visible to
# `docker service inspect` and in shell history).
SECRET_PW_FILE="${USSD_DB_PASSWORD_FILE:-/run/secrets/ussdgw_db_password}"
if [[ -r "$SECRET_PW_FILE" ]]; then
  QUARKUS_DATASOURCE_PASSWORD="$(cat "$SECRET_PW_FILE")"
  export QUARKUS_DATASOURCE_PASSWORD
  log "database password loaded from $SECRET_PW_FILE"
elif [[ -n "${QUARKUS_DATASOURCE_PASSWORD:-}" ]]; then
  log "database password from environment"
else
  die "no database password — mount the swarm secret 'ussdgw_db_password' at
       $SECRET_PW_FILE or set QUARKUS_DATASOURCE_PASSWORD."
fi

# --- 6. wait for PostgreSQL ---------------------------------------------------
# Swarm has no depends_on, so the app can start first. Bounded retry: fail loudly
# rather than crash-looping silently against a database that never appears.
waited=0
until (exec 3<>"/dev/tcp/$PG_HOST/$PG_PORT") 2>/dev/null; do
  waited=$((waited + 1))
  if (( waited > 60 )); then
    die "PostgreSQL not reachable at $PG_HOST:$PG_PORT after ${waited}0s — is the
       postgres service healthy? (check: docker service ps postgres)"
  fi
  (( waited % 10 == 0 )) && log "waiting for PostgreSQL at $PG_HOST:$PG_PORT … ${waited}s"
  sleep 10
done
exec 3>&- 2>/dev/null || true
log "PostgreSQL reachable at $PG_HOST:$PG_PORT (after ${waited}0s)"

# --- 7. writable runtime dirs -------------------------------------------------
mkdir -p "$CONFIG_DIR/ss7-persist" "$USSD_LOG_DIR" "$APP_HOME/data"
for d in "$CONFIG_DIR" "$USSD_LOG_DIR" "$APP_HOME/data"; do
  [[ -w "$d" ]] || die "$d is not writable — the mount must be rw (the admin UI saves
       SS7 stack JSON back into configs/, see AdminPlaneHandler.saveStackJson)."
done

# --- 8. go ---------------------------------------------------------------------
# Heap: default to the lab-safe values from AGENTS.md (a shared SS7 host cannot give
# this container 8 GB; see the resource-hygiene rule). Override via env on purpose.
export USSD_XMS="${USSD_XMS:-2g}"
export USSD_XMX="${USSD_XMX:-4g}"
export USSD_LOG_DIR

log "heap: -Xms$USSD_XMS -Xmx$USSD_XMX   log dir: $USSD_LOG_DIR"
log "starting gateway (config: $cfg)"
exec "$APP_HOME/run.sh"