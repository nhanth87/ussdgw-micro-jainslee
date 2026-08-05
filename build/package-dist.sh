#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "$APP_DIR"
USSD_SERVICE_ROOT="$(cd "${APP_DIR}/.." && pwd)"
DIST_ROOT="${USSD_DIST_DIR:-${DIST_DIR:-${APP_DIR}/dist}}"
LEGACY_DIST="${USSD_SERVICE_ROOT}/RestLink/Ussdgw"

if command -v mise >/dev/null 2>&1; then
  mj="$(mise where java 2>/dev/null || true)"
  if [[ -n "$mj" && -x "$mj/bin/java" ]]; then
    export JAVA_HOME="$mj"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
fi

echo "==> mvn package (fast-jar)"
mvn -B -ntp package -DskipTests -Dquarkus.build.skip=false

QA="$APP_DIR/target/quarkus-app"
if [[ ! -d "$QA" ]]; then
  echo "Missing target/quarkus-app — Quarkus package failed" >&2
  exit 1
fi

mkdir -p "$DIST_ROOT"/{configs/ss7-persist,app/html,data,logs}
rm -rf "$DIST_ROOT/lib" "$DIST_ROOT/quarkus"
cp -a "$QA/lib" "$DIST_ROOT/"
cp -a "$QA/quarkus" "$DIST_ROOT/"
cp -f "$QA/quarkus-run.jar" "$DIST_ROOT/"

if [[ -f "$QA/app/ussdgw-app.jar" ]]; then
  cp -f "$QA/app/ussdgw-app.jar" "$DIST_ROOT/ussdgw-app.jar"
elif ls "$QA/app"/*.jar >/dev/null 2>&1; then
  cp -f "$QA/app"/*.jar "$DIST_ROOT/ussdgw-app.jar"
fi

DAT="$DIST_ROOT/quarkus/quarkus-application.dat"
if [[ -f "$DAT" ]] && command -v python3 >/dev/null; then
  python3 - "$DAT" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
data = p.read_bytes()
old, new = b"app/ussdgw-app.jar", b"ussdgw-app.jar"
if old in data:
    p.write_bytes(data.replace(old, new))
    print("rewrote quarkus-application.dat jar path")
PY
fi

cp -f "$APP_DIR/build/application.properties" "$DIST_ROOT/configs/application.properties"
cp -f "$APP_DIR/build/run-dist.sh" "$DIST_ROOT/run.sh"
chmod +x "$DIST_ROOT/run.sh"
cp -a "$APP_DIR/app/html/." "$DIST_ROOT/app/html/"
cp -f "$APP_DIR/build/dist-README.md" "$DIST_ROOT/README.md"
echo "SS7 persist XML lives here." > "$DIST_ROOT/configs/ss7-persist/README.md"

if [[ "${USSD_MIRROR_LEGACY:-0}" == "1" ]]; then
  mkdir -p "$LEGACY_DIST"
  rsync -a --delete "$DIST_ROOT/" "$LEGACY_DIST/"
  echo "Mirrored to $LEGACY_DIST"
fi

echo "Dist ready: $DIST_ROOT"
