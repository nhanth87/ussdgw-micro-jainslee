#!/usr/bin/env bash
# Dual-push: nhanth87 (public/lab) + digicom-et (private + Digicom carrier seeds).
#
# Model:
#   main     → origin (nhanth87)     — lab/test only (ss7-lab.json); NO Digicom carrier files
#   digicom  → digicom-et main       — main + Digicom overlay (ss7-digicom-balance.json, …)
#
# Usage:
#   ./build/push-dual.sh              # push current main + rebuild/push digicom overlay
#   ./build/push-dual.sh --dry-run    # print plan only
#   ./build/push-dual.sh --origin-only
#   ./build/push-dual.sh --digicom-only
#   ./build/push-dual.sh --force-digicom  # digicom-et only: --force-with-lease (private)
#
# Digicom tip normally merges main (FF-friendly). Use --force-digicom only when the
# private tip was rebuilt and is not a fast-forward of digicom-et/main.
#
# Digicom seed sources (first hit wins when building digicom branch):
#   1) working tree paths listed below (often gitignored on main — keep them locally)
#   2) previous tip of local digicom / digicom-et/main / private/digicom-carrier-seeds
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ORIGIN_REMOTE="${ORIGIN_REMOTE:-origin}"
DIGICOM_REMOTE="${DIGICOM_REMOTE:-digicom-et}"
PUBLIC_BRANCH="${PUBLIC_BRANCH:-main}"
DIGICOM_BRANCH="${DIGICOM_BRANCH:-digicom}"
DIGICOM_REMOTE_BRANCH="${DIGICOM_REMOTE_BRANCH:-main}"

# Carrier paths that must NEVER appear on origin/main (tracked).
DIGICOM_PATHS=(
  "build/ss7-digicom-balance.json"
  "build/application-digicom.properties"
  "build/systemd/install-on-digicom.sh"
  "dist/configs/ss7-digicom-balance.json"
  "dist/configs/application-digicom.properties"
)

DRY_RUN=0
DO_ORIGIN=1
DO_DIGICOM=1
FORCE_DIGICOM=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --origin-only) DO_DIGICOM=0 ;;
    --digicom-only) DO_ORIGIN=0 ;;
    --force-digicom) FORCE_DIGICOM=1 ;;
    -h|--help)
      sed -n '2,24p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown arg: $arg" >&2
      exit 2
      ;;
  esac
done

die() { echo "push-dual: $*" >&2; exit 1; }
info() { echo "push-dual: $*"; }

# Tracked dirty tree blocks push. Digicom paths that are gitignored are OK.
require_clean_tracked() {
  if ! git diff --quiet || ! git diff --cached --quiet; then
    die "tracked working tree dirty — commit or stash first (ignored Digicom seeds OK)"
  fi
}

# Untracked but NOT ignored Digicom paths would leak onto a careless git add.
require_no_unignored_digicom() {
  local p
  for p in "${DIGICOM_PATHS[@]}"; do
    if [[ -e "$p" ]] && ! git check-ignore -q "$p" 2>/dev/null; then
      if ! git ls-files --error-unmatch "$p" >/dev/null 2>&1; then
        die "untracked Digicom path not gitignored: $p (fix .gitignore or remove before origin push)"
      fi
    fi
  done
}

tracked_digicom_on_ref() {
  local ref="$1"
  local p
  for p in "${DIGICOM_PATHS[@]}"; do
    if git cat-file -e "${ref}:${p}" 2>/dev/null; then
      echo "$p"
    fi
  done
}

assert_public_clean() {
  local ref="$1"
  local bad
  bad="$(tracked_digicom_on_ref "$ref" || true)"
  if [[ -n "${bad}" ]]; then
    die "ref ${ref} tracks Digicom carrier file(s) — refuse origin push:\n${bad}"
  fi
}

