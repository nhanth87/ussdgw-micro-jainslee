# USSDGW on Docker Swarm — operator runbook

Build everything from source, run it on a single SS7 node, audit what shipped.

This is the operator-facing guide. For agent footguns see `docs/agents/docker.md`;
for the requirement mapping and design decisions see `plan.md`.

---

## What "built from source" means here (the trust boundary)

The customer requires that we ship **no prebuilt binaries** and that the operator can audit
the codebase. Building 100 % from source is not possible — Quarkus, Netty, Jackson, Flyway
and the PostgreSQL driver are ~300 third-party jars. So the boundary is explicit:

| Built from source (pinned commit, `sources.lock`) | Accepted as pinned upstream |
|---|---|
| `sctp`, `jss7`, `jain-slee` (micro-jainslee + all RAs), `corsac-diameter`, `ussdgw` | Ubuntu 26.04 base image (**by digest**), OpenJDK 25, Maven, Maven Central dependencies (**`--strict-checksums`**), PostgreSQL 16 (**by digest**), nginx (Ubuntu repo, signed index) |

Every accepted artifact is either digest-pinned or checksum-verified, and every jar appears
in the CycloneDX SBOM at `/srv/ussdgw-build/out/sbom/`.

**To audit a build:**

```bash
cat   /srv/ussdgw-build/out/BUILD-INFO.json      # every SHA that produced this artifact
jq    '.components[].purl' /srv/ussdgw-build/out/sbom/ussdgw.cdx.json | head -50
docker exec ussdgw_ussdgw.<id> cat /opt/ussdgw/BUILD-INFO.json   # what is actually running
```

---

## Prerequisites on the node

* Docker Engine **≥ 24** (swarm `ulimits`, `stop_grace_period`)
* Kernel with the `sctp` module
* The node must be the one whose IP the carrier peers whitelist (Digicom:
  `172.16.144.163`) — see *Why host network* below
* ~8 GB RAM available to the container (`USSD_XMX` 4 g + overhead)

```bash
sudo ./docker/host-prep.sh
```

This loads `sctp`, persists it across reboots, raises the SCTP buffer sysctls, creates
`/srv/ussdgw/{configs,logs,data,pgdata}` with the right ownership, enables NTP, and checks
the Docker version and the node label. **A container cannot do any of this** — without
`modprobe sctp` the gateway boots and then fails at the first SCTP socket.

---

## 1. Build

```bash
# One-time: the builder image (toolchain only, no source inside)
docker build -f docker/build/Dockerfile -t ussdgw-builder:latest .

# Build the whole chain from source into /srv/ussdgw-build/out
mkdir -p /srv/ussdgw-build/{out,m2}

docker run --rm --user "$(id -u):$(id -g)" \
  -e HOME=/tmp \
  -v /srv/ussdgw-build/src:/src:ro \
  -v /srv/ussdgw-build/out:/out \
  -v /srv/ussdgw-build/m2:/m2 \
  -v "$PWD":/ussdgw-src:ro \
  -e SRC_USSDGW=/ussdgw-src \
  -e SOURCE_MODE=local \
  ussdgw-builder:latest build-all.sh
```

`--user` matters: without it Maven writes root-owned files into `/srv/ussdgw-build/m2` and
the operator cannot clean or reuse it.

`/srv/ussdgw-build/src` must contain, each at the exact commit in `sources.lock`:
`sctp/`, `jss7/`, `jain-slee/`, `corsac-diameter/`.

**The build refuses to proceed if anything is off:**

| Check | Failure message |
|---|---|
| A source tree is not at the pinned commit | `SHA mismatch — want …, got …` |
| The tree is not a git checkout | `not a git checkout` |
| `dist` is baked H2 | `REFUSING to ship: dist is baked 'h2'` |
| `dist/lib/main` missing | `dist incomplete` |
| A jar under `dist/app/` | `dist invalid: jars under app/` |

Re-running with `OFFLINE=1` builds with `mvn -o` against the already-populated `/m2`, which
proves no network is needed for the compile.

### `corsac-diameter` (the one that will surprise you)

`ra-diameter` depends on `com.mobius-software.protocols.diameter:*:10.0.0-41-SNAPSHOT`,
which is **not on Maven Central** (HTTP 404). The builder therefore compiles it from the
pinned upstream commit in `sources.lock` — a commit whose `pom.xml` genuinely reads
`10.0.0-41-SNAPSHOT`. If that dependency ever disappears from the classpath, check this
first; it is not a Docker problem.

---

## 2. Runtime image

```bash
TAG=$(git rev-parse --short HEAD)
docker build -f docker/ussdgw/Dockerfile -t "ussdgw:$TAG" .
docker build -f docker/nginx/Dockerfile  -t "ussdgw-nginx:$TAG" .
```

Tag with the git SHA — that is your rollback key.

The runtime image bakes **nothing but the artifact** and a `jlink` JRE. `configs/` is never
copied in; it is mounted from the operator's directory at run time.

---

## 3. Configuration (operator directory)

```bash
CONFIG_SRC=/path/operator/configs ./docker/install-config.sh --check   # validate only
CONFIG_SRC=/path/operator/configs ./docker/install-config.sh           # seed once
```

