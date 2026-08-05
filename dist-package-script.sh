#!/usr/bin/env bash
# dist-package-script.sh — bare-server bootstrap for RestLink USSD GW
#
# Clones restlink deps, installs them to ~/.m2 (JDK 25), then packages
# ussdgw into ./dist/ via build/package-dist.sh. Does NOT start the app.
#
# Usage:
#   ./dist-package-script.sh                    # workspace = $HOME/ussdgw-build
#   ./dist-package-script.sh /var/build/ussdgw  # custom workspace
#   ./dist-package-script.sh --fresh            # wipe workspace clones first
#   ./dist-package-script.sh --rebuild          # always mvn install (ignore skip)
#   ./dist-package-script.sh --clone-ussd       # clone ussd into workspace even if script lives in a checkout
#   ./dist-package-script.sh --help
#
# Env:
#   GIT_BASE     clone base (default https://github.com/restlink)
#   GIT_FALLBACK fallback org (default https://github.com/nhanth87)
#   JAVA_HOME    must be JDK 25, or mise zulu-25 is auto-discovered
#   MVN_OPTS     extra Maven flags (default: -B -ntp)
#   SKIP_PULL=1  do not git pull on existing clones
#   USSD_MIRROR_LEGACY=1  passed through to package-dist.sh → RestLink/Ussdgw
#
# Build order:
#   1) sctp          branch java25-upgrade  → org.mobicents.protocols.sctp:sctp-*:2.27.32
#   2) jss7          branch j25             → org.restcomm.protocols.ss7.*:9.2.8-j25 (+ ss7-config)
#   3) jain-slee     branch micro-jainslee-2→ com.microjainslee:*:1.2.0-SNAPSHOT
#        (reactor installs core/api/adapter-quarkus + vendor-ras:
#         ra-http-server, ra-jss7, ra-sip-servlet, ra-diameter, …)
#   4) ussdgw        branch master          → ./build/package-dist.sh → dist/
#        (tries restlink/ussdgw then restlink/ussdgw-jainslee then nhanth87/*)
#
# Fallback if restlink clone fails: nhanth87 (esp. jain-slee @ micro-jainslee-2).
# Private repos need GitHub auth (GH_TOKEN, gh auth, or SSH via GIT_BASE=git@github.com:restlink).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GIT_BASE="${GIT_BASE:-https://github.com/restlink}"
GIT_FALLBACK="${GIT_FALLBACK:-https://github.com/nhanth87}"
MVN_OPTS="${MVN_OPTS:--B -ntp}"
WORKSPACE="${HOME}/ussdgw-build"
FRESH=0
REBUILD=0
FORCE_CLONE_USSD=0

usage() {
  sed -n '2,35p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
}

log()  { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }
warn() { printf 'warn: %s\n' "$*" >&2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage ;;
    --fresh) FRESH=1; shift ;;
    --rebuild) REBUILD=1; shift ;;
    --clone-ussd) FORCE_CLONE_USSD=1; shift ;;
    --*) die "unknown flag: $1 (try --help)" ;;
    *)
      WORKSPACE="$1"
      shift
      ;;
  esac
done

mkdir -p "${WORKSPACE}"
WORKSPACE="$(cd "${WORKSPACE}" && pwd)"
LOG_DIR="${WORKSPACE}/logs"
mkdir -p "${LOG_DIR}"
LOG_FILE="${LOG_DIR}/dist-package-$(date '+%Y%m%d-%H%M%S').log"
exec > >(tee -a "${LOG_FILE}") 2>&1

log "workspace=${WORKSPACE}"
log "log=${LOG_FILE}"
log "GIT_BASE=${GIT_BASE}"
log "GIT_FALLBACK=${GIT_FALLBACK}"

resolve_java25() {
  local cand ver
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    ver="$("${JAVA_HOME}/bin/java" -version 2>&1 | head -1 || true)"
    if echo "${ver}" | grep -qF 'version "25'; then
      return 0
    fi
    warn "ignoring non-JDK-25 JAVA_HOME=${JAVA_HOME} (${ver})"
  fi
  if command -v mise >/dev/null 2>&1; then
    local mise_home
    mise_home="$(mise where java@zulu-25 2>/dev/null || mise where java@25 2>/dev/null || true)"
    if [[ -n "${mise_home}" && -x "${mise_home}/bin/java" ]]; then
      export JAVA_HOME="${mise_home}"
      return 0
    fi
  fi
  for cand in \
    "${HOME}/.local/share/mise/installs/java/zulu-25" \
    "${HOME}/.local/share/mise/installs/java/25" \
    /usr/lib/jvm/zulu-25 \
    /usr/lib/jvm/java-25-openjdk \
    /usr/lib/jvm/java-25; do
    if [[ -x "${cand}/bin/java" ]]; then
      export JAVA_HOME="${cand}"
      return 0
    fi
  done
  return 1
}

