#!/usr/bin/env bash
# Move the audited source chain to the LATEST pushed head of each pinned branch, and bring every
# place that names a version along with it.
#
#   ./docker/build/update-sources.sh                 # update + check out the trees
#   ./docker/build/update-sources.sh --dry-run       # print what would change, touch nothing
#
# What it rewrites (repo-relative):
#   docker/sources.lock                     <name>|<url>|<branch>|<sha>   (SHA stays authoritative)
#   docker/build/fetch-sources-digicom.sh   the same SHAs for the digicom-et mirrors
#   pom.xml                                 microjainslee.version, sctp.version
#   docker/build/Dockerfile                 ARG MICROJAINSLLEE_VERSION, ARG SS7_VERSION
#
# Versions are READ from the trees at the new SHAs (jain-slee BOM version, ra-jss7 ss7.version,
# sctp-parent version) — never typed by hand — so the pom and the lock cannot disagree.
# An entry whose ref is not a branch on the remote (a tag, e.g. corsac-diameter) is left alone.
#
# The result is a working-tree change: review `git diff`, run the tests, commit, then push with
# build/push-dual.sh. full-build.sh --latest runs this and builds with a -dirty tag.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
LOCK="${LOCK:-$REPO_ROOT/docker/sources.lock}"
DIGICOM_LOCK_SCRIPT="${DIGICOM_LOCK_SCRIPT:-$REPO_ROOT/docker/build/fetch-sources-digicom.sh}"
POM="${POM:-$REPO_ROOT/pom.xml}"
BUILDER_DOCKERFILE="${BUILDER_DOCKERFILE:-$REPO_ROOT/docker/build/Dockerfile}"
SOURCE_ROOT="${SOURCE_ROOT:-/srv/ussdgw-build/src}"
DRY_RUN=0
for a in "$@"; do
  case "$a" in
    --dry-run) DRY_RUN=1 ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "update-sources: unknown argument $a" >&2; exit 2 ;;
  esac
done

die()  { echo "update-sources: ERROR: $*" >&2; exit 1; }
info() { echo "update-sources: $*"; }
warn() { echo "update-sources: WARN: $*" >&2; }

[[ -f "$LOCK" ]] || die "no $LOCK"
command -v git >/dev/null || die "git is required"

