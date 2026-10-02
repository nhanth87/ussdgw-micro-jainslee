#!/usr/bin/env bash
# Host preparation. Run this as root ON THE NODE, once, before the first deploy.
#
# Everything here CANNOT be done from a container:
#   - loading the sctp kernel module
#   - the net.core.* / net.sctp.* sysctls (net.core is not namespaced)
#   - creating the persistent directories with the right ownership
#
# A container cannot modprobe or change these sysctls, so skipping this script means
# the gateway boots and then fails at the first SCTP socket with "Protocol not
# supported" — a long, expensive debug on the carrier host.
set -euo pipefail

DATA_ROOT="${DATA_ROOT:-/srv/ussdgw}"
SCTP_BUFFER_CONF="/etc/sysctl.d/99-ussdgw-sctp-buffers.conf"

[ "$(id -u)" -eq 0 ] || { echo "host-prep: must run as root (sudo)"; exit 1; }
ok()  { echo "host-prep: ok   $*"; }
warn(){ echo "host-prep: WARN $*" >&2; }
die() { echo "host-prep: ERROR $*" >&2; exit 1; }

# --- 1. kernel SCTP ------------------------------------------------------------
if modprobe sctp 2>/dev/null; then
  ok "loaded sctp module"
else
  warn "modprobe sctp failed — is the module available for this kernel?"
  die "without the sctp module jdk.sctp cannot open a socket at all"
fi
# Persist across reboots. Without this the module vanishes on every restart and the
# gateway only fails after the node comes back.
if [[ ! -f /etc/modules-load.d/sctp.conf ]]; then
  echo sctp > /etc/modules-load.d/sctp.conf
  ok "persisted via /etc/modules-load.d/sctp.conf"
else
  ok "/etc/modules-load.d/sctp.conf already present"
fi
[[ -e /proc/net/sctp ]] && ok "/proc/net/sctp present" || die "/proc/net/sctp missing after modprobe"

# --- 2. SCTP socket buffers ----------------------------------------------------
# A shared Digicom-class host defaults to rmem_max=212992, which caps the SCTP
# receive window at roughly 104 KiB and throttles every MAP dialog. These are the
# values from build/systemd/99-ussdgw-sctp-buffers.conf.
# NOT a measured throughput claim — just removing an artificial cap.
cat > "$SCTP_BUFFER_CONF" <<'EOF'
# Digicom-ET USSDGW — SCTP buffer headroom.
# A shared host defaults to 212992, capping a_rwnd at ~104 KiB.
net.core.rmem_max = 67108864
net.core.wmem_max = 67108864
net.core.rmem_default = 4194304
net.core.wmem_default = 4194304
net.sctp.sctp_rmem = 4096 4194304 67108864
net.sctp.sctp_wmem = 4096 4194304 67108864
EOF
if sysctl --system >/dev/null 2>&1; then
  ok "applied $SCTP_BUFFER_CONF"
  sysctl net.core.rmem_max net.sctp.sctp_rmem 2>/dev/null | sed 's/^/host-prep:      /'
else
  warn "sysctl --system failed — apply manually: sysctl -p $SCTP_BUFFER_CONF"
fi

# --- 3. persistent directories --------------------------------------------------
# uid 10001 matches the ussdgw image, so files stay writable across deploys.
ok "creating $DATA_ROOT (uid 10001)"
mkdir -p "$DATA_ROOT"/{configs,configs/ss7-persist,logs,data,pgdata,nginx/certs,backup}
chown -R 10001:10001 "$DATA_ROOT"/{configs,logs,data}
chown -R 999:999 "$DATA_ROOT"/pgdata        # postgres uid in the official image
chmod 775 "$DATA_ROOT"/{configs,logs,data}
# The admin UI saves SS7 stack JSON back into configs/ — it must stay writable.
chmod 775 "$DATA_ROOT/configs" "$DATA_ROOT/configs/ss7-persist"
# pgdata is NOT 775. It holds the database, and the official entrypoint sets the real
# PGDATA ($DATA_ROOT/pgdata/pgdata) to 0700 itself — initdb refuses a data directory
# with group or world access. A loose parent does not break initdb, but it does let any
# local user create files inside the directory tree that holds the USSD database, and
# "775 because the neighbours are 775" is how a directory ends up world-writable.
chmod 700 "$DATA_ROOT"/pgdata
ok "directories ready; configs/ is rw (the admin UI writes stack JSON), pgdata is 700"

