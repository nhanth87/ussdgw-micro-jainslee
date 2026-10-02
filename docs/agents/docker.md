# Docker / Swarm footguns

Agent-facing. Read before touching `docker/`. Operator runbook: `docker/README.md`.
Design + requirement mapping: `plan.md`.

The rules in the root [AGENTS.md](../../AGENTS.md) still hold — this file only records what
is **specific to the Docker path**.

---

## Never report "done" without `docker/prove.sh`

Same law as the Digicom host gate: green tests + a successful build prove nothing about the
running container. `prove.sh` checks the artifact *inside* the running container — image
digest, `BUILD-INFO.json`, `.baked-db-kind`, expected classes in the jar, the process command
line, `/proc/net/sctp`, Java 25, `status.json`, `/admin/cdr/partial`, persisted log files.

`status.json` 200 alone is **not** a proof: it means the app is ready, not that SS7 is up and
not that the admin UI renders.

---

## Build-time `db-kind` is still build-time

The H2-baked-jar crash loop is unchanged by Docker, and it is the single easiest way to
break a deploy. `build-all.sh` flips `db-kind` to `postgresql` **on a copy**, runs
`package-dist.sh` with `USSD_REQUIRE_PG_BAKE=1`, asserts `dist/.baked-db-kind == postgresql`,
then restores. The runtime `Dockerfile` re-checks the stamp and refuses to build otherwise.
The `entrypoint.sh` checks it a third time. **Do not remove any of the three.**

---

## `MAVEN_OPTS` is JVM flags, not Maven flags

```dockerfile
ENV MAVEN_OPTS="-Dmaven.repo.local=/m2 -Xmx2g"   # correct
ENV MAVEN_OPTS="-Dmaven.repo.local=/m2 -B -ntp"  # WRONG
```

`-B`/`-ntp` are Maven **CLI** flags. In `MAVEN_OPTS` they reach the JVM, and every `mvn`
call dies with `Unrecognized option: -B` before the build starts. The CLI flags live in
`MVN_FLAGS` inside `build-all.sh`. Cost of learning this: one failed build.

---

## Always `docker run --user $(id -u):$(id -g)`

Otherwise Maven writes **root-owned** files into the host's `/m2` and `/out`, and the
operator cannot clean or reuse them afterwards (`rm: Permission denied`).

Related: `/build` is not writable for a non-root uid, so `build-all.sh` falls back to
`mktemp -d` rather than dying with a bare `mkdir: Permission denied`.

---

## `/src` must be mounted read-only, and then copied

The operator's audited tree must not be mutated by a build. But Maven writes `target/` inside
each module, so a read-only mount cannot be compiled in place — the symptom is the deeply
misleading `could not create parent directories` from the compiler plugin.

`build-all.sh` therefore copies each tree to a writable `WORK_DIR` (excluding `target/`,
`dist/`, `.git`, `logs/`, `data/`) and builds there. The copy also stops a stale `dist/` from
being mistaken for fresh output.

---

## git "dubious ownership" inside the container

The bind-mounted `/src` belongs to the host user, so git inside the container refuses to read
it. That surfaces as `not a git checkout` from the verifier — a misleading error for a plain
UID mismatch. The builder sets `git config --global --add safe.directory /src/*` (only that
path, not the whole filesystem).

---

## Do NOT exclude sctp's fstack module

The original plan proposed excluding `sctp-backend-fstack` (DPDK not needed — production runs
on kernel SCTP via `jdk.sctp`). **That breaks the build:** jss7's `ss7-config` has a hard
compile dependency on it, so `jss7` fails with

```
Could not find artifact org.mobicents.protocols.sctp:sctp-backend-fstack:jar:2.27.32
```

Building the module needs no DPDK NIC — the native sidecar is behind the `exec` plugin in the
**test** phase, which the build skips. Build every sctp module.

---

## `set -o pipefail` + `unzip | grep -q` fails on a MATCH

`grep -q` exits at the first hit, `unzip` dies on `SIGPIPE`, and the pipeline returns
non-zero — so a valid check reports failure. Extract to a temp dir and test the file instead.
(Already hit once while writing the ADR 0004 profile-accessor check.)

---

## `docker stack config` is the only schema validator

Run it before deploying:

```bash
docker stack config -c docker/stack.yml > /dev/null
```

It caught `stop_grace_period` under `deploy:` — in swarm that key is **top-level** in the
service, not under `deploy`.

`docker stack config` takes no positional args (no stack name).

---

## Heredocs inside a Dockerfile `RUN` must be their own layer

A `cat > f <<EOF … EOF` block inside a continued `RUN` breaks the Dockerfile parser
(`unknown instruction: java` — the heredoc terminator ends the instruction early). Put the
heredoc in a `COPY`ed file instead. `SctpProbe.java` is copied, not inlined.

---

## Container cannot prepare the host

`modprobe sctp`, `net.core.*` sysctls, NTP and the persistent directories are all host-level.
`host-prep.sh` must run on the node as root. Missing `sctp` shows up as
`Protocol not supported` from `jdk.sctp` at the first socket — the app looks healthy right up
until a USSD request arrives.

`entrypoint.sh` checks `/proc/net/sctp` and refuses to start with an actionable message.

---

## SCTP-only is enforced in three places

A `tcp` channel in the stack JSON compiles fine, loads fine, and silently breaks M3UA.
Enforced by: `install-config.sh` validation, `entrypoint.sh` preflight, and the workspace
`AGENTS.md`. Do not add a TCP exception.

---

## Do not bake `configs/` into the runtime image

`configs/` is the operator's source of truth and changes per site. It is a bind mount, and it
must be **rw** — `AdminPlaneHandler.saveStackJson` writes SS7 stack JSON back into it
(`Files.writeString`, line 738). A read-only mount breaks the SS7 admin save with a confusing
error.

---

## Real logs are Log4j2 files, not `docker logs`

`json-file` driver is for stdout only. The application log is
`/opt/ussdgw/logs/ussdgw.log` and the **CDR ledger** is `/opt/ussdgw/logs/ussd-cdr.log`
(the source of truth for `/admin/cdr`; `cdr.file.*` in `status.json` reports its health).

---

## Heap limits

`USSD_XMX=4g` with `USSDGW_MEM_LIMIT=6g`. A shared SS7 host cannot spare 8 GB to one
container (workspace resource-hygiene rule). Setting the swarm limit too low gets the JVM
OOM-killed mid-MAP-dialog, which is worse than any throughput loss.

---

## Single node is a consequence, not a choice

Host network is required because SCTP multi-homing puts local IPs in the INIT chunk (a bridge
or ingress mesh breaks it). A host-networked container cannot resolve swarm service names, so
postgres and nginx are on the host network too. Result: `replicas: 1`, pinned by node label,
`update_config.order: stop-first`. Two instances would duplicate dialogs on the same SCTP
endpoints / Point Code.

---

## Resource hygiene

`docker stack rm ussdgw` and the builder's `--rm`. RAM is shared across all worktrees on this
machine; never leave a stack or a builder container running "for later".