#!/usr/bin/env bash
# Build the three runtime images the stack deploys, under ONE tag.
#
# Why this exists (it was a real outage on the Digicom test host):
#
#   docker/stack.yml pointed the postgres service straight at the stock upstream
#   `postgres:16@<digest>` image while docker/postgres/Dockerfile — the file that
#   layers in docker/postgres/initdb/ and the loopback-only postgresql.conf — was
#   never built by anything and never referenced by anything. The documented deploy
#   therefore produced a database with no initdb hook, and that has two outcomes:
#
#     1. It cannot start the gateway. 01-ussdgw.sh is what creates the `ussdgw` role
#        and the dedicated `ussdgw` database. Without it the gateway connects as
#        username=ussdgw and PostgreSQL answers
#        `FATAL: role "ussdgw" does not exist`.
#
#     2. It exposes the database to the network. The official image ships
#        listen_addresses = '*' so that `-p` publishing works. Every service here is
#        on hostnet, so there is no publishing step to mask it: 5432 would bind on
#        every interface of a carrier host. postgresql.conf pins it to 127.0.0.1.
#
# Neither failure is visible until the gateway is already deployed. So this script
# builds the image AND asserts the hook is inside it, and stack.yml resolves the
# postgres image through POSTGRES_IMAGE with a default that names the built image
# rather than the upstream one.
#
# Usage:
#   ./docker/build/stage-dist.sh          # must run first: mirrors the packaged tree
#   ./docker/build/build-images.sh        # tags everything <short-sha>
#   TAG=some-sha ./docker/build/build-images.sh
set -euo pipefail

cd "$(dirname "$0")/../.."

TAG="${TAG:-$(git rev-parse --short HEAD)}"
ok()  { echo "build-images: ok  $*"; }
die() { echo "build-images: ERROR: $*" >&2; exit 1; }

# stage-dist.sh is what guarantees the tree this script packages is the artifact the
# builder produced, PG-baked, with one version per artifact. Without it the images
# below would be built from whatever dist/ happens to contain.
[[ -f dist/.baked-db-kind ]] || die "dist/.baked-db-kind is missing — run ./docker/build/stage-dist.sh first"
[[ "$(tr -d '[:space:]' < dist/.baked-db-kind)" == "postgresql" ]] \
  || die "dist is baked '$(cat dist/.baked-db-kind)', not postgresql — re-run stage-dist.sh against a PG-baked tree"
[[ -f out/BUILD-INFO.json ]] || die "out/BUILD-INFO.json is missing — run ./docker/build/stage-dist.sh first"

echo "build-images: tag = $TAG"

docker build -f docker/ussdgw/Dockerfile  -t "ussdgw:$TAG" .
ok "ussdgw:$TAG"

# --- fail closed on the app image's liveness probe -------------------------------
# Run the probe in the built image with NOTHING listening on 8088. It must refuse.
#
# A probe that cannot fail is worse than no probe: Swarm then reports "healthy" for a dead
# gateway. This one has already broken in the other direction — it failed for a gateway that
# had booted, wired SS7 and was serving traffic, because it authenticated with
# $USSD_ADMIN_API_KEY, which never set the admin key in force (that comes from
# ussd.admin.api-key in the mounted configs). Swarm SIGTERM'd it every start-period:
# `exit (143): dockerexec: unhealthy container`, five times, then the task was retired and
# `docker stack services` kept listing a service that no longer ran.
if hc_out="$(docker run --rm --entrypoint /usr/local/bin/ussdgw-healthcheck.sh "ussdgw:$TAG" 2>&1)"; then
  die "ussdgw:$TAG — the healthcheck probe PASSED with nothing listening on 8088.
       A probe that cannot fail makes Swarm report 'healthy' for a dead gateway.
       Output was: $hc_out"
fi
ok "app image's healthcheck probe fails when nothing listens (so 'healthy' still means something)"

# --- the JRE can actually do kernel SCTP ----------------------------------------
# sctp.backend=NETTY_KERNEL is com.sun.nio.sctp, which lives in the jdk.sctp module — and
# this runtime is a jlink'd JRE, so a module list that forgot it produces an image whose
# gateway cannot bind a single SCTP socket. The symptom is indistinguishable from "the
# carrier peer is down": ss7.live=false, everything else green.
# Captured into a variable first: under `set -o pipefail` a `docker run | grep -q` guard
# FAILS ON A MATCH, because grep -q exits early and SIGPIPEs the left side.
jmods="$(docker run --rm --entrypoint /opt/jre/bin/java "ussdgw:$TAG" --list-modules 2>/dev/null)"
grep -q '^jdk\.sctp@' <<<"$jmods" \
  || die "ussdgw:$TAG — the jlink'd JRE has no jdk.sctp module, so NETTY_KERNEL SCTP cannot
       open a socket. Add jdk.sctp to the jlink module list in docker/ussdgw/Dockerfile."
