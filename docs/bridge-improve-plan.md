# Improve plan — AdaptiveTimeout + Virtual Session Bridge

> Status: **STEP 1 DONE 2026-10-02** (P0-1 fixed + red test green; rest unchanged).
> Code-read review + line-level verification against
> `nhanth87/virtual-bridge-improve` @ `1daf80188`. **No tests run except Step 1.**
> Verification verdict per finding is in §6. Every finding keeps its file:line, failure
> scenario, and fix. Before fixing a finding, add a **red unit test** that reproduces it.
> Prove-the-artifact law applies to every step (package → rsync bits only → restart →
> `:8088` ready → `jar tf` new symbols → live surface).

Paths relative to `src/main/java/et/restlink/ussdgw/`.

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
3. **The "CAS ≠ then rewrite the row" law is violated in ~9 places.** These are get + full `put()` calls that can revert a CAS.
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
Verification verdicts (§6) folded in; corrections to the original wording are marked **[verified]**.

### P0-1 — HTTP-NI interactive menu dies at the 2nd UE digit ✅ confirmed

**Where:** `sbbs/HttpServerSbb.java:293-299` · `sbbs/MapUssdParentSbb.java:701-713` · `bridge/VirtualSessionStore.java:496`

**Mechanism:**
1. AS `POST /ussd` with a Request menu creates a session at **gen=1**, and MAP sends Request to the UE.
2. UE digit `1` arrives in `onUserContinue` (HTTP-NI branch). `tryClaimMsDigitContinue` wins and marks the claim **in-flight**. `nextGeneration()` takes the session to **gen=2**. `completeParked` sends the digit to the AS.
3. The AS posts the next menu with Cookie, into `handleNiContinue`. That method sets `AWAITING_AS` with a full `put()`, then calls `onAsResponse(new AsResponse(corr, corr, **1**, …))`. **gen is hardcoded to 1** (`HttpServerSbb.java:293`) and there is no `stampedToSessionGeneration`. As a result:
   - `acceptAsResponse` sees `1 != 2` and the response is dropped: `dropLate`, CDR **`AS_DROP reason=genMismatch`**.
   - `releaseMsDigitInFlight` is **never called** on this path (releases exist only in `applyToLiveDialog` L351/L378; `dropLate` L268–302 has none).
   - The row stays in `AWAITING_AS`.
4. The MAP continue for menu 2 still goes out (`routeNiContinue`), so the UE sees menu 2.
5. UE digit `2` arrives. `tryClaimMsDigitContinue` returns **IN_FLIGHT** (and the `AWAITING_AS` belt check L683–691 would also trigger), giving `dup-skip-continue`. **The digit is dropped.** The AS park waits 25s and gets `GATE_EXPIRED`. The UE hangs until the MAP timeout.

**[verified]** Strictly, the menu after digit #1 is already dropped; "dies at 2nd digit" is the operator-visible symptom. No `onNiAsContinue` exists repo-wide.

**Why tests miss it:** `HttpServerSbbNiContinueTest` L82–87 stubs `bridge.onAsResponse` (no-op); single continue, no digit round-trip. Digicom has only proven the **Notify** path.

**Fix:**
- In `handleNiContinue`, replace the fake `AsResponse` shim with a dedicated `bridge.onNiAsContinue(corr)`. It would:
  - CAS `ACTIVE → RESPONDING`, so a duplicate AS POST loses;
  - call `releaseMsDigitInFlight`;
  - single-field `state → ACTIVE`.
- At minimum: stamp the session generation, and remove the get + `put(AWAITING_AS)` shim.
- **[verified addition]** Migration: rows parked by the old shim sit in forced `AWAITING_AS`; the new CAS must tolerate that transient (or accept one gate cycle on rollout).

**Tests:**
- Red test first: `HttpNiTwoDigitMenuTest`. Wire the real bridge and store, run POST → digit → POST(cookie) → digit, and assert the 2nd digit reaches `completeParked` with no `AS_DROP`.
- Lab prove: ss7-simulator Request menu with 3 digits on one TCAP dialog (pcap).

