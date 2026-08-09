#!/usr/bin/env bash
# Assemble Quarkus fast-jar runtime into worktree dist/ (OTA parity).
# Usage: ./build/package-dist.sh
# Agents (Cursor): run with unrestricted Shell (required_permissions: ["all"]) —
# never under sandbox when using .m2-agent-repo / Digicom ship (see docs/agents/lessons.md).
#
# Layout (never uber-jar):
#   quarkus-run.jar     thin launcher
#   ussdgw-app.jar      application classes at APP_HOME root (NOT under app/)
#   app/html/           admin UI ONLY — never put jars in app/
#   lib/boot/ lib/main/ Quarkus bootstrap + third-party + micro-jainslee + ra-*
#   quarkus/            generated model (dat rewritten for root jar path)
#   run.sh configs/ data/ logs/ README.md
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "$APP_DIR"
USSD_SERVICE_ROOT="$(cd "${APP_DIR}/.." && pwd)"
DIST_ROOT="${USSD_DIST_DIR:-${DIST_DIR:-${APP_DIR}/dist}}"
LEGACY_DIST="${USSD_SERVICE_ROOT}/RestLink/Ussdgw"

resolve_java25() {
  local cand ver
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    ver="$("${JAVA_HOME}/bin/java" -version 2>&1 | head -1 || true)"
    if echo "${ver}" | grep -qE 'version "25'; then
      return 0
    fi
    echo "warn: ignoring non-JDK-25 JAVA_HOME=${JAVA_HOME} (${ver})" >&2
  fi
  if command -v mise >/dev/null 2>&1; then
    cand="$(mise where java 2>/dev/null || true)"
    if [[ -n "$cand" && -x "$cand/bin/java" ]]; then
      export JAVA_HOME="$cand"
      return 0
    fi
  fi
  for cand in \
    "${HOME}/.local/share/mise/installs/java/zulu-25.34.17.0" \
    "${HOME}/.local/share/mise/installs/java/zulu-25.34.17" \
    "${HOME}/.local/share/mise/installs/java/zulu-25" \
    "${HOME}/.local/share/mise/installs/java/25.0.2" \
    "${HOME}/.local/share/mise/installs/java/25"; do
    if [[ -x "${cand}/bin/java" ]]; then
      export JAVA_HOME="${cand}"
      return 0
    fi
  done
  return 1
}

if ! resolve_java25; then
  echo "error: JDK 25 required (mise zulu-25). Do NOT use java-8." >&2
  exit 1
fi
export PATH="${JAVA_HOME}/bin:${PATH}"
JAVA_VER="$("${JAVA_HOME}/bin/java" -version 2>&1 | head -1 || true)"
if ! echo "${JAVA_VER}" | grep -qE 'version "25'; then
  echo "error: JAVA_HOME must be JDK 25, got: ${JAVA_VER}" >&2
  exit 1
fi
echo "JAVA_HOME=${JAVA_HOME} (${JAVA_VER})"

# Rewrite one DataOutputStream.writeUTF path in quarkus-application.dat.
rewrite_quarkus_app_jar_path() {
  local dat="$1"
  local old_rel="$2"
  local new_rel="$3"
  python3 - "${dat}" "${old_rel}" "${new_rel}" <<'PY'
import struct, sys
from pathlib import Path
dat_path, old, new = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
data = dat_path.read_bytes()
old_b, new_b = old.encode("utf-8"), new.encode("utf-8")
if len(old_b) > 0xFFFF or len(new_b) > 0xFFFF:
    sys.exit("error: path too long for writeUTF")
old_enc = struct.pack(">H", len(old_b)) + old_b
new_enc = struct.pack(">H", len(new_b)) + new_b
n = data.count(old_enc)
if n != 1:
    sys.exit(f"error: expected exactly 1 writeUTF path {old!r} in {dat_path}, found {n}")
dat_path.write_bytes(data.replace(old_enc, new_enc, 1))
print(f"  rewritten quarkus-application.dat: {old} → {new}")
PY
}