if ! resolve_java25; then
  die "JDK 25 required. Install mise zulu-25 or set JAVA_HOME to a JDK 25. Do NOT use java-8."
fi
export PATH="${JAVA_HOME}/bin:${PATH}"
JAVA_VER="$("${JAVA_HOME}/bin/java" -version 2>&1 | head -1 || true)"
echo "${JAVA_VER}" | grep -qF 'version "25' \
  || die "JAVA_HOME must be JDK 25, got: ${JAVA_VER}"
command -v mvn >/dev/null 2>&1 || die "mvn not found on PATH (need Maven 3.9+)"
command -v git >/dev/null 2>&1 || die "git not found on PATH"
log "JAVA_HOME=${JAVA_HOME} (${JAVA_VER})"
log "mvn=$(mvn -v 2>&1 | head -1)"

# name|branch|dir_under_workspace
REPOS=(
  "sctp|java25-upgrade|sctp"
  "jss7|j25|jss7"
  "jain-slee|micro-jainslee-2|jain-slee"
)

git_url() {
  local base="$1" repo="$2"
  if [[ "${base}" == git@* ]]; then
    printf '%s/%s.git\n' "${base}" "${repo}"
  else
    printf '%s/%s.git\n' "${base%/}" "${repo}"
  fi
}

clone_with_fallback() {
  local repo="$1" branch="$2" path="$3"
  local url
  url="$(git_url "${GIT_BASE}" "${repo}")"
  log "clone: ${url} → ${path} (${branch})"
  if git clone --branch "${branch}" --single-branch "${url}" "${path}"; then
    return 0
  fi
  warn "${GIT_BASE}/${repo} clone failed; trying ${GIT_FALLBACK}"
  url="$(git_url "${GIT_FALLBACK}" "${repo}")"
  git clone --branch "${branch}" --single-branch "${url}" "${path}" \
    || die "clone failed for ${repo} from both ${GIT_BASE} and ${GIT_FALLBACK}"
}

ensure_repo() {
  local repo="$1" branch="$2" dir="$3"
  local path="${WORKSPACE}/${dir}"

  if [[ "${FRESH}" -eq 1 && -e "${path}" ]]; then
    log "fresh: removing ${path}"
    rm -rf "${path}"
  fi

  if [[ -d "${path}/.git" ]]; then
    log "exists: ${path} (branch ${branch})"
    if [[ "${SKIP_PULL:-0}" != "1" ]]; then
      git -C "${path}" fetch --prune origin "${branch}" || warn "fetch failed for ${repo}"
      git -C "${path}" checkout "${branch}"
      git -C "${path}" pull --ff-only origin "${branch}" \
        || warn "pull --ff-only failed for ${repo} (continuing with local tree)"
    fi
  else
    clone_with_fallback "${repo}" "${branch}" "${path}"
  fi
}

# Prefer the checkout that contains this script when packaging USSD (lab / existing clone).
USSD_DIR=""
if [[ "${FORCE_CLONE_USSD}" -eq 0 && -x "${SCRIPT_DIR}/build/package-dist.sh" ]]; then
  USSD_DIR="${SCRIPT_DIR}"
  log "using script-local ussdgw: ${USSD_DIR}"
fi

for entry in "${REPOS[@]}"; do
  IFS='|' read -r repo branch dir <<<"${entry}"
  ensure_repo "${repo}" "${branch}" "${dir}"
done

ensure_ussd_repo() {
  local path="${WORKSPACE}/ussdgw"
  if [[ "${FRESH}" -eq 1 && -e "${path}" && "${FORCE_CLONE_USSD}" -eq 1 ]]; then
    rm -rf "${path}"
  fi
  if [[ -d "${path}/.git" ]]; then
    log "exists: ${path}"
    return 0
  fi
  local candidates=("ussdgw|master" "ussdgw-jainslee|master" "ussdgw|main" "ussdgw-jainslee|main")
  local entry repo branch url
  for entry in "${candidates[@]}"; do
    IFS='|' read -r repo branch <<<"${entry}"
    url="$(git_url "${GIT_BASE}" "${repo}")"
    log "try clone ussd: ${url} (${branch})"
    if git clone --branch "${branch}" --single-branch "${url}" "${path}" 2>/dev/null; then
      return 0
    fi
    url="$(git_url "${GIT_FALLBACK}" "${repo}")"
    log "try clone ussd fallback: ${url} (${branch})"
    if git clone --branch "${branch}" --single-branch "${url}" "${path}" 2>/dev/null; then
      return 0
    fi
  done
  die "could not clone ussdgw / ussdgw-jainslee from ${GIT_BASE} or ${GIT_FALLBACK}"
}

