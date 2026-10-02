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

# And the loopback pin, which is the only thing standing between a carrier host's
# LAN and the USSD database.
if ! docker run --rm --entrypoint sh "ussdgw-postgres:$TAG" \
     -c 'grep -q "^listen_addresses = .127.0.0.1." /etc/postgresql/postgresql.conf' 2>/dev/null; then
  die "ussdgw-postgres:$TAG does not pin listen_addresses to 127.0.0.1.
       On hostnet that means PostgreSQL binds 5432 on every interface of this host.
       The stock upstream image ships listen_addresses = '*'."
fi
ok "postgres image pins listen_addresses to loopback"

echo
echo "build-images: next — point docker/.env at these tags and deploy:"
echo "  USSDGW_IMAGE=ussdgw:$TAG"
echo "  NGINX_IMAGE=ussdgw-nginx:$TAG"
echo "  POSTGRES_IMAGE=ussdgw-postgres:$TAG"
echo
echo "  docker stack deploy -c docker/stack.yml -c docker/stack.test.yml ussdgw"