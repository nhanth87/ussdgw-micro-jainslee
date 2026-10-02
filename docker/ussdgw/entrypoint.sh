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

  # --- 4b. WHICH SCTP implementation will bind the sockets ----------------------
  # The check above proves the links are not TCP. It says nothing about whether anything
  # will listen, and that is the half that shipped broken.
  #
  # sctp.backend picks the implementation, and SctpBackend.from(null) returns FSTACK_DPDK —
  # a userspace DPDK stack that needs hugepages plus a native libsctp_fstack.so. A stack
  # JSON with no "backend" key therefore boots cleanly and logs
  #
  #     [ss7-config] SCTP server L1-BP-1404-srv listening 172.16.144.163:2011
  #     SS7 boot: ss7=wired;sctp=[L1-BP-1404:server:172.16.144.163:2011←10.177.55.241:2501,…]
  #
  # while creating ZERO kernel sockets. /proc/net/sctp/eps stays empty, `ss -ln --sctp`
  # shows nothing, the carrier peer can never reach 2011/2019, and status.json honestly
  # reports ss7.live=false — every log line says the wire is up and it is not. The sibling
  # gmlc deployment on this same host, same link names, same ports, has
  # "backend": "NETTY_KERNEL" in its copy of this file and is the one that holds the
  # associations. "channel is sctp" was true in both; only the backend differed.
  ss7_backend="$(grep -oE '"backend"[[:space:]]*:[[:space:]]*"[A-Za-z_-]+"' "$ss7_cfg_path" \
                 | head -1 | sed -E 's/.*"([A-Za-z_-]+)"[[:space:]]*$/\1/')"
  ss7_backend_norm="$(tr '[:lower:]-' '[:upper:]_' <<<"${ss7_backend:-FSTACK_DPDK}")"
  case "$ss7_backend_norm" in
    NETTY_KERNEL)
      # Kernel SCTP: the host module is step 1; jdk.sctp is com.sun.nio.sctp, and this JRE
      # is jlink'd, so a module list that forgot it yields an image that cannot bind a
      # single socket no matter what the JSON says.
      jmods="$("$JAVA_HOME/bin/java" --list-modules 2>/dev/null || true)"
      grep -q '^jdk\.sctp@' <<<"$jmods" \
        || die "sctp.backend=NETTY_KERNEL but this JRE has no jdk.sctp module — add it to the
           jlink module list in docker/ussdgw/Dockerfile. Without it every SCTP bind fails."
      log "SS7 SCTP backend = NETTY_KERNEL (kernel sockets; visible in /proc/net/sctp) ✓"
      ;;
    FSTACK_DPDK)
      # Legitimate only with the native library actually present. Otherwise this is the
      # deaf-SS7 case above, and refusing to boot beats logging "listening" for nothing.
      fstack_lib="$(grep -oE '"library"[[:space:]]*:[[:space:]]*"[^"]+"' "$ss7_cfg_path" \
                    | head -1 | sed -E 's/.*"([^"]+)"[[:space:]]*$/\1/')"
      if [[ -z "$fstack_lib" || ! -e "$fstack_lib" ]]; then
        die "sctp.backend resolves to FSTACK_DPDK ($(basename "$ss7_cfg_path") has
           backend='${ss7_backend:-<absent>}'; SctpBackend.from(null) = FSTACK_DPDK) but the
           native library is not here: sctp.library='${fstack_lib:-unset}'.
           The gateway would log 'listening' on every link, report ss7=wired, and bind no
           socket at all — a deaf SS7 plane that looks healthy.
           Fix: add  \"backend\": \"NETTY_KERNEL\"  to the sctp block (kernel SCTP — what the
           gmlc deployment on these same links uses), or mount the fstack native library and
           set sctp.library to its path."
      fi
      log "SS7 SCTP backend = FSTACK_DPDK (userspace; library $fstack_lib)"
      ;;
    *)
      die "unknown sctp.backend '${ss7_backend}' in $(basename "$ss7_cfg_path") — expected NETTY_KERNEL or FSTACK_DPDK"
      ;;
  esac
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
# Close only the probe fd. This line used to be `exec 3>&- 2>/dev/null || true`, and
# `exec 2>/dev/null` is PERMANENT: from that point on every stderr write in this
# script went to /dev/null, which included
#
#   * the `die` in step 7 for an unwritable configs/logs/data mount,
#   * every `log` after it, and
#   * the JVM's own stderr, because run.sh inherits the muted descriptor.
#
# So the checks whose entire purpose is to say WHY the container refused to start
# were the only thing that could not say anything. The symptom on a real host is the
# worst shape a failure can take: the container exits 1 with a blank reason, and
# `restart_policy: max_attempts: 5` then retires the task — `docker stack services`
# still reports the service, :8088 never binds, and `docker logs` has nothing.
exec 3>&- || true
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