verify_dist() {
  local dest="$1"
  local missing=0
  local app_jar="${dest}/ussdgw-app.jar"
  local req
  for req in \
    "${dest}/quarkus-run.jar" \
    "${dest}/ussdgw-app.jar" \
    "${dest}/run.sh" \
    "${dest}/README.md" \
    "${dest}/configs/application.properties" \
    "${dest}/configs/ss7-persist" \
    "${dest}/lib/main" \
    "${dest}/lib/boot" \
    "${dest}/quarkus" \
    "${dest}/quarkus/quarkus-application.dat" \
    "${dest}/app/html" \
    "${dest}/logs" \
    "${dest}/data"; do
    if [[ ! -e "${req}" ]]; then
      echo "error: package incomplete — missing ${req}" >&2
      missing=1
    fi
  done
  if [[ ! -x "${dest}/run.sh" ]]; then
    echo "error: ${dest}/run.sh is not executable" >&2
    missing=1
  fi
  if find "${dest}/app" -type f -name '*.jar' 2>/dev/null | grep -q .; then
    echo "error: jars must not live under ${dest}/app/ (UI only). Found:" >&2
    find "${dest}/app" -type f -name '*.jar' >&2 || true
    missing=1
  fi
  local main_count boot_count
  main_count="$(find "${dest}/lib/main" -maxdepth 1 -type f -name '*.jar' 2>/dev/null | wc -l | tr -d ' ')"
  boot_count="$(find "${dest}/lib/boot" -maxdepth 1 -type f -name '*.jar' 2>/dev/null | wc -l | tr -d ' ')"
  if [[ "${main_count}" -lt 30 ]]; then
    echo "error: lib/main has only ${main_count} jars (want ≥ 30) — dist not self-contained" >&2
    missing=1
  else
    echo "  verified lib/main=${main_count} jars, lib/boot=${boot_count} jars"
  fi
  if ! python3 - "${dest}/quarkus/quarkus-application.dat" <<'PY'
import struct, sys
from pathlib import Path
data = Path(sys.argv[1]).read_bytes()
want = b"ussdgw-app.jar"
enc = struct.pack(">H", len(want)) + want
old = b"app/ussdgw-app.jar"
old_enc = struct.pack(">H", len(old)) + old
if data.count(old_enc):
    sys.exit("dat still references app/ussdgw-app.jar")
if data.count(enc) != 1:
    sys.exit(f"dat writeUTF ussdgw-app.jar count={data.count(enc)} (want 1)")
PY
  then
    echo "error: quarkus-application.dat does not reference root ussdgw-app.jar" >&2
    missing=1
  else
    echo "  verified quarkus-application.dat → ussdgw-app.jar (not under app/)"
  fi
  local sample major
  if [[ -f "${app_jar}" ]]; then
    sample="$(jar tf "${app_jar}" 2>/dev/null | grep '\.class$' | grep -v 'META-INF/versions/' | head -1 || true)"
    if [[ -n "${sample}" ]]; then
      major="$(javap -verbose -classpath "${app_jar}" "${sample%.class}" 2>/dev/null \
        | awk '/major version:/ {print $3; exit}')"
      if [[ "${major}" != "69" ]]; then
        echo "error: ${app_jar} major version=${major:-unknown} (want 69 = Java 25)." >&2
        missing=1
      else
        echo "  verified $(basename "${app_jar}") major version 69 (Java 25)"
      fi
    fi
  fi
  if [[ "${missing}" -ne 0 ]]; then
    exit 1
  fi
}

echo "Packaging Quarkus fast-jar (non-uber) → ${DIST_ROOT}"
mvn -B -ntp package -Dquarkus.build.skip=false -Dmaven.test.skip=true \
  -Dquarkus.package.jar.type=fast-jar

QA="$APP_DIR/target/quarkus-app"
if [[ ! -f "$QA/quarkus-run.jar" ]]; then
  echo "error: fast-jar layout missing: $QA/quarkus-run.jar" >&2
  exit 1
fi
if [[ ! -d "$QA/lib/main" ]]; then
  echo "error: missing $QA/lib/main" >&2
  exit 1
fi

mkdir -p "$DIST_ROOT"/{configs/ss7-persist,data,logs,app}
# Preserve data/logs across re-package; refresh code layout.
rm -rf "$DIST_ROOT/lib" "$DIST_ROOT/quarkus"
rm -f "$DIST_ROOT/ussdgw-app.jar" "$DIST_ROOT/quarkus-run.jar"
find "$DIST_ROOT/app" -type f -name '*.jar' -delete 2>/dev/null || true

cp -f "$QA/quarkus-run.jar" "$DIST_ROOT/"
cp -a "$QA/lib" "$DIST_ROOT/"
cp -a "$QA/quarkus" "$DIST_ROOT/"
if [[ -f "$SCRIPT_DIR/dist-lib-README.md" ]]; then
  cp -f "$SCRIPT_DIR/dist-lib-README.md" "$DIST_ROOT/lib/README.md"
