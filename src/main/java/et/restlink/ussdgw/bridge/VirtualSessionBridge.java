package et.restlink.ussdgw.bridge;

import et.restlink.ussdgw.access.AccessNiDispatcher;
import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.api.classic.ClassicNiHttpPark;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.cdr.CdrStatuses;
import et.restlink.ussdgw.cdr.CdrUssdSnippet;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.profile.UssdUserProfileStore;
import et.restlink.ussdgw.service.GatedAsNotifyService;
import et.restlink.ussdgw.service.MapDialogHelper;

import com.microjainslee.api.RaCommandPort;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@ApplicationScoped
public class VirtualSessionBridge {
    private static final Logger LOG = LogManager.getLogger(VirtualSessionBridge.class);

    @Inject VirtualSessionStore store;
    @Inject AdaptiveTimeout adaptive;
    @Inject UssdConfigService config;
    @Inject CdrService cdr;
    @Inject AccessNiDispatcher accessNi;
    @Inject ClassicNiHttpPark niHttpPark;
    @Inject GatedSessionRegistry gatedSessions;
    @Inject GatedAsNotifyService gatedAsNotify;
    @Inject UssdUserProfileStore userProfiles;
    @Inject et.restlink.ussdgw.service.PendingMap2MapRegistry pendingMap2Map;
    @Inject NiPushRetryRegistry niPushRetries;
    @Inject NiPushFallback niPushFallback;

    private volatile Supplier<RaCommandPort> ss7Supplier = () -> null;

    private final AtomicLong bridgeCount = new AtomicLong();
    private final AtomicLong recoverCount = new AtomicLong();
    private final AtomicLong zombieDrop = new AtomicLong();

    public void bindSs7(Supplier<RaCommandPort> supplier) {
        this.ss7Supplier = supplier == null ? () -> null : supplier;
    }

