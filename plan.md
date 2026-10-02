# Plan — Digicom-ET USSDGW: build from source + run on Docker Swarm

> Status: **decisions locked 2026-10-02**, implementation in progress.
>
> **Locked decisions (owner):**
> - **D1 = Option A** trust boundary, with PostgreSQL pulled from Docker Hub (pinned by digest) and
>   nginx via `apt-get`; **Ubuntu 26.04 LTS** as the base for all images.
> - **`corsac-diameter 10.0.0-41-SNAPSHOT` is buildable from source** — see §1.1. The blocker that
>   made R3 unauditable is resolved; exact commit pinned in `docker/sources.lock`.
> - Scope: **all phases (0–7)**.

## 0. Customer requirements

| # | Requirement (from customer) |
|---|------------------------------|
| R1 | Go live soon, everything runs in Docker, **no binaries shipped by us** |
| R2 | Docker build **builds everything from source** |
| R3 | Operator must be able to **audit the codebase**; we cannot hand over prebuilt binaries or externally downloaded artifacts |
| R4 | Build-service Dockerfile fetches: OpenJDK 25, digicom-et `sctp`, `jss7`, `micro-jainslee` (jain-slee), `sip-servlets`, `ussd-microjainslee` → compiles everything inside Docker, output mounted to an **external persistent dir** |
| R5 | Runtime ussdgw container: SCTP must work, **nginx** fronts the HTTP port as **80** (public), open SCTP, PostgreSQL, and vert.x HTTP ports |
| R6 | PostgreSQL container with **persistent data** |
| R7 | jSS7/USSD config copied from an **operator-specified directory** |
| R8 | **Docker Swarm** stack starts all services |
| R9 | Anything else needed so ussdgw runs "perfect" in Docker |

---

## 1. Facts found in the codebase (these drive the design)

