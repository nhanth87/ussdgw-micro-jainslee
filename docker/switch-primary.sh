#!/usr/bin/env bash
# Move the gateway role between GMLC and USSDGW on the shared Digicom host.
#
# Both gateways bind :8088 and the same SCTP endpoints (2011/2019) on the host network, so
# exactly ONE runs at a time. The other stays at 0 replicas (STANDBY). The shared PostgreSQL
# (ussdgw_postgres) and nginx (ussdgw_nginx) are NEVER scaled, restarted or removed here —
# the script proves that by comparing postgres's task id before and after.
#
# Usage:
#   docker/switch-primary.sh status      # who is primary, task states, :8088, postgres task
#   docker/switch-primary.sh gmlc        # GMLC primary,  USSDGW standby
#   docker/switch-primary.sh ussdgw      # USSDGW primary, GMLC standby
#
# Order is STOP-FIRST: the current primary goes to 0, :8088 must be released, then the new
# primary goes to 1 and must answer on :8088. If it does not answer in READY_TIMEOUT, the
# script puts the previous primary back (rollback) so the host is never left with no gateway.
#
# Env: GMLC_SERVICE=gmlc_gmlc  USSDGW_SERVICE=ussdgw_ussdgw  PG_SERVICE=ussdgw_postgres
#      READY_TIMEOUT=300  RELEASE_TIMEOUT=120
set -euo pipefail

GMLC_SERVICE="${GMLC_SERVICE:-gmlc_gmlc}"
USSDGW_SERVICE="${USSDGW_SERVICE:-ussdgw_ussdgw}"
PG_SERVICE="${PG_SERVICE:-ussdgw_postgres}"
READY_TIMEOUT="${READY_TIMEOUT:-300}"
RELEASE_TIMEOUT="${RELEASE_TIMEOUT:-120}"

info() { echo "switch-primary: $*"; }
warn() { echo "switch-primary: WARN: $*" >&2; }
die()  { echo "switch-primary: ERROR: $*" >&2; exit 1; }

want_of()   { docker service inspect "$1" --format '{{.Spec.Mode.Replicated.Replicas}}' 2>/dev/null || echo "-"; }
running_of(){ docker ps -q --filter "label=com.docker.swarm.service.name=$1" | wc -l; }
pg_task()   { docker ps -q --no-trunc --filter "label=com.docker.swarm.service.name=$PG_SERVICE" | head -1; }
port_open() { (exec 3<>/dev/tcp/127.0.0.1/8088) 2>/dev/null; }
sctp_eps()  { awk 'NR>1{print $6}' /proc/net/sctp/eps 2>/dev/null | sort -un | tr '\n' ' '; }

status() {
  info "$GMLC_SERVICE   replicas=$(want_of "$GMLC_SERVICE") running=$(running_of "$GMLC_SERVICE")"
  info "$USSDGW_SERVICE replicas=$(want_of "$USSDGW_SERVICE") running=$(running_of "$USSDGW_SERVICE")"
  info "$PG_SERVICE  replicas=$(want_of "$PG_SERVICE") task=$(pg_task | cut -c1-12)"
  port_open && info ":8088 answering" || info ":8088 closed"
  info "kernel SCTP listening ports: $(sctp_eps)"
}

wait_released() {  # $1 = service that was scaled to 0
  local deadline=$(( $(date +%s) + RELEASE_TIMEOUT ))
  while (( $(running_of "$1") > 0 )) || port_open; do
    (( $(date +%s) < deadline )) || die "$1 did not release :8088 within ${RELEASE_TIMEOUT}s — NOT starting the other gateway.
     Check: docker service ps $1 --no-trunc"
    sleep 2
  done
  info "$1 stopped, :8088 released (SCTP listening now: $(sctp_eps))"
}

wait_ready() {  # $1 = service scaled to 1; returns 1 on timeout
  local deadline=$(( $(date +%s) + READY_TIMEOUT ))
  until (( $(running_of "$1") > 0 )) && port_open; do
    (( $(date +%s) < deadline )) || return 1
    sleep 3
  done
  info "$1 running and answering on :8088 (SCTP listening: $(sctp_eps))"
}

TARGET="${1:-}"
case "$TARGET" in
  status) status; exit 0 ;;
  gmlc)   NEW="$GMLC_SERVICE";   OLD="$USSDGW_SERVICE" ;;
  ussdgw) NEW="$USSDGW_SERVICE"; OLD="$GMLC_SERVICE" ;;
  *) die "usage: $0 status|gmlc|ussdgw" ;;
esac

for s in "$NEW" "$OLD" "$PG_SERVICE"; do
  docker service inspect "$s" >/dev/null 2>&1 || die "service $s does not exist — deploy both stacks first"
done
PG_BEFORE="$(pg_task)"
[[ -n "$PG_BEFORE" ]] || die "$PG_SERVICE has no running task — fix the database before switching gateways"

if [[ "$(want_of "$NEW")" == 1 && "$(want_of "$OLD")" == 0 ]] && port_open; then
  info "$NEW is already primary"; status; exit 0
fi

info "switching primary → $NEW (stop-first: $OLD goes to 0 first)"
docker service scale --detach "$OLD=0" >/dev/null
wait_released "$OLD"

docker service scale --detach "$NEW=1" >/dev/null
if ! wait_ready "$NEW"; then
  warn "$NEW did not answer on :8088 within ${READY_TIMEOUT}s — ROLLING BACK to $OLD"
  docker service ps "$NEW" --no-trunc | head -5 >&2 || true
  docker service scale --detach "$NEW=0" >/dev/null
  wait_released "$NEW"
  docker service scale --detach "$OLD=1" >/dev/null
  wait_ready "$OLD" || die "rollback: $OLD did not come back either — investigate now: docker service ps $OLD --no-trunc"
  die "switch to $NEW FAILED; $OLD is primary again"
fi

PG_AFTER="$(pg_task)"
[[ "$PG_AFTER" == "$PG_BEFORE" ]] \
  && info "postgres untouched (same task ${PG_AFTER:0:12})" \
  || warn "postgres task changed (${PG_BEFORE:0:12} → ${PG_AFTER:0:12}) — NOT caused by this script; check docker service ps $PG_SERVICE"
info "done: $NEW is PRIMARY, $OLD is STANDBY"
status