### P1-1 — NI park gate (25s) fires during UE think-time (open doc gap Q6 / P1) ✅ confirmed

**Where:** `api/classic/ClassicNiHttpPark.java:165-190, 263-307` · `HttpServerSbb.java:251, 319`

Every NI park uses `effectiveGateMs` (25s ceiling; `UssdConfigService` L27–30, `build/application.properties` L75–76), even when the outstanding MAP op is a **Request** waiting for a human. No `ussd.ni.request-ui-timeout-ms` or any per-op timeout exists (grep: zero hits). On expiry:
- the AS gets a gated abort;
- `httpSessionId` is set to `null` (`ClassicNiHttpPark` L301–306);
- the **MAP dialog stays open** (no `niClose`/`abort`, no state change);
- the late UE digit hits `completeParked` → `false` (`ClassicNiHttpPark` L215–217) → `http-ni-no-park` (`MapUssdParentSbb` L701–711), so the digit is lost;
- the digit claim stays in-flight;
- the dialog lingers until the MAP invoke timeout (a dialog leak in practice).

Re-arm exists **only per AS hop** (`HttpServerSbb` L300–301/L319 re-`park()`); nothing re-arms during UE think-time.

**[verified]** Scope: NI (`/ussd`, AS-initiated) path only. MO menus use the bridge gate (same 25s ceiling, different mechanism) — don't conflate in the fix.

**Fix:**
- Budget per outstanding op:
  - **Notify** (and the first SRI+Begin hop): keep the ceiling.
  - **Request awaiting UE input**: use a new `ussd.ni.request-ui-timeout-ms`, aligned with the MAP Request invoke timer (TS 29.002 `ml`, classic `customInvokeTimeout`).
- Re-arm on every AS hop.
- On any NI park expiry with a live MAP dialog: `MapDialogHelper.abort`/`niClose`, `clearMsDigitClaim`, then a terminal state for the session.

### P1-2 — ussdTx TTL can reclaim a live MO menu; the orphaned digit leaves the MAP dialog hanging ✅ confirmed

**Where:** `bridge/VirtualSessionStore.java:657-662` (`expiresAt`) · `sbbs/MapUssdParentSbb.java:663`

`expiresAt = max(created + max(120s, dialogTimeout), lastGateDeadline + 30s)` (`GATE_TTL_GRACE_MS` L43, `profile-ttl-ms=120000`). `reclaimExpired` (L428–465, every 30s via `BridgeGateScheduler` L141–151) removes **any** row past expiry even when `ACTIVE`/`AWAITING_AS` with a live MAP dialog. **[verified]** Horizon is created+120s (or gate+30s) — bites slow readers / long-lived menus, not every menu. Next digit → silent `"no-session"` (L661–663; contrast `no-rule` L714–717 which does `replyAndEnd`). `ussd.tx.max-session-ms` does not exist.

**Fix:**
- While `ACTIVE` (waiting for the UE), extend expiry by the MAP UE timeout. Keep an absolute cap such as `created + ussd.tx.max-session-ms` (e.g. 10 min, decision **D6**), which preserves the existing "no slide-forever leak" intent.
- `no-session` on a MAP continue must `replyAndEnd(hard-fail)` or `abort`, never stay silent.

### P1-3 — Late reconcile (S1 bridged → S2 NI) ignores the AS action ✅ confirmed

**Where:** `bridge/VirtualSessionBridge.java:131-145` → `access/MapUssdAccessAdapter.requestNiPush` (always `notifyOnly=false` via `NiPushRequestEvent` 4-arg ctor) → `sbbs/MapNiPushSbb.java:111` `keepOrCompleteSession` (COMPLETED + remove)

The late path never reads `response.action()` (contrast `applyToLiveDialog` L326/L344–366 which switches on it), and the push call chain has no action/notifyOnly channel (`AccessNiDispatcher` 2-arg only):

