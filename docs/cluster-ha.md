# Active/active cluster + HTTP plane — USSDGW side (ADR 0007)

Status: **wired, OFF by default, lab-only**. No active/active claim is valid
without a recorded P6 run (see jain-slee `docs/lab/active-active-two-node.md`).

- SS7 cluster wiring: `ClusterBootstrap` + `Ss7ApplyService` + `Ss7PeerRouteService`
- Idempotent NI async contract: `POST {niPath}` + `X-USSD-Activity-Id` → `202 {flowId,statusUrl}` + `GET {niPath}/status/{flowId}` (`NiFlowRegistry`, `HttpServerSbb`)
- Lab templates: `build/ss7-lab-aa-node1.json`, `build/ss7-lab-aa-node2.json` (loopback only, no carrier data)

## 1. SS7 cluster wiring (WP2)

### 1.1 Enable

Boot-time only (restart to join/leave). Single-node behaviour is unchanged
while `ussd.cluster.enabled=false` (default).

```properties
ussd.cluster.enabled=true
ussd.cluster.node-id=ussdgw-1            # stable: systemd %i / hostname — the lease fence keys on it
ussd.cluster.stack=tcp
ussd.cluster.initial-hosts=ussdgw-1[7800],ussdgw-2[7800],witness-1[7800]
ussd.ss7.otid-range-start=1               # this node's TCAP OTID slice
ussd.ss7.otid-range-end=1000000000       # disjoint across nodes — overlap refuses start
# ussd.ss7.sctp-local-endpoints=10.0.0.11:8013,10.0.0.12:8013
# ussd.ss7.sctp-endpoint-index=0
```

What happens at boot (`ClusterBootstrap.startIfEnabled`, `@PostConstruct`):

1. `validateClusterConfig` — **refuses start** on a missing/invalid OTID range
   (`start > 0 && end > start`) or blank `initial-hosts`. Fewer than 3 members
   or a node-id missing from the list is a WARN (a 2-node view without the
   witness cannot take over under partition — both sides fence, ADR 0007 D12).
2. `new ClusterManager(cfg, nodeId)` + `start()` — the app-owned JGroups/Infinispan
   fabric (same stack as the GMLC profile store; Infinispan is pinned to
   `15.2.6.Final` via `infinispan-bom` imported **before** the Quarkus BOM —
   the platform BOM manages `org.infinispan:*` at 16.0.15 and a mixed
   core/commons set blocked `ClusterManager` wiring).
3. `Ss7ApplyService.wireSs7Ra` hands the manager to ra-jss7 via
   `setClusterManager` **before** activation, binds the cluster-wide
   `Ss7PeerRouteAffinity` (`Ss7PeerRouteService.bindCluster`), and:
   - flat-props path: applies `dialogIdRangeStart/End` (validated),
     `sctpLocalEndpoints[index]` (index validated), forces `SCTP`
     (M3UA-over-TCP is an RFC 4666 §3 violation);
   - JSON-file path: requires `tcap.dialogIdRangeStart/End` in the file —
     otherwise refuses start. Disjointness across nodes is enforced at join by
     `Ss7InboundDialogRouter` (overlap throws, `ra-jss7` fails activation).
4. Cross-node CONTINUE/END/ABORT forwarding (`Ss7InboundDialogRouter` +
   `ClusterUnicast`), lease fence + takeover (`RaDialogLeaseCaches`), preferred
   ASP pinning (`Ss7PeerRouteAffinity.pickAndPin`) run inside ra-jss7.

Disable path: `setClusterManager` is never called, affinity stays on the
deterministic local hash, `ClusterManager` is never built. `isM3uaRouteReady()`
 semantics are untouched.

### 1.2 Lab templates

`build/ss7-lab-aa-node1.json` / `build/ss7-lab-aa-node2.json`: one AS
(`AS1`, `loadshare`, same OPC 1 / RC 12), each node one SCTP server link on
loopback (`127.0.0.1:8013` / `:8023`), disjoint `tcap` OTID ranges
(`[1, 1e9]` / `[1e9+1, 2e9]`), `NETTY_KERNEL` backend, `channel: "sctp"`.
SCTP only — a `TCP` channel in any of these files is a reject.

Node boot (placeholders, same host lab):

```bash
# node-1: ussd.cluster.node-id=ussdgw-1, ussd.map.config-file=build/ss7-lab-aa-node1.json
# node-2: ussd.cluster.node-id=ussdgw-2, ussd.map.config-file=build/ss7-lab-aa-node2.json,
#         ussd.ss7.sctp-endpoint-index=1, ussd.ss7.otid-range-start=1000000001,
#         ussd.ss7.otid-range-end=2000000000
# witness-1 (third host/JVM): jain-slee ClusterWitness voting on ra-jss7
```

