# nginx in front of the gateway.
#
# The app's only HTTP surface is the vert.x RA on :8088 (quarkus.http is disabled).
# nginx gives the operator the required :80 entry point and keeps 8088 private.
#
# WHY THE OFFICIAL IMAGE, NOT apt (owner decision, 2026-10-02)
# ----------------------------------------------------------
# The previous version installed nginx from Ubuntu's repository onto ubuntu:26.04
# and then failed to build:
#
#     RUN mkdir -p /var/log/nginx … && chown -R nginx:nginx …   ->  exit code: 1
#
# because Ubuntu's nginx package never creates a `nginx` user (it uses `www-data`),
# which also made `user nginx;` in nginx.conf wrong. The official image ships the
# `nginx` user, the entrypoint and the template engine already, so the whole apt
# layer — and the whole class of "which user owns /var/lib/nginx" mistakes — goes
# away. This also satisfies the audit requirement more directly than before: the
# base is pinned by digest in docker/sources.lock, so the exact bits are recorded.

FROM docker.io/library/nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10

# The stock image ships conf.d/default.conf, which listens on :80 and would become
# an unpredictable default_server alongside ussdgw.conf. We ship exactly one site.
#
# Ownership: this container runs as uid 101 (nginx), so every directory nginx
# writes to must belong to it. /var/cache/nginx holds the proxy_temp paths, and
# /etc/nginx/certs is where the operator's certificate is bind-mounted — a bind
# mount takes the HOST's ownership, so that half is the operator's job:
# docker/install-config.sh --check verifies it, and the runbook documents
# `install -o 101 -g 101`. /var/log/nginx is already symlinked to /dev/stdout and
# /dev/stderr by the base image, so the log files need no chown.
RUN rm -f /etc/nginx/conf.d/default.conf \
 && mkdir -p /etc/nginx/certs \
 && chown -R nginx:nginx /etc/nginx/certs /var/cache/nginx \
 && nginx -v 2>&1 | tee /usr/local/share/nginx-version.txt

COPY docker/nginx/nginx.conf   /etc/nginx/nginx.conf
COPY docker/nginx/ussdgw.conf  /etc/nginx/conf.d/ussdgw.conf

# Verify the shipped configuration at BUILD time, not at deploy time.
#
# This is not decoration. Both defects that made this image unusable were silent
# until `nginx -t` ran, and both were found only by hand:
#
#     [emerg] duplicate upstream "ussdgw_app" in /etc/nginx/conf.d/ussdgw.conf:7
#     [emerg] cannot load certificate "/etc/nginx/certs/fullchain.pem"
#
# With docker/stack.yml's `restart_policy: max_attempts: 5`, a config that fails
# to parse makes the task disappear without ever binding :80 — the deploy looks
# healthy and the admin UI is simply gone. Failing the BUILD turns a silent
# production outage into a red build.
#
# The operator's real certificate is not available at build time (it arrives as a
# bind mount), so a throwaway self-signed pair is written to the exact path the
# config references and `nginx -t` runs against the real files.
#
# Two things must be cleaned up afterwards, and BOTH are easy to get wrong:
#
#   * the throwaway certificate pair, and
#   * /tmp/nginx.pid — `nginx -t` opens and creates the pid file as root. Leaving
#     it behind bakes a root-owned file into the image, and because /tmp carries
#     the sticky bit a uid-101 master can then neither write nor unlink it:
#
#         [emerg] open() "/tmp/nginx.pid" failed (13: Permission denied)
#
#     which is exactly how this image failed the first time it was run for real.
#
# Docker commits only a layer's final filesystem diff, so deleting inside the same
# RUN leaves nothing behind. The two `test` lines are the guard: if a future nginx
# version starts leaving some other artefact behind, the build goes red instead of
# silently shipping it.
#
# This proves the directives, the includes and the upstream resolve. It
# deliberately does NOT prove the operator certificate is valid or unexpired —
# docker/install-config.sh --check covers that half.
RUN apk add --no-cache openssl curl libcap \
 && openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=build-selftest \
        -keyout /etc/nginx/certs/privkey.pem \
        -out    /etc/nginx/certs/fullchain.pem \
 && nginx -t \
 && rm -f /etc/nginx/certs/privkey.pem /etc/nginx/certs/fullchain.pem /tmp/nginx.pid \
 && test -z "$(ls -A /etc/nginx/certs)" \
 && test ! -e /tmp/nginx.pid \
 && nginx -v

# BINDING :80/:443 AS uid 101 — the part that `cap_add` in the stack file CANNOT do.
#
# docker/stack.yml grants NET_BIND_SERVICE, and that is necessary but nowhere near
# sufficient: capabilities in a container's permitted/effective set belong to **uid 0**.
# A process running as uid 101 has no effective capabilities at all, so the kernel's
# `capable(CAP_NET_BIND_SERVICE)` check fails and nginx dies on its very first bind:
#
#     [emerg] 1#1: bind() to 0.0.0.0:80 failed (13: Permission denied)
#     nginx: [emerg] bind() to 0.0.0.0:80 failed (13: Permission denied)
#
# With `restart_policy: max_attempts: 5` that is five failures and then a retired task:
# `docker stack services` still lists nginx, :80 never binds, and the admin UI is simply
# gone. It reached production exactly like that, because the local proof ran nginx on an
# unprivileged port and the build-time `nginx -t` runs as root — neither can hit this.
#
# The targeted fix is a FILE capability on the binary, which the kernel honours for any
# uid, so the container stays non-root. Not `net.ipv4.ip_unprivileged_port_start=0`,
# which would let every unprivileged process on a carrier host bind any port.
#
# `getcap` is the guard: if a future alpine base moves setcap out of libcap, or the
# capability is dropped by a later layer, the build goes red instead of shipping a proxy
# that cannot listen.
RUN setcap cap_net_bind_service=+ep /usr/sbin/nginx \
 && getcap /usr/sbin/nginx | tee /usr/local/share/nginx-caps.txt \
 && getcap /usr/sbin/nginx | grep -q 'cap_net_bind_service=ep' \
 && echo "nginx: file capability cap_net_bind_service=ep set (uid 101 can bind :80/:443)"

# Non-root: a compromise of the proxy is uid 101, not root on a carrier host. The
# :80/:443 bind comes from the FILE capability set above — not from the stack file's
# cap_add, which only ever applies to uid 0.
USER nginx
EXPOSE 80 443

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -fsS -o /dev/null http://127.0.0.1/healthz || exit 1

STOPSIGNAL SIGQUIT
CMD ["nginx", "-g", "daemon off;"]
