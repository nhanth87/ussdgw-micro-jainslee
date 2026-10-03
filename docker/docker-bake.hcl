# docker/docker-bake.hcl — THE main build file.
#
# One command builds all three images under one tag (HEAD SHA, never :latest):
#
#   TAG=$(git rev-parse --short HEAD) docker buildx bake -f docker/docker-bake.hcl
#
# Targets: ussdgw (Quarkus fast-jar + jlink'd JRE 25 with jdk.sctp),
# ussdgw-nginx (official nginx 1.27-alpine + ussdgw.conf), ussdgw-postgres
# (postgres:16 + initdb hooks + loopback-only postgresql.conf).
#
# `docker/build/build-images.sh` calls this file and then runs the
# per-image verification probes (healthcheck must FAIL with nothing
# listening, jlink must contain jdk.sctp, pg hooks must be readable and
# effective listen_addresses must be loopback-only). Bake builds; the
# script proves. Deploy is `docker stack deploy -c docker/stack.yml ussdgw`.
variable "TAG" {
  default = "dev"
}

group "default" {
  targets = ["ussdgw", "ussdgw-nginx", "ussdgw-postgres"]
}

target "ussdgw" {
  dockerfile = "docker/ussdgw/Dockerfile"
  context    = "."
  tags       = ["ussdgw:${TAG}"]
}

target "ussdgw-nginx" {
  dockerfile = "docker/nginx/Dockerfile"
  context    = "."
  tags       = ["ussdgw-nginx:${TAG}"]
}

target "ussdgw-postgres" {
  dockerfile = "docker/postgres/Dockerfile"
  context    = "."
  tags       = ["ussdgw-postgres:${TAG}"]
}
