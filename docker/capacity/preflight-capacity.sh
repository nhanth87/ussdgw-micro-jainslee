#!/usr/bin/env bash
# Refuse to deploy the 5k TPS profile on a host that cannot carry it.
#
# WHY THIS EXISTS
# ---------------
# capacity_5k_tps.md targets a 128 GiB / 32 CPU host: ussdgw 16 CPU / 64 GiB and
# postgres 12 CPU / 32 GiB. The Digicom TEST server has very little RAM and CPU.
# Deploying the capacity overlay there does not "scale down gracefully" — it
# makes Swarm schedule the container against reservations the host cannot honour,
# and the JVM gets OOM-killed mid-MAP-dialog, which is a production incident on a
# box that was only ever meant to answer a smoke test.
#
# So the 5k profile is not opt-out, it is opt-IN and gated on measured host size.
# The TEST profile (docker/stack.test.yml) is the default and needs none of this.
#
# Usage:
#   docker/capacity/preflight-capacity.sh test    # always passes; prints the profile
#   docker/capacity/preflight-capacity.sh 5k      # FAILS unless the host is big enough
set -euo pipefail

PROFILE="${1:-test}"
# Minimum host for the 5k profile. 128 GiB / 32 CPU is the design target; these
# floors leave headroom for the OS, dockerd, nginx and the file cache, and are
# deliberately well below the target so a half-sized host is caught early.
MIN_MEM_GIB="${MIN_MEM_GIB:-96}"
MIN_CPUS="${MIN_CPUS:-28}"

ok()  { echo "preflight: ok   $*"; }
die() { echo "preflight: ERROR $*" >&2; exit 1; }

if [[ "$PROFILE" != "5k" ]]; then
  cat <<EOF
preflight: profile = TEST (default, low resource)
  ussdgw  : 4 CPU  / 6 GiB   (USSD_XMX=4g, mem limit 6g)
  postgres: 1 CPU  / 1 GiB
  This is what the Digicom test server gets. 5k TPS is NOT a test-server goal:
  capacity_5k_tps.md is a PRODUCTION profile and is gated separately.

  Deploy:  docker stack deploy -c docker/stack.yml -c docker/stack.test.yml ussdgw
EOF
  exit 0
fi

mem_gib="$(awk '/MemTotal/ {printf "%d", $2/1024/1024}' /proc/meminfo 2>/dev/null || echo 0)"
cpus="$(nproc 2>/dev/null || echo 0)"

echo "preflight: profile = 5k PRODUCTION"
echo "preflight: host reports ${cpus} CPU, ${mem_gib} GiB RAM"
echo "preflight: requires >= ${MIN_CPUS} CPU, >= ${MIN_MEM_GIB} GiB"

[[ "${cpus:-0}" -ge "$MIN_CPUS" ]] \
  || die "host has ${cpus} CPU, need >= ${MIN_CPUS}.
     The 5k overlay reserves 16 CPU for ussdgw and 12 for postgres; on a smaller
     host Swarm cannot honour the reservation and the JVM is OOM-killed mid-dialog.
     For the test server use the TEST profile:
       docker stack deploy -c docker/stack.yml -c docker/stack.test.yml ussdgw"

[[ "${mem_gib:-0}" -ge "$MIN_MEM_GIB" ]] \
  || die "host has ${mem_gib} GiB RAM, need >= ${MIN_MEM_GIB}.
     The 5k overlay reserves 64 GiB for ussdgw and 32 GiB for postgres. Do NOT run
     it on the Digicom test server. Use the TEST profile:
       docker stack deploy -c docker/stack.yml -c docker/stack.test.yml ussdgw"

if [[ "${USSDGW_ALLOW_UNSAFE_5K:-0}" == "1" ]]; then
  echo "preflight: WARNING USSDGW_ALLOW_UNSAFE_5K=1 — proceeding on an undersized host"
fi

ok "host is large enough for the 5k profile"
echo "preflight: ALSO required before claiming 5k — the §8 load prove. A config that"
echo "           asks for 64 GiB is not evidence that 5 000 TPS is achieved."