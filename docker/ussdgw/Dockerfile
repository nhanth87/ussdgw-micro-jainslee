# Runtime image for the USSDGW.
#
# Contains ONLY the built artifact and a minimal JRE — no JDK, no Maven, no source.
# Everything under /opt/ussdgw is copied from the builder's /out/dist; configs are
# NEVER baked (they are the operator's source of truth and change per site).
#
# The build context is the REPOSITORY ROOT, with the packaged dist/ produced by
# `docker run ussdgw-builder build-all.sh`:
#   docker build -f docker/ussdgw/Dockerfile -t ussdgw:<git-sha> .

# ---------------------------------------------------------------------------
# Stage 1 — JRE produced by jlink from the same JDK 25 that compiled the app.
# ---------------------------------------------------------------------------
FROM ussdgw-builder:probe AS jre

# jlink a runtime sized for this application rather than shipping a full JDK.
# The module list is the union of what Quarkus fast-jar, netty, the PostgreSQL
# driver, Log4j2 and jdk.sctp need. jdk.sctp is non-negotiable (SCTP-only
# transport); jdk.unsupported is required by Netty/Agrona internals.
#
# java.rmi is NOT optional: SmallRye Context Propagation resolves
# java.rmi.RemoteException at Quarkus augmentation, so a runtime without it dies
# with a bare ClassNotFoundException before the port ever binds. Found by
# actually starting the container, not by reading the list.
#
# Missing reflective modules are the classic jlink failure: they fail at runtime,
# not at build time. That is why this image is always proved by booting it
# (docker/prove.sh), and why the list below errs wide rather than minimal.
RUN jlink \
      --add-modules java.base,java.logging,java.sql,java.naming,java.management,java.xml,java.desktop,\
java.instrument,java.net.http,java.rmi,java.security.jgss,java.security.sasl,jdk.unsupported,\
jdk.crypto.ec,jdk.crypto.cryptoki,jdk.management,jdk.sctp,jdk.localedata,jdk.jfr,jdk.zipfs,jdk.jsobject \
      --strip-debug --no-header-files --no-man-pages --compress=zip-9 \
      --output /opt/jre

# ---------------------------------------------------------------------------
# Stage 2 — the runtime.
# ---------------------------------------------------------------------------
FROM docker.io/library/ubuntu:26.04@sha256:61ebaa5cc23ca45450db85eac015435199ec569e28ec222ea13f2aed2110b8a6

ARG DEBIAN_FRONTEND=noninteractive

# libsctp1  — jdk.sctp dlopens it; without it the first SCTP socket dies with
#             "Protocol not supported" and the app looks healthy until then.
# tini       — PID 1: forwards SIGTERM so `docker stack rm` / stop actually ends
#             the JVM, which matters because live MAP dialogs are ended on SIGTERM.
# curl       — HEALTHCHECK and prove.sh.
# unzip      — prove.sh must be able to LIST the app jar to prove the expected
#             classes are inside the running artifact. Without it the check
#             silently degrades to "grep the raw bytes", which cannot tell a
#             present class from an absent one. ~200 KB, worth it.
# tzdata     — MAP timers / CDR timestamps depend on a stable zone (plan.md §7).
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      libsctp1 tini curl unzip tzdata ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && ln -snf /usr/share/zoneinfo/Africa/Addis_Ababa /etc/localtime \
 && echo "Africa/Addis_Ababa" > /etc/timezone

ENV APP_HOME=/opt/ussdgw
ENV JAVA_HOME=/opt/jre
ENV PATH="$JAVA_HOME/bin:$PATH"
ENV USSD_LOG_DIR="$APP_HOME/logs"