# --- 1. resolve the head of every pinned branch ----------------------------------------------
declare -A NEW_SHA=()
changed=0
while IFS='|' read -r name url ref sha; do
  [[ -z "$name" || "$name" == \#* || "$name" == base-* ]] && continue
  [[ -n "${url:-}" && -n "${ref:-}" && -n "${sha:-}" ]] || continue
  head="$(timeout 60 git ls-remote "$url" "refs/heads/$ref" 2>/dev/null | cut -f1 || true)"
  if [[ -z "$head" ]]; then
    info "$name: '$ref' is not a branch on $url (tag or unreachable) — kept at ${sha:0:9}"
    continue
  fi
  NEW_SHA[$name]="$head"
  if [[ "$head" == "$sha" ]]; then
    info "$name: $ref already at ${sha:0:9}"
  else
    info "$name: $ref ${sha:0:9} -> ${head:0:9}"
    changed=1
  fi
done < <(grep -vE '^\s*(#|$)' "$LOCK" | awk -F'|' 'NF==4')

if [[ "$DRY_RUN" == 1 ]]; then
  info "dry run — nothing written"
  exit 0
fi

# --- 2. rewrite both locks (same SHA, different mirror URL) ----------------------------------
rewrite_lock_lines() {  # $1 = file
  local f="$1" tmp
  [[ -f "$f" ]] || return 0
  tmp="$(mktemp)"
  awk -F'|' -v OFS='|' -v pairs="$(for k in "${!NEW_SHA[@]}"; do printf '%s=%s;' "$k" "${NEW_SHA[$k]}"; done)" '
    BEGIN { n = split(pairs, kv, ";"); for (i = 1; i <= n; i++) if (kv[i] != "") { split(kv[i], p, "="); want[p[1]] = p[2] } }
    NF == 4 && ($1 in want) && $4 ~ /^[0-9a-f]{40}$/ { $4 = want[$1] }
    { print }' "$f" > "$tmp"
  cat "$tmp" > "$f"; rm -f "$tmp"
}
rewrite_lock_lines "$LOCK"
rewrite_lock_lines "$DIGICOM_LOCK_SCRIPT"

# --- 3. bring the source trees to the new SHAs (local mode builds exactly these) -------------
while IFS='|' read -r name url ref sha; do
  [[ -n "${NEW_SHA[$name]:-}" ]] || continue
  tree="$SOURCE_ROOT/$name"
  if [[ -d "$tree/.git" ]]; then
    if git -C "$tree" cat-file -e "${NEW_SHA[$name]}^{commit}" 2>/dev/null \
       || timeout 300 git -C "$tree" fetch -q "$url" "${NEW_SHA[$name]}"; then
      git -C "$tree" checkout -q --detach "${NEW_SHA[$name]}"
      info "$tree checked out at ${NEW_SHA[$name]:0:9}"
    else
      warn "$tree: cannot fetch ${NEW_SHA[$name]:0:9} from $url — run full-build.sh --fetch-sources"
    fi
  fi
done < <(grep -vE '^\s*(#|$)' "$LOCK" | awk -F'|' 'NF==4 && $1 !~ /^base-/')

# --- 4. versions, read from the trees --------------------------------------------------------
# One awk for "the <version> right after <artifactId>X</artifactId>", one for a <property>.
coord_version() { awk -v a="<artifactId>$2</artifactId>" 'index($0,a){f=1;next} f&&match($0,/<version>[^<]+/){print substr($0,RSTART+9,RLENGTH-9);exit}' "$1" 2>/dev/null || true; }
prop() { grep -m1 -oE "<$2>[^<]+" "$1" 2>/dev/null | cut -d'>' -f2 || true; }

mj="" ; ss7="" ; sctp=""
if [[ -f "$SOURCE_ROOT/jain-slee/jainslee-pom/pom.xml" ]]; then
  mj="$(coord_version "$SOURCE_ROOT/jain-slee/jainslee-pom/pom.xml" jainslee-pom)"
  ss7="$(prop "$SOURCE_ROOT/jain-slee/vendor-ras/ra-jss7/pom.xml" ss7.version)"
fi
[[ -f "$SOURCE_ROOT/sctp/pom.xml" ]] && sctp="$(coord_version "$SOURCE_ROOT/sctp/pom.xml" sctp-parent)"
jss7v=""
[[ -f "$SOURCE_ROOT/jss7/pom.xml" ]] && jss7v="$(coord_version "$SOURCE_ROOT/jss7/pom.xml" ss7-parent)"

set_prop() {  # $1 file, $2 property, $3 value
  [[ -n "$3" ]] || return 0
  local cur; cur="$(prop "$1" "$2")"
  [[ "$cur" == "$3" ]] && return 0
  sed -i "0,/<$2>[^<]*<\/$2>/s//<$2>$3<\/$2>/" "$1"
  info "$(basename "$1"): $2 ${cur:-?} -> $3"
}
set_arg() {  # $1 file, $2 ARG name, $3 value
  [[ -n "$3" ]] || return 0
  grep -q "^ARG $2=$3\$" "$1" && return 0
  sed -i "s|^ARG $2=.*|ARG $2=$3|" "$1"
  info "$(basename "$1"): ARG $2 -> $3"
}

if [[ -n "$mj" ]]; then
  set_prop "$POM" microjainslee.version "$mj"
  set_arg "$BUILDER_DOCKERFILE" MICROJAINSLLEE_VERSION "$mj"
else
  warn "no jain-slee tree under $SOURCE_ROOT — pom/Dockerfile micro-jainslee version NOT synced"
fi
set_arg "$BUILDER_DOCKERFILE" SS7_VERSION "$ss7"
set_prop "$POM" sctp.version "$sctp"
if [[ -n "$ss7" && -n "$jss7v" && "$ss7" != "$jss7v" ]]; then
  warn "jain-slee ra-jss7 expects jSS7 $ss7 but the pinned jss7 tree is $jss7v — build-all.sh will refuse"
fi

[[ "$changed" == 1 ]] && info "sources moved — review: git diff docker/sources.lock pom.xml docker/build/" \
                      || info "sources already at the latest heads"
