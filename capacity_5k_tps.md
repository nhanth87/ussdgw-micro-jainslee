# Capacity plan — Digicom-ET USSDGW @ 5 000 TPS

> Status: **DRAFT for review** (2026-10-02). Code-read analysis plus a sizing plan. **Nothing measured
> yet**; every number is an estimate to validate with the load test in §8.
> Companion docs: [`improve_adaptime_virtual_bridge.md`](improve_adaptime_virtual_bridge.md) (bridge
> correctness, IDs P0-x / P1-x / P2-x referenced below) · [`plan.md`](plan.md) (Docker build/run).
> Scripts in [`docker/capacity/`](docker/capacity/) reserve host resources for the ussdgw container.

**TPS definition** (`docs/agents/lessons.md`): **unique-MSISDN MO sessions started per second**,
not TCAP messages.

---

## 1. Target host and resource split

Production host: **128 GiB RAM, 32 logical CPUs**.


| Consumer                                           | CPUs (logical)                             | Memory                     | Notes                                                                       |
| -------------------------------------------------- | ------------------------------------------ | -------------------------- | --------------------------------------------------------------------------- |
| **ussdgw** container                               | **16** (cpuset, one NUMA node if possible) | **64 GiB** container limit | JVM heap 40 GiB + \~10 GiB off-heap + \~14 GiB headroom/page cache (see §5) |
| **PostgreSQL** container                           | 4                                          | 8 GiB limit                | Light load today (CDR DB mirror off). `docker/capacity/postgresql-5k.conf`  |
| OS, nginx, dockerd, IRQs, page cache for log files | rest                                       | \~32 GiB                   | CDR/log writes ≈ 20–50 MB/s need page cache                                 |


The CPU layout is computed by `docker/capacity/host-reserve.sh` from `lscpu`. CPUs are sorted by
NUMA node and core so hyper-thread siblings stay together:

- the first 16 go to ussdgw;
- the next 4 go to PostgreSQL;
- the last  go to the OS.

Swarm services cannot set `cpuset` directly, so `docker/capacity/pin-cpusets.sh` applies it with
`docker update` after each task start (§7).

---

## 2. Load model (Little's law) — what 5k TPS really means


| Traffic class                                            | Holding time / session | Concurrent sessions @5k TPS | MAP msgs/s | AS pulls/s    |
| -------------------------------------------------------- | ---------------------- | --------------------------- | ---------- | ------------- |
| One-shot (`*101…` balance)                               | 0.5–2 s (AS latency)   | 2.5k–10k                    | \~10–15k   | 5k            |
| Multimenu (`*804#`, 3–4 turns, \~10 s UE think per turn) | 30–40 s                | **150k–200k**               | \~30–40k   | 15–20k        |
| AS degraded (gate 25 s expiring)                         | 25 s                   | **125k parked**             | —          | 5k timing out |


Sizing rule used below: **≥ 250k concurrent dialogs/sessions** = 200k multimenu + 25 % headroom.

Per-session work (estimated from code; measure in §8):

- **SLEE events:** \~10–15. At 5k TPS that is **50–75k events/s**.
- **`ussdTx` puts:** \~6 full-row puts × 29 fields, so **\~1M profile field writes/s** today (C4 reduces this).
- **CDR lines:** \~8–12 (\~400 B each), so **\~20 MB/s CDR** (\~1.7 TB/day raw, \~150–250 GB/day gz).
- **SLEE trace:** \~20 lines per session at INFO, so **\~30 MB/s**. This **also goes to CONSOLE**
(`log4j2.xml` `SLEE_ASYNC` → `CONSOLE`), i.e. docker stdout.

---

## 3. Verdict today

The current tree **cannot sustain 5k TPS**:


| Workload  | Estimated capacity | Limited by                                                       |
| --------- | ------------------ | ---------------------------------------------------------------- |
| One-shot  | \~1–2k TPS         | Synchronous logging + full-row writes; `ussdUser` OOM over hours |
| Multimenu | \~1–1.5k TPS       | `tcap.maxDialogs=50000`, 2–4 GiB heap, TTL bugs                  |