restore_digicom_from_ref() {
  local ref="$1"
  local p restored=0
  for p in "${DIGICOM_PATHS[@]}"; do
    if git cat-file -e "${ref}:${p}" 2>/dev/null; then
      mkdir -p "$(dirname "$p")"
      git show "${ref}:${p}" >"$p"
      if [[ "$p" == *.sh ]]; then
        chmod +x "$p"
      fi
      restored=1
      info "restored $p from ${ref}"
    fi
  done
  return $(( restored == 0 ))
}

ensure_digicom_files_present() {
  local missing=0 p
  for p in "build/ss7-digicom-balance.json" "build/application-digicom.properties"; do
    [[ -f "$p" ]] || missing=1
  done
  if [[ "$missing" -eq 0 ]]; then
    return 0
  fi
  info "Digicom seeds missing in working tree — trying prior tips"
  for ref in "$DIGICOM_BRANCH" "${DIGICOM_REMOTE}/${DIGICOM_REMOTE_BRANCH}" "private/digicom-carrier-seeds" "${DIGICOM_REMOTE}/private/digicom-carrier-seeds"; do
    if git rev-parse --verify "$ref" >/dev/null 2>&1; then
      if restore_digicom_from_ref "$ref"; then
        return 0
      fi
    fi
  done
  die "cannot find Digicom seed files (need build/ss7-digicom-balance.json + build/application-digicom.properties)"
}

# install-on-digicom.sh: prefer existing file; else clone install-lab-units.sh with Digicom name.
ensure_install_on_digicom() {
  local dest="build/systemd/install-on-digicom.sh"
  if [[ -f "$dest" ]]; then
    return 0
  fi
  if [[ -f build/systemd/install-lab-units.sh ]]; then
    sed 's/install-lab-units/install-on-digicom/g; s/lab host/Digicom host/g' \
      build/systemd/install-lab-units.sh >"$dest"
    chmod +x "$dest"
    info "wrote $dest from install-lab-units.sh"
  fi
}

push_origin() {
  assert_public_clean "HEAD"
  require_no_unignored_digicom
  info "push ${PUBLIC_BRANCH} → ${ORIGIN_REMOTE}/${PUBLIC_BRANCH} (lab only)"
  if [[ "$DRY_RUN" -eq 1 ]]; then
    return 0
  fi
  git push "$ORIGIN_REMOTE" "HEAD:${PUBLIC_BRANCH}"
}

