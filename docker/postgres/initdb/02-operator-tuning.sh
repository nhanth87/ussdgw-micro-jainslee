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

TUNING=/etc/postgresql/postgresql.conf
PGCONF="${PGDATA}/postgresql.conf"
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
  {
    echo ""
    echo "$MARKER"
    # Defaults first so every later line overrides cleanly if a value repeats.
    grep -vE '^[[:space:]]*(#|$)' "$TUNING"
    echo "# <<< ussdgw operator tuning <<<"
  } >> "$PGCONF"
  echo "operator tuning: applied $(grep -cvE '^[[:space:]]*(#|$)' "$TUNING") setting(s) to $PGCONF"
fi

# Prove the two settings that matter actually landed in the file the server reads.
# A missing tuning file and a tuning file that was never applied look identical from
# the outside, so check the effective file rather than trusting the copy above.
for want in "listen_addresses" "shared_buffers"; do
  grep -qE "^[[:space:]]*${want}[[:space:]]*=" "$PGCONF" \
    || { echo "ERROR: $want not present in $PGCONF after applying tuning" >&2; exit 1; }
done

# The loopback pin specifically: assert the value, not just the key.
if ! grep -qE "^[[:space:]]*listen_addresses[[:space:]]*=[[:space:]]*'127\.0\.0\.1'" "$PGCONF"; then
  # Accept the unquoted form too, but never '*'.
  if grep -qE "^[[:space:]]*listen_addresses[[:space:]]*=[[:space:]]*\*" "$PGCONF"; then
    echo "ERROR: listen_addresses is '*' in $PGCONF. On hostnet that exposes the USSD" >&2
    echo "       database on every interface of this host. Refusing to continue." >&2
    exit 1
  fi
  echo "ERROR: listen_addresses is not pinned to 127.0.0.1 in $PGCONF" >&2
  exit 1
fi

echo "operator tuning: listen_addresses pinned to loopback"