| AS action on a bridged session | Today | Should be (3GPP 22.090 / AGENTS "NI one-shot vs menu") |
|---|---|---|
| END (final text) | MAP **Request** (handset opens an input box); the session is removed, so the UE reply is orphaned (P1-2 path) | **Notify**, then release |
| ABORT | NI push of the abort text (or null) | **No push**; terminal `ABORTED` |
| CONTINUE (menu) | Request, but the session is removed, so the next digit has no session | Decision **D2**: (a) Request on S2 and keep the session `ACTIVE`, bound to the new S2 dialog; or (b) Notify the text and end |

### P1-4 — No handset-busy check before a late NI push (classic `shouldDeliverNow`) ✅ confirmed

**Where:** `VirtualSessionBridge.java:140`. Zero MSISDN lookup before `requestNiPush`; no `findActiveByMsisdn` exists repo-wide (only `findAwaitingAsByMsisdn`, used by SIP pull-reply only). The `msisdn` index exists (`ensureTable` L115) so a check is indexable. Realistic scenario: user sees "Please wait...", redials `*804#`, late S2 push collides with the new MO dialog → MSC/handset `ussd-Busy` (MAP error 72) or network abort, result lost. Classic deferred behind an active MO session (`SessionPriority`; payment/OTP could preempt).

**Fix:** before the push, query the msisdn index excluding self. If busy, keep `PUSH_PENDING` and retry (P1-5).

### P1-5 — No NI push retry / fallback (classic `PushRetryQueue` 3s/8s/15s + `NotificationFallback`) ✅ confirmed

**Where:** `UssdSagaCoordinator.onNiFailed` → `compensate` → `FAILED`. `MapNiPushSbb.push` L103–112 is `sendCommand` fire-and-forget then synchronous `COMPLETED`+remove+`BRIDGED_DONE` — a later MAP error finds no session. No `niRetry|pushRetry|redeliver|deadLetter` hits repo-wide (AS HTTP pull `maxRetries` in `AsPullClient` is pull-only). **[verified]** No `Thread.sleep` on the NI path, so the "vs Thread.sleep" framing is moot — the gap is single-shot-then-FAILED.

**Fix:**
- Keep the row in `PUSH_PENDING` until the MAP result arrives.
- Make retryable errors bounded-retryable: `ussd-Busy`, `systemFailure`, timeout. **Do not** retry `absentSubscriber` or `unknownSubscriber`.
- Add a fallback SPI. The default only logs and writes CDR; optionally SMS via the in-tree SMPP RA (decision **D4**).
- Retry scheduling goes through `BridgeGateScheduler` (deadline index), never `Thread.sleep`.

### P1-6 — "CAS ≠ then rewrite the row" law violated ✅ confirmed (9 sites, 0 refuted)

`UssdTxProfileMapper.write` republishes **~20 fields** from a detached snapshot (L13–44). The store itself warns about exactly this (`VirtualSessionStore` L328–336). Single-field API exists but only `setDialogAlive` uses it (bridge L203/L251). No `setMap2mapHopOutstanding`/`setGateMs`/`setInvokeId` on the store.

| # | Where | Concrete race |
|---|-------|---------------|
| a | `MapUssdParentSbb.clearMap2mapHopOutstanding` L325-337 | Hop response at gate deadline: gate CASes `AWAITING_AS→S1_RELEASED` + `replyAndEnd`, then this `put` restores `AWAITING_AS` + `dialogAlive=true` → second `replyAndEnd` on ended dialog, second `BRIDGED` CDR, second gated AS push |
| b | `VirtualSessionBridge.onNetworkAbort` L247-266 | Abort during `RESPONDING` writes ZOMBIE over the claim; then `applyToLiveDialog.persist` overwrites it with ACTIVE/COMPLETED (or reverse). L251 atomic `setDialogAlive` protects one field only |
| c | `VirtualSessionBridge.applyToLiveDialog` / `onAsResponse` `persist(s)` (L136–150, L334–448) | Post-CAS branches mutate the detached claim snapshot; concurrent `setDialogAlive(false)`/`gateMs`/`mscGt` reverted. (Gate BRIDGED path L200–203 does it right — follow that pattern everywhere) |
| d | `UssdSagaCoordinator.compensate` L89–105 | MAP `replyAndEnd`/`abort` with **zero** CAS in the class (grep: no hits); then `FAILED` + full `put`. Pull failure racing the gate = two MAP replies |
| e | `BridgeGateScheduler.sweepPendingCorrelations` L224-227 | Same as (a) on hop TTL; MAP `replyAndEnd` L218 guarded by read-only `alreadyBridged` check only |
| f | `ClassicNiHttpPark.stampSessionGate` L336-351 · `MapUssdParentSbb.onNotifyResponse` L630-637 · `MapNiPushSbb.keepOrCompleteSession` L171-182 · `HttpServerSbb.handleNiContinue` L275-298 | NI flows are multi-threaded (HTTP worker vs MAP event); lost updates on `state`/`invokeId`/`dialogAlive`. End branch does MAP `abort`/`niClose` without CAS |

