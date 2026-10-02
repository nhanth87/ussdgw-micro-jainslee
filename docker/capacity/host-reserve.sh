#!/usr/bin/env bash
# host-reserve.sh — reserve host resources for the Digicom-ET USSDGW Docker deployment (5k TPS plan).
#
# Target host: 128 GiB RAM / 32 logical CPUs. Split (capacity_5k_tps.md §1):
#   ussdgw   16 CPUs  64 GiB   (first 16 CPUs sorted by NUMA node, core → siblings stay together)
#   postgres 12 CPUs  32 GiB
#   OS        4 CPUs  rest     (nginx, dockerd, IRQs, page cache)
#
# Dry-run by default: prints the layout and every action. --apply changes the host.
#
# Usage:
#   sudo docker/capacity/host-reserve.sh            # dry-run
#   sudo docker/capacity/host-reserve.sh --apply    # apply + write /etc/ussdgw/capacity.env
#
# Env overrides:
#   USSDGW_CPUS=16 PG_CPUS=12   CPU counts
#   USSDGW_MEM=64g PG_MEM=32g   container memory limits (written to capacity.env)
#   DATA_ROOT=/srv/ussdgw        persistent bind-mount root (matches plan.md)
#   APP_UID=10001                uid of the ussdgw container user
#   MIN_CPUS=32 MIN_MEM_GIB=120  host floor (refuse below unless FORCE=1)
set -euo pipefail

APPLY=0
for a in "$@"; do
  case "$a" in
    --apply) APPLY=1 ;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown arg: $a" >&2; exit 2 ;;
  esac
done

USSDGW_CPUS="${USSDGW_CPUS:-16}"
PG_CPUS="${PG_CPUS:-12}"
USSDGW_MEM="${USSDGW_MEM:-64g}"
PG_MEM="${PG_MEM:-32g}"
DATA_ROOT="${DATA_ROOT:-/srv/ussdgw}"
APP_UID="${APP_UID:-10001}"
MIN_CPUS="${MIN_CPUS:-32}"
MIN_MEM_GIB="${MIN_MEM_GIB:-120}"
CAP_ENV_DIR=/etc/ussdgw
CAP_ENV="${CAP_ENV_DIR}/capacity.env"
SYSCTL_FILE=/etc/sysctl.d/98-ussdgw-capacity.conf
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SCTP_BUF_CONF="${REPO_ROOT}/build/systemd/99-ussdgw-sctp-buffers.conf"

log()  { printf '[host-reserve] %s\n' "$*"; }
warn() { printf '[host-reserve] WARN: %s\n' "$*" >&2; }
die()  { printf '[host-reserve] ERROR: %s\n' "$*" >&2; exit 1; }
run()  {
  if [[ "${APPLY}" -eq 1 ]]; then
    log "+ $*"
    "$@"
  else
    log "(dry-run) $*"
  fi
}
write_file() { # path, content
  local path="$1" content="$2"
  if [[ "${APPLY}" -eq 1 ]]; then
    mkdir -p "$(dirname "${path}")"
    printf '%s\n' "${content}" > "${path}"
    log "wrote ${path}"
  else
    log "(dry-run) would write ${path}:"
    printf '%s\n' "${content}" | sed 's/^/    | /'
  fi
}

[[ "${APPLY}" -eq 0 || "$(id -u)" -eq 0 ]] || die "--apply needs root"

# ── 1. Host checks ─────────────────────────────────────────────────────────────
command -v lscpu >/dev/null || die "lscpu not found"
NCPU="$(nproc --all)"
MEM_GIB="$(awk '/MemTotal/ {printf "%d", $2/1024/1024}' /proc/meminfo)"
log "host: ${NCPU} logical CPUs, ${MEM_GIB} GiB RAM, kernel $(uname -r)"
if (( NCPU < MIN_CPUS || MEM_GIB < MIN_MEM_GIB )); then
  if [[ "${FORCE:-0}" != "1" ]]; then
    die "host below plan floor (${MIN_CPUS} CPU / ${MIN_MEM_GIB} GiB). FORCE=1 to continue (lab only)."
  fi
  warn "host below plan floor — continuing because FORCE=1 (not a 5k-capable host)"
