# nginx in front of the gateway.
#
# The app's only HTTP surface is the vert.x RA on :8088 (quarkus.http is disabled).
# nginx gives the operator the required :80 entry point and keeps 8088 private.
#
# Installed from Ubuntu's repository per owner decision (2026-10-02) rather than
# compiled from nginx.org source: apt verifies the package with the distro's own
# signed index, which satisfies the audit requirement without a multi-step build.

FROM docker.io/library/ubuntu:26.04@sha256:61ebaa5cc23ca45450db85eac015435199ec569e28ec222ea13f2aed2110b8a6

ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends nginx ca-certificates curl \
 && rm -rf /var/lib/apt/lists/* \
 && nginx -v 2>&1 | tee /usr/local/share/nginx-version.txt

COPY docker/nginx/nginx.conf /etc/nginx/nginx.conf
COPY docker/nginx/ussdgw.conf /etc/nginx/conf.d/ussdgw.conf

# nginx needs CAP_NET_BIND_SERVICE for :80/:443, and nothing else. It runs as
# non-root otherwise, so a compromise of the proxy is not root on the host.
RUN mkdir -p /var/log/nginx /var/lib/nginx /etc/nginx/certs \
 && chown -R nginx:nginx /var/log/nginx /var/lib/nginx \
 && touch /var/run/nginx.pid && chown nginx:nginx /var/run/nginx.pid

USER nginx
EXPOSE 80 443

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -fsS -o /dev/null http://127.0.0.1/healthz || exit 1

STOPSIGNAL SIGQUIT
CMD ["nginx", "-g", "daemon off;"]