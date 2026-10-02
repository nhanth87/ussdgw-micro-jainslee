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

# The operator's tuning. READ THIS: the official image opens $PGDATA/postgresql.conf
# and NOTHING ELSE — grepping its entrypoint for /etc/postgresql returns nothing. So
# this file is not configuration as far as PostgreSQL is concerned; it is the single
# AUTHORED SOURCE that initdb/02-operator-tuning.sh appends into $PGDATA/postgresql.conf
# (where later assignments win). Keeping one authored file means one thing to edit and
# no second copy to drift.
#
# Loopback only. Everything in the stack shares the host network, so the gateway reaches
# PG at 127.0.0.1 — and on hostnet there is no `-p` publishing step to mask a wildcard
# bind. `listen_addresses = '*'` here would put the USSD database on every interface of
# a carrier host. To let a remote operator reach it, edit postgresql.conf AND pg_hba.conf,
# never 0.0.0.0/0.
COPY docker/postgres/postgresql.conf /etc/postgresql/postgresql.conf

# Assert the authored source at build time. The EFFECTIVE value is asserted separately,
# by running the hook, in docker/build/build-images.sh — this only catches a typo, a
# duplicated key or a missing trailing newline before any of it is baked in.
#
# A repeated listen_addresses is worth failing on because "which one wins" is a question
# nobody should have to ask of a security setting. A missing trailing newline is worth
# failing on because the hook appends to $PGDATA/postgresql.conf: without it the first
# appended line is glued onto the last existing one and PostgreSQL refuses to parse the
# file at all — which is exactly how this shipped once already.
RUN set -eu; \
    conf=/etc/postgresql/postgresql.conf; \
    n="$(grep -c "^listen_addresses" "$conf")"; \
    [ "$n" = 1 ] || { echo "FATAL: expected exactly one listen_addresses in $conf, found $n" >&2; exit 1; }; \
    grep -q "^listen_addresses = '127\.0\.0\.1'" "$conf" || { echo "FATAL: $conf does not pin listen_addresses to 127.0.0.1" >&2; exit 1; }; \
    [ -z "$(tail -c 1 "$conf")" ] || { echo "FATAL: $conf must end in a newline" >&2; exit 1; }; \
    echo "postgres: authored tuning pins loopback, one key, newline-terminated"

# The gateway image reads the password from this file (see docker/ussdgw/entrypoint.sh);
# `docker service inspect` and shell history never see it.
# Create on the host:  printf '<password>' | docker secret create ussdgw_db_password -
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
  CMD pg_isready -U ussdgw -d ussdgw || exit 1