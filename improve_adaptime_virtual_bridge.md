# Review — AdaptiveTimeout + Virtual Session Bridge (implementation vs docs)

> Status: **DRAFT for review** (2026-10-02). Code-read review only. **No code changed, no tests run.**
> Each finding has a file:line, a concrete failure scenario, and a proposed fix. Before fixing a
> finding, add a red unit test that reproduces it.

Paths below are relative to `src/main/java/et/restlink/ussdgw/`.

## 0. Sources read

| Kind | What |
|------|------|
| Docs (laws) | `AGENTS.md` (bridge idempotency, gate tick, multimenu, NI one-shot vs menu, AdaptiveTimeout on top of MAP NI) · `docs/agents/skills.md` (AdaptiveTimeout, bridge CAS, gate tick, CAS ≠ rewrite row) · `docs/as-contract/ussd-3gpp-notes.md` §6–8 (layering, Q6 park vs invoke timer, P0/P1 plan) |
| Greenfield code | `bridge/AdaptiveTimeout` · `VirtualSessionBridge` · `VirtualSessionStore` · `VirtualSessionState` · `GatedSessionRegistry` · `UssdSagaCoordinator` · `service/BridgeGateScheduler` · `GatedAsNotifyService` · `api/classic/ClassicNiHttpPark` · `sbbs/HttpServerSbb` (NI) · `sbbs/MapUssdParentSbb` (MS continue / Notify / hop) · `sbbs/MapNiPushSbb` · `access/AccessNiDispatcher` |
| Classic oracle | `ussdgateway/core/session-bridge`: `AdaptiveTimeout`, `BridgeReconciler`, `FsmState`, `PushRetryQueue`, `SessionPriorityResolver`, `NotificationFallback` |

## 1. Verdict

The **core MO pull bridge is correct and matches the laws**:
- The CAS claim is in place.
- The gate tick is safe.
- The gate budget is the configured ceiling.
- MS digit dedupe and generation stamping work.

The gaps are concentrated in four areas:
1. **HTTP-NI interactive menus (push Request + digits).** One P0 bug breaks them from the 2nd digit. The P1 "park vs invoke timer" gap from `ussd-3gpp-notes.md` is still open.
2. **Late reconcile (S1 bridged → S2 NI push).** It ignores the AS action. It is also missing three things classic has: the handset-busy check, retry, and fallback.
3. **The "CAS ≠ then rewrite the row" law is violated in ~8 places.** These are get + full `put()` calls that can revert a CAS.
4. **Unbounded in-memory registries.** Two leaks, plus one CDR write storm.

### Already correct (keep as-is)

| Law / behavior | Evidence |
|----------------|----------|
| Live gate = configured ceiling (`async-gate-timeout-ms` 25s, or dialog timeout if not below it). EWMA is telemetry only. | `AdaptiveTimeout.effectiveGateMs` L187–194 |
| EWMA hardening beyond classic: outlier clamp, decay toward config, stale reset, `nanoTime` | `AdaptiveTimeout.applySample` / `decayedLatencyMs` |
| Exactly-once AS claim `AWAITING_AS\|S1_RELEASED → RESPONDING`, with a re-try from `S1_RELEASED` when the gate wins | `VirtualSessionStore.claimForAsResponse` L357–379 |
| Gate expiry is a CAS, returns `false` on loss, single-field `dialogAlive` write after the CAS | `VirtualSessionBridge.onGateExpired` L174–204 |
| Gate tick: `SKIP` + per-session `catch (Throwable)` + O(due) deadline index, re-validated against the Profile | `BridgeGateScheduler.tickGates` · `VirtualSessionStore.awaitingPastDeadline` |
| Generation bumps only on MS digit; AS CONTINUE does not bump; stamp on HTTP/gRPC/SIP pull | `VirtualSessionBridge` L348 · `HttpClientSbb` L249 · `GrpcClientSbb` L151 · `SipUssiSbb` L218 |
| Dual jSS7 Response dedupe (process-wide claim, survives rehydrate) | `VirtualSessionStore.tryClaimMsDigitContinue` · `MapUssdParentSbb.onUserContinue` |
| MAP2MAP: AS END/ABORT is held while the hop is outstanding | `VirtualSessionBridge.applyToLiveDialog` L334–343 |
| ASYNC_ACK never feeds EWMA or emits | `VirtualSessionBridge.onAsResponse` L108–115 |
| Notify RESULT settles the NI park; same-dialog continue; END/abort release | `MapUssdParentSbb.onNotifyResponse` · `HttpServerSbb.handleNiContinue` |

