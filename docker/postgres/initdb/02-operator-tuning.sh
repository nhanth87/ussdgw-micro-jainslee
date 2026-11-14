#!/bin/bash
# Apply the operator's PostgreSQL tuning to the running server's configuration.
#
# Why this hook exists (a real exposure on the Digicom test host):
#
#   The official postgres image reads $PGDATA/postgresql.conf and nothing else.
#   docker/postgres/Dockerfile copies our tuning to /etc/postgresql/postgresql.conf
#   and appends a `listen_addresses` line to that same file — which no part of the
#   image ever opens. The server therefore kept its shipped defaults:
#
#       show listen_addresses;   ->  *          (all interfaces)
#       show shared_buffers;     ->  128MB      (not our 256MB)
#
#   Every service in this stack is on hostnet, so there is no `-p` publishing step to
#   mask that. Verified on digicom-nb with `ss -lnt` while this image ran under
#   --network host:
#
#       LISTEN 0 200  0.0.0.0:5433  0.0.0.0:*
#       172.16.144.163:5433  reachable
#       192.168.0.70:5433     reachable
#
#   i.e. the USSD database on every interface of a carrier host, including the
#   172.16.144.163 and 192.168.0.70 addresses the M3UA peers use. pg_hba.conf asks for
#   scram-sha-256, but that is the second line of defence — and pg_hba.conf was being
#   overridden by this same dead file.
#
# How it is applied: append the tuning to $PGDATA/postgresql.conf, which does take
# effect (later assignments win). /etc/postgresql/postgresql.conf stays the single
# authored source of truth and the Dockerfile still installs it, so there is exactly
# one file to edit. The block is delimited by markers and REPLACED on every run, so a
# re-run never appends it twice and an edited tuning file always takes effect.
set -euo pipefail

# Overridable only so the hook can be exercised in isolation; initdb always runs it
# with no TUNING in the environment, and the default is the file the Dockerfile
# installs. A stale TUNING exported by a parent shell would otherwise be ignored,
# which is exactly the kind of silent substitution this hook is meant to rule out.
TUNING="${TUNING:-/etc/postgresql/postgresql.conf}"
PGCONF="${PGCONF:-${PGDATA:?PGDATA is not set}/postgresql.conf}"
MARKER="# >>> ussdgw operator tuning (from $TUNING) >>>"

if [[ ! -r "$TUNING" ]]; then
  echo "ERROR: $TUNING is missing or unreadable — the gateway's database would run on" >&2
  echo "       PostgreSQL's defaults with listen_addresses='*' on hostnet." >&2
  exit 1
fi
if [[ ! -f "$PGCONF" ]]; then
  echo "ERROR: $PGCONF does not exist; initdb did not produce one" >&2
  exit 1
fi

# REPLACE, never "already applied → skip". This hook used to stop at the marker, and initdb
# hooks only run on an EMPTY data directory — so after the first boot, editing
# docker/postgres/postgresql.conf and shipping a new image changed nothing on any existing
# cluster, silently. The image's entrypoint wrapper (ussdgw-pg-entrypoint.sh) now runs this
# hook on every start of an existing cluster, and this block swaps the old tuning block out
# for the current one. Every block this hook ever wrote starts and ends with the same
# prefixes, whatever $TUNING path was in the marker.
if grep -q '^# >>> ussdgw operator tuning' "$PGCONF"; then
  tmp="$(mktemp "${PGCONF}.XXXXXX")"
  awk '/^# >>> ussdgw operator tuning/ {skip=1}
       !skip {print}
       /^# <<< ussdgw operator tuning <<</ {skip=0}' "$PGCONF" > "$tmp"
  # cat >, not mv: keeps the file's owner and mode exactly as initdb created them.
  cat "$tmp" > "$PGCONF"; rm -f "$tmp"
  echo "operator tuning: removed the previously applied block; re-applying from $TUNING"