# nginx/certs belongs to a DIFFERENT uid than the rest of the tree: the edge proxy
# runs as 101 (nginx in the official image), not 10001. This directory used to be
# created by mkdir and left root:root 755, which looks fine and works only if the
# operator happens to remember `install -o 101`. Forget it and the stack deploys,
# the task fails, restart_policy retires it after 5 attempts, and nginx logs:
#
#     [emerg] cannot load certificate key "/etc/nginx/certs/privkey.pem":
#             BIO_new_file() failed (SSL: …Permission denied)
#
# which is silent at the stack level: `docker stack services` still lists nginx and
# :80 simply never binds. Owned here so the correct setup is the default one.
NGINX_UID=101
NGINX_GID=101
chown "$NGINX_UID:$NGINX_GID" "$DATA_ROOT"/nginx "$DATA_ROOT"/nginx/certs
chmod 750 "$DATA_ROOT"/nginx "$DATA_ROOT"/nginx/certs
ok "nginx/certs owned by $NGINX_UID:$NGINX_GID mode 750 (the proxy user, not the app user)"

# --- 4. time ---------------------------------------------------------------------
# MAP timers, the AdaptiveTimeout gate and CDR timestamps all assume a sane clock.
if command -v timedatectl >/dev/null 2>&1; then
  timedatectl set-ntp true 2>/dev/null && ok "NTP enabled" || warn "could not enable NTP"
  timedatectl show -p NTPSynchronized --value 2>/dev/null | grep -qx yes \
    && ok "clock is NTP-synchronised" || warn "clock NOT synchronised — MAP timers will drift"
else
  warn "timedatectl not found; ensure chrony or systemd-timesyncd is running"
fi

# --- 5. Docker --------------------------------------------------------------------
command -v docker >/dev/null || die "docker not installed"
docker_ver="$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo 0)"
major="${docker_ver%%.*}"
[[ "$major" =~ ^[0-9]+$ ]] && (( major >= 24 )) \
  && ok "Docker Engine $docker_ver (>= 24 required for swarm ulimits)" \
  || die "Docker Engine $docker_ver is too old; swarm ulimits and cap_add need >= 24"

if [[ "$(docker info --format '{{.Swarm.LocalNodeState}}' 2>/dev/null)" == "active" ]]; then
  ok "swarm active"
else
  warn "swarm inactive — run: docker swarm init --advertise-addr <ip>"
fi

# The gateway is pinned to the SS7 node via a label so it cannot drift onto a node
# whose IP the carrier peers do not whitelist.
node_label="$(docker node inspect --format '{{index .Spec.Labels "ussdgw"}}' "self" 2>/dev/null || echo "")"
if [[ "$node_label" == "true" ]]; then
  ok "node labelled ussdgw=true"
else
  warn "node is NOT labelled ussdgw=true — add it: docker node update --label-add ussdgw=true \$(hostname)"
fi

# --- 6. firewall hints (not applied automatically) -------------------------------
cat <<'EOF'

host-prep: firewall (apply deliberately — do NOT run blind)
  ALLOW  sctp  to/from the STP / MSC / HLR peer addresses only
  ALLOW  tcp   80, 443  from the management + AS networks
  DENY   tcp   8088    from outside loopback  (app port behind nginx)
  DENY   tcp   5432    from outside loopback  (postgres)
  DENY   tcp   9099, 2775, 2776, 3868, 5060 from outside loopback
  SS7 must never be reachable from the open internet.
EOF

echo
echo "host-prep: done. Next:"
echo "  1. CONFIG_SRC=/path/to/configs ./docker/install-config.sh --check"
echo "  2. CONFIG_SRC=/path/to/configs ./docker/install-config.sh"
echo "  3. cp docker/.env.example docker/.env && edit"
echo "  4. printf '<db-password>' | docker secret create ussdgw_db_password -"
echo "  5. docker stack deploy -c docker/stack.yml ussdgw"