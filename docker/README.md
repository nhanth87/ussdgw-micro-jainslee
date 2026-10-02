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
| `sctp`, `jss7`, `jain-slee` (micro-jainslee + all RAs), `corsac-diameter`, `ussdgw` | Ubuntu 26.04 base image (**by digest**), OpenJDK 25, Maven, Maven Central dependencies (**`--strict-checksums`**), PostgreSQL 16 (**by digest**) |
| `ussdgw-nginx` | nginx **1.27-alpine official image, by digest** (`nginx` in `sources.lock`) — not the Ubuntu repo package, which has no `nginx` user and made the image unbuildable |

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
# The whole chain, from source. This builds the builder image, publishes the
# ussdgw-builder:probe tag that docker/ussdgw/Dockerfile needs, and runs build-all.sh.
RUN_TESTS=1 ./docker/build/run-build.sh

# Prove the cache is complete: same build with `mvn -o`, no network.
OFFLINE=1 ./docker/build/run-build.sh

# Seed from a warm ~/.m2 when building locally (never writes back into it).
SEED_FROM="$HOME/.m2/repository" RUN_TESTS=1 ./docker/build/run-build.sh
```

`run-build.sh` is the supported entry point. The raw `docker run` form below is what it does,
kept here for reference only — it is easy to get subtly wrong (the `--user`, the `HOME`, and
the `/src` mount mode each matter):

```bash
mkdir -p /srv/ussdgw-build/{out,m2}
docker run --rm --user "$(id -u):$(id -g)" \
  -e HOME=/tmp \
  -v /srv/ussdgw-build/src:/src \
  -v /srv/ussdgw-build/out:/out \
  -v /srv/ussdgw-build/m2:/m2 \
  -v "$PWD":/ussdgw-src:ro \
  -e SRC_USSDGW=/ussdgw-src \
  -e SOURCE_MODE=local \
  ussdgw-builder:latest build-all.sh
