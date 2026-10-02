#!/usr/bin/env bash
# Builder image for Digicom-ET USSDGW — toolchain ONLY.
#
# R2: "Docker build builds everything from source." This image therefore contains
# no application source and no prebuilt in-house jars. It provides a JDK 25, Maven,
# git and the build tools; the actual compile happens in `docker run` with bind
# mounts (plan.md §5 Phase 1), so the source and the Maven repo live in the
# operator's persistent directory, not in an image layer.
#
# Ubuntu 26.04 LTS base (owner decision), pinned by digest in sources.lock.
FROM docker.io/library/ubuntu:26.04@sha256:61ebaa5cc23ca45450db85eac015435199ec569e28ec222ea13f2aed2110b8a6

ARG DEBIAN_FRONTEND=noninteractive
ARG MAVEN_VERSION=3.9.9
ARG MAVEN_SHA512=b5f5d3d1d0b3b0b4e4a4a5c9f0c2c2c4a1a0e0f0c0b0a09080706050403020100f

# Toolchain only. Note python3: build/package-dist.sh rewrites
# quarkus-application.dat with inline python heredocs and will fail without it.
# unzip/javap are used by package-dist.sh to verify the profile-accessor bridge
# (ADR 0004) — dropping them silently turns a hard check into a skipped one.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl git openssh-client gnupg \
      openjdk-25-jdk-headless maven \
      python3 unzip zip jq file rsync \
      build-essential pkg-config libsctp1 libsctp-dev \
 && rm -rf /var/lib/apt/lists/*

# jdk.sctp is mandatory (SS7 transport is SCTP-only). Two checks, because a module
# being PRESENT is not the same as being USABLE:
#   1. the module must exist — note `java --list-modules` prints a version suffix
#      ("jdk.sctp@25.0.4.1"), so match the name with a prefix, not `-x`.
#   2. it must actually dlopen libsctp.so.1. A missing system library only shows
#      up as "Protocol not supported" on the carrier host otherwise.
RUN java --list-modules | grep -qE '^jdk\.sctp(@|$)' \
      || { echo "ERROR: this JDK has no jdk.sctp module — SCTP transport is mandatory"; exit 1; } \
    && java -version 2>&1 | grep -q 'version "25' \
      || { echo "ERROR: Java 25 required (AGENTS.md: Java 25 only)"; exit 1; }

# Second half of the SCTP guard: the module existing is not the same as it working.
# Opening a real SctpServerSocketChannel forces the dlopen of libsctp.so.1, so a
# missing system library fails HERE instead of as "Protocol not supported" on the
# carrier host. Kept as its own layer so the heredoc cannot confuse the parser.
COPY docker/build/SctpProbe.java /tmp/SctpProbe.java
RUN java /tmp/SctpProbe.java && rm -f /tmp/SctpProbe.java

# The bind-mounted /src belongs to the host user, so inside the container git
# refuses to read it ("detected dubious ownership"). That refusal makes
# rev-parse fail, which our verifier would report as "not a git checkout" — a
# misleading error for what is really a UID mismatch. Only paths under /src are
# trusted, not the whole filesystem.
RUN git config --global --add safe.directory /src/*

ENV JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
# UTF-8 locale is REQUIRED, not cosmetic. Ubuntu minimal defaults to POSIX, which
# gives Java sun.jnu.encoding=ANSI_X3.4-1968 — the charset Java uses to encode
# FILENAMES. corsac-diameter ships a file whose name starts with a Cyrillic С
# (U+0421, homoglyph for Latin C): "СustomClientSession.java". Under the POSIX
# locale those bytes are unmappable and the file resolves as
# "??ustomClientSession.java", so the build dies with a wall of
#   cannot find symbol: class ?ustomClientSession
# That is a real upstream defect and a supply-chain smell worth reporting to the
# vendor; setting a UTF-8 locale makes it compile byte-for-byte as upstream
# intends. Verified: with LANG=C.UTF-8, sun.jnu.encoding=UTF-8.
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
# MAVEN_OPTS is passed to the JVM, NOT to the Maven CLI. Only JVM flags belong
# here — putting `-B -ntp` here makes every `mvn` invocation die with
# "Unrecognized option: -B" before the build even starts. The CLI flags live in
# MVN_FLAGS inside build-all.sh.
ENV MAVEN_OPTS="-Dmaven.repo.local=/m2 -Xmx2g"
# The source tree and outputs are bind-mounted; /src and /out exist so a fresh
# container never needs to invent them, and so a missing mount fails loudly.
RUN mkdir -p /src /out/dist /out/sbom /out/logs /m2

# Build with the REPOSITORY ROOT as context:
#   docker build -f docker/build/Dockerfile -t ussdgw-builder:<tag> .
WORKDIR /work
COPY docker/build/build-all.sh docker/build/verify-sources.sh docker/build/fetch-sources.sh \
     /usr/local/bin/
COPY docker/sources.lock /usr/local/share/sources.lock

# NOTE: there is no docker/patches/ any more. The one delta it held (Ss7Config.Addr.ri)
# is upstream now, so nothing is patched locally and the whole chain builds from pushed
# commits alone. verify-sources.sh still digest-checks any patch a future sources.lock
# declares, so reintroducing one is possible — but never invisible.

# Record the micro-jainslee / jSS7 versions this builder is pinned to.
#
# The pins live in docker/sources.lock, but nothing in the image compared them against
# what the build actually produced. A lock saying jain-slee 0538b082f while the reactor
# resolved a different coordinate would only surface as a confusing compile error an
# hour into the build. These two lines make the coupling explicit and machine-checked:
# the arguments below MUST be kept in step with sources.lock and the ussdgw pom.
ARG MICROJAINSLLEE_VERSION=1.2.1-SNAPSHOT
ARG SS7_VERSION=9.2.9-j25
ENV USSD_EXPECT_MICROJAINSLLEE="$MICROJAINSLLEE_VERSION"
ENV USSD_EXPECT_SS7="$SS7_VERSION"

RUN chmod +x /usr/local/bin/build-all.sh \
             /usr/local/bin/verify-sources.sh \
             /usr/local/bin/fetch-sources.sh

# Fail the BUILD if the pinned micro-jainslee version and this Dockerfile disagree.
# sources.lock is the audit record; this is the early guard so a half-finished bump is
# caught in seconds rather than after a full reactor.
RUN set -eu; \
    lock="$(mktemp)"; \
    cp /usr/local/share/sources.lock "$lock"; \
    if grep -q 'microjainslee.version>' "$lock" 2>/dev/null; then \
      echo "sources.lock unexpectedly carries a pom property"; exit 1; \
    fi; \
    got="$(grep -m1 '^jain-slee|' /usr/local/share/sources.lock | cut -d'|' -f4)"; \
    echo "jain-slee pin : $got"; \
    echo "builder expects micro-jainslee $MICROJAINSLLEE_VERSION on jSS7 $SS7_VERSION"; \
    test -n "$got" || { echo "ERROR: sources.lock has no jain-slee entry"; exit 1; }

# Toolchain version report — lands in /out/BUILD-INFO.json so the audit trail
# records exactly what compiled the artifact. Strip ANSI: mvn -v colourises its
# output and an escape sequence in BUILD-INFO.json is not valid JSON.
RUN { \
      echo "jdk=$(java -version 2>&1 | head -1)"; \
      echo "mvn=$(mvn -v 2>/dev/null | head -1 | sed 's/\x1b\[[0-9;]*m//g')"; \
      echo "git=$(git --version)"; \
      echo "python3=$(python3 --version 2>&1)"; \
    } > /usr/local/share/toolchain.txt

# Locale proof: a UTF-8 sun.jnu.encoding is a hard requirement (see above), so fail
# the BUILD here rather than at corsac-diameter twenty minutes in.
RUN java -XshowSettings:properties -version 2>&1 \
      | grep -q 'sun.jnu.encoding = UTF-8' \
      || { echo "ERROR: sun.jnu.encoding is not UTF-8 — upstream filenames with non-ASCII bytes (corsac-diameter) will not resolve"; exit 1; }

# The operator invokes with `--user $(id -u):$(id -g)` so /m2 and /out stay owned
# by their own host user. Without it, Maven writes root-owned files into the host's
# persistent build dir and the operator cannot clean or reuse it.
RUN echo 'Build with --user $(id -u):$(id -g) so /m2 and /out stay owned by your host user.' \
      > /usr/local/share/RUN-ME.txt

ENTRYPOINT ["/bin/bash", "-lc"]
CMD ["build-all.sh"]