**Fix:**
- Single-field writes via `ProfileFacility.updateField`. Add `store.setMap2mapHopOutstanding`, `store.setGateMs`, `store.setInvokeId`.
- Every terminal or emitting transition goes through `compareAndTransition`.
- `compensate` must first CAS `AWAITING_AS|ACTIVE → FAILED`, and emit only on a win.
- Add a guard test that fails if `store.put(` is called after a `compareAndTransition` in the same method (source scan, like `Log4j2OnlyPolicyTest`).

### P1-7 — A network abort after bridging kills the committed late push ✅ confirmed

**Where:** `VirtualSessionBridge.onNetworkAbort` L253-260 turns `S1_RELEASED|PUSH_PENDING` into **ZOMBIE** (ZOMBIE falls to `dropLate` in `onAsResponse` L131–144 check). Called from `handleInboundOrAbort` on hard abort (`MapUssdParentSbb` L305–307 skips the `isBridgedStayOnCall` guard L340–348 when `hardAbort=true`) and **unconditionally** from the MAP returnError inbound path (L145/L154–155). Classic `markAborted` only touches pre-bridge states (`WAIT_AS`/`WAIT_USER`).

**Fix:** only `AWAITING_AS|RESPONDING|ACTIVE` go to ZOMBIE/ABORTED. Bridged states keep their state; log + CDR `ABORT_AFTER_BRIDGE` only.

### P1-8 — `MAP2MAP_MO_HOLD` CDR/log write storm in the gate tick ✅ confirmed

**Where:** `VirtualSessionBridge.onGateExpired` L166-171

When `arm=false` + hop outstanding, returns `false` **without moving the deadline** — session stays first in the due index, one CDR + one WARN per 100ms tick until the hop clears. Hop TTL sweep (`BridgeGateScheduler` L179–229) never clears `map2mapHopOutstanding` (only clearer is `clearMap2mapHopOutstanding`, unreachable while the hop is silent). **[verified]** Eventual `reclaimExpiredTx` TTL deletes the row, but the flag is never cleared — silent hop ≈ per-tick CDR until TTL reclaim.

**Fix:**
- On defer, re-index the deadline to the hop TTL deadline (or +1s backoff) and write the CDR once (flag on the row).
- The hop TTL sweep must clear `map2mapHopOutstanding` (single-field) and terminate the session.

### P1-9 — Unbounded in-memory registries (leaks) ✅ confirmed (both)

| Registry | Why it grows | Fix |
|----------|--------------|-----|
| `GatedSessionRegistry` (`byCorr`, `msisdnScToCorr`, `jsessionToCorr`) | `sweepExpired()` is `private`, called **only from `size()` (L94–97), which has no caller in `src/main`**. No `@Scheduled` sweep, no cap, lazy single-entry expiry only | Sweep from `BridgeGateScheduler.reclaimExpiredTx` (30s); add a hard cap |
| `ClassicNiHttpPark` (`byCorr`, `byJsession`) | No TTL field, no sweep at all. `unpark` only on park-replace / AS END-abort POST / gate httpId-null. `completeParked*` intentionally keep JSESSIONID→corr; `onNetworkAbort` never touches the park. `settled` CAS prevents double-reply but not eviction | TTL on the record (last activity); unpark on session terminal / MAP release / network abort. On MAP abort, **settle the parked HTTP right away** with ABORT instead of the 25s gate |