fi

# Quarkus Class-Path lists jainslee-api before jainslee-core. The api JAR ships a
# stub ProfileAccessorInvoker that always throws UnsupportedOperationException;
# core has the real impl (same FQCN). Without this shadow, Digicom POST /ussd dies
# in VirtualSessionStore.put / CMP setXxx before NI park (ss7.live can still be true).
shadow_profile_accessor_invoker() {
  local api core work
  api="$(find "${DIST_ROOT}/lib/main" -maxdepth 1 -type f -name 'com.microjainslee.jainslee-api-*.jar' | head -1 || true)"
  core="$(find "${DIST_ROOT}/lib/main" -maxdepth 1 -type f -name 'com.microjainslee.jainslee-core-*.jar' | head -1 || true)"
  if [[ -z "$api" || -z "$core" ]]; then
    echo "error: missing jainslee-api or jainslee-core under lib/main (ProfileAccessorInvoker shadow)" >&2
    exit 1
  fi
  work="$(mktemp -d)"
  (cd "$work" && jar xf "$core" com/microjainslee/api/ProfileAccessorInvoker.class)
  if ! javap -c -p "$work/com/microjainslee/api/ProfileAccessorInvoker.class" 2>/dev/null \
      | grep -q 'ProfileFieldStoreLocator'; then
    echo "error: core ProfileAccessorInvoker missing ProfileFieldStoreLocator (not the real impl?)" >&2
    rm -rf "$work"
    exit 1
  fi
  (cd "$work" && jar uf "$api" com/microjainslee/api/ProfileAccessorInvoker.class)
  rm -rf "$work"
  echo "  shadowed ProfileAccessorInvoker: $(basename "$core") → $(basename "$api")"
}
shadow_profile_accessor_invoker

src_app_jar="$(find "$QA/app" -maxdepth 1 -type f -name '*.jar' | head -1 || true)"
if [[ -z "$src_app_jar" ]]; then
  echo "error: no application jar under $QA/app" >&2
  exit 1
fi
dest_app_jar="$DIST_ROOT/$(basename "$src_app_jar")"
quarkus_rel="app/$(basename "$src_app_jar")"
cp -f "$src_app_jar" "$dest_app_jar"
rewrite_quarkus_app_jar_path \
  "$DIST_ROOT/quarkus/quarkus-application.dat" \
  "$quarkus_rel" \
  "$(basename "$dest_app_jar")"

# app/ is UI-only
rm -rf "$DIST_ROOT/app"
mkdir -p "$DIST_ROOT/app/html"
cp -a "$APP_DIR/app/html/." "$DIST_ROOT/app/html/"

# NEVER clobber configs/ — that is the operator's live DB/secret config on the server.
"$SCRIPT_DIR/install-config.sh" \
  "$APP_DIR/build/application.properties" \
  "$DIST_ROOT/configs/application.properties"
if [[ -f "$APP_DIR/build/ss7-lab.json" ]]; then
  "$SCRIPT_DIR/install-config.sh" \
    "$APP_DIR/build/ss7-lab.json" \
    "$DIST_ROOT/configs/ss7-lab.json"
fi
if [[ -f "$APP_DIR/build/ss7-lab-sim-pull.json" ]]; then
  "$SCRIPT_DIR/install-config.sh" \
    "$APP_DIR/build/ss7-lab-sim-pull.json" \
    "$DIST_ROOT/configs/ss7-lab-sim-pull.json"
fi
rm -f "$DIST_ROOT/application.properties"
cp -f "$APP_DIR/build/run-dist.sh" "$DIST_ROOT/run.sh"
chmod +x "$DIST_ROOT/run.sh"
cp -f "$APP_DIR/build/dist-README.md" "$DIST_ROOT/README.md"
echo "SS7 persist XML lives here." > "$DIST_ROOT/configs/ss7-persist/README.md"

verify_dist "$DIST_ROOT"

if [[ "${USSD_MIRROR_LEGACY:-0}" == "1" ]]; then
  mkdir -p "$LEGACY_DIST"
  rsync -a --delete \
    --exclude data/ --exclude logs/ \
    "$DIST_ROOT/" "$LEGACY_DIST/"
  echo "Mirrored to $LEGACY_DIST"
fi

echo "Dist ready: $DIST_ROOT"
echo "  Layout: quarkus-run.jar + ussdgw-app.jar + lib/ + app/html/ (OTA parity)"
