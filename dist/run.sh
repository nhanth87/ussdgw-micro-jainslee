#!/usr/bin/env bash
# nhanth87 USSDGW — dist starter (Quarkus fast-jar layout).
# Never java -jar ussdgw-app.jar alone; never ship uber-jar.
set -euo pipefail
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
cd "$APP_HOME"

if [[ -f "${APP_HOME}/ussdgw.jar" && ! -f "${APP_HOME}/quarkus-run.jar" ]]; then
  echo "error: found legacy uber-jar layout but missing quarkus-run.jar" >&2
  echo "  Re-run ./build/package-dist.sh (fast-jar). Single uber-jar is not supported." >&2
  exit 1
fi
if [[ ! -f "${APP_HOME}/quarkus-run.jar" ]]; then
  echo "error: missing ${APP_HOME}/quarkus-run.jar" >&2
  exit 1
fi
if [[ ! -f "${APP_HOME}/ussdgw-app.jar" ]]; then
  echo "error: missing ${APP_HOME}/ussdgw-app.jar (app classes at APP_HOME root)" >&2
  echo "  Re-run ./build/package-dist.sh — do not leave the jar under app/." >&2
  exit 1
fi
if [[ ! -d "${APP_HOME}/lib/main" ]]; then
  echo "error: missing ${APP_HOME}/lib/main (incomplete fast-jar dist)" >&2
  exit 1
fi
if find "${APP_HOME}/app" -type f -name '*.jar' 2>/dev/null | grep -q .; then
  echo "error: jars must not live under app/ (UI only)." >&2
  find "${APP_HOME}/app" -type f -name '*.jar' >&2 || true
  exit 1
fi
if [[ ! -f "${APP_HOME}/configs/application.properties" ]]; then
  echo "error: missing configs/application.properties" >&2
  exit 1
fi

resolve_java25() {
  if command -v mise >/dev/null 2>&1; then
    local mj
    mj="$(mise where java 2>/dev/null || true)"
    if [[ -n "$mj" && -x "$mj/bin/java" ]]; then
      echo "$mj"
      return
    fi
  fi
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    local ver
    ver="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 || true)"
    if echo "$ver" | grep -q '"25'; then
      echo "$JAVA_HOME"
      return
    fi
  fi
  echo "ERROR: JDK 25 required (mise zulu-25)" >&2
  exit 1
}

JAVA_HOME="$(resolve_java25)"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p data logs configs/ss7-persist
export USSD_LOG_DIR="${USSD_LOG_DIR:-$APP_HOME/logs}"
case "$USSD_LOG_DIR" in
  /tmp|/*/tmp/*)
    echo "Refuse USSD_LOG_DIR under /tmp" >&2
    exit 1
    ;;
esac

# Heap: Lab-safe defaults -Xms2g -Xmx4g (shared hosts). 8g+AlwaysPreTouch OOM'd ~15 GiB with ss7sim/as-node.
# Bigger hosts: USSD_XMS=8g USSD_XMX=8g. AlwaysPreTouch only when USSD_ALWAYS_PRETOUCH=1. Also: JAVA_OPTS / USSD_JAVA_OPTS.
: "${USSD_XMS:=2g}"
: "${USSD_XMX:=4g}"
JAVA_OPTS_DEFAULT=(
  "-Xms${USSD_XMS}"
  "-Xmx${USSD_XMX}"
  "-XX:+UseZGC"
  "-XX:+ExitOnOutOfMemoryError"
  "-XX:+HeapDumpOnOutOfMemoryError"
  "-XX:HeapDumpPath=${USSD_LOG_DIR}/heap.hprof"
)
if [[ "${USSD_ALWAYS_PRETOUCH:-0}" == "1" ]]; then
  JAVA_OPTS_DEFAULT+=("-XX:+AlwaysPreTouch")
fi
# shellcheck disable=SC2206
EXTRA_JAVA_OPTS=( ${JAVA_OPTS:-${USSD_JAVA_OPTS:-}} )

echo "Starting nhanth87 USSDGW (fast-jar: quarkus-run.jar + ussdgw-app.jar + lib/)"
echo "Data: ${APP_HOME}/data (H2 file DB by default — switch configs to postgresql for prod)"
echo "Heap: -Xms${USSD_XMS} -Xmx${USSD_XMX}"
exec java \
  "${JAVA_OPTS_DEFAULT[@]}" \
  "${EXTRA_JAVA_OPTS[@]}" \
  -Dussd.log.dir="$USSD_LOG_DIR" \
  -Dquarkus.config.locations="file:$APP_HOME/configs/application.properties" \
  -jar "$APP_HOME/quarkus-run.jar"