### P1-10 — `sweepPendingCorrelations` has no isolation ✅ confirmed

**Where:** `BridgeGateScheduler.java:158-231`

No `ConcurrentExecution.SKIP` (contrast `tickGates` L83–84, `reclaimExpiredTx` L141) and no per-item `catch` — one throw (saga/CDR/MAP) aborts the rest of the sweep, **including `hlrFace.expirePending` (L230)**, so inbound HLR dialogs leak. Same lesson as the gate tick, sibling job.

### P2-1 — ABA on the AS claim (generation is effectively unchecked) ✅ confirmed

CAS is on `state` only (`casState` L366/L371); generation is a read-only snapshot gate (`acceptAsResponse` L496). All three ingresses stamp to **current** gen, so a duplicate/slow AS response for turn N (pull + `/as/callback`, client retry) can apply to turn N+1. `AsPullState` has **no generation field** — nothing to disambiguate with. Classic used `requestId` + `inputGeneration < current → STALE`.

**Fix:** stamp with the generation **captured when the pull was sent** (`AsPullStateRegistry` already keys per-corr pulls), and make the CAS composite (`state`+`generation`, or a `version` field).

### P2-2 — `ClassicNiHttpPark.onGateExpired` can fail to answer the AS ✅ confirmed

L278-306: `trySettle()` CAS (L274) correctly precedes side effects, but `cdrWrite` (L280) + `gatedSessions.stamp` (L283–285) run unguarded **before** `reply` (L301–306). Only `pushToAs` (L295–299) and inner `store.get` (L291) are guarded. **[verified]** Depends on `CdrService.write` actually throwing (file-ledger path usually swallows) — ordering bug is real regardless.

**Fix:** wrap the side effects; always `reply` (try/finally).

### P2-3 — AdaptiveTimeout per-MSISDN trim is O(n) on the hot path ✅ confirmed with nuance

L361-381: full `entrySet()` scan + arbitrary drop at the 8,192 bound. **[verified]** Only when **at bound** (L362 early-return), not per response — bounded-amortized, worst case one ~8k scan per insert while saturated. Telemetry-only model, so cheapest correct fix counts.

**Fix (pick one):** drop the per-MSISDN EWMA, trim in the 30s scheduler (amortized), or sampled eviction.

### P2-4 — NI push text cut at 200 chars ✅ confirmed

`MapNiPushSbb.java:63`: UTF-16 `substring(0, 200)` — not alphabet/segment-aware, can split a surrogate pair; ignores GSM-7 (160/153) vs UCS-2 (70/67). Alphabet passes through as `AUTO` (L105/L121). Same cut shared by `continuePush` (L118).

**Fix:** alphabet-aware limit, plus a CDR `truncated=` flag.

### P2-5 — Ops clarity (mixed — see verdicts)