    private RaCommandPort ss7() {
        try {
            return ss7Supplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Arm AdaptiveTimeout for AS pull (default phase label {@code as}). */
    public void startAwaitingAs(VirtualSession session) {
        startAwaitingAs(session, "as");
    }

    /**
     * Arm AdaptiveTimeout budget and stamp {@link et.restlink.ussdgw.cdr.CdrStatuses#GATE_ARMED}
     * (countdown started — <em>not</em> UE async-wait). Gate <em>fires</em> later as
     * {@code BRIDGED} / {@code GATE_EXPIRED} if still waiting when the deadline elapses.
     *
     * <p>Budget = configured {@code ussd.bridge.async-gate-timeout-ms} ceiling (default 25s),
     * not EWMA×1.5. EWMA is still sampled on AS response for telemetry only.
     *
     * <p>{@code gatePhase}: {@code hop} = RE_ROUTE after hop USSD sent; {@code as} = classic
     * AS pull / re-arm after hop text.
     */
    public void startAwaitingAs(VirtualSession session, String gatePhase) {
        // Live budget = config ceiling (never EWMA shrink). Observed EWMA stays for CDR/admin.
        long gate = adaptive.effectiveGateMs(
                session.networkId(), session.msisdn(),
                config.asyncGateTimeoutMs(), config.dialogTimeoutMs());
        session.setGateMs(gate);
        // Wall clock only for the durable deadline (must survive a restart) and the CDR;
        // the latency sample that feeds the EWMA is taken from the monotonic clock.
        session.setPullStartedAtMs(System.currentTimeMillis());
        session.setPullStartedAtNanos(System.nanoTime());
        session.setGateDeadlineMs(session.pullStartedAtMs() + gate);
        session.setState(VirtualSessionState.AWAITING_AS);
        persist(session);
        String phase = gatePhase == null || gatePhase.isBlank() ? "as" : gatePhase.trim();
        cdrWrite(session, CdrPhase.S1_ACTIVE, et.restlink.ussdgw.cdr.CdrStatuses.GATE_ARMED,
                "service=VirtualSessionBridge|AdaptiveTimeout|gateMs=" + gate
                        + "|gateRole=budget|gateBudget=ceiling|phase=" + phase
                        + "|note=armed-not-fired");
    }

    /**
     * Deliver an AS response. Content responses take an exclusive CAS claim on the session
     * (classic {@code BridgeReconciler} parity) so the pull channel, the {@code /as/callback}
     * channel and the gate scheduler can never both act on one correlation.
     */
    public void onAsResponse(AsResponse response, long latencyMs) {
        String pushBackId = response == null ? null : response.resolvePushBackId();
        if (pushBackId == null) {
            dropLate(null, response);
            return;
        }
        if (response.async()) {
            // ASYNC_ACK carries no content: it neither replies on MAP nor pushes over NI, and
            // must not feed the EWMA. Validate only — the real callback still owns the session.
            if (store.acceptAsResponse(pushBackId, response.generation()).isEmpty()) {
                dropLate(pushBackId, response);
            }
            return;
        }

        Optional<VirtualSessionStore.AsResponseClaim> claimed =
                store.claimForAsResponse(pushBackId, response.generation());
        if (claimed.isEmpty()) {
            dropLate(pushBackId, response);
            return;
        }
        VirtualSession s = claimed.get().session();
        VirtualSessionState previous = claimed.get().previous();
        recordLatency(s, latencyMs);

        if (previous == VirtualSessionState.AWAITING_AS && s.dialogAlive()) {
            applyToLiveDialog(s, response);
            return;
        }
        boolean bridged = previous == VirtualSessionState.S1_RELEASED;
        boolean offMapLegGone = previous == VirtualSessionState.AWAITING_AS
                && s.originationType() != OriginationType.MAP;
        if (bridged || offMapLegGone) {
            recoverCount.incrementAndGet();
            // P1-3: the AS action decides the S2 shape (3GPP 22.090 one-shot vs menu).
            // ABORT pushes nothing — retire terminal. END and (D2b) bridged CONTINUE go
            // out as one-shot Notify: the S1 leg is gone and no one owns the next UE
            // digit, so an interactive Request would orphan its reply (P1-2 path).
            AsAction lateAction = response.action() == null ? AsAction.END : response.action();
            if (lateAction == AsAction.ABORT) {
                Optional<VirtualSession> gone = store.compareAndTransition(
                        pushBackId, VirtualSessionState.RESPONDING,
                        VirtualSessionState.ABORTED);
                if (gone.isEmpty()) return;
                store.clearMsDigitClaim(pushBackId);
                store.remove(pushBackId);
                cdrWrite(gone.get(), CdrPhase.FAILED, "ABORTED",
                        "service=VirtualSessionBridge|late-abort|note=no-push");
                return;
            }
            // CAS-law: this claim owns RESPONDING — move to PUSH_PENDING by CAS and write
            // caller-owned fields singly, never a detached full put over concurrent writers.
            Optional<VirtualSession> queued = store.compareAndTransition(
                    pushBackId, VirtualSessionState.RESPONDING, VirtualSessionState.PUSH_PENDING);
            if (queued.isEmpty()) return;
            VirtualSession q = queued.get();
            store.setPendingText(pushBackId, response.text());
            store.setPendingAlphabet(pushBackId, response.alphabet());
            // P1-4: handset busy behind another live dialog (classic shouldDeliverNow) —
            // hold PUSH_PENDING and retry instead of colliding on the S2 leg.
            if (niPushRetries != null
                    && store.findActiveByMsisdn(q.msisdn(), pushBackId).isPresent()) {
                niPushRetries.advance(pushBackId, true);
                cdrWrite(q, CdrPhase.S2_PUSH, "PUSH_DEFERRED",
                        "service=VirtualSessionBridge|busy|note=retry-armed|"
                                + CdrUssdSnippet.asUssdDetail(response.text()));
                return;
            }
            accessNi.requestNiPush(q, response.text(), true);
            cdrWrite(q, CdrPhase.S2_PUSH, "QUEUED",
                "service=VirtualSessionBridge|late AS reconcile|notify|"
                        + CdrUssdSnippet.asUssdDetail(response.text()));
            return;
        }
        // MAP leg died while parked and no abort was observed: nothing is deliverable. Retire
        // the claim rather than leaving the row stranded in RESPONDING.
        zombieDrop.incrementAndGet();
        Optional<VirtualSession> dead = store.compareAndTransition(
                pushBackId, VirtualSessionState.RESPONDING, VirtualSessionState.ZOMBIE);
        if (dead.isEmpty()) return;
        cdrWrite(dead.get(), CdrPhase.FAILED, "ZOMBIE", "AS response on dead MAP leg");
    }

    /**
     * HTTP-NI AS continue: the AS posted the next menu with the JSESSIONID Cookie after
     * a UE digit. The NI wire carries no generation (classic XML has none), so stamping
     * is meaningless here — the session's own CAS is the authority.
     *
     * <p>CAS {@code ACTIVE → RESPONDING} so a concurrent duplicate POST loses;
     * {@code AWAITING_AS} is tolerated once for rows forced there by the pre-fix shim.
     * Then release the digit claim and return to {@code ACTIVE} (still interactive).
     * No MAP emit here — {@code HttpServerSbb} routes the next Request after this.
     * No EWMA feed either: NI park dwell is AS+human think time, not AS latency
     * (EWMA stays telemetry for MO pull).
     *
     * @return {@code true} when this call owned the continue
     */
     public boolean onNiAsContinue(String correlationId, String text) {
        if (correlationId == null || correlationId.isBlank()) return false;
        Optional<VirtualSession> won = store.compareAndTransition(
                correlationId, VirtualSessionState.ACTIVE, VirtualSessionState.RESPONDING);
        if (won.isEmpty()) {
            won = store.compareAndTransition(
                    correlationId, VirtualSessionState.AWAITING_AS, VirtualSessionState.RESPONDING);
            if (won.isEmpty()) return false;
        }
        store.releaseMsDigitInFlight(correlationId);
        store.compareAndTransition(
                correlationId, VirtualSessionState.RESPONDING, VirtualSessionState.ACTIVE);
        VirtualSession s = store.get(correlationId).orElse(null);
        if (s == null) return true;
        cdrWrite(s, CdrPhase.S1_ACTIVE, AsAction.CONTINUE.name(),
                "service=VirtualSessionBridge|http-ni|asAction=CONTINUE"
                        + "|gen=" + s.generation()
                        + "|menuTurn=" + s.generation()
                        + "|" + CdrUssdSnippet.asUssdDetail(text)
                        + "|note=AS→UE");
        recordUserMenuState(s, AsAction.CONTINUE.name(), text);
        return true;
    }

    /**
     * Adaptive gate fired for {@code s}.
     *
     * @return {@code true} when this call actually expired the gate; {@code false} when the
     *         CAS was lost (an AS response got there first) so no dialog action was taken
     */
    public boolean onGateExpired(VirtualSession s) {
        if (s == null || s.state() != VirtualSessionState.AWAITING_AS) return false;
        boolean arm = config.bridgeEnabled() && s.adaptiveBridgeArm();
        // Hard-fail path must not replyAndEnd MO while MAP2MAP hop is still outstanding
        // (Brook: Abort/Reject is the hop terminal — hold until clearMap2mapHopOutstanding).
        // Stay-on-call (arm=true → BRIDGED async-wait) during hop is still allowed.
        if (!arm && s.map2mapHopOutstanding() && s.originationType() == OriginationType.MAP) {
            // P1-8: re-index out of the due head — returning false without moving the
            // deadline rewrites one MAP2MAP_MO_HOLD CDR per 100ms tick per held session.
            // Target = hop TTL expiry (the sweep clears the flag there); the CDR below
            // then fires at most once per defer window, not once per tick.
            long deferTo = System.currentTimeMillis() + hopTtlMs();
            store.deferGateDeadline(s.correlationId(), deferTo);
            LOG.warn("Gate hard-fail deferred — MAP2MAP hop outstanding corr={} deferTo={}",
                    s.correlationId(), deferTo);
            cdrWrite(s, CdrPhase.S1_ACTIVE, "MAP2MAP_MO_HOLD",
                    "service=VirtualSessionBridge|hopOutstanding|gate=no-bridge");
            return false;
        }
        // Re-load + CAS so concurrent ticks and AS responses do not double-bridge.
        Optional<VirtualSession> cas = store.compareAndTransition(
                s.correlationId(),
                VirtualSessionState.AWAITING_AS,
                arm ? VirtualSessionState.S1_RELEASED : VirtualSessionState.COMPLETED);
        if (cas.isEmpty()) return false;
        VirtualSession cur = cas.get();
        boolean mapPlane = cur.originationType() == OriginationType.MAP;
        RaCommandPort port = mapPlane ? ss7() : null;
        String jsession = lookupJsession(cur.correlationId());
        Long ewma = observedEwmaMs(cur);
        if (!arm) {
            if (mapPlane && cur.dialogAlive()) {
                MapDialogHelper.replyAndEnd(port, cur.dialogId(), cur.invokeId(),
                        config.asyncHardFailMessage());
                cur.setDialogAlive(false);
            }
            persist(cur);
            cdrWrite(cur, CdrPhase.FAILED, "GATE_NO_BRIDGE",
                    "service=VirtualSessionBridge|AdaptiveTimeout");
            stampGated(cur, jsession, GatedSessionMeta.REASON_GATE_NO_BRIDGE, ewma);
            return true;
        }
        bridgeCount.incrementAndGet();
        if (mapPlane && cur.dialogAlive()) {
            MapDialogHelper.replyAndEnd(port, cur.dialogId(), cur.invokeId(),
                    config.asyncWaitMessage());
            cur.setDialogAlive(false);
            // Single-field write: the CAS already published S1_RELEASED, and a full-row put
            // from this detached snapshot would revert a concurrent claim.
            store.setDialogAlive(cur.correlationId(), false);
        }
        cdrWrite(cur, CdrPhase.S1_RELEASED, "BRIDGED",
                "service=VirtualSessionBridge|AdaptiveTimeout asyncWait");
        stampGated(cur, jsession, GatedSessionMeta.REASON_BRIDGED, ewma);
        LOG.info("Bridging slow AS corr={} dialogId={} orig={} jsession={}",
                cur.correlationId(), cur.dialogId(), cur.originationType(), jsession);
        return true;
    }

    private String lookupJsession(String correlationId) {
        if (niHttpPark == null || correlationId == null || correlationId.isBlank()) {
            return null;
        }
        try {
            return niHttpPark.findByCorr(correlationId)
                    .map(ClassicNiHttpPark.ParkRecord::jsessionId)
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void stampGated(VirtualSession s, String jsession, String reason, Long ewma) {
        if (s == null) {
            return;
        }
        GatedSessionMeta meta = GatedSessionMeta.of(s, jsession, reason, ewma);
        if (gatedSessions != null) {
            try {
                gatedSessions.stamp(meta);
            } catch (RuntimeException e) {
                LOG.warn("GatedSessionRegistry stamp failed corr={}: {}", s.correlationId(), e.toString());
            }
        }
        if (gatedAsNotify != null) {
            try {
                gatedAsNotify.pushToAs(meta, s);
            } catch (RuntimeException e) {
                LOG.warn("Gated AS XML push failed corr={}: {}", s.correlationId(), e.toString());
            }
        }
    }

    public void onNetworkAbort(String dialogId) {
        Optional<VirtualSession> opt = store.byDialogId(dialogId);
        if (opt.isEmpty()) return;
        String corr = opt.get().correlationId();
        // Publish the dead leg atomically first, so a concurrent AS claim cannot reply
        // on a torn-down dialog even if it read the row before this snapshot was written.
        store.setDialogAlive(corr, false);
        // CAS-law: transitions via CAS only — never a detached-snapshot full put, which
        // would revert a concurrent claimForAsResponse winner back out of RESPONDING.
        // P1-7 (classic markAborted parity): teardown noise touches pre-bridge states
        // only. A bridged session (S1 released by us) keeps its state so the committed
        // S2 push still runs; the noise is logged, not acted on.
        Optional<VirtualSession> zombie = store.compareAndTransitionAny(corr,
                List.of(VirtualSessionState.AWAITING_AS,
                        VirtualSessionState.RESPONDING),
                VirtualSessionState.ZOMBIE);
        boolean won;
        if (zombie.isPresent()) {
            won = true;
            zombieDrop.incrementAndGet();
            cdrWrite(zombie.get(), CdrPhase.FAILED, "ZOMBIE", "network abort");
        } else {
            won = store.compareAndTransition(
                    corr, VirtualSessionState.ACTIVE, VirtualSessionState.ABORTED).isPresent();
        }
        if (won) {
            // P1-9: a parked AS HTTP must be answered now (ABORT), not after the gate.
            // No-op for MO sessions (never parked).
            abortHttpPark(corr);
            return;
        }
        Optional<VirtualSession> cur = store.get(corr);
        if (cur.isPresent()) {
            VirtualSessionState st = cur.get().state();
            if (st == VirtualSessionState.S1_RELEASED
                    || st == VirtualSessionState.PUSH_PENDING) {
                cdrWrite(cur.get(), CdrPhase.S1_RELEASED, "ABORT_AFTER_BRIDGE",
                        "service=VirtualSessionBridge|note=teardown-noise-kept-push");
            }
        }
    }

    /** Best-effort: settle a parked NI HTTP with ABORT when its MAP leg dies. */
    private void abortHttpPark(String correlationId) {
        if (niHttpPark == null || correlationId == null || correlationId.isBlank()) {
            return;
        }
        try {
            niHttpPark.abortParked(correlationId);
        } catch (RuntimeException e) {
            LOG.debug("NI park abort skipped corr={}: {}", correlationId, e.toString());
        }
    }

    /**
     * S2 outcome for a late push: MAP returnError on the push dialog (P1-5). The leg
     * is already dead — no endDialog, no abort. Retryable network refusals re-arm the
     * backoff; subscriber verdicts and exhausted retries go to the fallback and retire.
     *
     * @return slee detail fragment for the caller
     */
    public String onNiPushError(String correlationId, String errorName) {
        if (correlationId == null || correlationId.isBlank()) return "ni-push-error-no-corr";
        Optional<VirtualSession> opt = store.get(correlationId);
        if (opt.isEmpty() || opt.get().state() != VirtualSessionState.PUSH_PENDING) {
            return "ni-push-error-no-pending";
        }
        VirtualSession s = opt.get();
        cdrWrite(s, CdrPhase.FAILED, "MAP_RETURN_ERROR",
                "service=VirtualSessionBridge|leg=s2|error=" + errorName);
        if (NiPushRetryPolicy.isRetryable(errorName) && niPushRetries != null) {
            int attempt = niPushRetries.advance(correlationId, true);
            if (attempt <= NiPushRetryPolicy.MAX_ATTEMPTS) {
                cdrWrite(s, CdrPhase.S2_PUSH, "NI_RETRY_ARMED",
                        "service=VirtualSessionBridge|attempt=" + attempt
                                + "|error=" + errorName);
                return "ni-push-retry-armed attempt=" + attempt + " error=" + errorName;
            }
        }
        fallback(s, errorName == null ? "NI_PUSH_ERROR" : errorName);
        if (store.compareAndTransition(
                correlationId, VirtualSessionState.PUSH_PENDING,
                VirtualSessionState.FAILED).isPresent()) {
            store.remove(correlationId);
        }
        cancelNiRetry(correlationId);
        return "ni-push-failed error=" + errorName;
    }

    /**
     * S2 outcome for a late push: the Notify TC-END arrived (P1-5). Retire the row
     * the push kept pending since send.
     */
    public String onNiPushDelivered(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) return "ni-push-close-no-corr";
        Optional<VirtualSession> won = store.compareAndTransition(
                correlationId, VirtualSessionState.PUSH_PENDING, VirtualSessionState.COMPLETED);
        if (won.isEmpty()) {
            return "ni-push-close-lost";
        }
        cancelNiRetry(correlationId);
        store.remove(correlationId);
        cdrWrite(won.get(), CdrPhase.S2_PUSH, "NI_PUSH_OK",
                "service=VirtualSessionBridge|note=s2-close");
        return "ni-push-ok";
    }

    /** Scheduler-driven re-push of a due retry entry (P1-5). */
    public void retryNiPush(NiPushRetryRegistry.RetryEntry entry) {
        if (entry == null || entry.correlationId() == null
                || entry.correlationId().isBlank()) {
            return;
        }
        String corr = entry.correlationId();
        try {
            Optional<VirtualSession> opt = store.get(corr);
            if (opt.isEmpty() || opt.get().state() != VirtualSessionState.PUSH_PENDING) {
                cancelNiRetry(corr);
                return;
            }
            VirtualSession s = opt.get();
            if (niPushRetries != null
                    && store.findActiveByMsisdn(s.msisdn(), corr).isPresent()) {
                niPushRetries.advance(corr, entry.notifyOnly());
                cdrWrite(s, CdrPhase.S2_PUSH, "NI_RETRY_DEFERRED",
                        "service=VirtualSessionBridge|busy|attempt=" + entry.attempt());
                return;
            }
            accessNi.requestNiPush(s, s.pendingText(), entry.notifyOnly());
            cdrWrite(s, CdrPhase.S2_PUSH, "NI_RETRY",
                    "service=VirtualSessionBridge|attempt=" + entry.attempt() + "|"
                            + CdrUssdSnippet.asUssdDetail(s.pendingText()));
        } catch (Throwable t) {
            LOG.warn("NI push retry failed corr={}: {}", corr, t.toString());
        }
    }

    public void cancelNiRetry(String correlationId) {
        if (niPushRetries == null || correlationId == null || correlationId.isBlank()) {
            return;
        }
        try {
            niPushRetries.cancel(correlationId);
        } catch (RuntimeException e) {
            LOG.debug("NI retry cancel skipped corr={}: {}", correlationId, e.toString());
        }
    }

    private void fallback(VirtualSession session, String reason) {
        if (session == null) return;
        if (niPushFallback != null) {
            try {
                niPushFallback.fallback(session, reason);
                return;
            } catch (RuntimeException e) {
                LOG.warn("NI push fallback failed corr={}: {}", session.correlationId(),
                        e.toString());
            }
        }
        LOG.warn("NI push fallback (log/CDR only) corr={} reason={}",
                session.correlationId(), reason);
        cdrWrite(session, CdrPhase.FAILED, "NI_FALLBACK",
                "service=VirtualSessionBridge|reason=" + reason);
    }

    private void dropLate(String correlationId, AsResponse response) {
        zombieDrop.incrementAndGet();
        int wireGen = response == null ? -1 : response.generation();
        Optional<VirtualSession> opt = correlationId == null || correlationId.isBlank()
                ? Optional.empty() : store.get(correlationId);
        int sessionGen = opt.map(VirtualSession::generation).orElse(-1);
        String state = opt.map(s -> s.state() == null ? "NONE" : s.state().name()).orElse("NONE");
        String reason;
        if (opt.isEmpty()) {
            reason = "no-session";
        } else if (wireGen > 0 && sessionGen > 0 && wireGen != sessionGen) {
            reason = "genMismatch";
        } else {
            reason = "state";
        }
        String asSnip = response == null ? "" : CdrUssdSnippet.of(response.text());
        LOG.info("Drop late/zombie AS response corr={} wireGen={} sessionGen={} state={} reason={} asUssd={}",
                correlationId, wireGen, sessionGen, state, reason, asSnip);
        if (correlationId == null || correlationId.isBlank()) {
            return;
        }
        String detail = "service=VirtualSessionBridge|AS_DROP|reason=" + reason
                + "|wireGen=" + wireGen
                + "|sessionGen=" + sessionGen
                + "|state=" + state
                + (response == null || response.text() == null || response.text().isBlank()
                ? "" : "|" + CdrUssdSnippet.asUssdDetail(response.text()))
                + "|note=dropped-before-MAP";
        if (opt.isPresent()) {
            cdrWrite(opt.get(), CdrPhase.S1_ACTIVE, CdrStatuses.AS_DROP, detail);
        } else {
            cdr.write(correlationId, CdrPhase.S1_ACTIVE, null, null, CdrStatuses.AS_DROP, detail,
                    0, null, OriginationType.MAP.name(), null, null);
        }
    }

    /**
     * Feed the EWMA. When the caller cannot measure the round trip (HTTP/gRPC callback
     * ingress passes {@code latencyMs <= 0}) the sample is derived from the monotonic pull
     * start, so an NTP step cannot inject a nonsense sample.
     */
    private void recordLatency(VirtualSession s, long latencyMs) {
        long sample = latencyMs;
        if (sample <= 0) {
            if (s.pullStartedAtNanos() > 0) {
                sample = Math.max(1L, (System.nanoTime() - s.pullStartedAtNanos()) / 1_000_000L);
            } else if (s.pullStartedAtMs() > 0) {
                sample = System.currentTimeMillis() - s.pullStartedAtMs();
            }
        }
        if (sample > 0) {
            // Keep per-user + network EWMA for telemetry / observed_ewma_ms (not live gate).
            adaptive.recordLatency(s.networkId(), s.msisdn(), sample, config.dialogTimeoutMs());
        }
    }

    private void applyToLiveDialog(VirtualSession s, AsResponse response) {
        RaCommandPort port = ss7();
        AsAction action = response.action() == null ? AsAction.END : response.action();
        var alphabet = response.alphabet() == null
                ? et.restlink.ussdgw.api.UssdAlphabet.AUTO : response.alphabet();
        String corr = s.correlationId();
        // CAS-law: this claim owns RESPONDING — every emitting branch transitions by CAS
        // *before* touching MAP, so a lost race never double-replies. Snapshot fields
        // (dialogId/invokeId/text) are read-only inputs; row writes are CAS + single-field.
        // HTTP-NI continue from AS: HttpServerSbb re-routes NiPushRequestEvent — skip MAP
        // reply on the synthetic/parked dialog (MapNiPush owns the next UnstructuredSS-Request).
        boolean httpNi = niHttpPark != null && niHttpPark.isHttpNi(corr);
        if (s.originationType() == OriginationType.MAP && !httpNi) {
            // MAP2MAP: AS END/ABORT must wait for hop terminal (never end MO early).
            if (s.map2mapHopOutstanding()
                    && (action == AsAction.END || action == AsAction.ABORT)) {
                LOG.warn("AS {} deferred — MAP2MAP hop outstanding corr={}",
                        action, corr);
                if (store.compareAndTransition(
                        corr, VirtualSessionState.RESPONDING,
                        VirtualSessionState.AWAITING_AS).isEmpty()) {
                    return;
                }
                cdrWrite(store.get(corr).orElse(s), CdrPhase.S1_ACTIVE, "MAP2MAP_MO_HOLD",
                        "service=VirtualSessionBridge|hopOutstanding|asAction=" + action);
                return;
            }
            switch (action) {
                case CONTINUE -> {
                    if (store.compareAndTransition(
                            corr, VirtualSessionState.RESPONDING,
                            VirtualSessionState.ACTIVE).isEmpty()) {
                        return;
                    }
                    MapDialogHelper.replyContinue(port, s.dialogId(), s.invokeId(),
                            response.text(), alphabet);
                    // Do NOT bump generation here — classic oracle bumps only on MS input
                    // (MapUssdParentSbb.onUserContinue). Double-bump would skip AS turns.
                    store.releaseMsDigitInFlight(corr);
                }
                case ABORT -> {
                    if (store.compareAndTransition(
                            corr, VirtualSessionState.RESPONDING,
                            VirtualSessionState.ABORTED).isEmpty()) {
                        return;
                    }
                    MapDialogHelper.abort(port, s.dialogId());
                    store.setDialogAlive(corr, false);
                    store.clearMsDigitClaim(corr);
                    store.remove(corr);
                }
                case END -> {
                    if (store.compareAndTransition(
                            corr, VirtualSessionState.RESPONDING,
                            VirtualSessionState.COMPLETED).isEmpty()) {
                        return;
                    }
                    MapDialogHelper.replyAndEnd(port, s.dialogId(), s.invokeId(),
                            response.text(), alphabet);
                    store.setDialogAlive(corr, false);
                    store.clearMsDigitClaim(corr);
                    store.remove(corr);
                }
            }
        } else if (httpNi) {
            if (action == AsAction.ABORT) {
                if (store.compareAndTransition(
                        corr, VirtualSessionState.RESPONDING,
                        VirtualSessionState.ABORTED).isEmpty()) {
                    return;
                }
                store.setDialogAlive(corr, false);
                store.clearMsDigitClaim(corr);
                store.remove(corr);
            } else if (action == AsAction.END) {
                if (store.compareAndTransition(
                        corr, VirtualSessionState.RESPONDING,
                        VirtualSessionState.COMPLETED).isEmpty()) {
                    return;
                }
                store.setDialogAlive(corr, false);
                store.clearMsDigitClaim(corr);
                store.remove(corr);
            } else {
                if (store.compareAndTransition(
                        corr, VirtualSessionState.RESPONDING,
                        VirtualSessionState.ACTIVE).isEmpty()) {
                    return;
                }
                store.releaseMsDigitInFlight(corr);
            }
        } else {
            VirtualSessionState terminal = action == AsAction.ABORT
                    ? VirtualSessionState.ABORTED : VirtualSessionState.COMPLETED;
            if (store.compareAndTransition(
                    corr, VirtualSessionState.RESPONDING, terminal).isEmpty()) {
                return;
            }
            store.setDialogAlive(corr, false);
            store.clearMsDigitClaim(corr);
            store.remove(corr);
        }
        VirtualSession done = store.get(corr).orElse(s);
        // Status END/CONTINUE/ABORT = AS body applied toward UE (not hop-close).
        // END here means AS→UE final reply was received and forwarded — not MAP2MAP_HOP_CLOSE.
        CdrPhase phase = switch (action) {
            case CONTINUE -> CdrPhase.S1_ACTIVE;
            case ABORT -> CdrPhase.FAILED;
            case END -> CdrPhase.COMPLETED;
        };
        cdrWrite(done, phase, action.name(),
                "service=VirtualSessionBridge|"
                        + (httpNi ? "http-ni" : "sync")
                        + "|asAction=" + action.name()
                        + "|gen=" + done.generation()
                        + "|menuTurn=" + done.generation()
                        + "|" + CdrUssdSnippet.asUssdDetail(response.text())
                        + "|note=AS→UE");
        if (action == AsAction.CONTINUE || action == AsAction.END || action == AsAction.ABORT) {
            recordUserMenuState(done, action.name(), response.text());
        }
    }

    /** Best-effort ussdUser multimenu stamp after AS→UE CONTINUE/END/ABORT. Never breaks MAP. */
    private void recordUserMenuState(VirtualSession s, String asAction, String menuText) {
        if (s == null || userProfiles == null) {
            return;
        }
        try {
            Long ewma = observedEwmaMs(s);
            userProfiles.recordMenuState(s.msisdn(), new UssdUserProfileStore.MenuStateSnapshot(
                    s.correlationId(),
                    s.shortCode(),
                    s.generation(),
                    null,
                    menuText,
                    asAction,
                    s.dialogId(),
                    s.gateMs() > 0 ? s.gateMs() : null,
                    ewma,
                    s.networkId(),
                    s.tenantId()));
            LOG.info(
                    "bridge ussdUser menu-write corr={} msisdn={} asAction={} gen={} menu={}",
                    s.correlationId(),
                    AdaptiveTimeout.normalizeMsisdn(s.msisdn()),
                    asAction,
                    s.generation(),
                    CdrUssdSnippet.of(menuText));
        } catch (Throwable t) {
            LOG.info("bridge ussdUser menu-write FAILED corr={} reason={}",
                    s.correlationId(), t.toString());
        }
    }

    /** Write Profile row; remove when terminal (COMPLETED/ABORTED/FAILED/ZOMBIE). */
    private void persist(VirtualSession s) {
        if (s == null) return;
        if (s.state().terminal()) {
            store.put(s); // final snapshot
            store.remove(s.correlationId());
            return;
        }
        store.put(s);
    }

    private void cdrWrite(VirtualSession s, CdrPhase phase, String status, String detail) {
        cdr.write(s.correlationId(), phase, s.msisdn(), s.shortCode(), status, detail,
                s.networkId(), s.tenantId(), s.originationType().name(),
                s.gateMs() > 0 ? s.gateMs() : null,
                observedEwmaMs(s));
    }

    private Long observedEwmaMs(VirtualSession s) {
        if (adaptive == null || s == null) {
            return null;
        }
        double v = adaptive.observedLatencyMs(s.networkId(), s.msisdn());
        return v > 0d ? Math.round(v) : null;
    }

    public long bridgeCount() { return bridgeCount.get(); }
    public long recoverCount() { return recoverCount.get(); }
    public long zombieDrop() { return zombieDrop.get(); }

    /** Hop TTL for gate-defer re-indexing (P1-8); falls back when the registry is absent. */
    private long hopTtlMs() {
        try {
            if (pendingMap2Map != null) {
                long ttl = pendingMap2Map.ttlMs();
                if (ttl > 0) return ttl;
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return 30_000L;
    }
}
