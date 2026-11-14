# ClickHouse for the USSD gateway CDR store (≥ 3 months, see CdrClickHouseSchema).
#
# The official image, pinned BY DIGEST (docker/sources.lock base-clickhouse) — not built from
# source, same trust decision as postgres:16 and nginx.
#
# 26.3 LTS, not 26.8: the Digicom host is a KVM guest exposing "QEMU Virtual CPU version 2.5+"
# (SSE4.2, no AVX/AVX2/BMI). The 26.8 binary dies there with SIGILL (exit 132, "Illegal
# instruction"); 26.3 runs (measured 2026-11-15). Move to 26.8+ only after the hypervisor passes
# the host CPU through (cpu model host / host-passthrough) — build-images.sh starts the image on
# the build host, so a CPU mismatch fails the build, not the deploy.
#
# Loopback only. The stack runs on the host network, so whatever ClickHouse binds is bound on
# the carrier host itself. The upstream image ships config.d/docker_related_config.xml with
# listen_host :: and 0.0.0.0 — every interface. We REPLACE that file (same path) instead of
# adding a second listen_host, because ClickHouse merges repeated elements and would keep the
# wildcard. The effective bind is proven by build-images.sh against a running server.
FROM docker.io/clickhouse/clickhouse-server:26.3.63.123@sha256:9a7eef96121925a9ff162b6f36db27822a5b95d9b021b7b0511ea9f4b52bf257

COPY docker/clickhouse/config.d/docker_related_config.xml /etc/clickhouse-server/config.d/docker_related_config.xml
COPY docker/clickhouse/config.d/ussdgw.xml /etc/clickhouse-server/config.d/ussdgw.xml
COPY docker/clickhouse/users.d/ussdgw-loopback.xml /etc/clickhouse-server/users.d/ussdgw-loopback.xml

# Fail the build on malformed XML instead of at 3 a.m. on the carrier host.
RUN clickhouse extract-from-config --config-file /etc/clickhouse-server/config.xml --key http_port >/dev/null \
 && test "$(clickhouse extract-from-config --config-file /etc/clickhouse-server/config.xml --key mark_cache_size)" = "268435456"

LABEL org.opencontainers.image.title="ussdgw-clickhouse" \
      org.opencontainers.image.description="ClickHouse 26.3 LTS — USSDGW CDR store, loopback-only"
