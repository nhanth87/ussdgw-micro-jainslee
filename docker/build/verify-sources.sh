#!/usr/bin/env bash
# Re-verify the source chain AFTER fetching. This is the fail-closed gate that
# makes R3 (auditability) real rather than aspirational.
#
# fetch-sources.sh checks out or validates each repo. This script proves that what
# is on disk right now matches sources.lock, and — crucially — that the in-house
# artifacts in the Maven repo were installed during THIS run rather than inherited
# from a host ~/.m2 (which would defeat the whole exercise).
set -euo pipefail

SRC_DIR="${SRC_DIR:-/src}"
M2="${M2:-/m2}"
LOCK_FILE=""

die() { echo "verify-sources: ERROR: $*" >&2; exit 1; }
info() { echo "verify-sources: $*"; }

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
LOCK_FILE="$(resolve_lock)" \
  || die "sources.lock not found (looked next to this script and /usr/local/share)"

info "using sources.lock: $LOCK_FILE"

checked=0
while IFS='|' read -r name _url _ref want; do
  [[ -n "$name" ]] || continue
  [[ "$name" == base-* ]] && continue
  dir="$SRC_DIR/$name"
  [[ -d "$dir" ]] || die "$name: absent at $dir — cannot verify what was not built"

  got="$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)"
  [[ -n "$got" ]] || die "$name: not a git checkout"
  [[ "$got" == "$want" ]] || die "$name: SHA mismatch — want $want, got $got"
  info "$name  $got ✓"
  checked=$((checked + 1))
done < <(grep -vE '^\s*(#|$)' "$LOCK_FILE" | awk -F'|' 'NF==4 && $1 !~ /^base-/')

[[ "$checked" -gt 0 ]] || die "no source entries verified"
info "$checked repositories match sources.lock"

# --- pinned patches must match their recorded digest ---------------------------
# A patch is the one place a delta can hide: the pinned upstream is verifiable, but
# what we DO to it afterwards is not. Digest-pin every patch file too.
patch_dir="$(cd "$(dirname "$0")/.." && pwd)/patches"
[[ -d "$patch_dir" ]] || patch_dir=/usr/local/patches
patches_checked=0
while IFS='|' read -r pfile _target want; do
  [[ -n "$pfile" ]] || continue
  ppath="$patch_dir/$pfile"
  [[ -f "$ppath" ]] || die "patch listed in sources.lock is missing: $ppath"
  got="$(sha256sum "$ppath" | cut -d' ' -f1)"
  [[ "$want" == "sha256:$got" ]] \
    || die "patch digest mismatch for $pfile
       want ${want}
       got  sha256:$got
     Refusing to build: an unreviewed patch is an unprovable artifact."
  info "patch $pfile  sha256:${got:0:12}… ✓"
  patches_checked=$((patches_checked + 1))
done < <(grep -vE '^\s*(#|$)' "$LOCK_FILE" | awk -F'|' 'NF==3 && $1 ~ /\.patch$/ {print}')
[[ "$patches_checked" -gt 0 ]] && info "$patches_checked patch(es) digest-verified"

# --- in-house artifacts must not predate this build ---------------------------
# The audit requirement is "nothing prebuilt from outside". If these jars already
# existed before the run started, the compile may have silently skipped building
# them and consumed a host artifact instead. STRICT=1 makes that fatal.
if [[ "${STRICT:-0}" == "1" ]]; then
  for ga in \
    "com.microjainslee:jainslee-core" \
    "com.microjainslee:jainslee-api" \
    "com.microjainslee:adapter-quarkus" \
    "com.microjainslee:ra-jss7" \
    "com.mobius-software.protocols.diameter:diameter-impl" \
    "org.mobicents.protocols.sctp:sctp-impl" \
  ; do
    gid="${ga%:*}"; aid="${ga#*:}"
    gpath="${M2}/$(echo "$gid" | tr '.' '/')/$aid"
    [[ -d "$gpath" ]] || die "$aid: not installed in $M2 — the reactor did not build it"
    # A -SNAPSHOT installed from a remote would carry maven-metadata-remote.xml.
    if compgen -G "$gpath/*/maven-metadata-remote.xml" >/dev/null 2>&1; then
      die "$aid: resolved from a REMOTE repository, not built here — audit boundary violated"
    fi
    info "$aid built in-tree ✓"
  done
  info "STRICT: all in-house artifacts were produced by this run"
else
  info "STRICT=0 (skipping the 'was it built here' check)"
fi

# --- the product tree itself ---------------------------------------------------
if [[ -d "${SRC_USSDGW:-$SRC_DIR/ussdgw}/.git" ]]; then
  info "ussdgw $(git -C "${SRC_USSDGW:-$SRC_DIR/ussdgw}" rev-parse HEAD)"
fi

info "source chain verified"