# Non-root. uid 10001 is fixed so the bind-mounted /srv/ussdgw/{configs,logs,data}
# can be chowned once on the host and then be read/write by the container.
RUN groupadd -g 10001 ussdgw \
 && useradd -u 10001 -g 10001 -d "$APP_HOME" -s /usr/sbin/nologin ussdgw \
 && mkdir -p "$APP_HOME/configs" "$APP_HOME/configs/ss7-persist" \
              "$APP_HOME/logs" "$APP_HOME/data" \
 && chown -R 10001:10001 "$APP_HOME"

# ---------------------------------------------------------------------------
# The artifact. Fast-jar layout exactly as package-dist.sh produces it:
#   quarkus-run.jar  thin launcher — NEVER `java -jar ussdgw-app.jar` alone
#   ussdgw-app.jar   app classes at APP_HOME ROOT
#   lib/{boot,main}  dependencies
#   quarkus/         generated model, points at the ROOT app jar
#   app/html/        admin UI only — never any jar under app/
# configs/ is NOT copied: it is mounted at run time from the operator's directory.
# ---------------------------------------------------------------------------
COPY --from=jre /opt/jre/ "$JAVA_HOME/"
COPY --chown=10001:10001 dist/ussdgw-app.jar     "$APP_HOME/"
COPY --chown=10001:10001 dist/quarkus-run.jar    "$APP_HOME/"
COPY --chown=10001:10001 dist/lib/               "$APP_HOME/lib/"
COPY --chown=10001:10001 dist/quarkus/          "$APP_HOME/quarkus/"
COPY --chown=10001:10001 dist/app/html/          "$APP_HOME/app/html/"
COPY --chown=10001:10001 dist/run.sh             "$APP_HOME/run.sh"
# The PG-bake stamp travels with the image so prove.sh can check it on the running
# container, not just on the build host.
COPY --chown=10001:10001 dist/.baked-db-kind      "$APP_HOME/.baked-db-kind"
COPY --chown=10001:10001 docker/ussdgw/entrypoint.sh "$APP_HOME/entrypoint.sh"

# Audit trail inside the image: which SHAs produced this artifact (plan.md §7).
COPY --chown=10001:10001 out/BUILD-INFO.json      "$APP_HOME/BUILD-INFO.json"

RUN chmod +x "$APP_HOME/run.sh" "$APP_HOME/entrypoint.sh" \
 && test -d "$APP_HOME/lib/main" || { echo "dist incomplete: lib/main missing"; exit 1; } \
 && test -f "$APP_HOME/ussdgw-app.jar" || { echo "dist incomplete: ussdgw-app.jar missing"; exit 1; } \
 && test -f "$APP_HOME/quarkus-run.jar" || { echo "dist incomplete: quarkus-run.jar missing"; exit 1; } \
 && if find "$APP_HOME/app" -name '*.jar' | grep -q .; then \
      echo "dist invalid: jars under app/ — app/ is UI-only"; exit 1; \
    fi \
 && test "$(cat "$APP_HOME/.baked-db-kind")" = postgresql \
      || { echo "REFUSING: image is not PG-baked ($(cat "$APP_HOME/.baked-db-kind")) — crash-loop on a PG host"; exit 1; } \
 && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "25' \
      || { echo "refusing: runtime is not Java 25"; exit 1; }

USER 10001:10001
WORKDIR $APP_HOME

EXPOSE 8088/tcp

# App-ready, not SS7-up: ss7.live may legitimately be false when the peer is down,
# and a healthcheck that fails on that would restart a perfectly good gateway.
# start_period is generous because Flyway + profile tables run before the port binds.
HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
  CMD curl -fsS -o /dev/null -H "X-USSD-Admin-Key: ${USSD_ADMIN_API_KEY:-}" \
      http://127.0.0.1:8088/admin/status.json || exit 1

# SIGTERM so tini forwards it to the JVM and Quarkus runs its shutdown hook
# (drain the CDR queue, end parked MAP dialogs) instead of being SIGKILLed.
STOPSIGNAL SIGTERM

ENTRYPOINT ["/usr/bin/tini", "--", "/opt/ussdgw/entrypoint.sh"]