#!/usr/bin/env bash
# Install the operator's configuration into the persistent mount.
#
# The operator's configs directory is the SOURCE OF TRUTH (plan.md R7). This script
# seeds it ONCE and then never overwrites it: ussdgw's own admin UI writes SS7 stack
# JSON back into configs/, so anything we "refresh" would destroy live edits.
#
# Usage:
#   ./docker/install-config.sh                 # seed, validate, never overwrite
#   ./docker/install-config.sh --force         # back up to *.bak-<ts> first, then overwrite
#   ./docker/install-config.sh --check         # validate only, change nothing
set -euo pipefail

CONFIG_SRC="${CONFIG_SRC:-}"
DEST="${DEST:-/srv/ussdgw/configs}"
MODE="seed"
case "${1:-}" in
  --force) MODE="force" ;;
  --check) MODE="check" ;;
  "") ;;
  *) echo "usage: $0 [--force|--check]" >&2; exit 2 ;;
esac

warn() { echo "install-config: WARN: $*" >&2; }
die()  { echo "install-config: ERROR: $*" >&2; exit 1; }
ok()   { echo "install-config: ok  $*"; }

# --- validation ----------------------------------------------------------------
validate() {
  local dir="$1" errors=0
  [[ -f "$dir/application.properties" ]] || { die "no application.properties in $dir"; }

  # db-kind must match the PG-baked image, or the gateway crash-loops on boot.
  local kind url
  kind="$(grep -E '^quarkus\.datasource\.db-kind=' "$dir/application.properties" | cut -d= -f2- | tr -d ' ')"
  url="$(grep -E '^quarkus\.datasource\.jdbl?c?\.url=|^quarkus\.datasource\.jdbc\.url=' "$dir/application.properties" | cut -d= -f2- | tr -d ' ')"
  [[ "$kind" == "postgresql" ]] \
    || die "db-kind='${kind:-unset}' — the shipped image is PG-baked; H2 here is a crash-loop"
  case "$url" in
    jdbc:postgresql://*) ok "JDBC $url" ;;
    *) die "JDBC url='${url:-unset}' is not jdbc:postgresql://" ;;
  esac

  # SS7 stack files: must parse, and every link must be SCTP (never TCP).
  local f
  for f in "$dir"/ss7-*.json; do
    [[ -f "$f" ]] || continue
    jq empty "$f" 2>/dev/null || die "$(basename "$f") is not valid JSON"
    local bad
    bad="$(jq -r '.. | objects | .channel? // empty' "$f" 2>/dev/null | grep -vi '^sctp$' | sort -u || true)"
    [[ -z "$bad" ]] || die "$(basename "$f") has non-SCTP channel(s): $bad (SS7 is SCTP-only)"
    ok "$(basename "$f") parses, all links channel=sctp"
  done

  # The referenced stack file must exist, or jSS7 NPEs at boot.
  local cfgref
  cfgref="$(grep -E '^ussd\.map\.config-file=' "$dir/application.properties" | cut -d= -f2- | tr -d ' ')"
  if [[ -n "$cfgref" ]]; then
    local resolved="$dir/$(basename "$cfgref")"
    [[ -f "$resolved" ]] || die "ussd.map.config-file=$cfgref but $resolved does not exist"
    ok "map config file present: $(basename "$resolved")"
  fi

  # Lab-only escape hatch must not be on in production.
  if grep -qE '^ussd\.lab\.allow-default-secrets=true' "$dir/application.properties"; then
    warn "ussd.lab.allow-default-secrets=true — lab only. Secrets are NOT fail-closed."
  fi

  # MO SSN 147 lesson: some peers address the gateway as gsmSCF.
  for f in "$dir"/ss7-*.json; do
    [[ -f "$f" ]] || continue
    if jq -e '.. | objects | select(has("ssn")) | .ssn' "$f" 2>/dev/null | grep -qx '147'; then
      ok "$(basename "$f"): SSN 147 (gsmSCF) present"
    else
      warn "$(basename "$f"): no SSN 147 — some peers address the gateway as gsmSCF"
    fi
  done

  # tenant network_id must match the SCCP networkId or routing silently fails.
  warn "remember: every tenant network_id must equal the SCCP networkId it routes on"
  warn "  (live Digicom traffic is typically networkId=0 only; a mismatch gives 'no matching Rule')"

  return $errors
}

# --- mode: check only ----------------------------------------------------------
if [[ "$MODE" == "check" ]]; then
  [[ -n "$CONFIG_SRC" ]] || die "CONFIG_SRC is not set"
  validate "$CONFIG_SRC"
  echo "install-config: check passed for $CONFIG_SRC"
  exit 0
fi

# --- mode: seed / force --------------------------------------------------------
[[ -n "$CONFIG_SRC" ]] || die "CONFIG_SRC is not set (the operator's config directory)"
[[ -d "$CONFIG_SRC" ]] || die "CONFIG_SRC=$CONFIG_SRC is not a directory"

validate "$CONFIG_SRC"

mkdir -p "$DEST"

if [[ -d "$DEST" ]] && [[ -n "$(ls -A "$DEST" 2>/dev/null)" ]]; then
  if [[ "$MODE" == "seed" ]]; then
    ok "destination already populated — leaving it untouched (operator SoT)"
    echo "install-config: run with --force to overwrite (a timestamped backup is taken first)"
    exit 0
  fi
  bak="$DEST.bak-$(date +%Y%m%d%H%M%S)"
  cp -a "$DEST" "$bak"
  ok "backed up existing configs to $bak"
  rm -rf "${DEST:?}"/*
fi

cp -a "$CONFIG_SRC"/. "$DEST"/
mkdir -p "$DEST/ss7-persist"

# The admin UI writes stack JSON here — without it, saving from /admin/ss7 fails.
chmod 775 "$DEST" "$DEST/ss7-persist" 2>/dev/null || warn "could not chmod $DEST"

ok "installed operator config into $DEST"
echo "install-config: next — docker stack deploy -c docker/stack.yml <stackname>"