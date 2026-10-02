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
# one file to edit. Guarded by a marker so a re-run cannot append it twice.
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

if grep -qF "$MARKER" "$PGCONF"; then
  echo "operator tuning: already applied"
else
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
fi

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
if ! parsed="$(postgres -D "$PGCONF" -C listen_addresses 2>&1)"; then
  echo "ERROR: $PGCONF does not parse — PostgreSQL will refuse to start." >&2
  echo "  postgres says: $parsed" >&2
  echo "  The operator tuning must be a whole number of lines, each newline-terminated." >&2
  exit 1
fi
# postgres echoes the effective value; strip surrounding whitespace.
parsed="${parsed//[[:space:]]/}"
[[ "$parsed" == "127.0.0.1" ]] \
  || { echo "ERROR: effective listen_addresses is '$parsed', not 127.0.0.1." >&2
       echo "       On hostnet any other value exposes the USSD database on this" >&2
       echo "       host's interfaces. Refusing to continue." >&2
       exit 1; }

shared="$(postgres -D "$PGCONF" -C shared_buffers 2>&1 || true)"
shared="${shared//[[:space:]]/}"
[[ -n "$shared" && "$shared" != *"error"* ]] \
  || { echo "ERROR: could not read effective shared_buffers ($shared)" >&2; exit 1; }

echo "operator tuning: $PGCONF parses; listen_addresses=127.0.0.1, shared_buffers=$shared"