fi
(( USSDGW_CPUS + PG_CPUS < NCPU )) || die "USSDGW_CPUS+PG_CPUS must leave ≥1 CPU for the OS"

if [[ -f /sys/fs/cgroup/cgroup.controllers ]]; then
  log "cgroup v2: OK ($(tr '\n' ' ' < /sys/fs/cgroup/cgroup.controllers))"
else
  warn "cgroup v1 detected — cpuset pinning via docker update still works, but v2 is recommended"
fi

if command -v docker >/dev/null; then
  DOCKER_VER="$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo 0)"
  DOCKER_MAJOR="${DOCKER_VER%%.*}"
  if [[ "${DOCKER_MAJOR}" =~ ^[0-9]+$ ]] && (( DOCKER_MAJOR >= 24 )); then
    log "docker ${DOCKER_VER}: OK"
  else
    warn "docker ${DOCKER_VER}: need Engine ≥ 24 (swarm ulimits/cap_add)"
  fi
else
  warn "docker not installed yet"
fi

# ── 2. CPU layout: sort CPUs by NUMA node, core, cpu → ussdgw | postgres | os ────
mapfile -t ORDERED < <(lscpu -p=CPU,NODE,CORE | grep -v '^#' \
  | awk -F, '{ node = ($2 == "" ? 0 : $2); printf "%d %d %d\n", node, $3, $1 }' \
  | sort -n -k1,1 -k2,2 -k3,3 | awk '{print $3}')

join_list() { local IFS=,; echo "$*"; }
USSDGW_SET=("${ORDERED[@]:0:${USSDGW_CPUS}}")
PG_SET=("${ORDERED[@]:${USSDGW_CPUS}:${PG_CPUS}}")
OS_SET=("${ORDERED[@]:$((USSDGW_CPUS + PG_CPUS))}")
USSDGW_CPUSET="$(join_list "${USSDGW_SET[@]}")"
PG_CPUSET="$(join_list "${PG_SET[@]}")"
OS_CPUSET="$(join_list "${OS_SET[@]}")"

nodes_of() { # cpu list → comma NUMA node list
  local cpu out=()
  for cpu in "$@"; do
    out+=("$(lscpu -p=CPU,NODE | grep -v '^#' | awk -F, -v c="${cpu}" '$1==c {print ($2==""?0:$2)}')")
  done
  printf '%s\n' "${out[@]}" | sort -un | paste -sd, -
}
USSDGW_MEMS="$(nodes_of "${USSDGW_SET[@]}")"
PG_MEMS="$(nodes_of "${PG_SET[@]}")"

log "layout:"
log "  ussdgw   cpus=${USSDGW_CPUSET}  mems=${USSDGW_MEMS}  mem=${USSDGW_MEM}"
log "  postgres cpus=${PG_CPUSET}  mems=${PG_MEMS}  mem=${PG_MEM}"
log "  os       cpus=${OS_CPUSET}"
if [[ "${USSDGW_MEMS}" == *,* ]]; then
  warn "ussdgw spans NUMA nodes ${USSDGW_MEMS} — acceptable, but one node is preferred"
fi

# ── 3. Kernel: sctp module + sysctl ───────────────────────────────────────────
if [[ -d /proc/sys/net/sctp ]]; then
  log "sctp module: loaded"
else
  run modprobe sctp
fi
write_file /etc/modules-load.d/sctp.conf "sctp"

SYSCTL_CONTENT="# Digicom-ET USSDGW 5k TPS capacity (capacity_5k_tps.md §6.5) — managed by host-reserve.sh
vm.max_map_count = 1048576
vm.swappiness = 1
net.core.somaxconn = 65535
net.core.netdev_max_backlog = 250000
net.ipv4.ip_local_port_range = 10240 65000
net.ipv4.tcp_tw_reuse = 1
fs.file-max = 4194304
fs.nr_open = 4194304"
write_file "${SYSCTL_FILE}" "${SYSCTL_CONTENT}"
if [[ -f "${SCTP_BUF_CONF}" ]]; then
  run install -m 0644 "${SCTP_BUF_CONF}" /etc/sysctl.d/99-ussdgw-sctp-buffers.conf
else
  warn "missing ${SCTP_BUF_CONF} — SCTP buffer sysctl not installed"