fi
{
  # Both files must end in a newline before anything is appended, or the first
  # appended line is glued onto the last existing one.
  #
  # docker/postgres/postgresql.conf did not end in a newline, and the block below
  # started with `listen_addresses = '127.0.0.1'`. The result was a single line:
  #
  #     log_statement = 'ddl'listen_addresses = '127.0.0.1'
  #
  # and PostgreSQL refused to start at all:
  #
  #     LOG:  syntax error in file ".../postgresql.conf" line 848, near token "listen_addresses"
  #     FATAL:  configuration file ".../postgresql.conf" contains errors
  #
  # Fix the file, and be defensive about both sides so a future edit cannot
  # reintroduce it.
  if [[ -s "$PGCONF" && -n "$(tail -c 1 "$PGCONF")" ]]; then
    echo "" >> "$PGCONF"
  fi

  {
    echo "$MARKER"
    # awk rather than grep: grep emits its final line without a trailing newline
    # when the input lacks one, which is the same defect one level down.
    awk 'NF && $0 !~ /^[[:space:]]*#/' "$TUNING" | while IFS= read -r line; do
      printf '%s\n' "$line"
    done
    echo "# <<< ussdgw operator tuning <<<"
  } >> "$PGCONF"

  applied="$(awk 'NF && $0 !~ /^[[:space:]]*#/' "$TUNING" | wc -l)"
  echo "operator tuning: applied $applied setting(s) to $PGCONF"
}

# --- validate by PARSING, not by grepping (this is the part that was wrong) ------
# The previous version asserted with
#
#     grep -qE "^[[:space:]]*listen_addresses[[:space:]]*=[[:space:]]*'127\.0\.0\.1'"
#
# and printed "pinned to loopback" — against the very file that had just been shown
# to fail to parse. `log_statement = 'ddl'listen_addresses = '127.0.0.1'` still
# matches that pattern, because the merged line still contains the key, whitespace
# and an equals sign. A regex cannot tell a valid assignment from a corrupted one.
#
# Ask the server instead. `postgres -C <name>` reads and parses the configuration and
# exits non-zero if it does not, so it is a real syntax check.
# -D takes the DATA DIRECTORY; postgres appends postgresql.conf itself. Passing the
# file path instead produced
#   could not access the server configuration file ".../postgresql.conf/postgresql.conf"
#   Not a directory
# which is a confusing way to say the argument was wrong, and it failed identically
# for a good config and a corrupt one — the check was answering nothing.
PGDATA_DIR="$(dirname "$PGCONF")"

# postgres -C writes its LOG lines to stdout as well as its result, e.g.
#
#     LOG:  skipping missing configuration file ".../postgresql.auto.conf"
#     127.0.0.1
#
# so the result is the LAST non-empty line, not the whole of stdout. Capturing
# everything and comparing the lot made a correct configuration read as
# '2026-10-02T08:23:31.661 GMT [19] LOG: ... 127.0.0.1' and fail the check.
setting_value() {
  local out last
  out="$(postgres -D "$PGDATA_DIR" -C "$1" 2>&1)" || return 1
  last=""
  while IFS= read -r line; do
    [[ -n "${line//[[:space:]]/}" ]] && last="$line"
  done <<< "$out"
  printf '%s' "${last//[[:space:]]/}"
}

if ! parsed="$(setting_value listen_addresses)"; then
  echo "ERROR: $PGCONF does not parse — PostgreSQL will refuse to start." >&2
  echo "  postgres says: $(postgres -D "$PGDATA_DIR" -C listen_addresses 2>&1 | tail -3)" >&2
  echo "  The operator tuning must be a whole number of lines, each newline-terminated." >&2
  exit 1
fi
[[ "$parsed" == "127.0.0.1" ]] \
  || { echo "ERROR: effective listen_addresses is '$parsed', not 127.0.0.1." >&2
       echo "       On hostnet any other value exposes the USSD database on this" >&2
       echo "       host's interfaces. Refusing to continue." >&2
       exit 1; }

if ! shared_blcks="$(setting_value shared_buffers)"; then
  echo "ERROR: could not read effective shared_buffers from $PGCONF" >&2
  exit 1
fi
# postgres -C reports shared_buffers in 8 kB blocks, so 32768 is 256MB. Say so, or
# the log reads as a wildly wrong value to anyone comparing it against the file.
if [[ "$shared_blcks" =~ ^[0-9]+$ ]]; then
  shared_mb=$(( shared_blcks / 128 ))
  shared="shared_buffers=${shared_mb}MB (${shared_blcks} x 8kB blocks)"
else
  shared="shared_buffers=${shared_blcks}"
fi

echo "operator tuning: $PGCONF parses; listen_addresses=127.0.0.1, $shared"