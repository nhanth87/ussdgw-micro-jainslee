#!/usr/bin/env bash
# Warm-start the builder's Maven repository, then build.
#
# Why this exists: a cold /m2 downloads ~300 jars from Maven Central and that
# dominated the first build (206 MB before jss7 even started compiling). Two caches
# are supported, and both are worth using:
#
#   SERVER  — /srv/ussdgw-build/m2 persists across runs. Run once online to fill it,
#             then rebuilds are almost entirely offline. Add OFFLINE=1 to prove it:
#             a build that succeeds with `mvn -o` needs no network at all, which is
#             the strongest possible statement about reproducibility.
#
#   LOCAL   — seed from the machine's own ~/.m2, which is already warm from working
#             on the tree. Never let the build WRITE into it: the copy is one-way
#             (cp -rn into the container's /m2), so a corrupted download can never
#             damage the operator's real cache.
#
# Usage:
#   ./docker/build/run-build.sh                       # server: persistent /srv cache
#   SEED_FROM="$HOME/.m2/repository" ./docker/build/run-build.sh   # local: reuse ~/.m2
#   OFFLINE=1 ./docker/build/run-build.sh             # prove the cache is complete
set -euo pipefail

BUILD_ROOT="${BUILD_ROOT:-/srv/ussdgw-build}"
SOURCE_ROOT="${SOURCE_ROOT:-$BUILD_ROOT/src}"
USSDGW_SRC="${USSDGW_SRC:-$PWD}"          # the product tree (mounted read-only)
OUT="${OUT:-$BUILD_ROOT/out}"
M2="${M2:-$BUILD_ROOT/m2}"
BUILDER_IMAGE="${BUILDER_IMAGE:-ussdgw-builder:latest}"
SOURCE_MODE="${SOURCE_MODE:-local}"
export OFFLINE="${OFFLINE:-0}"
export RUN_TESTS="${RUN_TESTS:-0}"

die() { echo "run-build: ERROR: $*" >&2; exit 1; }

docker image inspect "$BUILDER_IMAGE" >/dev/null 2>&1 || \
  docker build -f docker/build/Dockerfile -t "$BUILDER_IMAGE" .

[[ -d "$SOURCE_ROOT" ]] || die "SOURCE_ROOT=$SOURCE_ROOT not found.
     It must contain sctp/ jss7/ jain-slee/ corsac-diameter/ at the commits pinned in
     docker/sources.lock (see docker/README.md § Build)."
[[ -d "$USSDGW_SRC" ]] || die "USSDGW_SRC=$USSDGW_SRC not found"

mkdir -p "$OUT" "$M2"

echo "run-build: builder=$BUILDER_IMAGE  mode=$SOURCE_MODE  offline=$OFFLINE"
echo "run-build: src=$SOURCE_ROOT  ussdgw=$USSDGW_SRC"
echo "run-build: out=$OUT  m2=$M2${SEED_FROM:+  seed=$SEED_FROM}"
echo

# --user keeps $M2/$OUT owned by the operator; without it Maven writes root-owned
# files that the operator then cannot delete.
exec docker run --rm \
  --user "$(id -u):$(id -g)" \
  -e HOME=/tmp \
  -e MAVEN_CONFIG=/tmp/.m2 \
  -e SOURCE_MODE="$SOURCE_MODE" \
  -e SRC_DIR=/src \
  -e SRC_USSDGW=/ussdgw-src \
  -e M2=/m2 \
  -e OUT=/out \
  -e OFFLINE="$OFFLINE" \
  -e RUN_TESTS="$RUN_TESTS" \
  ${SEED_FROM:+-e SEED_FROM="$SEED_FROM"} \
  -v "$SOURCE_ROOT:/src:ro" \
  -v "$USSDGW_SRC:/ussdgw-src:ro" \
  -v "$OUT:/out" \
  -v "$M2:/m2" \
  "$BUILDER_IMAGE" build-all.sh