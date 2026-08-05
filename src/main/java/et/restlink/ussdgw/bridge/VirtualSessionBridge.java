package et.restlink.ussdgw.bridge;

import et.restlink.ussdgw.access.AccessNiDispatcher;
import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;
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

    public void startAwaitingAs(VirtualSession session) {
        long gate = adaptive.effectiveGateMs(
                session.networkId(), config.asyncGateTimeoutMs(), config.dialogTimeoutMs());
        session.setGateMs(gate);
        session.setPullStartedAtMs(System.currentTimeMillis());
        session.setGateDeadlineMs(session.pullStartedAtMs() + gate);
        session.setState(VirtualSessionState.AWAITING_AS);
        persist(session);
        cdrWrite(session, CdrPhase.S1_ACTIVE, "AWAITING_AS", "gateMs=" + gate);
    }

    public void onAsResponse(AsResponse response, long latencyMs) {
        Optional<VirtualSession> opt = store.acceptAsResponse(
                response.correlationId(), response.generation());
        if (opt.isEmpty()) {
            zombieDrop.incrementAndGet();
            LOG.info("Drop late/zombie AS response corr={} gen={}",
                    response.correlationId(), response.generation());
            return;
        }
        VirtualSession s = opt.get();
        // Feed EWMA only for content responses (not ASYNC_ACK). When caller
        // passes latencyMs<=0 (HTTP/gRPC callback ingress), derive from pull start.
        if (!response.async()) {
            long sample = latencyMs;
            if (sample <= 0 && s.pullStartedAtMs() > 0) {
                sample = System.currentTimeMillis() - s.pullStartedAtMs();
            }
            if (sample > 0) {
                adaptive.recordLatency(s.networkId(), sample);
            }
        }
        if (response.async()) {
            return;
        }
        if (s.dialogAlive() && s.state() == VirtualSessionState.AWAITING_AS) {
            applyToLiveDialog(s, response);
            return;
        }
        if (s.state() == VirtualSessionState.S1_RELEASED
                || (!s.dialogAlive() && s.state() == VirtualSessionState.AWAITING_AS
                    && s.originationType() != OriginationType.MAP)) {
            if (s.originationType() != OriginationType.MAP
                    && s.state() == VirtualSessionState.AWAITING_AS) {
                s.setState(VirtualSessionState.S1_RELEASED);
            }
            recoverCount.incrementAndGet();
            s.setPendingText(response.text());
            s.setPendingAlphabet(response.alphabet());
            s.setState(VirtualSessionState.PUSH_PENDING);
            persist(s);
            accessNi.requestNiPush(s, response.text());
            cdrWrite(s, CdrPhase.S2_PUSH, "QUEUED", "late AS reconcile");
        }
    }

    public void onGateExpired(VirtualSession s) {
        if (s.state() != VirtualSessionState.AWAITING_AS) return;
        // Re-load + CAS so concurrent ticks do not double-bridge
        Optional<VirtualSession> cas = store.compareAndTransition(
                s.correlationId(),
                VirtualSessionState.AWAITING_AS,
                config.bridgeEnabled() && s.adaptiveBridgeArm()
                        ? VirtualSessionState.S1_RELEASED
                        : VirtualSessionState.COMPLETED);
        if (cas.isEmpty()) return;
        VirtualSession cur = cas.get();
        boolean mapPlane = cur.originationType() == OriginationType.MAP;
        RaCommandPort port = mapPlane ? ss7() : null;
        boolean doBridge = cur.state() == VirtualSessionState.S1_RELEASED;
        if (!doBridge) {
            if (mapPlane && cur.dialogAlive()) {
                MapDialogHelper.replyAndEnd(port, cur.dialogId(), cur.invokeId(),
                        config.asyncHardFailMessage());
                cur.setDialogAlive(false);
            }
            persist(cur);
            cdrWrite(cur, CdrPhase.FAILED, "GATE_NO_BRIDGE", null);
            return;
        }
        bridgeCount.incrementAndGet();
        if (mapPlane && cur.dialogAlive()) {
            MapDialogHelper.replyAndEnd(port, cur.dialogId(), cur.invokeId(),
                    config.asyncWaitMessage());
            cur.setDialogAlive(false);
        }
        persist(cur);
        cdrWrite(cur, CdrPhase.S1_RELEASED, "BRIDGED", "asyncWait");
        LOG.info("Bridging slow AS corr={} dialogId={} orig={}",
                cur.correlationId(), cur.dialogId(), cur.originationType());
    }

    public void onNetworkAbort(String dialogId) {
        store.byDialogId(dialogId).ifPresent(s -> {
            s.setDialogAlive(false);
            if (s.state() == VirtualSessionState.AWAITING_AS
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

    private void applyToLiveDialog(VirtualSession s, AsResponse response) {
        RaCommandPort port = ss7();
        AsAction action = response.action() == null ? AsAction.END : response.action();
        var alphabet = response.alphabet() == null
                ? et.restlink.ussdgw.api.UssdAlphabet.AUTO : response.alphabet();
        if (s.originationType() == OriginationType.MAP) {
            switch (action) {
                case CONTINUE -> {
                    MapDialogHelper.replyContinue(port, s.dialogId(), s.invokeId(),
                            response.text(), alphabet);
                    // Do NOT bump generation here — classic oracle bumps only on MS input
                    // (MapUssdParentSbb.onUserContinue). Double-bump would skip AS turns.
                    s.setState(VirtualSessionState.ACTIVE);
                }
                case ABORT -> {
                    MapDialogHelper.abort(port, s.dialogId());
                    s.setDialogAlive(false);
                    s.setState(VirtualSessionState.ABORTED);
                }
                case END -> {
                    MapDialogHelper.replyAndEnd(port, s.dialogId(), s.invokeId(),
                            response.text(), alphabet);
                    s.setDialogAlive(false);
                    s.setState(VirtualSessionState.COMPLETED);
                }
            }
        } else {
            s.setDialogAlive(false);
            s.setState(action == AsAction.ABORT
                    ? VirtualSessionState.ABORTED : VirtualSessionState.COMPLETED);
        }
        persist(s);
        CdrPhase phase = switch (action) {
            case CONTINUE -> CdrPhase.S1_ACTIVE;
            case ABORT -> CdrPhase.FAILED;
            case END -> CdrPhase.COMPLETED;
        };
        cdrWrite(s, phase, action.name(), "sync");
    }

    /** Write Profile row; remove when terminal (COMPLETED/ABORTED/ZOMBIE). */
    private void persist(VirtualSession s) {
        if (s == null) return;
        VirtualSessionState st = s.state();
        if (st == VirtualSessionState.COMPLETED
                || st == VirtualSessionState.ABORTED
                || st == VirtualSessionState.FAILED
                || st == VirtualSessionState.ZOMBIE) {
            store.put(s); // final snapshot
            store.remove(s.correlationId());
            return;
        }
        store.put(s);
    }

    private void cdrWrite(VirtualSession s, CdrPhase phase, String status, String detail) {
        cdr.write(s.correlationId(), phase, s.msisdn(), s.shortCode(), status, detail,
                s.networkId(), s.tenantId(), s.originationType().name());
    }

    public long bridgeCount() { return bridgeCount.get(); }
    public long recoverCount() { return recoverCount.get(); }
    public long zombieDrop() { return zombieDrop.get(); }
}