build_and_push_digicom() {
  local start_branch overlay_msg
  start_branch="$(git branch --show-current)"
  [[ -n "$start_branch" ]] || die "detached HEAD — checkout ${PUBLIC_BRANCH} first"
  [[ "$start_branch" == "$PUBLIC_BRANCH" ]] || die "checkout ${PUBLIC_BRANCH} before digicom overlay (on ${start_branch})"

  assert_public_clean "HEAD"
  ensure_digicom_files_present
  ensure_install_on_digicom

  info "update ${DIGICOM_BRANCH} = merge ${PUBLIC_BRANCH} + Digicom overlay"
  if [[ "$DRY_RUN" -eq 1 ]]; then
    info "would force-add: ${DIGICOM_PATHS[*]}"
    if [[ "$FORCE_DIGICOM" -eq 1 ]]; then
      info "would push --force-with-lease → ${DIGICOM_REMOTE}/${DIGICOM_REMOTE_BRANCH}"
    else
      info "would push ${DIGICOM_BRANCH} → ${DIGICOM_REMOTE}/${DIGICOM_REMOTE_BRANCH}"
    fi
    return 0
  fi

  # Preserve ignored Digicom files across branch switch.
  local stash_dir
  stash_dir="$(mktemp -d "${TMPDIR:-/tmp}/push-dual-digicom.XXXXXX")"
  local p
  for p in "${DIGICOM_PATHS[@]}"; do
    if [[ -f "$p" ]]; then
      mkdir -p "$stash_dir/$(dirname "$p")"
      cp -a "$p" "$stash_dir/$p"
    fi
  done

  if git rev-parse --verify "$DIGICOM_BRANCH" >/dev/null 2>&1; then
    git checkout "$DIGICOM_BRANCH"
    # Prefer FF merge of lab main; keep prior Digicom overlay commits when possible.
    if ! git -c user.name='Tran Nhan' -c user.email='nhanth87@gmail.com' merge --ff-only "$PUBLIC_BRANCH" 2>/dev/null; then
      git -c user.name='Tran Nhan' -c user.email='nhanth87@gmail.com' \
        merge --no-edit -m "Merge ${PUBLIC_BRANCH} into ${DIGICOM_BRANCH} for Digicom overlay." "$PUBLIC_BRANCH" \
        || die "merge ${PUBLIC_BRANCH} into ${DIGICOM_BRANCH} failed — resolve manually"
    fi
  else
    git branch "$DIGICOM_BRANCH" "$PUBLIC_BRANCH"
    git checkout "$DIGICOM_BRANCH"
  fi

  for p in "${DIGICOM_PATHS[@]}"; do
    if [[ -f "$stash_dir/$p" ]]; then
      mkdir -p "$(dirname "$p")"
      cp -a "$stash_dir/$p" "$p"
    fi
  done
  rm -rf "$stash_dir"

  # Force-add despite main .gitignore (Digicom-only on this branch).
  git add -f build/ss7-digicom-balance.json \
            build/application-digicom.properties \
            build/systemd/install-on-digicom.sh 2>/dev/null || true
  [[ -f dist/configs/ss7-digicom-balance.json ]] && git add -f dist/configs/ss7-digicom-balance.json || true
  [[ -f dist/configs/application-digicom.properties ]] && git add -f dist/configs/application-digicom.properties || true

  if git diff --cached --quiet; then
    info "digicom overlay already present on ${DIGICOM_BRANCH}"
  else
    overlay_msg="$(cat <<'EOF'
Keep Digicom carrier seeds on private digicom-et main.

Public nhanth87 main stays lab-only; this branch is main plus Digicom SS7/props overlay.
EOF
)"
    git -c user.name='Tran Nhan' -c user.email='nhanth87@gmail.com' commit -m "$overlay_msg"
  fi

  info "push ${DIGICOM_BRANCH} → ${DIGICOM_REMOTE}/${DIGICOM_REMOTE_BRANCH}"
  if [[ "$FORCE_DIGICOM" -eq 1 ]]; then
    git push --force-with-lease="${DIGICOM_REMOTE_BRANCH}:refs/remotes/${DIGICOM_REMOTE}/${DIGICOM_REMOTE_BRANCH}" \
      "$DIGICOM_REMOTE" "${DIGICOM_BRANCH}:${DIGICOM_REMOTE_BRANCH}"
  else
    if ! git push "$DIGICOM_REMOTE" "${DIGICOM_BRANCH}:${DIGICOM_REMOTE_BRANCH}"; then
      die "digicom-et push rejected (non-FF). Review digicom-et/main then re-run with --force-digicom"
    fi
  fi

  git checkout "$PUBLIC_BRANCH"
  ensure_digicom_files_present || true
  info "back on ${PUBLIC_BRANCH}; Digicom seeds kept locally (gitignored)"
}

# --- main ---
git rev-parse --is-inside-work-tree >/dev/null
git remote get-url "$ORIGIN_REMOTE" >/dev/null
git remote get-url "$DIGICOM_REMOTE" >/dev/null

cur="$(git branch --show-current)"
[[ "$cur" == "$PUBLIC_BRANCH" ]] || die "must run from ${PUBLIC_BRANCH} (currently ${cur:-detached})"

require_clean_tracked
require_no_unignored_digicom

if [[ "$DO_ORIGIN" -eq 1 ]]; then
  push_origin
fi
if [[ "$DO_DIGICOM" -eq 1 ]]; then
  build_and_push_digicom
fi

info "done"
info "  ${ORIGIN_REMOTE}/${PUBLIC_BRANCH}     = lab (nhanth87)"
info "  ${DIGICOM_REMOTE}/${DIGICOM_REMOTE_BRANCH} = Digicom-inclusive"