### 1.3 Observability

`/admin/status.json` (and `/admin/monitor-feed`) carry flat keys:

| Key | Meaning |
|-----|---------|
| `cluster.enabled` | cluster actually running (enabled **and** manager up) |
| `cluster.nodeId` | this node's id (never `local`) |
| `cluster.mode` | `REPL_SYNC` (clustered) / `LOCAL` |
| `cluster.otidRange` | this node's OTID slice |
| `cluster.initialHosts` | configured view (witness must be listed) |
| `ni.flow.size` | idempotent-flow index size (§2) |

## 2. Idempotent NI async contract (WP5)

Classic `POST /ussd` parks the caller's HTTP until MAP progress — that socket
dies with the node (ADR 0007 D10), so it cannot be the cross-node contract.
The opt-in async contract is:

```text
POST /ussd  (X-USSD-Activity-Id: <uuid or business key>)
  → 202 {"flowId": "<corr>", "statusUrl": "/ussd/status/<corr>"}

GET /ussd/status/<flowId>
  → 200 {"flowId": ..., "activityId": ..., "state": ..., "msisdn": ...,
         "nodeId": ..., "updatedAtMs": ...}
```

States: `PARKED` (park record live) → `COMPLETE` | `GATE_EXPIRED` | `ABORTED`;
`IN_PROGRESS` (session alive, park settled); unknown ids → `404`.

Rules:

- The activity id is the **cluster-wide idempotency key** (`NiFlowRegistry.claim`
  is atomic via ISPN `putIfAbsent` when clustered, map-guarded locally).
  A duplicate POST — on **any** node — replays the original `202` without a
  second MAP dialog. Without the header the classic sync-park is byte-for-byte
  unchanged (backward compatible).
- `flowId == correlationId == MAP dialogId`: one name end to end (ADR 0007 D1).
- A retry of a **completed** activity returns the same `202` (the client reads
  the terminal state via `GET status`). A retry that binds a *different*
  MSISDN to a live flow is `409` (identity SIẾT — same law as classic
  correlationIds).
- The 202 flow is parked with **no HTTP waiter** (fire-and-status): the MAP leg,
  the AdaptiveTimeout gate and the CDR funnels run exactly as in the classic
  path; the park completion funnels (`completeParked*`, gate expiry, abort)
  move the shared flow snapshot so `GET status` works from either node.
- Cross-node exactly-once is **at-least-once at the MAP layer**: a retry that
  lands on the survivor while the owner is still in the view is refused (never
  two concurrent BEGINs); once the owner has left the view the retry proceeds.
  The first BEGIN may already have reached the HLR — same guarantee as the
  TCAP lease path.

## 3. Per-node app state audit (WP5)

Cluster-wide truth lives in Infinispan (`ClusterManager` caches) or the shared
database. Everything below is **per-node (×N)** unless noted — divide
configured ceilings by node count, or accept N× headroom.

| Holder | What | Cluster story |
|--------|------|---------------|
| `TenantGuard.windows` | per-tenant + global 1s rate windows | **Per-node: effective ceiling is maxTps × N.** Set per-node `maxTps ≈ global/N`, or accept N× burst. No shared counter (hot path). |
| `AsPullClient.breakers` | per-AS-URL circuit + retry policy | **Per-node.** One node's OPEN does not shed on the other — under a sick AS, N× half-open probes. Acceptable (fail-closed per node); do not share without a lease. |
| `AsPullStateRegistry` | pull state (EWMA seed, retry, TTL 60s) | **Per-node.** Keyed by corr; the AS answer returns to the node that pulled. Cross-node AS callbacks resolve by corr on the pulling node only. |
| `HlrLocationCache` | SRI-SM MSC/IMSI/LMSI (TTL 5min, 50k cap) | **Per-node.** A miss re-queries SRI-SM (fail-closed). N× HLR load on cold start only. |
| `GatedSessionRegistry` | gated hints (TTL 3min, 10k cap) | **Per-node, best-effort.** Re-derivable; never authority. |
| `AdaptiveTimeout` EWMA | per-networkId latency + per-MSISDN profiles | **Per-node.** Gates converge independently; gate budget is the configured ceiling (not EWMA×1.5), so divergence is bounded. Do not average across nodes. |
| `ClassicNiHttpPark` | parked AS HTTP (byJsession/byCorr, TTL 10min) | **Per-node by design** — the socket lives on one heap. The async contract (§2) replaces cross-node waiting with `GET status`. |
| `VirtualSessionStore` claims | MS-digit claim, in-flight, CAS generations (`ussdTx` profile table) | **Per-node (JVM-local profile facility).** In-flight dedup does not span nodes; the TCAP lease fence (§1) is the cross-node guard. |
| `PendingSriRegistry` (30s) / `PendingHlrProxyRegistry` (15s) / `PendingMap2MapRegistry` | outbound correlation rendezvous | **Per-node.** Key strictly by corr, miss = fail-closed, never `takeAny`. A response that lands on the wrong node is dropped to timeout (documented D11 follow-up, same as MAP state). |
| `CdrFileLedger` ring | hot read model over `logs/ussd-cdr.log` | **Per-node files.** Aggregate by shipping files (or ClickHouse sink when enabled). Never merge by string concat — ledger lines are self-describing. |
| `CampaignScheduler` / `CampaignService` | campaign claim (`claim-limit`) | **Per-node counters over shared PG rows.** Keep `claim-limit ≤ target/N` or add `FOR UPDATE SKIP LOCKED` before raising N. |
| `EsmeSessionRegistry` (SMPP) | binds/sessions | **Per-node. Out of scope** (SMPP skipped this round). |
| `NiFlowRegistry` | §2 flow index | **Shared when clustered** (`ussd.http.ni-flows`, REPL_SYNC), **local map** otherwise. The one HTTP-plane index that must be global. |
| `Ss7PeerRouteAffinity` | N–N ASP round-robin + pins | **Shared when clustered** (cluster cache), deterministic local hash otherwise. Counters are cluster-wide — no /N needed. |