ok "JRE carries $(grep -o '^jdk\.sctp@[^ ]*' <<<"$jmods") — NETTY_KERNEL can bind kernel SCTP"

docker build -f docker/nginx/Dockerfile   -t "ussdgw-nginx:$TAG" .
ok "ussdgw-nginx:$TAG"

docker build -f docker/postgres/Dockerfile -t "ussdgw-postgres:$TAG" .
ok "ussdgw-postgres:$TAG"

# --- fail closed on the postgres image ------------------------------------------
# Do not trust that the build did what the Dockerfile says. Ask the image.
#
# The check is for the hook being PRESENT AND READABLE, not executable. The official
# postgres entrypoint sources a non-executable .sh rather than skipping it:
#
#     *.sh)
#         if [ -x "$f" ]; then "$f"
#         else              . "$f"      # <- our case
#         fi
#
# so demanding +x rejected a perfectly good image. What must hold is that the file
# survived the build context and stayed readable by the postgres user.
if ! docker run --rm --user postgres --entrypoint sh "ussdgw-postgres:$TAG" \
     -c 'test -r /docker-entrypoint-initdb.d/01-ussdgw.sh' 2>/dev/null; then
  die "ussdgw-postgres:$TAG cannot read /docker-entrypoint-initdb.d/01-ussdgw.sh — the
       \`ussdgw\` role and database would never be created and the gateway would fail to
       start with 'FATAL: role \"ussdgw\" does not exist'.
       Something in the build dropped /docker-entrypoint-initdb.d/ or its permissions."
fi
ok "postgres image carries a readable docker-entrypoint-initdb.d/01-ussdgw.sh"

# And the loopback pin, which is the only thing standing between a carrier host's LAN and
# the USSD database.
#
# ASK THE SERVER, NOT THE FILE. The first version of this check was
#
#     grep -q "^listen_addresses = .127.0.0.1." /etc/postgresql/postgresql.conf
#
# and it passed on an image whose database bound 0.0.0.0:5432. The official image never
# opens /etc/postgresql/postgresql.conf — that path is only the AUTHORED SOURCE which
# initdb/02-operator-tuning.sh appends into $PGDATA/postgresql.conf. Grepping it asserts
# that a file we ship says something, not that PostgreSQL will do it: a check that cannot
# fail, guarding the exact defect it was written to catch.
#
# So run the real hook against a throwaway PGDATA inside the real image and ask postgres
# for the effective value. This fails if the hook is missing, if the tuning file is
# unparseable, or if the pin is absent or '*' — i.e. it fails for every way this has
# actually been broken.
if ! docker run --rm --user postgres --entrypoint sh "ussdgw-postgres:$TAG" \
     -c 'test -r /docker-entrypoint-initdb.d/02-operator-tuning.sh' 2>/dev/null; then
  die "ussdgw-postgres:$TAG has no readable initdb/02-operator-tuning.sh.
       Without it the operator tuning is never applied to \$PGDATA/postgresql.conf and
       the server runs on upstream defaults — listen_addresses = '*', which on hostnet
       binds the USSD database on every interface of this host."
fi

effective="$(docker run --rm --user postgres --entrypoint bash "ussdgw-postgres:$TAG" -c '
  set -e
  export PGDATA=/tmp/pg-assert
  mkdir -p "$PGDATA"
  cp /usr/share/postgresql/postgresql.conf.sample "$PGDATA/postgresql.conf"
  printf "local all all trust\n" > "$PGDATA/pg_hba.conf"
  /docker-entrypoint-initdb.d/02-operator-tuning.sh > /tmp/hook.log 2>&1 || {
    echo "HOOK_FAILED"; cat /tmp/hook.log; exit 1; }
  postgres -D "$PGDATA" -C listen_addresses 2>/dev/null | tail -1
' 2>&1)" || die "ussdgw-postgres:$TAG — the tuning hook failed inside the image:
       $(printf '%s' "$effective" | sed 's/^/         /')"

effective="$(printf '%s' "$effective" | tr -d '[:space:]')"
if [ "$effective" != "127.0.0.1" ]; then
  die "ussdgw-postgres:$TAG would run with listen_addresses='$effective', not 127.0.0.1.
       On hostnet that binds the USSD database on every interface of this host — proven
       reachable from the M3UA peer addresses on digicom-nb. The stock upstream image
       ships '*'. This value was read from postgres itself after running the image's own
       initdb hook, not from a file we ship."
fi
ok "postgres image really runs listen_addresses=127.0.0.1 (hook applied, value read back from postgres)"

echo
echo "build-images: next — point docker/.env at these tags and deploy:"
echo "  USSDGW_IMAGE=ussdgw:$TAG"
echo "  NGINX_IMAGE=ussdgw-nginx:$TAG"
echo "  POSTGRES_IMAGE=ussdgw-postgres:$TAG"
echo
echo "  docker stack deploy -c docker/stack.yml -c docker/stack.test.yml ussdgw"