`install-config.sh` copies **once** and never overwrites: the admin UI writes SS7 stack JSON
back into `configs/`, so refreshing it would destroy live edits. Use `--force` for a
timestamped backup first if you really mean it.

Validation refuses: a non-`postgresql` `db-kind`, a non-`jdbc:postgresql://` URL, invalid
SS7 JSON, **any non-SCTP `channel`** (SS7 is SCTP-only, RFC 4666 §3), a missing
`ussd.map.config-file`, and warns on `ussd.lab.allow-default-secrets=true`, a missing
SSN 147, and tenant `network_id` ≠ SCCP `networkId`.

---

## 4. Secrets

```bash
printf '%s' "$(openssl rand -base64 32)" | docker secret create ussdgw_db_password -
printf '%s' "<your-admin-key>"        | docker secret create ussdgw_admin_key -
```

Never put these in `docker/stack.yml` or `.env` — both are readable via
`docker service inspect` and end up in shell history.

---

## 5. Deploy

```bash
docker swarm init --advertise-addr <node-ip>
docker node update --label-add ussdgw=true "$(hostname)"
cp docker/.env.example docker/.env      # then edit
docker stack deploy -c docker/stack.yml ussdgw
docker stack services ussdgw
```

Wait for the healthcheck (`start_period` is 120 s because Flyway and the profile tables run
before the port binds):

```bash
until curl -fsS -o /dev/null http://127.0.0.1:8088/admin/status.json; do sleep 5; done
```

---

## Why host network (and why that makes this single-node)

* SCTP multi-homing puts the **local IP addresses inside the INIT chunk**. A bridge network
  or a swarm ingress mesh (IPVS) changes the source address the peer sees, and the M3UA peer
  rejects it. SS7 must never be load-balanced.
* The gateway binds SCTP on real host IPs (`172.16.144.163:2011/2019`), so it must be on the
  host network to reach them.
* A container on the host network cannot resolve swarm service names, so **postgres and nginx
  also run on the host network** and everything talks over `127.0.0.1`.
* Therefore: `replicas: 1`, pinned to `node.labels.ussdgw == true`, `update_config.order:
  stop-first`. Two instances would fight over the same SCTP endpoints and the same Point
  Code, which on a live network means duplicate dialogs.

---

## 6. Prove it (do not skip)

```bash
./docker/prove.sh
```

Green `mvn test` and a successful build **never** mean the host runs the new code. `prove.sh`
checks the *running container*: the image digest, `BUILD-INFO.json`, the PG-bake stamp, the
expected classes inside the jar, the running process command line, `/proc/net/sctp`, Java 25,
`status.json`, the CDR page, the persisted log files.

**`status.json` alone does not prove the CDR ledger or the UI** — a 200 only means the app is
ready, not that SS7 is up and not that the admin surface renders.

---

## Operations

```bash
# Logs — real logs and the CDR ledger are Log4j2 files, NOT docker logs
docker exec "$(docker ps -q --filter name=ussdgw_ussdgw | head -1)" tail -f /opt/ussdgw/logs/ussdgw.log
tail -f /srv/ussdgw/logs/ussd-cdr.log        # the CDR ledger (source of truth for /admin/cdr)

# SS7 link truth (never infer from LISTEN)
curl -sS -H 'X-USSD-Admin-Key: <key>' http://127.0.0.1:8088/admin/status.json | jq '."ss7.live"'

# SCTP endpoints on the host
cat /proc/net/sctp/eps

# pcap (host network makes this trivial, no sidecar container)
sudo tcpdump -i any -w /tmp/ussdgw-$(date +%Y%m%d-%H%M%S).pcap sctp

# Rollback
docker service rollback ussdgw_ussdgw
docker service update --image ussdgw:<previous-sha> ussdgw_ussdgw

# Tear down (resource hygiene — always clean up)
docker stack rm ussdgw
```

---

## Known limitations

* **Single node.** The SS7 endpoint and its Point Code make this a single point of failure.
  HA needs peer-side changes (active/standby + SCTP multi-homing), so it is post-go-live.
* **`ss7.live` may be honestly `false`** in the healthcheck. That is correct: the healthcheck
  means *app ready*, not *peer up*. Restarting on that would take down a healthy gateway.
* **Two test failures are expected** and are reported, not hidden:
  `GrpcClientSbbPullStateTest.completionOnAnotherInstanceStillSeedsTheAdaptiveGate` and
  `Map2MapBridgeArmTest.fastHopStillRearmsAwaitingAsAndPulls`. Both reproduce on a clean
  tree — pre-existing debt in the AS-pull state registry, unrelated to this build.
* **`jain-slee` is pinned to a pushed commit.** Digicom currently runs a `ra-jss7` built from
  a commit that was never pushed (read-only association/AS status accessors). We pin the
  pushed commit because it is auditable; `ussdgw` does not reference those accessors.
* **5432 is loopback-only** by design. Opening it to the network, as the original request
  suggested, would expose the USSD database — if remote access is genuinely needed, add the
  management range in both `postgresql.conf` and `pg_hba.conf`.