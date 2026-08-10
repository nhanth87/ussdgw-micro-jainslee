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
            s.setPendingText(response.text());
            s.setPendingAlphabet(response.alphabet());
            s.setState(VirtualSessionState.PUSH_PENDING);
            persist(s);
            accessNi.requestNiPush(s, response.text());
            cdrWrite(s, CdrPhase.S2_PUSH, "QUEUED",
                "service=VirtualSessionBridge|late AS reconcile|"
                        + CdrUssdSnippet.asUssdDetail(response.text()));
            return;
        }
        // MAP leg died while parked and no abort was observed: nothing is deliverable. Retire
        // the claim rather than leaving the row stranded in RESPONDING.
        zombieDrop.incrementAndGet();
        s.setState(VirtualSessionState.ZOMBIE);
        persist(s);
        cdrWrite(s, CdrPhase.FAILED, "ZOMBIE", "AS response on dead MAP leg");
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
            LOG.warn("Gate hard-fail deferred — MAP2MAP hop outstanding corr={}",
                    s.correlationId());
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
        store.byDialogId(dialogId).ifPresent(s -> {
            // Publish the dead leg atomically first, so a concurrent AS claim cannot reply
            // on a torn-down dialog even if it read the row before this snapshot was written.
            store.setDialogAlive(s.correlationId(), false);
            s.setDialogAlive(false);
            if (s.state() == VirtualSessionState.AWAITING_AS
                    || s.state() == VirtualSessionState.RESPONDING
                    || s.state() == VirtualSessionState.S1_RELEASED
                    || s.state() == VirtualSessionState.PUSH_PENDING) {
                s.setState(VirtualSessionState.ZOMBIE);
                zombieDrop.incrementAndGet();
                persist(s);
                cdrWrite(s, CdrPhase.FAILED, "ZOMBIE", "network abort");
            } else {
                s.setState(VirtualSessionState.ABORTED);
                persist(s);
            }
        });
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
        // HTTP-NI continue from AS: HttpServerSbb re-routes NiPushRequestEvent — skip MAP
        // reply on the synthetic/parked dialog (MapNiPush owns the next UnstructuredSS-Request).
        boolean httpNi = niHttpPark != null && niHttpPark.isHttpNi(s.correlationId());
        if (s.originationType() == OriginationType.MAP && !httpNi) {
            // MAP2MAP: AS END/ABORT must wait for hop terminal (never end MO early).
            if (s.map2mapHopOutstanding()
                    && (action == AsAction.END || action == AsAction.ABORT)) {
                LOG.warn("AS {} deferred — MAP2MAP hop outstanding corr={}",
                        action, s.correlationId());
                s.setState(VirtualSessionState.AWAITING_AS);
                persist(s);
                cdrWrite(s, CdrPhase.S1_ACTIVE, "MAP2MAP_MO_HOLD",
                        "service=VirtualSessionBridge|hopOutstanding|asAction=" + action);
                return;
            }
            switch (action) {
                case CONTINUE -> {
                    MapDialogHelper.replyContinue(port, s.dialogId(), s.invokeId(),
                            response.text(), alphabet);
                    // Do NOT bump generation here — classic oracle bumps only on MS input
                    // (MapUssdParentSbb.onUserContinue). Double-bump would skip AS turns.
                    s.setState(VirtualSessionState.ACTIVE);
                    store.releaseMsDigitInFlight(s.correlationId());
                }
                case ABORT -> {
                    MapDialogHelper.abort(port, s.dialogId());
                    s.setDialogAlive(false);
                    s.setState(VirtualSessionState.ABORTED);
                    store.clearMsDigitClaim(s.correlationId());
                }
                case END -> {
                    MapDialogHelper.replyAndEnd(port, s.dialogId(), s.invokeId(),
                            response.text(), alphabet);
                    s.setDialogAlive(false);
                    s.setState(VirtualSessionState.COMPLETED);
                    store.clearMsDigitClaim(s.correlationId());
                }
            }
        } else if (httpNi) {
            if (action == AsAction.ABORT) {
                s.setDialogAlive(false);
                s.setState(VirtualSessionState.ABORTED);
                store.clearMsDigitClaim(s.correlationId());
            } else if (action == AsAction.END) {
                s.setDialogAlive(false);
                s.setState(VirtualSessionState.COMPLETED);
                store.clearMsDigitClaim(s.correlationId());
            } else {
                s.setState(VirtualSessionState.ACTIVE);
                store.releaseMsDigitInFlight(s.correlationId());
            }
        } else {
            s.setDialogAlive(false);
            s.setState(action == AsAction.ABORT
                    ? VirtualSessionState.ABORTED : VirtualSessionState.COMPLETED);
            store.clearMsDigitClaim(s.correlationId());
        }
        persist(s);
        // Status END/CONTINUE/ABORT = AS body applied toward UE (not hop-close).
        // END here means AS→UE final reply was received and forwarded — not MAP2MAP_HOP_CLOSE.
        CdrPhase phase = switch (action) {
            case CONTINUE -> CdrPhase.S1_ACTIVE;
            case ABORT -> CdrPhase.FAILED;
            case END -> CdrPhase.COMPLETED;
        };
        cdrWrite(s, phase, action.name(),
                "service=VirtualSessionBridge|"
                        + (httpNi ? "http-ni" : "sync")
                        + "|asAction=" + action.name()
                        + "|gen=" + s.generation()
                        + "|menuTurn=" + s.generation()
                        + "|" + CdrUssdSnippet.asUssdDetail(response.text())
                        + "|note=AS→UE");
        if (action == AsAction.CONTINUE || action == AsAction.END || action == AsAction.ABORT) {
            recordUserMenuState(s, action.name(), response.text());
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
}