fi
run sysctl --system

# ── 4. Transparent huge pages = madvise (JVM -XX:+UseTransparentHugePages) ─────
THP_UNIT="[Unit]
Description=USSDGW: THP madvise for JVM UseTransparentHugePages
After=sysinit.target
[Service]
Type=oneshot
ExecStart=/bin/sh -c 'echo madvise > /sys/kernel/mm/transparent_hugepage/enabled; echo madvise > /sys/kernel/mm/transparent_hugepage/defrag'
RemainAfterExit=yes
[Install]
WantedBy=multi-user.target"
write_file /etc/systemd/system/ussdgw-thp.service "${THP_UNIT}"
run systemctl daemon-reload
run systemctl enable --now ussdgw-thp.service

# ── 5. Keep NIC IRQs off the ussdgw / postgres cores ─────────────────────────
if [[ -f /etc/default/irqbalance ]] || command -v irqbalance >/dev/null; then
  BANNED="$(join_list "${USSDGW_SET[@]}" "${PG_SET[@]}")"
  if [[ "${APPLY}" -eq 1 ]]; then
    touch /etc/default/irqbalance
    sed -i '/^IRQBALANCE_BANNED_CPULIST=/d' /etc/default/irqbalance
    echo "IRQBALANCE_BANNED_CPULIST=${BANNED}" >> /etc/default/irqbalance
    log "irqbalance: banned ${BANNED}"
  else
    log "(dry-run) irqbalance IRQBALANCE_BANNED_CPULIST=${BANNED}"
  fi
  run systemctl restart irqbalance || warn "irqbalance restart failed (service missing?)"
else
  warn "irqbalance not installed — NIC IRQs may land on ussdgw cores"
fi

# ── 6. Persistent dirs (plan.md bind mounts) ──────────────────────────────────
for d in configs logs data data/offheap dumps capacity; do
  run mkdir -p "${DATA_ROOT}/${d}"
done
run chown -R "${APP_UID}:${APP_UID}" "${DATA_ROOT}/logs" "${DATA_ROOT}/data" "${DATA_ROOT}/dumps"
run mkdir -p "${DATA_ROOT}/pgdata"
# Swarm bind sources must be absolute host paths: stage the PG tuning file outside the repo.
run install -m 0644 "${SCRIPT_DIR}/postgresql-5k.conf" "${DATA_ROOT}/capacity/postgresql-5k.conf"
# The systemd pin service must not depend on where the repo checkout lives.
run install -m 0755 "${SCRIPT_DIR}/pin-cpusets.sh" /usr/local/sbin/ussdgw-pin-cpusets

# ── 7. capacity.env consumed by stack.capacity-5k.yml / pin-cpusets.sh ─────────
CAP_CONTENT="# Generated by docker/capacity/host-reserve.sh on $(hostname) — do not edit by hand.
USSDGW_CPUSET=${USSDGW_CPUSET}
USSDGW_MEMS=${USSDGW_MEMS}
USSDGW_CPUS=${USSDGW_CPUS}
USSDGW_MEM=${USSDGW_MEM}
PG_CPUSET=${PG_CPUSET}
PG_MEMS=${PG_MEMS}
PG_CPUS=${PG_CPUS}
PG_MEM=${PG_MEM}
OS_CPUSET=${OS_CPUSET}
DATA_ROOT=${DATA_ROOT}"
write_file "${CAP_ENV}" "${CAP_CONTENT}"

PIN_UNIT="[Unit]
Description=USSDGW: pin swarm task containers to reserved cpusets
After=docker.service
Requires=docker.service
[Service]
ExecStart=/usr/local/sbin/ussdgw-pin-cpusets --watch
Restart=always
RestartSec=5
[Install]
WantedBy=multi-user.target"
write_file /etc/systemd/system/ussdgw-pin-cpusets.service "${PIN_UNIT}"
run systemctl daemon-reload
run systemctl enable --now ussdgw-pin-cpusets.service

log "done$([[ "${APPLY}" -eq 1 ]] || echo ' (dry-run — re-run with --apply)')."
log "next: docker stack deploy -c docker/stack.yml -c docker/capacity/stack.capacity-5k.yml ussdgw"
