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

  # --- TLS certificate for the nginx edge ---------------------------------------
  # docker/nginx/ussdgw.conf has an UNCONDITIONAL `listen 443 ssl` server block, so
  # nginx does not start at all without these two files:
  #
  #     [emerg] cannot load certificate "/etc/nginx/certs/fullchain.pem":
  #             BIO_new_file() failed (SSL: …No such file or directory)
  #
  # A comment in that file used to claim the block was conditional and that "no cert
  # means no :443 server block". It was not, and that false claim is how an HTTP-only
  # first deployment was expected to succeed and would not have.
  #
  # docker/nginx/Dockerfile proves the REST of the config parses, at build time, with a
  # throwaway self-signed pair. It cannot check the operator's real certificate, so
  # this is the half that has to happen on the host.
  #
  # The container runs as uid 101 (nginx) and docker/stack.yml bind-mounts
  # /srv/ussdgw/nginx/certs, so ownership on the HOST decides whether nginx can read
  # the key. A root-owned 0600 key produces:
  #
  #     [emerg] cannot load certificate key "/etc/nginx/certs/privkey.pem":
  #             BIO_new_file() failed (SSL: …Permission denied)
  #
  # which is a start failure with the service then disappearing under
  # `restart_policy: max_attempts: 5` — no :80, no admin UI, and the stack looks fine.
  local cdir="${CERT_DIR:-/srv/ussdgw/nginx/certs}"
  local cert="$cdir/fullchain.pem" key="$cdir/privkey.pem"
  if [[ -f "$cert" && -f "$key" ]]; then
    # Readable by uid 101? Compare against the key, which is the sensitive one.
    local kuid kperm readable=no
    kuid="$(stat -c %u "$key" 2>/dev/null || echo -1)"
    kperm="$(stat -c %a "$key" 2>/dev/null || echo 000)"
    # group/other bit, or owned by nginx (101), or owned by the operator who runs the
    # stack as root — the first two are what actually matter for uid 101.
    if (( kuid == 101 )) || [[ "$kperm" =~ [0-7][0-7][0-46-7] ]] \
       || (( kuid == 0 && $EUID == 0 )); then
      readable=yes
    fi
    if [[ "$readable" == yes ]]; then
      ok "TLS certificate present: $cert (key mode $kperm, uid $kuid)"
    else
      die "TLS key $key is mode $kperm owned by uid $kuid — nginx runs as uid 101 and cannot read it.
     Fix on the host:  sudo install -m 0640 -o 101 -g 101 <key> $key"
    fi

    # Expired or not yet valid is worse than missing: it serves a red browser warning
    # on the operator-facing admin UI rather than a container that refuses to start.
    if command -v openssl >/dev/null 2>&1; then
      local not_after
      not_after="$(openssl x509 -noout -enddate -in "$cert" 2>/dev/null | cut -d= -f2- || true)"
      [[ -n "$not_after" ]] || die "openssl cannot parse $cert — not a PEM certificate"
      if openssl x509 -noout -checkend 86400 -in "$cert" >/dev/null 2>&1; then
        ok "TLS certificate valid for >24h (expires $not_after)"
      else
        die "TLS certificate expired or expires within 24h (notAfter=$not_after)"
      fi
      # A single-leaf cert served as fullchain.pem yields an incomplete chain warning.
      local chain
      chain="$(grep -c 'BEGIN CERTIFICATE' "$cert" || true)"
      if (( chain > 1 )); then
        ok "certificate chain has $chain certificates"
      else
        warn "$cert holds ONE certificate — if the CA issued an intermediate, concatenate it
     (leaf first, then intermediates) or clients will report an incomplete chain."
      fi
    else
      warn "openssl not installed — skipping certificate expiry and chain checks"
    fi
  else
    die "no TLS certificate at $cert / $key — the nginx :443 server block is unconditional and
     nginx will not start without them.
     Seed them on the host, e.g.:
       sudo install -m 0644 -o 101 -g 101 <fullchain.pem> $cert
       sudo install -m 0640 -o 101 -g 101 <privkey.pem>  $key
     (Override the directory with CERT_DIR=… if it differs from the stack's bind mount.)"
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