if [[ -z "${USSD_DIR}" ]]; then
  ensure_ussd_repo
  USSD_DIR="${WORKSPACE}/ussdgw"
fi
[[ -x "${USSD_DIR}/build/package-dist.sh" ]] \
  || die "missing ${USSD_DIR}/build/package-dist.sh"

resolve_maven_root() {
  local root="$1"
  if [[ -f "${root}/pom.xml" ]]; then
    printf '%s\n' "${root}"
  elif [[ -f "${root}/jain-slee/pom.xml" ]]; then
    printf '%s\n' "${root}/jain-slee"
  else
    die "no pom.xml under ${root}"
  fi
}

SCTP_ROOT="$(resolve_maven_root "${WORKSPACE}/sctp")"
JSS7_ROOT="$(resolve_maven_root "${WORKSPACE}/jss7")"
JAIN_ROOT="$(resolve_maven_root "${WORKSPACE}/jain-slee")"

m2_has() {
  local g="$1" a="$2" v="$3"
  local path="${HOME}/.m2/repository/$(echo "${g}" | tr '.' '/')/${a}/${v}"
  [[ -f "${path}/${a}-${v}.jar" || -f "${path}/${a}-${v}.pom" ]]
}

need_build() {
  local label="$1"
  shift
  if [[ "${REBUILD}" -eq 1 ]]; then
    return 0
  fi
  local ga
  for ga in "$@"; do
    IFS=':' read -r g a v <<<"${ga}"
    if ! m2_has "${g}" "${a}" "${v}"; then
      log "missing ~/.m2 ${ga} → will build ${label}"
      return 0
    fi
  done
  log "skip ${label} (artifacts present; pass --rebuild to force)"
  return 1
}

mvn_install() {
  local label="$1" dir="$2"
  shift 2
  log "═══ mvn install: ${label} (${dir}) ═══"
  (
    cd "${dir}"
    # shellcheck disable=SC2086
    mvn ${MVN_OPTS} clean install -DskipTests "$@"
  )
}

if need_build "sctp" \
  "org.mobicents.protocols.sctp:sctp-impl:2.27.32" \
  "org.mobicents.protocols.sctp:sctp-api:2.27.32"; then
  mvn_install "sctp" "${SCTP_ROOT}"
fi

if need_build "jss7" \
  "org.restcomm.protocols.ss7.config:ss7-config:9.2.8-j25" \
  "org.restcomm.protocols.ss7.map:map-impl:9.2.8-j25"; then
  mvn_install "jss7" "${JSS7_ROOT}" -Dmaven.test.skip=true
fi

if need_build "jain-slee" \
  "com.microjainslee:jainslee-core:1.2.0-SNAPSHOT" \
  "com.microjainslee:ra-jss7:1.2.0-SNAPSHOT" \
  "com.microjainslee:ra-http-server:1.2.0-SNAPSHOT" \
  "com.microjainslee:adapter-quarkus:1.2.0-SNAPSHOT"; then
  mvn_install "jain-slee (micro-jainslee)" "${JAIN_ROOT}"
fi

log "═══ package-dist: ${USSD_DIR} ═══"
(
  cd "${USSD_DIR}"
  export JAVA_HOME PATH
  ./build/package-dist.sh
)

DIST_DIR="${USSD_DIR}/dist"
[[ -x "${DIST_DIR}/run.sh" ]] || die "package incomplete: ${DIST_DIR}/run.sh missing"
[[ -f "${DIST_DIR}/quarkus-run.jar" ]] || die "package incomplete: quarkus-run.jar missing"

echo
log "DONE — USSD GW dist ready (not started)."
echo "  dist:     ${DIST_DIR}"
echo "  start:    cd ${DIST_DIR} && ./run.sh"
echo "  log:      ${LOG_FILE}"
echo "  workspace:${WORKSPACE}"
echo
echo "Ship only dist/ to the server (or RestLink/Ussdgw with USSD_MIRROR_LEGACY=1)."
