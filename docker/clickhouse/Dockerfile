# ClickHouse for the USSD gateway CDR store (≥ 3 months, see CdrClickHouseSchema).
#
# The official image, pinned BY DIGEST (docker/sources.lock base-clickhouse) — not built from
# source, same trust decision as postgres:16 and nginx. 26.8 is the current LTS line.
#
# Loopback only. The stack runs on the host network, so whatever ClickHouse binds is bound on
# the carrier host itself. The upstream image ships config.d/docker_related_config.xml with
# listen_host :: and 0.0.0.0 — every interface. We REPLACE that file (same path) instead of
# adding a second listen_host, because ClickHouse merges repeated elements and would keep the
# wildcard. The effective bind is proven by build-images.sh against a running server.
FROM docker.io/clickhouse/clickhouse-server:26.8.37.168@sha256:a56702852d7c8055a021a42a3a60f49daa49e9204a949ea033e51bb327948648

COPY docker/clickhouse/config.d/docker_related_config.xml /etc/clickhouse-server/config.d/docker_related_config.xml
COPY docker/clickhouse/config.d/ussdgw.xml /etc/clickhouse-server/config.d/ussdgw.xml
COPY docker/clickhouse/users.d/ussdgw-loopback.xml /etc/clickhouse-server/users.d/ussdgw-loopback.xml

# Fail the build on malformed XML instead of at 3 a.m. on the carrier host.
RUN clickhouse extract-from-config --config-file /etc/clickhouse-server/config.xml --key http_port >/dev/null \
 && test "$(clickhouse extract-from-config --config-file /etc/clickhouse-server/config.xml --key mark_cache_size)" = "268435456"

LABEL org.opencontainers.image.title="ussdgw-clickhouse" \
      org.opencontainers.image.description="ClickHouse 26.8 LTS — USSDGW CDR store, loopback-only"
