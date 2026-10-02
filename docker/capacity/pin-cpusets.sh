#!/usr/bin/env bash
# pin-cpusets.sh — apply reserved cpusets to running swarm task containers.
#
# Docker Swarm services cannot declare cpuset-cpus/cpuset-mems, so this pins each task container
# after it starts via `docker update` (capacity_5k_tps.md §7). Layout comes from
# /etc/ussdgw/capacity.env written by host-reserve.sh.
#
# Usage:
#   pin-cpusets.sh            # pin once (all running ussdgw/postgres task containers)
#   pin-cpusets.sh --watch    # pin now, then re-pin on every container start (systemd service)
#
# Env:
#   CAP_ENV=/etc/ussdgw/capacity.env
#   STACK=ussdgw                       swarm stack name
#   USSDGW_SERVICE=ussdgw PG_SERVICE=postgres   service names inside the stack
set -euo pipefail

CAP_ENV="${CAP_ENV:-/etc/ussdgw/capacity.env}"
STACK="${STACK:-ussdgw}"
USSDGW_SERVICE="${USSDGW_SERVICE:-ussdgw}"
PG_SERVICE="${PG_SERVICE:-postgres}"

log() { printf '[pin-cpusets] %s\n' "$*"; }
[[ -f "${CAP_ENV}" ]] || { log "missing ${CAP_ENV} — run host-reserve.sh --apply first"; exit 1; }
# shellcheck disable=SC1090
source "${CAP_ENV}"

pin_service() { # swarm service name, cpuset, mems
  local svc="$1" cpus="$2" mems="$3" id cur
  while read -r id; do
    [[ -n "${id}" ]] || continue
    cur="$(docker inspect --format '{{.HostConfig.CpusetCpus}}' "${id}" 2>/dev/null || true)"
    if [[ "${cur}" == "${cpus}" ]]; then
      continue
    fi
    if docker update --cpuset-cpus "${cpus}" --cpuset-mems "${mems}" "${id}" >/dev/null; then
      log "pinned ${svc} container ${id:0:12} → cpus=${cpus} mems=${mems}"
    else
      log "WARN: docker update failed for ${svc} container ${id:0:12}"
    fi
  done < <(docker ps -q --filter "label=com.docker.swarm.service.name=${svc}")
}

pin_all() {
  pin_service "${STACK}_${USSDGW_SERVICE}" "${USSDGW_CPUSET}" "${USSDGW_MEMS}"
  pin_service "${STACK}_${PG_SERVICE}" "${PG_CPUSET}" "${PG_MEMS}"
}

pin_all
if [[ "${1:-}" == "--watch" ]]; then
  log "watching docker start events for ${STACK}_${USSDGW_SERVICE} / ${STACK}_${PG_SERVICE}"
  docker events --filter type=container --filter event=start \
      --format '{{index .Actor.Attributes "com.docker.swarm.service.name"}}' \
    | while read -r svc; do
        case "${svc}" in
          "${STACK}_${USSDGW_SERVICE}"|"${STACK}_${PG_SERVICE}") pin_all ;;
        esac
      done
fi