```

`--user` matters: without it Maven writes root-owned files into `/srv/ussdgw-build/m2` and
the operator cannot clean or reuse it.

### `SOURCE_MODE=local` vs `SOURCE_MODE=git`

| Mode | Who clones | `/src` mount | When |
|---|---|---|---|
| `local` (**default**) | **You, on the host** | `:ro` | The normal path. Fail-closed: `build-all.sh` checks each tree is a git checkout **at the pinned SHA** and refuses otherwise. |
| `git` | `fetch-sources.sh`, inside the container | **read-write** | Convenience only. Needs network + credentials for every repo in `sources.lock`. |

This used to be impossible in the `git` direction: `run-build.sh` mounted `/src:ro`
unconditionally, and `fetch-sources.sh` in `git` mode has to clone and check out **into**
`/src`. The mount is now read-write **only** in `git` mode, so `local` still cannot mutate a
host checkout even if `SOURCE_MODE` is mistyped.

With `local`, put the four trees on the host first:

```bash
mkdir -p /srv/ussdgw-build/src
git clone https://github.com/nhanth87/sctp.git            /srv/ussdgw-build/src/sctp
git clone https://github.com/nhanth87/jss7.git            /srv/ussdgw-build/src/jss7
git clone https://github.com/nhanth87/jain-slee.git       /srv/ussdgw-build/src/jain-slee
git clone https://github.com/nhanth87/corsac-diameter.git /srv/ussdgw-build/src/corsac-diameter
# then `git -C <tree> checkout --detach <sha>` for each, using sources.lock
```

`build-all.sh` re-checks the SHAs itself, so a wrong checkout is a failed build rather than a
subtly different binary.

**The build refuses to proceed if anything is off:**

| Check | Failure message |
|---|---|
| A source tree is not at the pinned commit | `SHA mismatch — want …, got …` |
| The tree is not a git checkout | `not a git checkout` |
| `dist` is baked H2 | `REFUSING to ship: dist is baked 'h2'` |
| `dist/lib/main` missing | `dist incomplete` |
| A jar under `dist/app/` | `dist invalid: jars under app/` |
| `RUN_TESTS=1` and any test fails | build stops; **no image is produced** |

Re-running with `OFFLINE=1` builds with `mvn -o` against the already-populated `/m2`, which
proves no network is needed for the compile.

### Stage the packaged tree, then build the runtime image

`docker/ussdgw/Dockerfile` reads its payload from the **working tree** (`dist/…`,
`out/BUILD-INFO.json`), while the build writes to `/srv/ussdgw-build/out`. Connect them with
the staging script — it is a **mirror**, not a merge:

```bash
./docker/build/stage-dist.sh          # rm -rf dist/ first, then copy
docker build -f docker/ussdgw/Dockerfile -t "ussdgw:$(git rev-parse --short HEAD)" .
docker build -f docker/nginx/Dockerfile  -t "ussdgw-nginx:$(git rev-parse --short HEAD)" .
```

Do **not** hand-copy with `cp -a /srv/ussdgw-build/out/dist/. dist/`. That merges, so every jar
from the previous build survives and the image ships two versions of the same artifact
(`jainslee-core-1.2.0` and `jainslee-core-1.2.1`) — a classpath conflict picked at runtime by
whichever class happens to sort first. `stage-dist.sh` also asserts
`.baked-db-kind == postgresql` and refuses to stage a tree the host cannot run.

### `corsac-diameter` (the one that will surprise you)

`ra-diameter` depends on `com.mobius-software.protocols.diameter:*:10.0.0-41-SNAPSHOT`,
which is **not on Maven Central** (HTTP 404). The builder therefore compiles it from the
pinned upstream commit in `sources.lock` — a commit whose `pom.xml` genuinely reads
`10.0.0-41-SNAPSHOT`. If that dependency ever disappears from the classpath, check this
first; it is not a Docker problem.

---

## 2. Runtime images

```bash
TAG=$(git rev-parse --short HEAD)
./docker/build/stage-dist.sh      # mirror the packaged tree in (required first)
./docker/build/build-images.sh    # all three images, one tag, with asserts
```

That is the whole step. `build-images.sh` builds `ussdgw`, `ussdgw-nginx` **and**
`ussdgw-postgres`, then asks the postgres image whether it is correct instead of
trusting the build log: the initdb hook must be readable by the postgres user, and
`postgresql.conf` must pin loopback.

**Do not skip the postgres image.** `docker/stack.yml` must never resolve this service
to a bare upstream `postgres:16` — see [B17](#b17-why-the-postgres-image-is-built-not-pulled)
below. The two raw `docker build` lines the script wraps are kept only for reference.

Tag with the git SHA — that is your rollback key. `BUILD-INFO.json` inside the image
records the SHA it was actually built from; if that differs from the tag, the tag is
lying, and `ussdgw:<sha>` / `ussdgw-nginx:<sha>` / `ussdgw-postgres:<sha>` are
**byte-identical in payload** for commits that touched no `COPY`-ed path, so re-tagging
to the current commit is safe — but say so in the commit rather than implying a rebuild.

`stage-dist.sh` must run first: the Dockerfiles copy `dist/` and `out/BUILD-INFO.json`
from the **working tree**, while the build writes to `/srv/ussdgw-build/out`. It is a
mirror (`rm -rf dist/` then copy), never a merge — see § 1.

### B17 — why the postgres image is built, not pulled

`docker/stack.yml` used to point this service straight at stock
`postgres:16@<digest>`, while `docker/postgres/Dockerfile` — which layers in
`docker/postgres/initdb/` and the loopback-only `postgresql.conf` — was built by nothing
and referenced by nothing. The documented deploy therefore produced a database that
could not serve the gateway:

1. `initdb/01-ussdgw.sh` creates the `ussdgw` role and the dedicated `ussdgw` database.
   Without it the gateway connects as `username=ussdgw` and PostgreSQL answers
   `FATAL: role "ussdgw" does not exist` — it cannot start at all.
2. Upstream ships `listen_addresses = '*'` so that `-p` publishing works, and every
   service here is on **hostnet**, where there is no publishing step to mask it. 5432
   binds on every interface of a carrier host.

### B18 — the operator tuning must be applied, not merely installed

`docker/postgres/postgresql.conf` is **not** read by the official image. It reads
`$PGDATA/postgresql.conf` and nothing else; grepping its entrypoint for
`/etc/postgresql` returns nothing. So the file the Dockerfile installs — and the
`listen_addresses` line it appends there — is **never opened**, and the server runs on
shipped defaults (`listen_addresses = *`, `shared_buffers = 128MB`).

`initdb/02-operator-tuning.sh` closes the loop: it appends the tuning to
`$PGDATA/postgresql.conf` (later assignments win) so
`/etc/postgresql/postgresql.conf` stays the single authored source and there is exactly
one file to edit. It then **validates by parsing** —
`postgres -D <datadir> -C listen_addresses` — and refuses to continue unless the
effective value is `127.0.0.1`.

Validate configuration files by asking the consumer what it derived
(`postgres -C`, `nginx -T`, `java -XshowSettings`), never with `test -f` and never with
a regex: a merged line such as `log_statement = 'ddl'listen_addresses = '127.0.0.1'`
still matches `^[[:space:]]*listen_addresses[[:space:]]*=`, so a grep-based assert
reported success against a file PostgreSQL had already refused to parse.

The nginx image **proves its own configuration at build time**. It writes a throwaway
self-signed pair to the exact paths `ussdgw.conf` references, runs `nginx -t`, then deletes
both the pair and the pid file `nginx -t` created and asserts nothing was left behind. That
turns "nginx silently refuses to start on the operator's host" into a red build. It has
already earned its keep: the shipped config had a `duplicate upstream "ussdgw_app"` emerg
that made the container impossible to start, and it went unnoticed because `prove.sh` only
logged *"nginx not answering on :80 (expected if not deployed yet)"*.

The runtime image bakes **nothing but the artifact** and a `jlink` JRE. `configs/` is never
copied in; it is mounted from the operator's directory at run time.

### nginx base image

`docker/nginx/Dockerfile` builds on the **official** `nginx:1.27-alpine`, pinned by digest in
`sources.lock`. The previous version installed nginx from Ubuntu's repository and could not
build at all:

```
RUN mkdir -p /var/log/nginx … && chown -R nginx:nginx …   ->  exit code: 1
```

because Ubuntu's nginx package never creates a `nginx` user (it uses `www-data`), which also
made `user nginx;` in `nginx.conf` wrong. The official image ships the user, the entrypoint
and the template engine already.

The container runs as uid 101 with only `CAP_NET_BIND_SERVICE`. Three consequences are baked
into the config and are easy to undo by accident:

| Directive | Why it is what it is |
|---|---|
| **no** `user nginx;` | Ignored, with a warning, when the master is not super-user: *"[warn] the `user` directive makes sense only if the master process runs with super-user privileges"*. Workers are already `nginx` because of `USER nginx`. |
| `pid /tmp/nginx.pid;` | nginx creates this file itself and `/var/run` is root-owned, so a uid-101 master dies with `[emerg] open() … failed (13: Permission denied)`. The old `touch /var/run/nginx.pid && chown` only worked until someone remounted `/run` as a tmpfs. |
| `worker_rlimit_nofile 8192` + `worker_connections 4096` | Without the first, the container's default soft limit caps real connections while nginx only warns: `[warn] 4096 worker_connections exceed open file resource limit: 2048`. `stack.test.yml` sets the matching service `ulimits`. |

`:443` is **required**, not conditional. `ussdgw.conf` used to claim *"Enabled only when certs
are mounted"* while the `server` block was unconditional, so nginx refused to start on any
host without a certificate. Certs are bind-mounted from `/srv/ussdgw/nginx/certs`
(`install-config.sh --check` verifies they exist, are readable by uid 101, are unexpired and
chain-complete), and an admin UI that silently downgrades to cleartext is a worse failure than
a container that refuses to start and says why.

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
`ussd.map.config-file`, a missing/unreadable/expired TLS certificate for the nginx edge, and
warns on `ussd.lab.allow-default-secrets=true`, a missing SSN 147, a single-leaf certificate
served as `fullchain.pem`, and tenant `network_id` ≠ SCCP `networkId`.

Seed the certificate on the host so uid 101 can read it — nginx runs non-root and the bind
mount takes the host's ownership:

```bash
sudo install -m 0644 -o 101 -g 101 fullchain.pem /srv/ussdgw/nginx/certs/fullchain.pem
sudo install -m 0640 -o 101 -g 101 privkey.pem  /srv/ussdgw/nginx/certs/privkey.pem
```

A root-owned `0600` key makes nginx fail with `[emerg] cannot load certificate key … (Permission
denied)`, after which `restart_policy: max_attempts: 5` retires the task: no `:80`, no admin
UI, and `docker stack services` still looks healthy. Set `CERT_DIR=` if your bind mount is
somewhere other than `/srv/ussdgw/nginx/certs`.

---

## 4. Secrets

Three, all declared `external: true` in `docker/stack.yml`, so all three must exist
**before** `docker stack deploy`:

```bash
printf '%s' "$(openssl rand -base64 32)" | docker secret create ussdgw_db_password -
printf '%s' "$(openssl rand -base64 32)" | docker secret create ussdgw_pg_super_password -
printf '%s' "<your-admin-key>"           | docker secret create ussdgw_admin_key -
```

| Secret | Consumer | Role |
|--------|----------|------|
| `ussdgw_db_password` | gateway JDBC + `initdb/01-ussdgw.sh` | application role **`ussdgw`** |
| `ussdgw_pg_super_password` | `POSTGRES_PASSWORD_FILE` | postgres **superuser** `ussdgw_admin` |
| `ussdgw_admin_key` | admin UI / API | `X-USSD-Admin-Key` |

Keep the two database credentials **separate**. The gateway only ever needs the `ussdgw`
role; sharing one secret would make a leaked JDBC password equal to DDL and
role-administration on the host.

A missing secret is **not** caught by `docker stack deploy` — the deploy succeeds, the
container exits 1 because `/run/secrets/<name>` is absent, and after
`restart_policy: max_attempts: 5` Swarm retires the task while `docker stack services`
still lists it. Postgres fails with
`Error: Database is uninitialized and superuser password is not specified`, which is how
B19 shipped: the stack had set `POSTGRES_USER`/`POSTGRES_DB` and never a password.
`./docker/install-config.sh --check` verifies all three exist before anything is stopped.

Never put these in `docker/stack.yml` or `.env` — both are readable via
`docker service inspect` and end up in shell history. For the same reason postgres reads
`POSTGRES_PASSWORD_FILE`, not `POSTGRES_PASSWORD`.

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
* **Two test failures were expected here and no longer are.** This section used to list
  `GrpcClientSbbPullStateTest.completionOnAnotherInstanceStillSeedsTheAdaptiveGate` and
  `Map2MapBridgeArmTest.fastHopStillRearmsAwaitingAsAndPulls` as "expected failures", while
  `build-all.sh` printed `NOTE: continuing — known pre-existing failures` and packaged the
  image anyway. Both tests are green now (669 run / 0 fail), and `RUN_TESTS=1` **fails the
  build** on any red test. The allowance was not merely stale: with tests switched on, a
  genuinely broken tree still produced an image, so anyone running `RUN_TESTS=1` reasonably
  believed the tests had gated the build. They had not.
* **`jain-slee` is pinned to a pushed commit.** Digicom currently runs a `ra-jss7` built from
  a commit that was never pushed (read-only association/AS status accessors). We pin the
  pushed commit because it is auditable; `ussdgw` does not reference those accessors.
* **5432 is loopback-only** by design. Opening it to the network, as the original request
  suggested, would expose the USSD database — if remote access is genuinely needed, add the
  management range in both `postgresql.conf` and `pg_hba.conf`.