The 64 GiB / 16 CPU budget is **enough hardware**, but only after the code changes in §4 and the
config changes in §6.

---

## 4. Code changes required (file → change)

Ordered by impact. **B** = blocker for 5k · **H** = high · **M** = medium. Each needs a unit test
plus the §8 load prove.

### C1 (B) — `ussdUser` grows without bound in heap


| File                                                                                                                | Change                                                                                                                                                                                                                                                                 |
| ------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `src/main/java/et/restlink/ussdgw/profile/UssdUserProfileStore.java`                                                | Add `lastSeenMs` to the row. Bound the table by TTL (`ussd.user.profile-ttl-ms`, default 3 600 000) **and** by count (`ussd.user.max-profiles`, default 2 000 000, oldest-`lastSeenMs` evicted). Replace `synchronized ensureTable()` (L76) with a volatile fast path. |
| `src/main/java/et/restlink/ussdgw/profile/UssdUserProfile.java` (+ mapper)                                          | New CMP field `lastSeenMs`                                                                                                                                                                                                                                             |
| `src/main/java/et/restlink/ussdgw/service/BridgeGateScheduler.java`                                                 | `reclaimExpiredTx` (30 s) also sweeps `ussdUser` (bounded batch per run, never a full scan under lock)                                                                                                                                                                 |
| `UssdUserProfileStore` callers (`VirtualSessionBridge.recordUserMenuState`, `MapUssdParentSbb.recordUserMenuDigit`) | The write only refreshes the fields that changed (single-field `updateField`), not a full-row republish                                                                                                                                                                |
| `docs/agents/cmp-inventory.md`                                                                                      | Document the bound. G1 (JVM-local) stays deferred.                                                                                                                                                                                                                     |


Why it's a blocker: one row per MSISDN, forever, in a JVM-local ProfileFacility. Millions of
subscribers will OOM any heap.

### C2 (B) — Logging is synchronous on the hot path, and the CDR ledger can silently drop lines


| File                                  | Change                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| ------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `src/main/resources/log4j2.xml`       | **(a)** Root: `<AsyncRoot level="INFO" includeLocation="false">` (mixed async loggers; LMAX `disruptor-4.0.0` is already in `lib/main`). **(b)** Remove `CONSOLE` from `SLEE_ASYNC` and from Root in prod. Console only for `USSD_FIRST_RUN` + WARN+. Otherwise docker `json-file` re-writes every SLEE line and dockerd burns CPU. **(c)** `CDR_ASYNC`: `blocking="true"`, `bufferSize="262144"`. The ledger is the source of truth; back-pressure beats loss. Count stalls (C9). **(d)** CDR retention: `SizeBasedTriggeringPolicy 1GB`, `DefaultRolloverStrategy fileIndex="nomax"`, delete by `IfAccumulatedFileSize` (e.g. 400 GB) + `IfLastModified` (e.g. 30d). Today `max=30` × `200MB` keeps only **\~5 min of CDR** at 5k TPS because `%i` rolls over within the day. **(e)** Make the file overridable: `-Dlog4j2.configurationFile=/opt/ussdgw/configs/log4j2.xml` when present. |
| `build/run-dist.sh` (→ `dist/run.sh`) | If `configs/log4j2.xml` exists, add `-Dlog4j2.configurationFile=…`. Add `USSD_JAVA_OPTS_PROFILE` hook (the env file in §6.4 sets the full option set).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| Per-session `LOG.info` → `debug`      | `bridge/VirtualSessionBridge.java` L208 ("Bridging slow AS"), L426 ("bridge ussdUser menu-write"), L284 ("Drop late…" keep INFO but rate-limit); `bridge/GatedSessionRegistry.java` `stamp` ("Gated session stamped"); `service/GatedAsNotifyService.java` ("queued"/"skipped"); `api/classic/ClassicNiHttpPark.java` L278; `sbbs/HttpClientSbb.java`, `service/AsPullRouter.java`, `profile/UssdUserProfileStore.java` (11 INFO sites). Rule: **per-session = DEBUG or CDR; per-minute/state change = INFO.**                                                                                                                                                                                                                                                                                                                                                                               |
| `SleeEventTrace` (40 call sites)      | Prod: logger `SLEE` at `WARN`, or a sampling switch `ussd.slee.trace.sample=1/100`. The CDR already carries the business tape. The AGENTS "IN SBB= / OUT SBB=" prove still works in lab at INFO.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `pom.xml`                             | **Log4j2-only mandate not enforced in this tree:** there is no `bannedDependencies` enforcer, and `dist/lib/main` ships **`log4j.log4j-1.2.8.jar` + `org.slf4j.slf4j-log4j12-1.7.2.jar`**. Find the source (`mvn dependency:tree -Dincludes=log4j:log4j,org.slf4j:slf4j-log4j12`), exclude both, add `log4j-slf4j2-impl` / `log4j-slf4j-impl`, and add the enforcer rule (recipe: workspace AGENTS § Logging).                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |


### C3 (H) — Global monitor on every store call


| File                                                                    | Change                                                                                                                                                                                                                                     |
| ----------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `src/main/java/et/restlink/ussdgw/bridge/VirtualSessionStore.java` L103 | `ensureTable()`: `if (tableReady) return;` **before** entering `synchronized` (double-checked on the existing `volatile tableReady`). It's called from `get/put/remove/find/claim`, 20+ times per session, i.e. &gt;100k monitor enters/s. |
| `UssdUserProfileStore.java` L76                                         | Same pattern.                                                                                                                                                                                                                              |


### C4 (H) — Full-row republish on every `put` (CPU + GC + CAS races)


| File                                                                                                                                           | Change                                                                                                                                                                                                                         |
| ---------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `src/main/java/et/restlink/ussdgw/profile/UssdTxProfileMapper.java`                                                                            | `write(p, session, expires)` writes **only fields whose value differs** from the current row (read-compare-write per field), or callers switch to field-level setters. This also fixes P1-6 (CAS law).                         |
| `VirtualSessionBridge`, `MapUssdParentSbb`, `HttpServerSbb`, `MapNiPushSbb`, `UssdSagaCoordinator`, `BridgeGateScheduler`, `ClassicNiHttpPark` | Replace get + `put` with single-field updates (`store.setState` via CAS, `setDialogAlive`, new `setMap2mapHopOutstanding`, `setGateMs`, `setInvokeId`). Same list as review P1-6.                                              |
| **upstream** `jain-slee/jainslee-core/.../InMemoryProfileFacility.java` `notifyFieldWrite`                                                     | Check `eventSinks.containsKey(table)` **before** allocating `ProfileUpdatedEvent`. Today every field write allocates one even with no sink (\~1M allocs/s). Needs a micro-jainslee release, then bump `microjainslee.version`. |


### C5 (H) — `AdaptiveTimeout` per-MSISDN O(n) trim


