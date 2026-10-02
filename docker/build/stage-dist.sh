#!/usr/bin/env bash
# Stage the packaged dist/ from a container build into the working tree, so that
# `docker build -f docker/ussdgw/Dockerfile .` can COPY it.
#
# WHY THIS SCRIPT HAS TO EXIST
# ----------------------------
# docker/ussdgw/Dockerfile reads its payload from the WORKING TREE:
#
#     COPY dist/ussdgw-app.jar  "$APP_HOME/"
#     COPY dist/lib/            "$APP_HOME/lib/"
#     COPY out/BUILD-INFO.json  "$APP_HOME/BUILD-INFO.json"
#
# but docker/build/build-all.sh writes to /srv/ussdgw-build/out/dist. Nothing
# connected the two, so the only way to build a runtime image was to hand-copy,
# which is exactly how a previous attempt shipped an image containing BOTH
# jainslee-core 1.2.0 and 1.2.1: `cp -a out/dist/. dist/` MERGES, it never
# replaces, so every jar left over from the previous build survived.
#
# THIS IS A MIRROR, NOT A MERGE
# -----------------------------
# `rm -rf` first, then copy. A stale jar in dist/lib is not a warning, it is a
# classpath conflict picked at runtime by whichever class happens to sort first,
# and the symptom appears weeks later as an impossible stack trace.
set -euo pipefail

BUILD_OUT="${BUILD_OUT:-/srv/ussdgw-build/out}"
DEST="${DEST:-$PWD}"

die() { echo "stage-dist: ERROR: $*" >&2; exit 1; }

SRC="$BUILD_OUT/dist"
[[ -d "$SRC" ]] || die "no packaged dist at $SRC — run docker/build/run-build.sh first"
[[ -f "$BUILD_OUT/BUILD-INFO.json" ]] || die "no $BUILD_OUT/BUILD-INFO.json — the build did not finish"

# --- the PG bake is the one property that cannot be checked after the fact -----
# dist/.baked-db-kind records the build-time quarkus.datasource.db-kind. A host
# running PostgreSQL with an H2-baked image dies with:
#
#     Build time property cannot be changed at runtime:
#      - quarkus.datasource.db-kind is set to 'postgresql' but it is build time fixed to 'h2'
#
# and the whole fast-jar bake (this jar plus lib/ plus quarkus/) must be replaced,
# not patched. Refuse to stage a jar the host cannot run.
BAKED="$SRC/.baked-db-kind"
if [[ -f "$BAKED" ]]; then
  require="$(cat "$BAKED")"
  if [[ "$require" != "postgresql" ]]; then
    die "dist was baked for db-kind=$require, but the Digicom host runs PostgreSQL.
     Rebuild with quarkus.datasource.db-kind=postgresql before deploying.
     (An H2-baked tree on a PostgreSQL host is a crash loop, not a warning.)"
  fi
  echo "stage-dist: ok   baked db-kind = $require"
else
  echo "stage-dist: WARN no .baked-db-kind in $SRC — cannot prove the datasource bake" >&2
fi

# --- MIRROR ------------------------------------------------------------------
echo "stage-dist: mirroring $SRC -> $DEST/dist"
rm -rf "$DEST/dist"
cp -a "$SRC" "$DEST/dist"

mkdir -p "$DEST/out"
cp -a "$BUILD_OUT/BUILD-INFO.json" "$DEST/out/BUILD-INFO.json"

# --- prove the result, do not assume it --------------------------------------
# These are the exact paths docker/ussdgw/Dockerfile COPYs. If one is missing the
# image build fails with a COPY error that does not say why.
missing=()
for p in ussdgw-app.jar quarkus-run.jar run.sh .baked-db-kind lib quarkus app/html; do
  [[ -e "$DEST/dist/$p" ]] || missing+=("$p")
done
(( ${#missing[@]} == 0 )) || die "staged dist is missing ${missing[*]} — refusing to continue"

# One version per artifact. The Dockerfile has the same guard, but failing here
# names the duplicate artifact instead of just failing the build.
dupes="$(find "$DEST/dist/lib" -name '*.jar' -printf '%f\n' \
         | sed -E 's/-[0-9][0-9A-Za-z.+-]*\.jar$//' | sort | uniq -d)"
[[ -z "$dupes" ]] || die "duplicate artifact versions in dist/lib:
$dupes
A mirror copy cannot produce this; something else merged into dist/lib."

echo "stage-dist: ok   $(find "$DEST/dist/lib" -name '*.jar' | wc -l) jars, one version each"
echo "stage-dist: ok   BUILD-INFO.json -> $DEST/out/BUILD-INFO.json"
echo "stage-dist: ready — next:  docker build -f docker/ussdgw/Dockerfile -t ussdgw:<sha> ."