---

## 2. Findings

Severity: **P0** = user-visible break on a documented flow · **P1** = race / leak / spec gap that will
hit at Digicom load or in edge timing · **P2** = hardening / correctness at the margins / ops clarity.

### P0-1 — HTTP-NI interactive menu dies at the 2nd UE digit

**Where:** `sbbs/HttpServerSbb.java:293-299` · `sbbs/MapUssdParentSbb.java:701-713` · `bridge/VirtualSessionStore.java:496`

**Mechanism:**
1. AS `POST /ussd` with a Request menu creates a session at **gen=1**, and MAP sends Request to the UE.
2. UE digit `1` arrives in `onUserContinue` (HTTP-NI branch). `tryClaimMsDigitContinue` wins and marks the claim **in-flight**. `nextGeneration()` takes the session to **gen=2**. `completeParked` sends the digit to the AS.
3. The AS posts the next menu with Cookie, into `handleNiContinue`. That method sets `AWAITING_AS` with a full `put()`, then calls `onAsResponse(new AsResponse(corr, corr, **1**, …))`. **gen is hardcoded to 1** and there is no `stampedToSessionGeneration`. As a result:
   - `acceptAsResponse` sees `1 != 2` and the response is dropped: `dropLate`, CDR **`AS_DROP reason=genMismatch`**.
   - `releaseMsDigitInFlight` is **never called**. It only runs in `applyToLiveDialog`.
   - The row stays in `AWAITING_AS`.
4. The MAP continue for menu 2 still goes out (`routeNiContinue`), so the UE sees menu 2.
5. UE digit `2` arrives. `tryClaimMsDigitContinue` returns **IN_FLIGHT** (and the `AWAITING_AS` belt check would also trigger), giving `dup-skip-continue`. **The digit is dropped.** The AS park waits 25s and gets `GATE_EXPIRED`. The UE hangs until the MAP timeout.

**Why tests miss it:** `HttpServerSbbNiContinueTest` stubs `bridge.onAsResponse`. Digicom has only proven the **Notify** path (doc P0 "Interactive Request prove" is still open).

**Fix:**
- In `handleNiContinue`, replace the fake `AsResponse` shim with a dedicated `bridge.onNiAsContinue(corr)`. It would:
  - CAS `ACTIVE → RESPONDING`, so a duplicate AS POST loses;
  - call `releaseMsDigitInFlight`;
  - single-field `state → ACTIVE`.
- At minimum: stamp the session generation, and remove the get + `put(AWAITING_AS)` shim.

**Tests:**
- Red test first: `HttpNiTwoDigitMenuTest`. Wire the real bridge and store, run POST → digit → POST(cookie) → digit, and assert the 2nd digit reaches `completeParked` with no `AS_DROP`.
- Lab prove: ss7-simulator Request menu with 3 digits on one TCAP dialog (pcap).

### P1-1 — NI park gate (25s) fires during UE think-time (open doc gap Q6 / P1)

**Where:** `api/classic/ClassicNiHttpPark.java:165-190, 263-307` · `HttpServerSbb.java:251, 319`

Every NI park uses `effectiveGateMs`, which is 25s, even when the outstanding MAP op is a **Request** waiting for a human. On expiry:
- the AS gets a gated abort;
- `httpSessionId` is set to `null`;
- the **MAP dialog stays open**;
- the late UE digit hits `completeParked` and returns `false` ("http-ni-no-park"), so the digit is lost;
- the digit claim stays in-flight;
- the dialog lingers until the MAP invoke timeout (a dialog leak in practice).