## 4. PostgreSQL HA (WP5)

The JDBC URL may list several hosts; `targetServerType=primary` keeps writes on
the primary after failover (pgJDBC; Agroal passes the URL through). No code
change — topology only:

```properties
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=ussdgw
quarkus.datasource.jdbc.url=jdbc:postgresql://pg1:5432,pg2:5432,pg3:5432/ussdgw?targetServerType=primary&connectTimeout=5&socketTimeout=30
# password via QUARKUS_DATASOURCE_PASSWORD (never in git)
```

Notes:

- `db-kind` is **build-time**: H2→PG needs a re-package (`./build/package-dist.sh`
  with `db-kind=postgresql`, then restore local `h2`). A PG URL on an H2 bake
  is the Digicom crash-loop (see root `AGENTS.md`).
- Flyway `migrate-at-start` runs on whichever node boots first; concurrent
  first-boots serialize on `flyway_schema_history` locks. Rolling restarts are
  safe; simultaneous cold boots of N nodes are not an HA pattern — start one,
  then the rest.
- CDR PG mirror stays opt-in (`ussd.cdr.db.enabled=false` default); the file
  ledger is the source of truth. Campaign claims and `ussd_short_code`
  routing read PG on every node — that path is already shared-safe.
- Dedicated DB **`ussdgw`** (never OTA's `ota`).

## 5. ClassicNiHttpPark long-poll — protocol change needed for clustering (WP5)

**No behaviour change in this round.** The current contract parks the AS's HTTP
request on the node that took the NI push and answers it when MAP progresses
or the AdaptiveTimeout gate fires. Under active/active that breaks two ways:

1. **Affinity:** a continue/END for the same `JSESSIONID` must reach the node
   holding the park. Today there is no affinity: HTTP LB → any node →
   `findByJsession` miss → `404 unknown JSESSIONID` (indistinguishable from a
   genuinely dead dialog, and the tenant-mismatch guard answers the same 404).
2. **Failover:** if the parking node dies, the parked HTTP dies with it; the
   survivor has the MAP dialog (lease takeover) but no handle on the AS's
   socket, and the flow state (`byCorr`/`byJsession`) is heap-local.

Required protocol change (design, not implemented):

- **Option A — sticky LB on `JSESSIONID`** (recommended for NI sync): the LB
  routes `Cookie: JSESSIONID=...` to the node that set it. No app change; the
  202/status contract (§2) covers the non-sticky case. Park records stay
  node-local; failover of a *parked* request is still impossible — the client
  re-reads via `GET status` and re-pushes with the same activity id.
- **Option B — shared park:** export `ParkRecord` (httpSessionId, jsession,
  corr, format, networkId, gate deadline) into the cluster cache and forward
  the MAP-progress event to the parking node via `ClusterUnicast` for the
  reply. Costs: the reply still needs the original socket (no better than A
  when the node is dead), plus cross-node chatter per NI message. Rejected for
  now — §2 gives the same client outcome with one index write per flow.

Until one of these ships: run NI-sync clients sticky, or use the §2 async
contract. The park code paths are untouched by the cluster work (same CAS,
same gate, same CDR).
