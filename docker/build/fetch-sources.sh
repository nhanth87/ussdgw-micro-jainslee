#!/usr/bin/env bash
# Fetch and verify the audited source chain (plan.md §5 Phase 0/1, R3).
#
# Two modes:
#   SOURCE_MODE=local  (default, RECOMMENDED for audit)
#       The operator hands us a /src tree. We only VERIFY each directory's commit
#       against sources.lock. No GitHub access, no network, nothing to trust.
#   SOURCE_MODE=git
#       Clone each repo at the pinned SHA. Token (private repos) via a BuildKit
#       secret / env file — never an ARG, ENV, or a URL with a PAT in it.
#
# Fail-closed: any SHA mismatch is fatal. A moved branch must not change what we
# build, so the SHA is authoritative and branch names are ignored.
set -euo pipefail

SRC_DIR="${SRC_DIR:-/src}"
# The image ships the lock at /usr/local/share/sources.lock; in a source checkout it
# sits next to this script. Resolve both so the same script works in either place.
resolve_lock() {
  if [[ -n "${LOCK_FILE:-}" && -f "$LOCK_FILE" ]]; then
    echo "$LOCK_FILE"; return
  fi
  local here="$(cd "$(dirname "$0")" && pwd)"
  for cand in "$here/../sources.lock" "$here/sources.lock" /usr/local/share/sources.lock; do
    [[ -f "$cand" ]] && { echo "$cand"; return; }
  done
  return 1
}
SOURCE_MODE="${SOURCE_MODE:-local}"
GH_TOKEN="${GH_TOKEN:-}"

die() { echo "fetch-sources: ERROR: $*" >&2; exit 1; }
info() { echo "fetch-sources: $*"; }

# Token must never end up in a URL that gets logged. Use a credential helper.
git_auth() {
  if [[ -n "$GH_TOKEN" ]]; then
    local askpass
    askpass="$(mktemp)"
    # Never echo the token; answer the prompt from the env var instead.
    cat >"$askpass" <<EOF
#!/bin/sh
case "\$1" in
  *sername*) echo "\${GIT_AUTH_USER:-x-access-token}" ;;
  *assword*) echo "\${GH_TOKEN}" ;;
  *) echo ;;
esac
EOF
    chmod +x "$askpass"
    export GIT_ASKPASS="$askpass"
    export GIT_TERMINAL_PROMPT=0
    # AskPass is a file, so it must outlive this function.
    ASKPASS_FILE="$askpass"
  fi
}

# Reads "<name>|<url>|<ref>|<sha>" lines, ignoring comments and section headers.
lock_entries() {
  grep -vE '^\s*(#|$)' "$LOCK_FILE" \
    | awk -F'|' 'NF==4 && $1 !~ /^(base)-/ {print}'
}

verify_local_sha() {
  local name="$1" want="$2" dir="$3"
  [[ -d "$dir" ]] || die "$name: expected source dir '$dir' (SOURCE_MODE=local)"
  local got
  got="$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)"
  if [[ -z "$got" ]]; then
    die "$name: '$dir' is not a git checkout — the operator must hand over a tree at a known commit"
  fi
  if [[ "$got" != "$want" ]]; then
    die "$name: SHA mismatch
       want $want  (sources.lock)
       got  $got  ($dir)
     Refusing to build: an unaudited tree must never be compiled."
  fi
  info "$name: SHA verified $got"
}

fetch_git_sha() {
  local name="$1" url="$2" ref="$3" sha="$4" dir="$5"
  if [[ -d "$dir/.git" ]]; then
    git_auth
    git -C "$dir" fetch --quiet --depth 1 origin "$sha" 2>/dev/null \
      || git -C "$dir" fetch --quiet origin
    git -C "$dir" checkout --quiet --detach "$sha"
  else
    git_auth
    # Clone then hard-pin. The ref is only a hint; the SHA decides.
    git clone --quiet --no-checkout "$url" "$dir"
    git -C "$dir" checkout --quiet --detach "$sha"
  fi
  local got
  got="$(git -C "$dir" rev-parse HEAD)"
  [[ "$got" == "$sha" ]] || die "$name: checked out $got, expected $sha"
  info "$name: checked out $sha (ref hint: $ref)"
}

main() {
  LOCK_FILE="$(resolve_lock)" \
  || die "sources.lock not found (looked next to this script and /usr/local/share)"
  local count=0
  while IFS='|' read -r name url ref sha; do
    [[ -n "$name" ]] || continue
    local dir="$SRC_DIR/$name"
    case "$SOURCE_MODE" in
      local) verify_local_sha "$name" "$sha" "$dir" ;;
      git)   fetch_git_sha "$name" "$url" "$ref" "$sha" "$dir" ;;
      *)     die "SOURCE_MODE must be 'local' or 'git' (got '$SOURCE_MODE')" ;;
    esac
    count=$((count + 1))
  done < <(lock_entries)
  [[ "$count" -gt 0 ]] || die "sources.lock contained no source entries — refusing to build nothing"
  info "$count source repos verified (mode=$SOURCE_MODE)"
}

main "$@"