#!/bin/sh
# Liveness probe for the USSD gateway.
#
# WHY A SCRIPT AND NOT A HEALTHCHECK LINE IN THE DOCKERFILE
#   The first version was
#
#       HEALTHCHECK CMD curl -fsS -o /dev/null \
#         -H "X-USSD-Admin-Key: ${USSD_ADMIN_API_KEY:-}" http://127.0.0.1:8088/admin/status.json
#
#   and it returned 401 on every probe forever, so Swarm SIGTERM'd a gateway that had
#   already booted, wired SS7 and was serving traffic:
#
#       Failed … "task: non-zero exit (143): dockerexec: unhealthy container"
#
#   Two independent reasons it could not pass:
#     * HEALTHCHECK runs as its own `docker exec`, so it sees the image's Config.Env and
#       nothing else — in particular nothing entrypoint.sh exports.
#     * USSD_ADMIN_API_KEY does not set the admin key. The key in force is
#       ussd.admin.api-key in the mounted configs/application.properties. Proved on a live
#       container: `ussd-admin` → 401, the properties value → 200.
#   A shell one-liner cannot hold that much reasoning, and the fix has to stay identical
#   between the image and docker/stack.yml — so it lives here, once.
#
# WHAT THIS PROVES, AND WHAT IT DOES NOT
#   It proves the vert.x resource adaptor accepted a TCP connection on 8088 and routed it to
#   the admin handler. It does NOT prove SS7 is up (ss7.live is legitimately false when the
#   carrier peer is down — restarting the gateway for that would drop live MAP dialogs), and
#   it does NOT prove the operator's admin key is correct; docker/prove.sh does that. A
#   liveness probe that restarts a gateway over a credential question turns one config
#   mistake into an outage, and with restart_policy max_attempts: 5 into a retired task:
#   `docker stack services` still lists the service while nothing ever binds.
set -eu

URL="${USSD_HEALTHCHECK_URL:-http://127.0.0.1:8088/admin/status.json}"
KEY_FILE="${USSD_ADMIN_API_KEY_FILE:-/run/secrets/ussdgw_admin_key}"

if [ -r "$KEY_FILE" ]; then
  # -K - sends the header through curl's stdin config instead of argv. `docker top` on the
  # host shows container argv, so a key in argv is a key in the host's process table for the
  # whole life of the probe — every 30 seconds.
  code="$(printf 'header = "X-USSD-Admin-Key: %s"\n' "$(tr -d '\r\n' < "$KEY_FILE")" \
          | curl -sS -o /dev/null -w '%{http_code}' -K - "$URL" || echo 000)"
else
  code="$(curl -sS -o /dev/null -w '%{http_code}' "$URL" || echo 000)"
fi

case "$code" in
  200) exit 0 ;;                       # admin plane answered with the key
  401) exit 0 ;;                       # HTTP plane up and routing; key not readable here
  *)
    echo "ussdgw-healthcheck: $URL returned '$code' (200/401 expected)" >&2
    exit 1
    ;;
esac
