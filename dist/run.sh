#!/usr/bin/env bash
set -euo pipefail
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
cd "$APP_HOME"

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

exec java \
  -Dussd.log.dir="$USSD_LOG_DIR" \
  -Dquarkus.config.locations="file:$APP_HOME/configs/application.properties" \
  -jar "$APP_HOME/quarkus-run.jar"