- `BRIDGED_DONE` written on **every** NI push ✅ (`MapNiPushSbb` L109–112 / L133–136, incl. lab fallback L100 and HTTP-NI-keep L173; no failure branch skips it). Write only when source state was `S1_RELEASED|PUSH_PENDING`.
- `AS_DROP` noise on every NI continue: side effect of P0-1, disappears with that fix. ✅
- `ussd-3gpp-notes.md` §6 "AdaptiveTimeout **EWMA gate**" ⚠️ **diagram label stale only** — normative text L242 already says ceiling (25s, not EWMA shrink); code javadoc agrees (`AdaptiveTimeout` L17–22, `VirtualSessionBridge` L70–72). Fix the diagram label.
- Hard-fail string ✅ — duplicated in **5 classes + config default** (worse than the 3 reported): `UssdConfigService` L34, `UssdSagaCoordinator` L131, `BridgeGateScheduler` L238, `MapUssdParentSbb` L250, `Map2MapSbb` L184. Reads like a placeholder — confirm operator text (**D5**), keep one constant + config.

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
| 1 | **P0-1** HTTP-NI 2-digit menu, with a red test first — ✅ **DONE 2026-10-02**: `HttpNiTwoDigitMenuTest` (red: `genMismatch` + stuck `AWAITING_AS`, then green after fix); fix = `VirtualSessionBridge.onNiAsContinue` (CAS `ACTIVE`/`AWAITING_AS`→`RESPONDING`, release digit claim, →`ACTIVE`, CDR CONTINUE, no EWMA feed) + `HttpServerSbb.handleNiContinue` shim removed (dup suffix on loss). Full suite: 613 tests, only 2 **pre-existing** failures (`GrpcClientSbbPullStateTest`, `Map2MapBridgeArmTest` — fail identically on clean tree) | S | unit ✅ (lab ss7-simulator 3-digit Request pcap still open — needs SCTP host) |
| 2 | **P1-6** CAS-law sweep (single-field writers + CAS-gated compensate) + source-scan guard test — ✅ **DONE 2026-10-02**: `store.compareAndTransitionAny` + 7 single-field setters; 10 sites fixed (9 verified + `markDialogDead` found during work); `CasLawGuardTest` (claim+put combo + denied-list); `BridgeConcurrencyTest` +2 (hop-race re-fire, stale-claim-after-abort). Full suite green except 2 pre-existing | M | ✅ |
| 3 | **P1-8**, **P1-9**, **P1-10** (storm + leaks + sweep isolation) — ✅ **DONE 2026-10-02**: defer re-indexes to hop TTL (`deferGateDeadline`); sweep clears flag both paths; gated sweep public + cap 10k + 30s scheduler sweep; park `sweepStaleParked` + `abortParked` (settle-on-abort wired in `onNetworkAbort`); sweep SKIP + per-item isolation; `bridge.gated.size`/`ni.park.size` in status.json. Tests: `GateDeferAndRegistrySweepTest` (4). Full suite green except 2 pre-existing | S | ✅ (30-min soak on SCTP host still open) |
| 4 | **P1-1**, **P1-2** (NI Request UI timeout, TTL on activity + cap, `no-session` ends the dialog) — ✅ **DONE 2026-10-02**: `ussd.ni.request-ui-timeout-ms` (120s, clamped to dialog) + per-op budget on park (`requestUi` flag, re-armed per hop); expiry CASes terminal + aborts MAP + clears claim; `ussd.tx.max-session-ms` (10min) for ACTIVE rows; `no-session-hard-fail`. Tests: `NiParkBudgetAndSessionTtlTest` (4). Full suite green except 2 pre-existing | M | ✅ (lab: 60s think-time on turn 4; gate expiry closes MAP) |
| 5 | **P1-3**, **P1-7**, **P1-4**, **P1-5** (late reconcile semantics + busy + retry/fallback) — ✅ **DONE 2026-10-02**: D2b+D4 decided. Late: ABORT→no-push terminal; END/CONTINUE→Notify (3-arg access, survives SRI); busy→PUSH_DEFERRED+retry; S2 error→retry(3s/8s/15s, Busy/systemFailure/timeout only)/fallback(CDR); S2 CLOSE→NI_PUSH_OK; P1-7 pre-bridge-only abort + ABORT_AFTER_BRIDGE; `NiPushRetryRegistry` + `NiPushRetryPolicy` + `NiPushFallback` (logging default); `tickNiRetries` 5s; BRIDGED_DONE scoped to bridged rows. Tests: `LateNiPushTest` (18). Full suite green except 2 pre-existing | L | ✅ (lab: bridged END → Notify; redial → deferred→delivered) |
| 6 | **P2-1**, **P2-2**, **P2-3**, **P2-4**, **P2-5** (ABA guard + onGateExpired reply + per-MSISDN trim + NI truncation + hard-fail constant) — ✅ **DONE 2026-10-02**: P2-1 = `AsPullState.generation` field + `open(corr, target, nowMs, generation)` overload + session-gen capture at pull send (not request gen) + post-CAS revalidation in `claimForAsResponse` (roll back RESPONDING→from if gen drifted); P2-2 = `ClassicNiHttpPark.onGateExpired` side effects wrapped in try/finally so HTTP reply always runs; P2-3 = `AdaptiveTimeout.trimMsisdn()` moved off hot path to 30s scheduler tick; P2-4 = `UssdEncodingPolicy.truncateToFit` (GSM-7 septet-aware, UCS-2 surrogate-safe, UCS-8 octet-safe) replaces hardcoded 200-char substring in `MapNiPushSbb`; P2-5 = `UssdConfigService.DEFAULT_HARD_FAIL_MESSAGE` constant shared by 5 classes (UssdSagaCoordinator, BridgeGateScheduler, MapUssdParentSbb, Map2MapSbb, UssdConfigService). Full suite: 643 tests, only 2 **pre-existing** failures (`GrpcClientSbbPullStateTest`, `Map2MapBridgeArmTest` — fail identically on clean tree) | S | ✅ |

