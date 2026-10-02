# PostgreSQL 16 for the USSD gateway.
#
# Per owner decision (2026-10-02) this is the official image pinned BY DIGEST from
# docker.io/library/postgres — not built from postgresql.org source. The digest is
# recorded in docker/sources.lock so the exact bits are auditable even though the
# package build itself is not reproduced here.
#
# Dedicated database `ussdgw` — never shared with any other product.

FROM docker.io/library/postgres:16@sha256:2c72031ac25606bf94fd2fece7c35efdf263a48b08446950034f0725653f4efc

# initdb hooks run as the postgres user on an empty data directory.
COPY docker/postgres/initdb/ /docker-entrypoint-initdb.d/

# Tuned for the gateway's connection pool (JDBC max-size=128) plus operator sessions.
# Values are conservative: a shared SS7 host cannot spare large caches, and an
# over-tuned PG under memory pressure will get OOM-killed and take the gateway down
# with it (the database is a hard dependency at boot).
COPY docker/postgres/postgresql.conf /etc/postgresql/postgresql.conf

# Loopback only. Everything in the stack shares the host network, so the gateway
# reaches PG at 127.0.0.1 — publishing 5432 would expose the USSD database to the
# whole network for no benefit.
# To let a remote operator reach it, add their management range here AND in
# pg_hba.conf (see postgresql.conf), never 0.0.0.0/0.
RUN printf "listen_addresses = '127.0.0.1'\nport = 5432\n" >> /etc/postgresql/postgresql.conf \
 && echo "postgres: listening on loopback only" >&2

# The gateway image reads the password from this file (see docker/ussdgw/entrypoint.sh);
# `docker service inspect` and shell history never see it.
# Create on the host:  printf '<password>' | docker secret create ussdgw_db_password -
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
  CMD pg_isready -U ussdgw -d ussdgw || exit 1