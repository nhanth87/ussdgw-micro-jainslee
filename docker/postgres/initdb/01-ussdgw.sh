#!/bin/bash
# Create the dedicated ussdgw role + database on first initialisation.
#
# Runs inside the postgres entrypoint (empty data dir) with these already set:
#   POSTGRES_USER / POSTGRES_DB  — the superuser the image creates
#
# The app password is read from the swarm secret so it never appears in the stack
# file, an env var, or shell history. If the secret is absent the script fails loudly
# rather than falling back to a default: a default DB password on a live gateway is
# exactly the failure mode the audit requirement exists to prevent.
set -euo pipefail

SECRET_FILE="${USSD_DB_PASSWORD_FILE:-/run/secrets/ussdgw_db_password}"

if [[ ! -r "$SECRET_FILE" ]]; then
  echo "ERROR: ussdgw DB password secret not readable at $SECRET_FILE" >&2
  echo "       create it: printf '<password>' | docker secret create ussdgw_db_password -" >&2
  exit 1
fi
APP_PW="$(cat "$SECRET_FILE")"
[[ -n "$APP_PW" ]] || { echo "ERROR: secret $SECRET_FILE is empty" >&2; exit 1; }

echo "initdb: creating role + database 'ussdgw' (dedicated, never shared)"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
-- The application role owns its own database and cannot create extensions or
-- roles: least privilege for a component that only needs its own tables.
CREATE ROLE ussdgw WITH LOGIN PASSWORD '${APP_PW}';
CREATE DATABASE ussdgw OWNER ussdgw;
SQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname ussdgw <<'SQL'
-- Flyway V1-V13 create their own schema objects as the app user. Grant on the
-- public schema so those CREATEs succeed on PostgreSQL 15+, where PUBLIC no
-- longer holds CREATE by default.
GRANT ALL ON SCHEMA public TO ussdgw;
ALTER SCHEMA public OWNER TO ussdgw;
SQL

echo "initdb: ussdgw database ready"