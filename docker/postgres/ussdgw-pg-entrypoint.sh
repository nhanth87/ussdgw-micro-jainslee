#!/bin/bash
# Entrypoint wrapper for the ussdgw PostgreSQL image.
#
# The official image runs /docker-entrypoint-initdb.d/* ONLY on an empty data directory.
# initdb/02-operator-tuning.sh is what puts docker/postgres/postgresql.conf (loopback
# listen_addresses, memory settings) into $PGDATA/postgresql.conf, so on every cluster that
# already existed a newer image's tuning was never applied — and nothing said so.
#
# This wrapper re-runs that hook on every start of an EXISTING cluster (it replaces its own
# marked block and re-validates the result with `postgres -C`), then hands over to the
# official entrypoint unchanged. A fresh data directory is left to docker-entrypoint.sh,
# which runs the hook itself during initdb.
set -euo pipefail

HOOK=/docker-entrypoint-initdb.d/02-operator-tuning.sh

if [[ -n "${PGDATA:-}" && -s "$PGDATA/PG_VERSION" ]]; then
  echo "ussdgw-pg-entrypoint: existing cluster in $PGDATA — re-applying operator tuning"
  # The hook validates with `postgres -C`, which refuses to run as root.
  if [[ "$(id -u)" == 0 ]]; then
    gosu postgres bash "$HOOK"
  else
    bash "$HOOK"
  fi
fi

exec docker-entrypoint.sh "$@"