| Fact | Where | Consequence |
|------|-------|-------------|
| App HTTP is the **vert.x RA on `:8088`** (`http.ra.port=8088`), serving admin UI + NI `/ussd` + `/metrics` + `/admin/status.json`. Quarkus HTTP is **disabled** (`quarkus.http.host-enabled=false`). Nothing listens on 8080. | `dist/configs/application.properties` | nginx `:80 → 127.0.0.1:8088` (the request says "8080", see **D5**) |
| Other listeners: gRPC `9099`, SMPP `2775/2776`, Diameter `3868`, SIP `5060` tcp+udp, SCTP lab `8013`, Digicom SCTP **`172.16.144.163:2011` / `:2019`** (+ `127.0.0.1:8023`) | properties + `build/ss7-digicom-balance.json` | SCTP binds **specific host IPs** → container needs the **host network** (see **D4**) |
| `quarkus.datasource.db-kind` is **BUILD-TIME**. An H2 bake on Digicom PG = crash loop. | AGENTS § H2-baked jar | The builder **always** bakes `postgresql` and asserts `dist/.baked-db-kind` |
| Build chain today = `dist-package-script.sh`: sctp → jss7 → jain-slee → `package-dist.sh` (needs `python3`) | repo root | The Docker builder reuses this order (not reinvented) |
| `ra-sip-servlet` does **not** use restcomm `sip-servlets`. It uses **Mobius `corsac-sip` 10.1.0-31** (Maven Central). | `jain-slee/vendor-ras/ra-sip-servlet/pom.xml` | `digicom-et/sip-servlets` is **not on the ussdgw classpath** (see **D7**) |
| `ra-diameter` uses **`corsac-diameter` 10.0.0-41-SNAPSHOT** (a SNAPSHOT, **not** on Maven Central) + `jdiameter 1.7.0.178` | `vendor-ras/ra-diameter/pom.xml` | `corsac-diameter` must be **built from source** in the chain (missing from the request list) |
| Known bootstrap gotchas: jss7 + jain-slee need the parent `mvn -N install` first; `jainslee-pom` BOM first | AGENTS § Cursor Cloud | Encoded in `build-all.sh` |
| JDK SCTP (`jdk.sctp`) **dlopens `libsctp.so.1`** at runtime, and needs the **host kernel `sctp` module** | — | Runtime image installs `libsctp1`; the **host** loads `sctp` (a container can't, unless privileged) |
| SCTP buffer sysctls already exist (`net.core.*mem*`, `net.sctp.sctp_*mem`) | `build/systemd/99-ussdgw-sctp-buffers.conf` | Reused in `host-prep.sh` (`net.core.*` is not namespaced, so it's set on the host) |
| `run.sh` hardcodes `-Dquarkus.config.locations=file:$APP_HOME/configs/application.properties` | `build/run-dist.sh` | The container mounts configs at `/opt/ussdgw/configs` (`APP_HOME=/opt/ussdgw`) |
| ⚠ Several local clones have **GitHub PATs embedded in the remote URL** (`jain-slee` nhanth87 remote, `sctp`, `sip-servlets`) | `git remote -v` | **Rotate those tokens.** The builder must take the token via a **BuildKit secret**, never `ARG`/`ENV`/URL |

### 1.1 `corsac-diameter 10.0.0-41-SNAPSHOT` — resolved, buildable from source

This was the one hard blocker for R3 (audit): the artifact Digicom runs is a **SNAPSHOT that is
not on Maven Central**, and the workspace had only `10.0.0-34-SNAPSHOT` source. Findings:

| Check | Result |
|-------|--------|
| `corsac-diameter` on Maven Central | **HTTP 404** — not published there |
| `~/.m2` `diameter-impl-10.0.0-41-SNAPSHOT.jar` | present, but `_remote.repositories` empty → installed locally, **provenance unprovable** |
| Local `corsac-diameter/` clone | `10.0.0-34-SNAPSHOT` on `master`/`main` — **no 41** |
| `github.com/mobius-software-ltd/corsac-diameter` | tag **`diameter-parent-10.0.0-41`** exists (commit `c8cd37b7`) |

The SNAPSHOT coordinates are the **`[maven-release-plugin] prepare for next development iteration`**
commit that follows the 40 release — i.e. the dev line *between* releases 40 and 41. On
`origin/main` there are commits whose `pom.xml` literally reads `10.0.0-41-SNAPSHOT`, dated
**2026-08-12**, consistent with the `~/.m2` jar build date of **2026-08-23**.

→ The builder checks out the pinned commit from `sources.lock` and installs it **as-is** (no version
rewrite). `-SNAPSHOT` is what that upstream commit genuinely is; we are not forging coordinates.
Verified equivalent path: tag `diameter-parent-10.0.0-41` + `mvn versions:set` would produce the
same code, but the pinned dev commit is preferred because it is byte-reproducible from a SHA.

`corsac-sip 10.1.0-31` **is** on Maven Central (HTTP 200) → nothing extra to build; D7(c) is moot.

### 1.2 Gaps found while fact-checking this plan (added to the file list in §4)

| Gap | Fix |
|-----|-----|
| `cyclonedx-maven-plugin` is **not** in `pom.xml` (`grep -c cyclonedx` = 0) | SBOM is generated by the builder's `cyclonedx-maven-plugin` CLI goal against each reactor, not by a pom change. Keeps the source tree untouched. |
| `out/dist` holds **290 jars** under `lib/` | `docker/.dockerignore` — without it the build context is huge and may ship `configs/` or stale `data/` into the image. |
| `AdminPlaneHandler` **writes** `ss7-persist`/stack JSON into `configs/` (`Files.writeString`, line 738) | The `configs` bind mount must be **rw**, and `install-config.sh` must not mark it read-only. Confirmed R7 concern. |
| No `verify-sources.sh` — `fetch-sources.sh` fetches but nothing *proves* the SHA | Added: fail-closed on SHA mismatch before any compile. This is what makes R3 real. |
| `sctp` branch conflict (local clone `master`, `dist-package-script.sh` says `java25-upgrade`) | `sources.lock` pins one SHA; `fetch-sources.sh` in `local` mode ignores branch names entirely and checks the SHA only. |
| `.m2` from the host could leak into the build (audit defeat) | `build-all.sh` uses `--strict-checksums` and asserts every in-house artifact was installed **during this run** (see `verify-sources.sh` pre/post manifest diff). |

---

## 2. Decisions to make BEFORE implementation

### D1 — What "no binaries" can mean in practice (most important)

Building **100%** from source is not feasible. Quarkus + netty + jackson + flyway + PG JDBC + … is **~300 third-party jars** from Maven Central, plus a base OS, a bootstrap JDK, and Maven itself. The design needs a clear **trust boundary** that the operator can audit.

| Option | Built from source | Accepted as pinned upstream binaries |
|--------|-------------------|--------------------------------------|
| **A (recommended)** | **All Digicom/our code**: sctp, jss7, jain-slee (micro-jainslee + all RAs), corsac-diameter, ussdgw; **PostgreSQL** and **nginx** from verified source tarballs | Base OS image (pinned by **digest**), bootstrap JDK 25, Maven, and Maven Central deps. All verified by checksum (`--strict-checksums`) and listed in a **CycloneDX SBOM** + `sources.lock` |
| B | A + OpenJDK 25 built from source (still needs a binary **boot JDK**, so the chain only moves one step) | Base OS, boot JDK, Maven Central deps |
| C | Everything, including Maven Central deps | — (months of work; not realistic for "go live soon") |

Option A also gives the operator:
- `sources.lock`: exact git commit SHA for every in-house repo, plus SHA-256 for every source tarball.
- `sbom/*.cdx.json`: every third-party jar with its version and hash.
- An **offline Maven mirror** (`/persist/m2`), fetched once. It can be audited and frozen, and later builds run with `mvn -o`, so there's no network during compile.

→ **Please confirm A** (or say if the customer wants B).

### D2 — Which JDK 25

The workspace standard is **zulu-25**; the customer said "openjdk-25". Options: Eclipse Temurin 25 (pinned digest), or Debian `openjdk-25-jdk` (if available for the pinned base). **Recommend Temurin 25** (OpenJDK build, TCK-certified, reproducible by digest). The runtime image uses a **`jlink` runtime** generated in the builder (smaller, includes `jdk.sctp`). → Confirm Temurin vs Zulu.

### D3 — PostgreSQL / nginx: official images or source-built?

"Download postgresql dockerfile": the official `docker-library/postgres` Dockerfile installs **apt binaries**, which conflicts with R1/R3. **Recommend:** our own `Dockerfile` that builds **PostgreSQL 16.x** and **nginx 1.2x** from `postgresql.org` / `nginx.org` source tarballs (SHA-256 / PGP verified). Both builds are small (<5 min). → Confirm, or accept the official images pinned by digest.

### D4 — Network mode for ussdgw: **host network** (recommended)

- Digicom SCTP binds `172.16.144.163:2011/2019`, which are real host IPs. M3UA peers whitelist those IPs.
- SCTP **multi-homing** puts IP addresses inside the INIT chunk. NAT (bridge network / swarm ingress mesh = IPVS) breaks multi-homing and gives the peer the wrong source IP.
- Swarm ingress has no SCTP-aware load-balancing semantics, and SS7 must not be load-balanced anyway.
- → ussdgw joins the **`host`** network (swarm: `networks: { hostnet: { external: true, name: host } }`), runs **replicas = 1**, and is **pinned to the SS7 node** with a node label.
- Consequence: a container on the host network **cannot resolve swarm service names**. PostgreSQL and nginx therefore also run on the host network on the same node, and everything talks over `127.0.0.1`. Simple and deterministic.
- Alternative: macvlan with a dedicated IP. More complex and needs network-team involvement. Not recommended for first go-live.

### D5 — "Hide 8080 as 80": the real port is 8088

nginx `:80` (and **`:443` TLS**, recommended) → `127.0.0.1:8088`. Also:
- Set `http.ra.host=127.0.0.1` so **8088 is not exposed directly** and everything goes through nginx. Exception: if the AS/BPLUS must call NI `/ussd` on 8088 directly, keep 0.0.0.0 and firewall it.
- → Confirm: AS/NI clients go through `:80`/`:443`? Is `/metrics` reachable only from the monitoring IP?

### D6 — Where the build gets its source

| Option | How |
|--------|-----|
| **6a (recommended for audit)** | Source **tarball / local dirs** given to the operator (`git archive` at SHAs in `sources.lock`). The builder mounts `/src` and needs **no GitHub access**. The operator audits exactly what gets compiled. |
| 6b | The builder `git clone`s `digicom-et/*` at pinned SHAs, with the token via `--secret id=gh_token` (private repos) |

Support both: `SOURCE_MODE=local|git`. → Confirm the default.

### D7 — `sip-servlets`

`digicom-et/sip-servlets` (restcomm 3.0.46) is **not a dependency** of ussdgw. SIP/USSI uses `corsac-sip` (Maven Central). Options: (a) skip it, (b) build it for audit completeness but don't consume it, (c) also build **`corsac-sip` from source**, so the SIP stack actually on the classpath is source-built.
→ **Recommend (c) + skip restcomm sip-servlets** (unless the customer explicitly wants it on the list).

### D8 — Public vs Digicom branch (dual push)

The `docker/` templates on public `main` = **lab only** (`ss7-lab.json`, no Digicom IPs/secrets). The Digicom swarm `.env`, `stack.digicom.yml`, and Digicom config dir live only on the **`digicom` branch → digicom-et**, via `./build/push-dual.sh`.

---

## 3. Target architecture

```
                    ┌────────────────────── SS7 node (swarm manager, label ussdgw=true) ─────────────────────┐
  Browser / AS ──►  │  nginx (host net) :80/:443 ──► 127.0.0.1:8088  ussdgw (host net, replicas=1)             │
                    │                                               ├─ vert.x HTTP RA :8088 (admin, /ussd, /metrics)
  STP / MSC / HLR ◄─┼──── SCTP/M3UA ───────────────────────────────►├─ SCTP 172.16.144.163:2011,2019 (NETTY_KERNEL)
                    │                                               ├─ gRPC :9099 · SMPP :2775/2776 · Diameter :3868 · SIP :5060
                    │  postgres (host net) 127.0.0.1:5432 ◄─────────┘
                    │                                                                                        │
                    │  Volumes (bind mounts, persistent):                                                    │
                    │   /srv/ussdgw/configs   (operator SoT, copied once from CONFIG_SRC)                    │
                    │   /srv/ussdgw/logs      (Log4j2 + CDR ledger ussd-cdr.log)                             │
                    │   /srv/ussdgw/data      · /srv/ussdgw/pgdata · /srv/ussdgw/nginx/certs                │
                    └────────────────────────────────────────────────────────────────────────────────────────┘
  Build host (can be the same machine):
   ussdgw-builder (docker run) ── mounts ──► /srv/ussdgw-build/{src,m2,out,sbom,logs}
          out/dist  ──(docker build context)──►  ussdgw-runtime image  (tag = ussdgw git SHA)
```

Host kernel prerequisites (container can't do these): `modprobe sctp` + `/etc/modules-load.d/sctp.conf`, sysctl buffers, NTP sync, firewall.

---

## 4. Files to add (proposed layout)

```
docker/
  README.md                    # operator runbook (build → audit → deploy → prove → rollback)
  sources.lock                 # repo | url | branch | commit SHA ; tarball | sha256
  build/
    Dockerfile                 # ussdgw-builder: pinned base + JDK25 + Maven + git + python3 + build deps
    build-all.sh               # ordered, idempotent build (see §5) → /out/dist + /out/sbom + /out/build-info.json
    fetch-sources.sh           # SOURCE_MODE=local|git, verifies SHAs from sources.lock
  ussdgw/
    Dockerfile                 # runtime: pinned slim base + jlink JRE25 (jdk.sctp) + libsctp1 + curl; non-root uid 10001
    entrypoint.sh              # preflight checks → wait PG → exec run.sh
  postgres/
    Dockerfile                 # PostgreSQL 16.x from source (sha256-verified)
    initdb/01-ussdgw.sh        # role/db ussdgw (password from /run/secrets)
    postgresql.conf.tmpl       # listen 127.0.0.1, tuned for ussdgw (JDBC pool 128)
  nginx/
    Dockerfile                 # nginx from source (sha256/PGP-verified)
    nginx.conf                 # :80/:443 → 127.0.0.1:8088, timeouts > bridge park, XFF, /metrics ACL
  stack.yml                    # docker swarm stack (lab defaults)
  .env.example                 # CONFIG_SRC, DATA_ROOT, image tags, heap, public base URL
  host-prep.sh                 # modprobe sctp, sysctl, dirs+ownership, firewall hints, docker version check
  install-config.sh            # copy CONFIG_SRC → /srv/ussdgw/configs (never overwrites; validates)
  prove.sh                     # AGENTS "prove the artifact" adapted to Docker (§8)
docs/agents/docker.md          # agent-facing footguns (linked from AGENTS.md topic index, 1 line)
```

Digicom-only (on the `digicom` branch only): `docker/stack.digicom.yml` overrides + `docker/env.digicom.example`.

---

## 5. Phase details

### Phase 0 — Lock the source chain (½ day)
- Record SHAs for: `digicom-et/sctp` (branch? the local clone is on `master` 2.27.32; the script says `java25-upgrade`, **must reconcile**), `digicom-et/jss7@j25`, `digicom-et/jain-slee@micro-jainslee-2`, `corsac-diameter` (version 10.0.0-41-SNAPSHOT source), optionally `corsac-sip@10.1.0-31`, `digicom-et/ussdgw-micro-jainslee`.
- Verify the jss7 `Ss7Config.As.routingContexts` gotcha is already in `digicom-et/jss7@j25` (otherwise the build fails on `ra-jss7`).
- Check whether the admin UI writes back to `configs/` (SS7 `stackJson` save, `ss7-persist`). If it does, the configs mount must be **rw** (expected).

### Phase 1 — Builder (`docker/build/`) (1–1.5 days)
- Image = toolchain only (no source baked in). Compilation happens in **`docker run`** with bind mounts, which matches R4 ("mount to an external dir for persistence"):
  ```
  docker run --rm \
    -v /srv/ussdgw-build/src:/src  -v /srv/ussdgw-build/m2:/m2 \
    -v /srv/ussdgw-build/out:/out  [--secret/ env file for git mode] \
    ussdgw-builder:<tag> build-all.sh
  ```
- `build-all.sh` order (fail-fast, logged to `/out/logs/`):
  1. `sctp` → `mvn install -DskipTests` (**exclude `sctp-native-fstack`/DPDK modules**; Docker uses `NETTY_KERNEL`)
  2. `jss7` → `mvn -N install` (parent), then full `install -Dmaven.test.skip=true`
  3. `corsac-diameter` (+ `corsac-sip` if D7c) → `install -DskipTests`
  4. `jain-slee` → `jainslee-pom` `-N install` → root `-N install` → full reactor `install`
  5. `ussdgw` → **force `db-kind=postgresql`** in a *copy* of `build/application.properties` (never mutate the source tree) → `./build/package-dist.sh` with `USSD_DIST_DIR=/out/dist` → assert `/out/dist/.baked-db-kind == postgresql`
  6. `cyclonedx-maven-plugin` → `/out/sbom/*.cdx.json`; write `/out/build-info.json` (all SHAs, JDK version, mvn version, timestamp)
- Maven: `-Dmaven.repo.local=/m2 --strict-checksums -B -ntp`. Optional `OFFLINE=1` → `-o` (run once online to fill `/m2`, then freeze).
- Optional `RUN_TESTS=1` → `mvn test` for ussdgw (2 known pre-existing failures: `GrpcClientSbbPullStateTest`, `Map2MapBridgeArmTest`. Report them, don't hide them).
- Non-root build user. The Maven repo and outputs are owned by the host uid passed via `--user`.

### Phase 2 — Runtime image `ussdgw` (1 day)
- Base: pinned slim Debian (digest) + `libsctp1` + `ca-certificates` + `curl` (healthcheck) + `tini` (PID 1, signal forwarding).
- JRE: the builder produces a `jlink` runtime with `java.base, java.sql, java.naming, java.management, jdk.sctp, jdk.unsupported, java.xml, jdk.crypto.ec, java.net.http, jdk.management, java.instrument, …` (exact list via `jdeps` on `lib/`; fall back to the full JDK if jdeps misses reflective modules).
- COPY from `out/dist`: `quarkus-run.jar`, `ussdgw-app.jar`, `lib/`, `quarkus/`, `app/html/`, `run.sh`, `.baked-db-kind`. **Never bake `configs/`**.
- `entrypoint.sh` preflight (fail fast with a clear message):
  - `/proc/net/sctp` exists → otherwise *"host kernel has no sctp: run docker/host-prep.sh"*
  - `.baked-db-kind == postgresql`, and the config's `db-kind`/JDBC URL is postgresql
  - every SS7 link `channel` is `sctp` (**SCTP-only mandate**; reject `tcp`)
  - read `QUARKUS_DATASOURCE_PASSWORD` from `/run/secrets/ussdgw_db_password` (not env in the stack file)
  - wait for PG `127.0.0.1:5432` (bounded retry; swarm has no `depends_on`)
  - `exec run.sh` (heap via `USSD_XMS/USSD_XMX`; `USSD_LOG_DIR=/opt/ussdgw/logs`)
- `HEALTHCHECK`: `curl -fsS http://127.0.0.1:8088/admin/status.json` (200 = Quarkus ready). `start_period` ≈ 120s.
- `STOPSIGNAL SIGTERM` + stack `stop_grace_period: 60s` so live MAP dialogs end/abort cleanly.
- Runs as uid 10001. `read_only` rootfs + writable mounts `configs/ logs/ data/` + tmpfs `/tmp`.

### Phase 3 — nginx (½ day)
- Source build. `listen 80` (+ `443 ssl` with certs from `/srv/ussdgw/nginx/certs`, optional HTTP→HTTPS redirect).
- `proxy_pass http://127.0.0.1:8088;` with `proxy_http_version 1.1`, `Host`/`X-Forwarded-*`.
- **`proxy_read_timeout` > the longest NI park** (AdaptiveTimeout / bridge hard-fail window, e.g. 120s). Otherwise nginx cuts NI `/ussd` with a 504 before the bridge settles.
- `proxy_buffering off` for HTMX partials / long-poll. `client_max_body_size` sized for the AS XML bodies.
- `/metrics` and optionally `/admin` limited by an `allow` IP list. `server_tokens off`.
- Set `ussd.admin.public-base-url` to the public URL (`http(s)://<host>`), never 0.0.0.0.

### Phase 4 — PostgreSQL (½ day)
- Source build of 16.x (same major as Digicom today). Data `/srv/ussdgw/pgdata` (bind mount, uid of `postgres`).
- `listen_addresses='127.0.0.1'` (host network). **Don't publish 5432 publicly.** If remote ops access is needed: mgmt IP only + `pg_hba` with scram + firewall. *(The request says "open the postgresql port". That's a security risk; please confirm the scope.)*
- `initdb` script creates db/role **`ussdgw`** (dedicated, never `ota`). Password from a swarm secret.
- Flyway V1–V13 runs on first ussdgw boot (unchanged).
- **Backup** service (optional): daily `pg_dump` → `/srv/ussdgw/backup`, with retention.

### Phase 5 — Config from the operator-specified dir (½ day)
- `.env`: `CONFIG_SRC=/path/given/by/operator` (contains `application.properties`, `ss7-*.json`, optionally `log4j2.xml`).
- `install-config.sh`: copy → `/srv/ussdgw/configs` **only if absent** (operator SoT, never overwrite; `--force` makes a timestamped backup first). Then validate:
  - JSON parses (`jq`), links `channel: sctp`, `ussd.map.config-file` points at an existing file
  - `db-kind=postgresql`, JDBC URL → `127.0.0.1:5432/ussdgw`
  - no default secrets unless lab (`ussd.lab.allow-default-secrets`)
  - the `services` SSN list includes 8/6/147 when live (MO SSN 147 lesson)
  - tenant `network_id` ↔ SCCP `networkId` reminder (warn only)
- Create `configs/ss7-persist/` writable.

### Phase 6 — Swarm stack (1 day)
- `docker swarm init` on the SS7 node; `docker node update --label-add ussdgw=true <node>`.
- `stack.yml`: services `postgres`, `ussdgw`, `nginx`. All on `hostnet` (external `host`), `placement.constraints: [node.labels.ussdgw == true]`.
- `ussdgw`: `replicas: 1`, **`update_config.order: stop-first`** (two instances must never bind the same SCTP endpoints / same PC), `restart_policy: on-failure, delay 10s, max_attempts` (avoid a silent crash loop), `rollback_config`, `stop_grace_period: 60s`, `ulimits.nofile: 1048576`, resource limit memory ≥ Xmx + ~1.5 GiB.
- `secrets`: `ussdgw_db_password`, `ussdgw_admin_key` (+ TLS key). `configs` stays a bind mount (rw, operator-editable, admin UI writes `ss7-persist`).
- Logging driver `json-file` with `max-size`/`max-file` for **stdout only**. The real logs and CDR are Log4j2 files under `/srv/ussdgw/logs` (Log4j2-only mandate unchanged).
- Requires **Docker Engine ≥ 24** (swarm `ulimits`, `cap_add`, etc.). Checked by `host-prep.sh`.

### Phase 7 — Extras for a "perfect" run (R9)
| Item | Why |
|------|-----|
| `host-prep.sh`: `modprobe sctp` + `/etc/modules-load.d/sctp.conf`; install `99-ussdgw-sctp-buffers.conf` | The container cannot load kernel modules; without it `jdk.sctp` fails "Protocol not supported" |
| NTP/chrony on the host, TZ in the container (`Africa/Addis_Ababa` or UTC, decided once) | MAP timers, CDR timestamps, Adaptive EWMA |
| Firewall (nftables) allow: SCTP to the STP peers only, 80/443, block 8088/5432/9099 from outside | SS7 must never be open to the world |
| **No `privileged`, no `cap_add` needed** (host net, ports > 1024 except nginx 80/443 → nginx keeps `CAP_NET_BIND_SERVICE` only) | Least privilege |
| Image tag = ussdgw git SHA + `build-info.json` inside the image (`/opt/ussdgw/BUILD-INFO.json`) | Traceability/audit; one-command rollback (`docker service rollback`) |
| `prove.sh` (§8) | AGENTS "prove the artifact" law applies to Docker too |
| Prometheus scrape `:8088/metrics` (through an nginx ACL or locally) | Ops visibility |
| pcap: use host `tcpdump -i any sctp` (host network makes this trivial) | SCTP debugging without extra containers |
| Optional `lab` profile: `as-node` + `ss7-simulator` stack for the acceptance test (never in prod) | Repeatable MO/NI smoke over real SCTP |
| HA (later, not go-live): second node active/standby, keepalived VIP + SCTP multi-homing | Single SS7 node is a SPOF; needs peer config changes |
| Resource hygiene: `docker/README.md` documents `docker stack rm` / builder `--rm` | Workspace rule |

---

## 6. Constraints carried over (must not break)

- Java 25 only; `maven.compiler.release=25`.
- Fast-jar layout exactly as `package-dist.sh` produces it (no uber-jar/WAR; jars never under `app/`).
- Build-time `db-kind=postgresql` for the shipped image. The local source tree stays H2 (the builder works on a copy).
- Never overwrite operator `configs/`; never mutate Digicom DB data.
- SCTP only for SS7 (validator rejects TCP). Log4j2 only.
- `ss7.live` from `/admin/status.json` is link truth. Docker healthcheck = *app ready*, **not** *SS7 up*.
- Commits as nhanth87 / Tran Nhan only; push via `./build/push-dual.sh`; Digicom overlay never on public `main`.

---

## 7. Acceptance (definition of done)

1. Clean host, only Docker + source tarball → `build-all.sh` produces `dist/` + SBOM + `build-info.json`, with **zero** prebuilt in-house jars consumed (no `~/.m2` from outside).
2. Second build with `OFFLINE=1` succeeds → no network needed for the compile.
3. `docker stack deploy` → all 3 services healthy; `:80` serves the admin login; `status.json` 200 through nginx.
4. `cat /proc/net/sctp/eps` on the host shows ussdgw endpoints; with the lab sim (or Digicom STP): `ss7.live=true`, M3UA ACTIVE.
5. MO `*101…` / `*804#` multimenu round trip, plus one NI Notify via `/ussd` through nginx → CDR ledger shows the 6-hop spine.
6. `docker service update --force ussdgw` → restart OK, CDR page still populated (`cdr.file.warmed`), PG data intact.
7. Kill the PG container → ussdgw degrades honestly and recovers. Reboot the host → everything comes back (sctp module + sysctl persisted).
8. `prove.sh`: running image digest == built tag; `jar tf` in the container shows the expected classes; `.baked-db-kind=postgresql`; live surface OK.

---

## 8. Open questions for you / the customer (summary)

1. **D1** Trust boundary: Option A OK? (Maven Central deps + base OS + boot JDK accepted as pinned/hashed + SBOM)
2. **D2** Temurin 25 or Zulu 25?
3. **D3** Build PostgreSQL + nginx from source, or official images pinned by digest?
4. **D4** Host network for ussdgw/PG/nginx on one SS7 node: OK?
5. **D5** Port 8080 in the request = the app port **8088**? Should 8088 be closed externally (only via nginx)? Is TLS 443 needed?
6. PostgreSQL "open port": public, or loopback/mgmt IP only?
7. **D6** Default source mode: tarball (`local`) or `git clone` from digicom-et?
8. **D7** sip-servlets: skip restcomm sip-servlets and build `corsac-sip` from source instead?
9. sctp branch: `master` (local digicom-et clone, 2.27.32) or `java25-upgrade` (in `dist-package-script.sh`)?
10. Target Docker Engine version and host OS on the customer side?

## 9. Rough effort

| Phase | Est. |
|-------|------|
| 0 source lock | 0.5 d |
| 1 builder + SBOM + offline | 1–1.5 d |
| 2 runtime image + entrypoint | 1 d |
| 3 nginx · 4 postgres · 5 config | 1.5 d |
| 6 swarm stack + host-prep | 1 d |
| 7 extras + prove.sh + docs | 1 d |
| Lab acceptance on test host (`100.110.205.176`, has SCTP) | 1 d |
| **Total** | **~7 days** |
