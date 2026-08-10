#!/usr/bin/env bash
# Build ussd-cli.jar + ussd-load.jar (Java 25, JDK only — no Maven deps).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/src"
OUT="$ROOT/out"
CLI_JAR="$ROOT/ussd-cli.jar"
LOAD_JAR="$ROOT/ussd-load.jar"

resolve_java25() {
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/javac" ]]; then
    if "${JAVA_HOME}/bin/javac" -version 2>&1 | grep -qE '25'; then
      echo "$JAVA_HOME"
      return 0
    fi
  fi
  local cand
  for cand in \
    "${HOME}/.local/share/mise/installs/java/zulu-25" \
    "${HOME}/.local/share/mise/installs/java/25" \
    "${HOME}/.local/share/mise/installs/java/latest"
  do
    if [[ -x "$cand/bin/javac" ]]; then
      echo "$(readlink -f "$cand")"
      return 0
    fi
  done
  return 1
}

if ! JH="$(resolve_java25)"; then
  echo "Java 25 javac not found. Set JAVA_HOME to zulu-25 (mise install java@zulu-25)." >&2
  exit 1
fi
export JAVA_HOME="$JH"
JAVAC="$JAVA_HOME/bin/javac"
JAR_BIN="$JAVA_HOME/bin/jar"

rm -rf "$OUT"
mkdir -p "$OUT"
# shellcheck disable=SC2046
"$JAVAC" --release 25 -encoding UTF-8 -d "$OUT" $(find "$SRC" -name '*.java' | sort)

write_jar() {
  local main="$1"
  local jar="$2"
  local mf="$OUT/MANIFEST-$main.MF"
  cat >"$mf" <<EOF
Manifest-Version: 1.0
Main-Class: $main

EOF
  "$JAR_BIN" cfm "$jar" "$mf" -C "$OUT" .
  echo "Built $jar (Main-Class=$main JAVA_HOME=$JAVA_HOME)"
}

write_jar et.digicom.ussdsim.UssdCli "$CLI_JAR"
write_jar et.digicom.ussdsim.UssdLoadDriver "$LOAD_JAR"