Step dependencies: step 2 touches `handleNiContinue` (f4) — land step 1 first so the new `onNiAsContinue` is the swept target, not the old shim. Step 5 needs **D2** decided before design.

Prove-the-artifact law applies to every step: package, rsync bits only, restart, `:8088` ready, `jar tf` for the new symbols, then the live surface (CDR tape: no `AS_DROP`, one `MS_DIGIT` per digit, `CONTINUE gen=` per menu). Use the test host `100.110.205.176` (has SCTP) for the ss7-simulator steps. On Digicom live `*804`, manual prove only, with your OK.

Per-step new config keys (all runtime `configs/`, none build-time; add to `UssdConfigService` + `build/application.properties` defaults + operator docs):

| Step | Keys |
|------|------|
| 3 | registry caps + `ni.park.size` / `bridge.gated.size` status counters |
| 4 | `ussd.ni.request-ui-timeout-ms` (default 120s, **D3**), `ussd.tx.max-session-ms` (default 10 min, **D6**) |
| 5 | retry bounds (attempts/delays for Busy/systemFailure/timeout), fallback selector (**D4**) |

---

## 5. Decisions needed from you

| ID | Question | Default |
|----|----------|---------|
| **D1** | Keep the gate ceiling-only (current law), or add `ussd.bridge.gate-mode=CEILING\|ADAPTIVE` (classic EWMA×1.5, floor ≥5s, MO pull only, never NI park)? | Keep CEILING; add the flag only if Digicom wants faster "Please wait" when the AS is dead |
| **D2** ⛔ blocks step 5 design | Bridged AS **CONTINUE** (menu) over S2: (a) Request + keep the session interactive, or (b) Notify the text and end? | (b) for go-live (simpler, no orphan dialogs); (a) later |
| **D3** | NI Request UE timeout value (`ussd.ni.request-ui-timeout-ms`) | 120s (inside the MAP `ml` invoke range); re-armed per AS hop |
| **D4** | NI push fallback when retries are exhausted: log/CDR only, or SMS via SMPP? | log/CDR only now; SMS behind a flag later |
| **D5** | Hard-fail UE text: is the current literal the intended Digicom text? (5 classes + config default) | Ask the operator; one constant + config |
| **D6** | Max interactive session lifetime cap (`ussd.tx.max-session-ms`) | 10 min |

---

## 6. Verification log (2026-10-02, code-read, no tests run)

Every finding checked against the tree. Line numbers are the verified ones (may differ ±few lines from the draft).