**Fix:**
- Budget per outstanding op:
  - **Notify** (and the first SRI+Begin hop): keep the ceiling.
  - **Request awaiting UE input**: use a new `ussd.ni.request-ui-timeout-ms`, aligned with the MAP Request invoke timer (TS 29.002 `ml`, classic `customInvokeTimeout`).
- Re-arm on every AS hop. (The doc already demands this, and it isn't implemented.)
- On any NI park expiry with a live MAP dialog: `MapDialogHelper.abort`/`niClose`, `clearMsDigitClaim`, then a terminal state for the session.

### P1-2 — ussdTx TTL can reclaim a live MO menu; the orphaned digit leaves the MAP dialog hanging

**Where:** `bridge/VirtualSessionStore.java:657-662` (`expiresAt`) · `sbbs/MapUssdParentSbb.java:663`

`expiresAt = max(created + max(120s, dialogTimeout), lastGateDeadline + 30s)`. After the AS CONTINUE, the row expires about **55s after the last digit** (once past 120s total). A user who reads a long Amharic menu for more than about 30–50s on turn 4+ hits this sequence: `reclaimExpired` removes the row, the next digit sees `onUserContinue → "no-session"`, and **nothing is sent on MAP**. The handset waits, then shows a network error. The dialog leaks until the MAP timeout.

**Fix:**
- While `ACTIVE` (waiting for the UE), extend expiry by the MAP UE timeout. Keep an absolute cap such as `created + ussd.tx.max-session-ms` (e.g. 10 min), which preserves the existing "no slide-forever leak" intent.
- `no-session` on a MAP continue must `replyAndEnd(hard-fail)` or `abort`, never stay silent.

### P1-3 — Late reconcile (S1 bridged → S2 NI) ignores the AS action

**Where:** `bridge/VirtualSessionBridge.java:131-145` → `access/MapUssdAccessAdapter.requestNiPush` (always `notifyOnly=false`) → `sbbs/MapNiPushSbb.java:111` `keepOrCompleteSession` (COMPLETED + remove)

| AS action on a bridged session | Today | Should be (3GPP 22.090 / AGENTS "NI one-shot vs menu") |
|---|---|---|
| END (final text) | MAP **Request** (handset opens an input box); the session is removed, so the UE reply is orphaned (P1-2 path) | **Notify**, then release |
| ABORT | NI push of the abort text (or null) | **No push**; terminal `ABORTED` |
| CONTINUE (menu) | Request, but the session is removed, so the next digit has no session | Decision **D2**: (a) Request on S2 and keep the session `ACTIVE`, bound to the new S2 dialog; or (b) Notify the text and end |

### P1-4 — No handset-busy check before a late NI push (classic `shouldDeliverNow`)

**Where:** `VirtualSessionBridge.java:140`. There is no lookup of another live row for the same MSISDN. The `msisdn` index already exists: `VirtualSessionStore.ensureTable`.

Realistic scenario: the user sees "Please wait...", redials `*804#` at once, and the late S2 push collides with the new MO dialog. The MSC/handset answers with `ussd-Busy` (MAP error 72), or the network aborts, and the result is lost. Classic deferred the push behind an active MO session (`SessionPriority`; payment/OTP could preempt).

**Fix:** before the push, `findActiveByMsisdn(msisdn)` excluding self. If busy, keep `PUSH_PENDING` and retry (P1-5).

### P1-5 — No NI push retry / fallback (classic `PushRetryQueue` 3s/8s/15s + `NotificationFallback`)

**Where:** `UssdSagaCoordinator.onNiFailed` → `compensate` → `FAILED`. Also, `MapNiPushSbb` marks the session COMPLETED right after sending, before the MAP result arrives, so a later returnError finds no session.

**Fix:**
- Keep the row in `PUSH_PENDING` until the MAP result arrives.
- Make retryable errors bounded-retryable: `ussd-Busy`, `systemFailure`, timeout. **Do not** retry `absentSubscriber` or `unknownSubscriber`.
- Add a fallback SPI. The default only logs and writes CDR; optionally SMS via the in-tree SMPP RA (decision **D4**).
- Retry scheduling goes through `BridgeGateScheduler` (deadline index), never `Thread.sleep`.

### P1-6 — "CAS ≠ then rewrite the row" law violated (get + full `put()`)

`UssdTxProfileMapper.write` republishes **every** field from a detached snapshot. Any of the writes below can revert a concurrent CAS (state) or a single-field write (`dialogAlive`):

| # | Where | Concrete race |
|---|-------|---------------|
| a | `MapUssdParentSbb.clearMap2mapHopOutstanding` L325-337 | The hop response lands at the gate deadline. The gate CASes `AWAITING_AS→S1_RELEASED` and does `replyAndEnd`, then this `put` restores `AWAITING_AS` + `dialogAlive=true`. The gate fires **again**: a second `replyAndEnd` on an ended dialog, a second `BRIDGED` CDR, and a second gated AS push. |
| b | `VirtualSessionBridge.onNetworkAbort` L247-266 | An abort during `RESPONDING` writes ZOMBIE; then `applyToLiveDialog.persist` overwrites it with ACTIVE/COMPLETED (or the reverse). |
| c | `VirtualSessionBridge.applyToLiveDialog` / `onAsResponse` `persist(s)` | Writes the claim snapshot over a concurrent `setDialogAlive(false)`, which resurrects the dead leg. |
| d | `UssdSagaCoordinator.compensate` | Emits a MAP `replyAndEnd`/`abort` **without winning a CAS**. A pull failure racing the gate means two MAP replies. |
| e | `BridgeGateScheduler.sweepPendingCorrelations` L224-227 | Same pattern as (a), on hop TTL. |
| f | `ClassicNiHttpPark.stampSessionGate` L336-351 · `MapUssdParentSbb.onNotifyResponse` · `MapNiPushSbb.keepOrCompleteSession` · `HttpServerSbb.handleNiContinue` | NI flows are multi-threaded (HTTP worker vs MAP event); lost updates on `state`/`invokeId`/`dialogAlive`. |

**Fix:**
- Single-field writes via `ProfileFacility.updateField`. Add `store.setMap2mapHopOutstanding`, `store.setGateMs`, `store.setInvokeId`.
- Every terminal or emitting transition goes through `compareAndTransition`.
- `compensate` must first CAS `AWAITING_AS|ACTIVE → FAILED`, and emit only on a win.
- Add a guard test that fails if `store.put(` is called after a `compareAndTransition` in the same method (source scan, like `Log4j2OnlyPolicyTest`).

### P1-7 — A network abort after bridging kills the committed late push

**Where:** `VirtualSessionBridge.onNetworkAbort` L253-260 turns `S1_RELEASED|PUSH_PENDING` into **ZOMBIE**. It is called from `handleInboundOrAbort` on hard abort (USER/PROVIDER_ABORT/TIMEOUT) and **unconditionally** from the MAP returnError path (`MapUssdParentSbb` L155).

Classic `markAborted` deliberately only touches the pre-bridge states (`WAIT_AS`/`WAIT_USER`). Once S1 is released by us, teardown noise must not cancel the S2 push.

**Fix:** only `AWAITING_AS|RESPONDING|ACTIVE` go to ZOMBIE/ABORTED. Bridged states keep their state; log + CDR `ABORT_AFTER_BRIDGE` only.

### P1-8 — `MAP2MAP_MO_HOLD` CDR/log write storm in the gate tick

**Where:** `VirtualSessionBridge.onGateExpired` L166-171

When `arm=false` (operator set `ussd.bridge.enabled=false` or `http-client-bridge-enabled=false`) and the hop is outstanding, the method returns `false` **without moving the deadline**. The session stays first in the due index, and every 100ms tick writes one CDR line plus one WARN. The hop TTL is 60s, and after the TTL sweep `map2mapHopOutstanding` stays `true`, so the storm continues until TTL reclaim: **about 1,000 CDR lines per session**. This violates "no per-tick write storms".

**Fix:**
- On defer, re-index the deadline to the hop TTL deadline (or +1s backoff) and write the CDR once (flag on the row).
- The hop TTL sweep must clear `map2mapHopOutstanding` (single-field) and terminate the session.

### P1-9 — Unbounded in-memory registries (leaks)

| Registry | Why it grows | Fix |
|----------|--------------|-----|
| `GatedSessionRegistry` (`byCorr`, `msisdnScToCorr`, `jsessionToCorr`) | Expiry is lazy (`getLive`) and `sweepExpired()` only runs from `size()`, which **has no caller in main**. One entry per gated session, forever. | Sweep from `BridgeGateScheduler.reclaimExpiredTx` (30s); add a hard cap. |
| `ClassicNiHttpPark` (`byCorr`, `byJsession`) | `unpark` only on park replace or AS END/abort POST. Notify-only flows where the AS never sends END, gate-expired parks, MAP aborts, and TTL-reclaimed sessions all stay forever. | TTL on the record (last activity); unpark on session terminal / MAP release / network abort. On a MAP abort, **settle the parked HTTP right away** with ABORT instead of waiting out the 25s gate. |

### P1-10 — `sweepPendingCorrelations` has no isolation

**Where:** `BridgeGateScheduler.java:158-231`

It has no `ConcurrentExecution.SKIP` and no per-item `catch (Throwable)`. One throw (saga/CDR/MAP) aborts the rest of the sweep, **including `hlrFace.expirePending`**, so inbound HLR dialogs leak. This is the same lesson as the gate tick, applied to the sibling job.

### P2-1 — ABA on the AS claim (generation is effectively unchecked)

The CAS is on `state` only, and the generation is checked on a snapshot. Since every wire is now stamped to the **current** session gen at response time, a duplicate or slow AS response for turn N (e.g. pull + `/as/callback`, or a client retry) can be applied to turn N+1. Classic used `requestId` + `inputGeneration < current → STALE`.

**Fix:** stamp with the generation **captured when the pull was sent** (`AsPullStateRegistry` already keys per-corr pulls), and make the CAS composite (`state`+`generation`, or a `version` field).

### P2-2 — `ClassicNiHttpPark.onGateExpired` can fail to answer the AS

L278-306: `cdr.write` and `gatedSessions.stamp` run unguarded **before** `reply`. A throw means the AS HTTP request hangs until its client timeout.

**Fix:** wrap the side effects; always `reply` (try/finally).

### P2-3 — AdaptiveTimeout per-MSISDN trim is O(n) on the hot path

L361-381: at the 8,192 bound with no stale entries, every **new** MSISDN does a full scan plus an arbitrary drop. At 5k TPS with mostly unique subscribers, that's an ~8k-entry scan per AS response, all for a model that is telemetry-only.

**Fix:**
- Drop the per-MSISDN EWMA, or
- trim in the 30s scheduler (amortized), or
- use sampled eviction.

### P2-4 — NI push text cut at 200 chars

`MapNiPushSbb.java:63`. The USSD-String limit is 160 octets (TS 23.038: 182 GSM-7 chars / **80 UCS-2 chars**). Amharic (UCS-2) over 80 chars breaks encoding or gets truncated by the peer.

**Fix:** alphabet-aware limit, plus a CDR `truncated=` flag.

### P2-5 — Ops clarity

- `BRIDGED_DONE` is written on **every** NI push (`MapNiPushSbb` L111/L135), including plain NI that was never bridged. Write it only when the source state was `S1_RELEASED|PUSH_PENDING`.
- `AS_DROP` noise on every NI continue (side effect of P0-1) will disappear with that fix.
- `ussd-3gpp-notes.md` §6 still draws "AdaptiveTimeout **EWMA gate**", while the law is ceiling-only. Align the wording ("AdaptiveTimeout park gate (ceiling) + EWMA telemetry").
- The hard-fail fallback string `"ማው ማውማው ማውማው ማውማው ማው"` is duplicated in 3 classes (`UssdConfigService`, `BridgeGateScheduler`, `UssdSagaCoordinator`) and in `dist/configs`. It reads like a placeholder. Confirm the operator text (**D5**) and keep one constant.

---

## 3. Classic oracle parity (session-bridge)

| Classic feature | Greenfield | Gap → finding |
|-----------------|------------|---------------|
| `AdaptiveTimeout.suggestGateMs` = live gate (EWMA×1.5 clamp) | Ceiling only, EWMA telemetry (**intentional owner law**) | Decision **D1** (optional mode flag) |
| `BridgeReconciler` CAS `BRIDGED→PUSH_PENDING` | `claimForAsResponse` (one step earlier) | ✔ |
| STALE via `inputGeneration` | Stamped to current gen | P2-1 |
| `markAborted` pre-bridge only | Aborts bridged too | P1-7 |
| `shouldDeliverNow` / `SessionPriority` | none | P1-4 |
| `PushRetryQueue` + `NotificationFallback` | none | P1-5 |
| Cache last menu for retry (`setLastMenu`) | `pendingText` only | P1-5 |

---

## 4. Proposed work order

| Step | Items | Size | Gate |
|------|-------|------|------|
| 1 | **P0-1** HTTP-NI 2-digit menu, with a red test first | S | unit + lab ss7-simulator 3-digit Request (pcap: one TCAP dialog) |
| 2 | **P1-6** CAS-law sweep (single-field writers + CAS-gated compensate) + source-scan guard test | M | `BridgeConcurrencyTest` extended (hop-response-at-gate race, abort-during-RESPONDING) |
| 3 | **P1-8**, **P1-9**, **P1-10** (storm + leaks + sweep isolation) | S | status.json counters: `bridge.gated.size`, `ni.park.size` stay flat in a 30-min soak |
| 4 | **P1-1**, **P1-2** (NI Request UI timeout, TTL on activity + cap, `no-session` ends the dialog) | M | lab: UE think 60s on turn 4 still works; gate expiry closes the MAP dialog |
| 5 | **P1-3**, **P1-7**, **P1-4**, **P1-5** (late reconcile semantics + busy + retry/fallback) | L | lab: bridged END → Notify; redial during wait → push deferred, then delivered |
| 6 | P2 items + doc alignment | S | — |

Prove-the-artifact law applies to every step: package, rsync bits only, restart, `:8088` ready, `jar tf` for the new symbols, then the live surface (CDR tape: no `AS_DROP`, one `MS_DIGIT` per digit, `CONTINUE gen=` per menu). Use the test host `100.110.205.176` (has SCTP) for the ss7-simulator steps. On Digicom live `*804`, manual prove only, with your OK.

## 5. Decisions needed from you

| ID | Question | My default |
|----|----------|------------|
| **D1** | Keep the gate ceiling-only (current law), or add `ussd.bridge.gate-mode=CEILING\|ADAPTIVE` (classic EWMA×1.5, floor ≥5s, MO pull only, never NI park)? | Keep CEILING; add the flag only if Digicom wants faster "Please wait" when the AS is dead |
| **D2** | Bridged AS **CONTINUE** (menu) over S2: (a) Request + keep the session interactive, or (b) Notify the text and end? | (b) for go-live (simpler, no orphan dialogs); (a) later |
| **D3** | NI Request UE timeout value (`ussd.ni.request-ui-timeout-ms`) | 120s (inside the MAP `ml` invoke range); re-armed per AS hop |
| **D4** | NI push fallback when retries are exhausted: log/CDR only, or SMS via SMPP? | log/CDR only now; SMS behind a flag later |
| **D5** | Hard-fail UE text: is `ማው ማውማው…` the intended Digicom text? | Ask the operator; one constant + config |
| **D6** | Max interactive session lifetime cap (`ussd.tx.max-session-ms`) | 10 min |
