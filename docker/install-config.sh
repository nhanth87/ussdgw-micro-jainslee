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
# The uid the gateway container runs as (USER 10001:10001 in docker/ussdgw/Dockerfile).
# Seeded files must be owned by it, not by whoever ran this script — see the chown below.
APP_UID="${APP_UID:-10001}"
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

  # Which stack file actually BOOTS. Findings against that file are errors; findings against
  # a spare file in the same tree are warnings. Refusing to install because an UNUSED lab
  # file lacks a key trains operators to ignore this check — and the day someone switches
  # stacks from /admin/ss7 is the day it matters again.
  local cfgref selected_stack
  cfgref="$(grep -E '^ussd\.map\.config-file=' "$dir/application.properties" | cut -d= -f2- | tr -d ' ')"
  selected_stack="$(basename "${cfgref:-ss7-lab.json}")"

  stack_note() {  # $1 = file, $2 = message. Bash locals are dynamically scoped, so this
                  # sees validate()'s `errors` and `selected_stack`.
    if [[ "$(basename "$1")" == "$selected_stack" ]]; then
      errors=$((errors + 1)); echo "ERROR: $2" >&2
    else
      warn "$2"
      warn "  ($(basename "$1") is not what ussd.map.config-file selects, so it does not boot"
      warn "   today — fix it before switching to it from /admin/ss7)"
    fi
  }

  # SS7 stack files: must parse, and every link must be SCTP (never TCP).
  local f
  for f in "$dir"/ss7-*.json; do
    [[ -f "$f" ]] || continue
    if ! jq empty "$f" 2>/dev/null; then
      stack_note "$f" "$(basename "$f") is not valid JSON"
      continue
    fi
    local bad
    bad="$(jq -r '.. | objects | .channel? // empty' "$f" 2>/dev/null | grep -vi '^sctp$' | sort -u || true)"
    [[ -z "$bad" ]] || stack_note "$f" "$(basename "$f") has non-SCTP channel(s): $bad (SS7 is SCTP-only)"

    # ...and WHICH SCTP implementation binds the sockets. "channel: sctp" is necessary but
    # nowhere near sufficient: sctp.backend selects the provider, and SctpBackend.from(null)
    # is FSTACK_DPDK — a userspace DPDK stack needing hugepages and a native
    # libsctp_fstack.so. Omitting the key does not error. The gateway logs
    # "SCTP server … listening" and "ss7=wired" and creates no kernel socket, so the carrier
    # peer never connects and /proc/net/sctp/eps stays empty. That is exactly what shipped on
    # the Digicom test host, and the channel check above passed it.
    local backend lib
    backend="$(jq -r '.sctp.backend // empty' "$f" 2>/dev/null)"
    if [[ -z "$backend" ]]; then
      stack_note "$f" "$(basename "$f") has no sctp.backend, so it resolves to FSTACK_DPDK
       (SctpBackend.from(null)) — a userspace DPDK stack. The gateway boots, logs 'listening'
       on every link and binds nothing: a deaf SS7 plane that looks wired.
       Fix: add  \"backend\": \"NETTY_KERNEL\"  to the sctp block (kernel SCTP), or set
       FSTACK_DPDK explicitly together with a sctp.library path that exists."
      continue
    fi
    case "$(tr '[:lower:]-' '[:upper:]_' <<<"$backend")" in
      NETTY_KERNEL) ;;
      FSTACK_DPDK)
        lib="$(jq -r '.sctp.library // empty' "$f" 2>/dev/null)"
        [[ -n "$lib" && -e "$lib" ]] \
          || stack_note "$f" "$(basename "$f") asks for FSTACK_DPDK but sctp.library='${lib:-unset}' does not exist
       — the gateway would log 'listening' and create no socket. entrypoint.sh re-checks this
       inside the container, where the path must also be mounted."
        ;;
      *) stack_note "$f" "$(basename "$f") sctp.backend='$backend' — expected NETTY_KERNEL or FSTACK_DPDK" ;;
    esac
    if [[ "$(basename "$f")" == "$selected_stack" ]]; then
      ok "$(basename "$f") parses, links channel=sctp, backend=$backend — this is the file that boots"
    else
      ok "$(basename "$f") parses, links channel=sctp, backend=$backend (spare)"
    fi
  done

  # The referenced stack file must exist, or jSS7 NPEs at boot. cfgref/selected_stack were
  # resolved above, before the ss7-*.json loop, so that loop could tell the file that boots
  # from a spare one — do not recompute them here and risk the two disagreeing.
  if [[ -n "$cfgref" ]]; then
    [[ -f "$dir/$selected_stack" ]] \
      || die "ussd.map.config-file=$cfgref but $dir/$selected_stack does not exist"
    ok "map config file present: $selected_stack"
  else
    warn "ussd.map.config-file is not set — entrypoint.sh will fall back to $selected_stack,
       or to the first ss7-*.json it finds, which is alphabetical rather than intentional"
    [[ -f "$dir/$selected_stack" ]] \
      || die "no ussd.map.config-file, and no $selected_stack in $dir to fall back to"
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
    # "Not readable" and "not there" are different problems with different fixes, and
    # reporting them as one is how an operator spends an hour on a certificate that was
    # present all along. `test -f` needs +x on every parent directory: on the Digicom host
    # /srv/ussdgw/nginx is drwxr-x--- messagebus, so as `app` the test returns false for
    # files that DO exist and nginx DOES serve — the check said "no TLS certificate" while
    # nginx was listening on 443 with that very certificate.
    local unreadable=()
    for f in "$cdir" "$cert" "$key"; do
      if [[ -e "$f" ]] && ! stat -c %a "$f" >/dev/null 2>&1; then
        unreadable+=("$f")
      fi
    done
    if (( ${#unreadable[@]} > 0 )); then
      die "cannot inspect ${unreadable[*]} as $(id -un) — the path exists but this user cannot
     traverse/read it, so the certificate check below is UNVERIFIED, not passed or failed.
     (/srv/ussdgw/nginx being drwxr-x--- messagebus is enough to cause this.)
     Re-run this gate with enough privilege to stat the files (e.g. sudo), or point CERT_DIR at
     a path the operator can read. Do not seed certificates on the strength of this result."
    fi
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

  # --- swarm secrets the stack declares as `external: true` -----------------------
  # A missing secret is not caught by `docker stack deploy`: the deploy SUCCEEDS, the
  # task is created, and then the container exits 1 because /run/secrets/<name> is not
  # there. With restart_policy: max_attempts: 5 the task is retired, `docker stack
  # services` keeps listing the service, and nothing ever binds. Checking here turns
  # that into a message before anything is stopped or started.
  if command -v docker >/dev/null 2>&1 \
     && [[ "$(docker info --format '{{.Swarm.LocalNodeState}}' 2>/dev/null)" == "active" ]]; then
    local s
    for s in ussdgw_db_password ussdgw_admin_key ussdgw_pg_super_password; do
      if docker secret inspect "$s" >/dev/null 2>&1; then
        ok "swarm secret present: $s"
      else
        errors=$((errors + 1))
        echo "ERROR: swarm secret '$s' does not exist, but docker/stack.yml declares it" >&2
        echo "       external: true. The deploy would succeed and the container would then" >&2
        echo "       exit 1 — five times, after which Swarm retires the task and the" >&2
        echo "       service silently stops existing while still being listed." >&2
        echo "       Create it, e.g.:" >&2
        echo "         openssl rand -base64 24 | tr -d '\\n=' | docker secret create $s -" >&2
      fi
    done
  else
    warn "docker swarm not active here — cannot verify the external secrets the stack needs"
  fi

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

# "Populated" must mean THE OPERATOR CONFIG IS THERE, not "the directory has any
# entry at all".
#
# It used to test `ls -A "$DEST"` being non-empty. host-prep.sh creates
# $DEST/ss7-persist before this script ever runs — and the documented order IS
# `host-prep.sh` then `install-config.sh` — so on every freshly prepared host the
# directory was never empty, the script decided it had already seeded, printed
# "destination already populated — leaving it untouched", exited 0 having copied
# nothing, and the deploy carried on with an EMPTY configs mount. The gateway then
# died one step later in its own entrypoint with "no application.properties",
# pointing at the wrong thing entirely.
#
# application.properties is the right test: it is the file the entrypoint requires,
# so it is exactly what decides whether a seed is still needed.
if [[ -f "$DEST/application.properties" ]]; then
  if [[ "$MODE" == "seed" ]]; then
    ok "destination already has application.properties — leaving it untouched (operator SoT)"
    echo "install-config: run with --force to overwrite (a timestamped backup is taken first)"
    exit 0
  fi
  bak="$DEST.bak-$(date +%Y%m%d%H%M%S)"
  cp -a "$DEST" "$bak"
  ok "backed up existing configs to $bak"
  rm -rf "${DEST:?}"/*
else
  # Not a seeded config. Say so explicitly — an operator who expected a populated
  # directory must be able to tell that apart from one that was never seeded.
  existing="$(ls -A "$DEST" 2>/dev/null | grep -v '^ss7-persist$' | tr '\n' ' ' || true)"
  if [[ -n "${existing// /}" ]]; then
    warn "$DEST has entries but no application.properties ($existing)"
    warn "seeding anyway — that state cannot have come from a successful install"
  fi
  ok "destination has no application.properties — seeding"
fi

# --- writability, BEFORE any copy (B14) -----------------------------------------
# host-prep.sh creates $DEST owned by 10001:10001 (the image's user, so the running
# container can write back). The operator running this script is normally `app`
# (uid 1000), so on the documented path `host-prep.sh` then `install-config.sh` the
# directory is NOT writable by whoever is installing.
#
# `cp -a "$src"/. "$dest"/` then produced roughly thirty `Permission denied` lines
# and the script carried on to its next step, burying the real cause under noise.
# Check once, up front, and name the remedy.
if [[ ! -w "$DEST" ]]; then
  die "$DEST is not writable by $(id -un) (uid $(id -u)); owner is $(stat -c '%U:%G %a' "$DEST").
     host-prep.sh creates it as 10001:10001 so the container can write back, which
     is why the installing user needs elevation here:
       sudo $0 ${MODE:+--force}
     (or install the files by hand, then re-run with --check)"
fi

# --- copy a WHITELIST, never the whole directory (B15) --------------------------
# `cp -a "$CONFIG_SRC"/. "$DEST"/` copied everything the operator happened to keep
# next to the live config. On a long-lived host that directory also holds:
#
#   application.properties.bak-*      ten rollback copies
#   ss7-digicom-balance.json.bak-*, … historic stack files
#   ss7-persist.quarantine-*          SIM persist XML kept ASIDE BECAUSE it was bad
#   bak-sysctl-*                      host tweak snapshots
#
# Copying the quarantine directories back in defeats their entire purpose: they are
# separated from the live tree precisely so their corrupt *sccp*.xml keys can never
# be loaded by a booting gateway. It also left the running config directory holding
# eleven application.properties files, so "which one is live?" became a guess.
#
# Seed only what the gateway actually reads:
#   application.properties   the entrypoint refuses to start without it
#   ss7-*.json               the stack definitions (validated above)
#   ss7-persist/             created EMPTY — a fresh gateway must not inherit
#                            association/persist state from another install
seeded=()
install_one() {
  local src="$1"
  local base
  base="$(basename "$src")"
  cp -a "$src" "$DEST/$base" || die "copying $base into $DEST failed"
  seeded+=("$base")
}

install_one "$CONFIG_SRC/application.properties"

# This file carries the credentials: ussd.admin.api-key, ussd.admin.session-hmac-secret
# (knowing it forges an ADMIN session cookie without ever logging in), first-run-password
# and any smpp password. `cp -a` PRESERVES THE SOURCE MODE, and an operator's copy is
# normally 0644 — so the seeded file inherits world-readable secrets on a carrier host that
# has other services with local accounts. 0600 keeps it readable by the gateway (uid 10001)
# and by root, and by nobody else.
chmod 0600 "$DEST/application.properties" \
  || die "cannot chmod 0600 $DEST/application.properties — it holds the admin API key and the session HMAC secret"
perm="$(stat -c '%a' "$DEST/application.properties" 2>/dev/null || stat -f '%Lp' "$DEST/application.properties")"
[[ "$perm" == "600" ]] \
  || die "application.properties ended up mode $perm, not 600 — every local user can read the admin credentials"
ok "application.properties is mode 600 (api-key + session HMAC secret are not world-readable)"

for f in "$CONFIG_SRC"/ss7-*.json; do
  [[ -f "$f" ]] || continue
  install_one "$f"
done

# ss7-persist stays empty on purpose; anything else in the source is not ours to
# place into a running gateway's config tree.
mkdir -p "$DEST/ss7-persist"

ok "seeded ${#seeded[@]} file(s): ${seeded[*]}"

# Account honestly for what was LEFT BEHIND.
#
# Deriving this from the copy loop was wrong: `*.bak` never ends in `.json` so it
# never matched the glob, and application.properties.bak-* was never a candidate to
# begin with — the counter could only ever read zero, which reads as "nothing was
# skipped" and is worse than not reporting at all. Enumerate the source instead.
left_files=0; left_dirs=0
while IFS= read -r e; do
  [[ -n "$e" ]] || continue
  b="$(basename "$e")"
  # Anything that got seeded is not "left behind".
  case " ${seeded[*]} " in *" $b "*) continue ;; esac
  if [[ -d "$CONFIG_SRC/$e" ]]; then left_dirs=$((left_dirs + 1)); else left_files=$((left_files + 1)); fi
done < <(ls -A "$CONFIG_SRC" 2>/dev/null || true)

if (( left_files > 0 || left_dirs > 0 )); then
  ok "left behind ${left_files} file(s) and ${left_dirs} dir(s) in the source — backups, quarantine"
  ok "  material and ss7-persist state are never seeded into the live tree (list: $(ls -A "$CONFIG_SRC" | tr '\n' ' '))"
fi

# Prove the copy landed. `cp` returning 0 is not evidence on its own when the
# destination already contained a same-named file.
[[ -f "$DEST/application.properties" ]] \
  || die "seeding reported success but $DEST/application.properties is missing — check permissions"
if ! compgen -G "$DEST/ss7-*.json" >/dev/null 2>&1; then
  warn "no ss7-*.json landed in $DEST — SS7 boot will find no stack config"
fi
# Nothing quarantined may have slipped through the whitelist.
if compgen -G "$DEST/*quarantine*" >/dev/null 2>&1; then
  die "quarantined material is present in $DEST: $(ls -d "$DEST"/*quarantine* | tr '\n' ' ')
     Corrupt SIM persist state must never be reachable from a booting gateway."
fi

# The admin UI writes stack JSON here — without it, saving from /admin/ss7 fails.
chmod 775 "$DEST" "$DEST/ss7-persist" 2>/dev/null || warn "could not chmod $DEST"

# Own the seeded files as the CONTAINER uid, not as whoever ran this script.
#
# `cp -a` preserves ownership too, so seeding from an operator tree owned by their login
# (uid 1000) leaves every file uid 1000 inside a directory owned by 10001. The gateway can
# read them, and it can even replace them by write-temp-then-rename because the DIRECTORY
# is 10001-owned — but it cannot open one for writing. Saving SS7 stack JSON from
# /admin/ss7, or anything else that rewrites a config file in place, then fails at runtime
# with a permission error that has nothing to do with the code being debugged.
if [[ "$(id -u)" == "0" ]]; then
  chown "$APP_UID:$APP_UID" "$DEST"/*.properties "$DEST"/ss7-*.json 2>/dev/null \
    || warn "could not chown the seeded files to $APP_UID — check that the container can rewrite them"
  # Re-apply: chown is fine, but the 0600 on application.properties must survive it.
  chmod 0600 "$DEST/application.properties" 2>/dev/null || true
  ok "seeded files owned by $APP_UID:$APP_UID (the container uid)"
else
  warn "not running as root — the seeded files keep uid $(id -u), but the container runs as $APP_UID."
  warn "  it can read them and can replace them via rename, but it CANNOT rewrite one in place."
  warn "  fix:  sudo chown $APP_UID:$APP_UID $DEST/*.properties $DEST/ss7-*.json"
fi

ok "installed operator config into $DEST"
echo "install-config: next — docker stack deploy -c docker/stack.yml <stackname>"