| File                                                                    | Change                                                                                                                                                                                                                                         |
| ----------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `src/main/java/et/restlink/ussdgw/bridge/AdaptiveTimeout.java` L361-381 | Remove the hot-path trim. Either (a) drop the per-MSISDN EWMA (it's telemetry-only, so the live gate never reads it), or (b) trim from the 30 s scheduler with a bounded batch. Recommend (a) plus keep the seed from `ussdUser` for CDR only. |


### C6 (H) — CDR ring = \~1 s of history at 5k TPS; rollup assumes adjacent events


| File                                                      | Change                                                                                                                                                                                                                                                                                                                                                                                                              |
| --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `src/main/java/et/restlink/ussdgw/cdr/CdrFileLedger.java` | **(a)** `sessions()` groups by correlation over the scanned window (map corr → events), instead of "complete when the corr changes". At 5k TPS events interleave, and today one session becomes many fragment rows. **(b)** Search by msisdn/corr falls back to a bounded reverse scan of the current + previous file (or a small corr→file-offset index) when not found in the ring. **(c)** Capacity from config. |
| `dist/configs/application.properties`                     | `ussd.cdr.recent-events=2000000` (\~1–1.5 GiB heap, \~40 s window) — see §6.1                                                                                                                                                                                                                                                                                                                                       |


### C7 (H) — Session lifetime / dialog bookkeeping (from bridge review)


| File                                                                     | Change                                                                                      |
| ------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------- |
| `bridge/VirtualSessionStore.java` `expiresAt`                            | Expiry by last activity, plus absolute cap `ussd.tx.max-session-ms` (600 000). Review P1-2. |
| `sbbs/MapUssdParentSbb.java` L663                                        | `no-session` on MS continue → `replyAndEnd`/`abort` (no silent leak of a TCAP dialog).      |
| `bridge/GatedSessionRegistry.java`, `api/classic/ClassicNiHttpPark.java` | Bounded + swept (review P1-9). At 5k TPS these leak \~GiB/hour when the AS is slow.         |
| `bridge/VirtualSessionBridge.java` `onGateExpired`                       | Remove the MO\_HOLD per-tick write storm (review P1-8).                                     |


### C8 (M) — AS HTTP client back-pressure


| File                                                                                                                        | Change                                                                                                                                                                                                                                 |
| --------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `src/main/java/et/restlink/ussdgw/config/UssdConfigService.java` + the client wiring (`HttpApplyService` / `HttpClientSbb`) | Add `ussd.http.client.max-wait-queue-size` (vert.x `WebClientOptions.setMaxWaitQueueSize`, default unbounded → OOM when the AS stalls). On a full queue, fail fast → `AS_TRANSPORT` saga compensate. Keep `request-timeout-ms` ≤ gate. |
| `jain-slee/vendor-ras/ra-http-client/.../HttpCallbackClientRa.java`                                                         | Expose `maxWaitQueueSize` / `http2` / `keepAliveTimeout` setters if missing.                                                                                                                                                           |


### C9 (M) — Observability needed to *prove* 5k

Add these to `/admin/status.json` (`admin/AdminHttpHandler` status builder):

- `ussdTx.size`, `ussdUser.size`, `bridge.armedGates`
- `ni.park.size`, `gated.size`
- `tcap.activeDialogs` (from ra-jss7)
- `cdr.file.dropped|stalls`, `log4j.ringRemaining`
- `http.client.inflight|waitQueue`
- event-router queue depth

Without these, a load run can't say *where* it saturates.

### C10 (M) — Load generator must out-run the gateway

`tools/ss7-simulator` `UssdLoadDriver` (JMX ceiling = 1 dialog per instance, see
`tools/ss7-simulator/SPIKE-JMX-CONCURRENCY.md`). 5k sessions/s needs **N simulator instances on a
separate host**, each with its own SCTP association/ASP, plus as-node `pull:fast` clustered (Node
cluster mode). Budget: a second machine with ≥ 16 CPUs.

---

## 5. ussdgw memory budget (container limit 64 GiB)


| Region                                                         | Size                                                                                 | Setting                                                  |
| -------------------------------------------------------------- | ------------------------------------------------------------------------------------ | -------------------------------------------------------- |
| Java heap (ZGC, generational by default on JDK 25)             | **40 GiB**, Xms = Xmx, pre-touched                                                   | `USSD_XMS=40g USSD_XMX=40g USSD_ALWAYS_PRETOUCH=1`       |
| — live set @250k sessions                                      | \~4–6 GiB (≈15–20 KB/session: TCAP dialog + `ussdTx` + SBB + index + HTTP in flight) | —                                                        |
| — `ussdUser` (after C1, ≤2M rows × \~1.5 KB)                   | \~3 GiB                                                                              | `ussd.user.max-profiles`                                 |
| — CDR ring (2M events)                                         | \~1–1.5 GiB                                                                          | `ussd.cdr.recent-events`                                 |
| — headroom for ZGC at \~1–2 GB/s allocation                    | rest (\~25 GiB)                                                                      | `-XX:SoftMaxHeapSize=34g`                                |
| Direct memory (Netty: vert.x 12k conns, SCTP, Log4j)           | ≤ 6 GiB                                                                              | `-XX:MaxDirectMemorySize=6g`                             |
| Metaspace + code cache                                         | \~1.5 GiB                                                                            | `-XX:MaxMetaspaceSize=1g -XX:ReservedCodeCacheSize=512m` |
| Platform thread stacks (vert.x loops/workers, jSS7, GC, Log4j) | \~1 GiB                                                                              | 512 workers + \~100 others                               |
| GC/NMT/JIT internal                                            | \~1–2 GiB                                                                            | —                                                        |
| Page cache (log/CDR writes inside the container cgroup)        | remaining \~12 GiB                                                                   | Counted by the cgroup, reclaimable                       |


Heap dumps: a 40 GiB `.hprof` written on OOM takes minutes and fills `logs/`. Point
`-XX:HeapDumpPath` at a dedicated volume `/opt/ussdgw/dumps` (≥ 60 GiB free), or disable it in prod
and rely on `-XX:+ExitOnOutOfMemoryError` + GC logs + NMT.

## 6. Configuration changes

### 6.1 `dist/configs/application.properties` (runtime; operator SoT on Digicom — change by ask)


| Key                                                             | Today                     | 5k target                                             | Why                                                                                          |
| --------------------------------------------------------------- | ------------------------- | ----------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| `http.ra.worker-pool-size`                                      | 512                       | **512**                                               | Runs `executeBlocking(fireEvent)`. Fine with VT SLEE; raise only if the C9 queue depth grows |
| `http.ra.event-loop-threads`                                    | 8                         | **8**                                                 | NI/admin ingress is not the hot path for MO                                                  |
| `ussd.http.client.max-pool-size`                                | 8192                      | **12288**                                             | Little: 5k pulls/s × p99 AS 2 s = 10k concurrent                                             |
| `ussd.http.client.request-timeout-ms`                           | 15000                     | **10000**                                             | Must stay &lt; gate (25 s); fail fast frees pool slots                                       |
| `ussd.http.client.max-wait-queue-size` (new, C8)                | —                         | **20000**                                             | Bound memory when the AS stalls                                                              |
| `ussd.cdr.recent-events`                                        | 50000                     | **2000000**                                           | \~40 s window (C6)                                                                           |
| `ussd.cdr.db.enabled`                                           | false                     | **false**                                             | Keep the PG mirror off at 5k (file = SoT)                                                    |
| `quarkus.datasource.jdbc.max-size` / `min-size`                 | 128 / 16                  | **48 / 8**                                            | No CDR DB writes; routing/tenant/user reads only. Frees PG backends                          |
| `ussd.tx.profile-ttl-ms`                                        | 120000                    | **120000** + new `ussd.tx.max-session-ms=600000` (C7) | Activity-based expiry                                                                        |
| `ussd.user.profile-ttl-ms` / `ussd.user.max-profiles` (new, C1) | —                         | **3600000 / 2000000**                                 | Bound `ussdUser`                                                                             |
| `ussd.bridge.gate-tick-ms`                                      | 100                       | **100**                                               | O(due); fine                                                                                 |
| `ussd.bridge.async-gate-timeout-ms`                             | 25000                     | **25000**                                             | Unchanged (law)                                                                              |
| `microjainslee.container.offheap-storage-dir`                   | unset → `$java.io.tmpdir` | **`data/offheap`**                                    | No `/tmp` (read-only rootfs in Docker); unused today (no `@OffHeap`) but keep it safe        |


⚠ Some keys are also stored in the **RuntimeConfigStore** (admin UI → DB). A DB value **overrides**
the properties file and env (`ussd.http.client.*`, `http.ra.event-loop-threads`,
`ussd.bridge.*`, … see `config/RuntimeConfigStore.Keys`). After deploy, check `/admin/http` and
`/admin/bridge` show the 5k values, or update them there (operator, by ask).

### 6.2 `build/application.properties` (BUILD\_TIME — requires `./build/package-dist.sh`)


| Key                                              | Today        | 5k target                      | Why                                                                                                                                                                                        |
| ------------------------------------------------ | ------------ | ------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `microjainslee.container.buffer-size`            | 16384        | **65536**                      | Disruptor ring ≈ 1 s of 50–75k events/s                                                                                                                                                    |
| `microjainslee.container.sbb-pool-max`           | 40960        | **262144**                     | ≥ concurrent sessions (§2). **Verify semantics in micro-jainslee docs** (pool of SBB entities vs in-flight deliveries) before baking; if it caps in-flight deliveries only, 65536 suffices |
| `microjainslee.container.sbb-pool-min`           | 128          | **1024**                       | Avoid cold-start churn under ramp                                                                                                                                                          |
| `microjainslee.container.prefer-virtual-threads` | true         | **true**                       | —                                                                                                                                                                                          |
| `quarkus.datasource.db-kind`                     | h2 (tracked) | **postgresql at package time** | Digicom bake law (`.baked-db-kind`)                                                                                                                                                        |


### 6.3 SS7 config JSON (operator file, e.g. `configs/ss7-digicom-balance.json`; never on public `main`)


| Key                         | Today                                  | 5k target                                                   | Why                                                                                                           |
| --------------------------- | -------------------------------------- | ----------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| `tcap.maxDialogs`           | 50000 (lab file: unset → jSS7 default) | **262144**                                                  | §2: 200k multimenu + headroom                                                                                 |
| `tcap.dialogIdleTimeout`    | 60000                                  | **60000**                                                   | Keep. TTL fixes (C7) handle UE think time                                                                     |
| `tcap.invokeTimeout`        | 30000                                  | **30000**                                                   | —                                                                                                             |
| `sctp.workerThreads`        | 8                                      | **8**                                                       | One association ≈ one event loop; more threads don't help 2 links                                             |
| SCTP links / M3UA AS        | 2 links (2011/2019), 1 AS              | **4 links** (negotiate with the STP), loadshare, SLS spread | \~30–40k MAP msg/s; a single association event loop is the likely SS7-side cap. **Peer change — ask Digicom** |
| `ss7-lab.json` (public lab) | no `tcap` block                        | add `"tcap": {"maxDialogs": 262144, …}`                     | So the lab load test is not capped by the jSS7 default                                                        |


### 6.4 JVM options (container env → `run.sh` `JAVA_OPTS`)

All in [`docker/capacity/ussdgw-5k.env`](docker/capacity/ussdgw-5k.env). Summary:

```
USSD_XMS=40g USSD_XMX=40g USSD_ALWAYS_PRETOUCH=1
-XX:+UseZGC -XX:SoftMaxHeapSize=34g -XX:ConcGCThreads=4 -XX:+UseTransparentHugePages
-XX:ActiveProcessorCount=16 -Djdk.virtualThreadScheduler.parallelism=16
-Djdk.virtualThreadScheduler.maxPoolSize=256
-XX:MaxDirectMemorySize=6g -XX:MaxMetaspaceSize=1g -XX:ReservedCodeCacheSize=512m
-XX:NativeMemoryTracking=summary
-Xlog:gc*,safepoint:file=/opt/ussdgw/logs/gc.log:time,uptime,level,tags:filecount=10,filesize=100m
-XX:HeapDumpPath=/opt/ussdgw/dumps
-Dlog4j2.asyncLoggerRingBufferSize=262144 -Dlog4j2.asyncQueueFullPolicy=Default
-Dmicrojainslee.container.offheap-storage-dir=/opt/ussdgw/data/offheap
```

`run.sh` already adds `-XX:+UseZGC -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError`.
Flags passed later via `JAVA_OPTS` win for `-XX` duplicates (HeapDumpPath).

### 6.5 Host OS (applied by `docker/capacity/host-reserve.sh --apply`)


| Setting                                     | Value                                                | Why                            |
| ------------------------------------------- | ---------------------------------------------------- | ------------------------------ |
| `vm.max_map_count`                          | 1048576                                              | ZGC on a 40 GiB heap           |
| `vm.swappiness`                             | 1                                                    | Never swap the heap            |
| THP                                         | `madvise` (enabled + defrag)                         | `-XX:+UseTransparentHugePages` |
| `net.core.somaxconn` / `netdev_max_backlog` | 65535 / 250000                                       | Ingress bursts                 |
| `net.ipv4.ip_local_port_range`              | `10240 65000`                                        | 12k outbound AS connections    |
| `net.ipv4.tcp_tw_reuse`                     | 1                                                    | Outbound connection churn      |
| `fs.file-max` / `fs.nr_open`                | 4194304                                              | \~30k sockets + files          |
| SCTP buffers                                | existing `build/systemd/99-ussdgw-sctp-buffers.conf` | Already in the tree            |
| `sctp` module                               | `modprobe sctp` + `/etc/modules-load.d/sctp.conf`    | Container can't load it        |
| irqbalance                                  | ban ussdgw + PG cpus (`IRQBALANCE_BANNED_CPULIST`)   | Keep NIC IRQs on OS cores      |
| Container ulimits                           | `nofile=1048576`                                     | Swarm ≥ Docker 23              |


### 6.6 PostgreSQL (`docker/capacity/postgresql-5k.conf`)

8 GiB / 4 CPU. Settings:

- `shared_buffers=2GB`, `effective_cache_size=4GB`, `work_mem=32MB`, `maintenance_work_mem=2GB`
- `max_connections=200`
- `max_worker_processes=4`, `max_parallel_workers=8`
- WAL: `wal_compression=on`, `max_wal_size=4GB`, `checkpoint_completion_target=0.9`
- `listen_addresses=127.0.0.1` (host network, per plan.md)

## 7. Reserving resources for the ussdgw Docker container

Scripts in `docker/capacity/` (new dir; the Docker stack itself is being built by the other agent
per `plan.md`, so these are an **overlay** the stack can include):


| File                    | Role                                                                                                                                                                                                                                                                                 |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `host-reserve.sh`       | Checks the host (32 CPU / 128 GiB / Docker ≥ 24 / cgroup v2), computes the CPU split, applies sysctl/THP/sctp/irqbalance/dirs, and writes `/etc/ussdgw/capacity.env` with `USSDGW_CPUSET`, `PG_CPUSET`, `OS_CPUSET`, and mems. **Dry-run by default**; `--apply` to change the host. |
| `ussdgw-5k.env`         | Container env: heap, JVM flags, runtime property overrides via env (Quarkus env-name mapping)                                                                                                                                                                                        |
| `stack.capacity-5k.yml` | Swarm override: `resources.limits/reservations` (ussdgw 16 CPU / 64 GiB; PG 12 CPU / 32 GiB), `ulimits`, `stop_grace_period`, `env_file`                                                                                                                                             |
| `pin-cpusets.sh`        | Swarm lacks `cpuset`: pins running task containers with `docker update --cpuset-cpus/--cpuset-mems`. `--watch` follows `docker events` so restarts are re-pinned (run as a systemd service)                                                                                          |
| `postgresql-5k.conf`    | PG tuning for its 8 GiB / 4 CPU share                                                                                                                                                                                                                                                |


Deploy (once the plan.md stack exists):

```bash
sudo docker/capacity/host-reserve.sh            # dry-run: prints the layout and actions
sudo docker/capacity/host-reserve.sh --apply    # apply + write /etc/ussdgw/capacity.env
docker stack deploy -c docker/stack.yml -c docker/capacity/stack.capacity-5k.yml ussdgw
# --apply installs + enables ussdgw-pin-cpusets.service (/usr/local/sbin/ussdgw-pin-cpusets --watch)
systemctl status ussdgw-pin-cpusets.service
docker inspect --format '{{.HostConfig.CpusetCpus}}' $(docker ps -q -f label=com.docker.swarm.service.name=ussdgw_ussdgw)
```

## 8. Prove plan (no 5k claim without this)

1. **Rig:** prod-like host (this 128 GiB / 32 CPU box) + a separate load host (≥ 16 CPU) running N ×
 ss7-simulator `UssdLoadDriver` + clustered as-node `pull:fast` (AS latency profile 200 ms p50 /
 1 s p99) + a multimenu script (3 turns, 10 s think).
2. **Ramp:** 500 → 1k → 2k → 3k → 4k → 5k TPS, 10 min each step, then **5k for 30 min**. Run
 one-shot and multimenu separately, then a 70/30 mix.
3. **Pass criteria at 5k for 30 min:**
   - session success ≥ 99.9 %
   - MO round trip p99 ≤ AS p99 + 300 ms
   - ZGC max pause &lt; 1 ms; heap after GC stable (no upward trend)
   - `cdr.file.dropped=0` and 0 CDR stalls &gt; 100 ms
   - `tcap.activeDialogs` &lt; 80 % of `maxDialogs`
   - container CPU &lt; 75 % of 16
   - `ussdTx.size` / `ussdUser.size` / `ni.park.size` / `gated.size` flat
   - no `AS_DROP genMismatch`
4. **Evidence:** JFR recording (`jcmd <pid> JFR.start duration=10m`) at 5k, NMT summary, GC log,
 `/proc/net/sctp/assocs`, status.json series, CDR sample (one `MS_DIGIT` per digit, `CONTINUE gen=`).
5. **Failure drills:** AS stalls 60 s (pool/wait queue bounded, gate → BRIDGED, recovery), one SCTP
 link down, PG restart, container restart (CDR warm, no Digicom configs touched).
6. Prove-the-artifact law: image tag = git SHA; `jar tf` new classes inside the running container.

## 9. Work order and estimate


| Step      | Items                                                                            | Est.                                       |
| --------- | -------------------------------------------------------------------------------- | ------------------------------------------ |
| 1         | C2 logging + C3 + C5 (cheap, biggest CPU win) + C9 metrics                       | 2 d                                        |
| 2         | C1 `ussdUser` bound + C7 TTL/leaks (with bridge review P1-2/8/9)                 | 2–3 d                                      |
| 3         | C4 field-level writes (+ bridge review P1-6) + upstream micro-jainslee alloc fix | 3 d                                        |
| 4         | C6 CDR ledger windowed rollup + file fallback                                    | 2 d                                        |
| 5         | C8 HTTP client back-pressure; §6 configs; repackage (BUILD\_TIME)                | 1 d                                        |
| 6         | C10 load rig + §8 ramp, profile, iterate                                         | 3–5 d                                      |
| **Total** |                                                                                  | **\~2.5–3 weeks** to a *measured* 5k claim |


## 10. Decisions needed


| ID  | Question                                                                                         | Default                         |
| --- | ------------------------------------------------------------------------------------------------ | ------------------------------- |
| K1  | Confirm the 64 GiB / 16 CPU ussdgw share and 32 GiB / 12 CPU PG share                            | As in §1                        |
| K2  | SLEE trace in prod: WARN only, or 1/100 sampling?                                                | WARN (CDR carries the tape)     |
| K3  | CDR retention on disk (raw \~1.7 TB/day; gz \~150–250 GB/day) — local days kept + offload target | 14 days local + nightly offload |
| K4  | Per-MSISDN EWMA: drop it (telemetry-only)?                                                       | Drop                            |
| K5  | Ask Digicom/STP for 4 SCTP associations instead of 2?                                            | Yes, before the 5k test         |
| K6  | Traffic mix to certify (one-shot vs multimenu %)                                                 | 70/30                           |