| Finding | Verdict | Key evidence |
|---------|---------|--------------|
| P0-1 | ✅ CONFIRMED | `HttpServerSbb.java:293` literal gen `1`; `:294-297` get+put(AWAITING_AS); no `releaseMsDigitInFlight` on path; `MapUssdParentSbb.java:702` digit→gen 2; 2nd digit `IN_FLIGHT` L671–672 or `state` belt L683–691 |
| P1-1 | ✅ CONFIRMED (NI-path only) | `ClassicNiHttpPark.java:165-178` single ceiling; `:301-306` nulls httpSessionId, MAP untouched; `:215-217` late digit → false; zero `request-ui` hits repo-wide |
| P1-2 | ✅ CONFIRMED (horizon created+120s) | `VirtualSessionStore.java:657-662` expiry; `:443-444` reclaim any expired non-terminal; `MapUssdParentSbb.java:661-663` silent no-session; no `max-session-ms` |
| P1-3 | ✅ CONFIRMED | `VirtualSessionBridge.java:131-145` no `action()` read; `AccessNiDispatcher` 2-arg; `NiPushRequestEvent` defaults `notifyOnly=false`; `MapNiPushSbb.java:163-184` unconditional COMPLETED+remove |
| P1-4 | ✅ CONFIRMED | no `findActiveByMsisdn` repo-wide; `findAwaitingAsByMsisdn` SIP-only; msisdn index exists but unqueried on push path |
| P1-5 | ✅ CONFIRMED (Thread.sleep framing moot — none exists) | `UssdSagaCoordinator.java:59-69,103-105` terminal FAILED; zero retry/DLQ hits; `MapNiPushSbb.java:103-112` COMPLETED before MAP result |
| P1-6 | ✅ CONFIRMED 9/9 sites | (a) L325-337 (b) L247-266 (c) L117-150/L334-448 (d) L89-105 no CAS in class (e) L216-228 (f1) L336-351 (f2) L630-637 (f3) L171-182 (f4) L275-298; only `setDialogAlive` uses `updateField` |
| P1-7 | ✅ CONFIRMED | `VirtualSessionBridge.java:247-266` bridged→ZOMBIE; `MapUssdParentSbb.java:305-307` skips bridged guard on hardAbort; `:127-157` returnError inbound unconditional |
| P1-8 | ✅ CONFIRMED | `onGateExpired.java:166-172` return false, no deadline move; sweep L179-229 never clears flag; per-tick CDR until TTL reclaim |
| P1-9 | ✅ CONFIRMED both | `GatedSessionRegistry.sweepExpired` private, only caller `size()` (no `src/main` callers); `ClassicNiHttpPark` no TTL/sweep, `onNetworkAbort` never unparks |
| P1-10 | ✅ CONFIRMED | `BridgeGateScheduler.java:158-159` no SKIP, no per-item catch; `hlrFace.expirePending` L230 last, unguarded |
| P2-1 | ✅ CONFIRMED | `casState` state-only; `acceptAsResponse` L496 snapshot gate; `AsPullState` no generation field |
| P2-2 | ✅ CONFIRMED (throw-dependence caveat) | `ClassicNiHttpPark.java:274` CAS good; L280/L283-285 unguarded before L301-306 reply |
| P2-3 | ✅ CONFIRMED (at-bound only) | `AdaptiveTimeout.java:361-381`, bound 8192, L362 early-return |
| P2-4 | ✅ CONFIRMED | `MapNiPushSbb.java:63` UTF-16 cut; alphabet `AUTO` passthrough |
| P2-5 | ✅ / ✅ / ⚠️ / ✅ | BRIDGED_DONE unconditional; AS_DROP→P0-1; §6 body correct, **diagram label** stale; hard-fail in 5 classes (not 3) |

## 7. What this plan does NOT cover (out of scope, noted for completeness)

- **gRPC/SIP NI-interactive parity**: step 1 fixes the HTTP-NI shim; verify the gRPC/SIP NI-continue paths don't share the hardcoded-gen shape before closing step 1.
- **New-metrics plumbing**: step 3 gates on `bridge.gated.size` / `ni.park.size` — those counters don't exist yet; add them (status.json + `/metrics` mirror) as part of step 3.
- **`BridgeConcurrencyTest` baseline**: step 2 extends it — confirm it exists and covers CAS races before extending.
- **D2-dependent S2 dialog binding** (step 5, option (a)): if (a) is chosen later, the S2 dialog→session bind and its own digit-claim scope need a separate design pass.
