package et.restlink.ussdgw.service;

import et.restlink.ussdgw.bridge.UssdSagaCoordinator;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionState;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.campaign.CampaignService;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.cdr.Map2MapCdr;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.events.Map2MapRequestEvent;
import et.restlink.ussdgw.events.NiPushRequestEvent;
import et.restlink.ussdgw.hlr.HlrFaceService;
import et.restlink.ussdgw.telemetry.Map2MapTelemetry;

import com.microjainslee.api.RaCommandPort;

import io.quarkus.scheduler.Scheduled;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Adaptive-gate ticker + TTL reclaim for ussdTx profiles and pending SRI / MAP2MAP correlations —
 * NOT gRPC/HTTP response polling.
 *
 * <p>Arms automatically on Quarkus boot via {@code @Scheduled} — no admin Start and no
 * {@code ussd.bridge.enabled} gate on the ticker itself (that flag only chooses BRIDGE vs
 * hard-fail when a due session expires).
 */
@ApplicationScoped
public class BridgeGateScheduler {
    private static final Logger LOG = LogManager.getLogger(BridgeGateScheduler.class);

    @Inject VirtualSessionStore store;
    @Inject VirtualSessionBridge bridge;
    @Inject AdaptiveTimeout adaptive;
    @Inject PendingSriRegistry pendingSri;
    @Inject PendingMap2MapRegistry pendingMap2Map;
    @Inject HlrFaceService hlrFace;
    @Inject UssdSagaCoordinator saga;
    @Inject CdrService cdr;
    @Inject CampaignService campaigns;
    @Inject UssdConfigService config;
    @Inject Map2MapTelemetry map2MapTelemetry;

    @ConfigProperty(name = "ussd.bridge.gate-tick-ms", defaultValue = "100")
    long gateTickMsProp;

    private final AtomicLong gateTicks = new AtomicLong();
    private final AtomicLong gateExpired = new AtomicLong();
    private final AtomicLong reclaimCount = new AtomicLong();
    private final AtomicLong sriExpired = new AtomicLong();
    private final AtomicLong map2mapExpired = new AtomicLong();
    private final AtomicLong hlrProxyExpired = new AtomicLong();
    private final AtomicBoolean firstTickLogged = new AtomicBoolean();

    @PostConstruct
    void armOnBoot() {
        LOG.info("BridgeGateScheduler armed: gate-tick={}ms (Quarkus @Scheduled, ConcurrentExecution.SKIP)",
                Math.max(1L, gateTickMsProp));
    }

    /**
     * One session must never be able to stall every other parked dialog: the store can throw
     * transiently (row removed mid-tick, ProfileFacility briefly unavailable) and the due list
     * comes back in a stable order, so an unguarded throw would put the same session first on
     * every tick and no gate would ever fire again. Each session is therefore isolated, and
     * ticks never overlap.
     */
    @Scheduled(every = "${ussd.bridge.gate-tick-ms:100}ms",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tickGates() {
        long n = gateTicks.incrementAndGet();
        if (firstTickLogged.compareAndSet(false, true)) {
            LOG.info("BridgeGateScheduler first gate tick (scheduler alive)");
        }
        List<VirtualSession> due;
        try {
            due = store.awaitingPastDeadline(System.currentTimeMillis());
        } catch (Throwable t) {
            LOG.warn("gate tick: cannot list due sessions: {}", t.toString());
            return;
        }
        for (VirtualSession s : due) {
            try {
                if (bridge.onGateExpired(s)) {
                    gateExpired.incrementAndGet();
                    // Hop-phase gate: pending MAP2MAP still awaiting SRI/USSD while UE got async-wait.
                    if (s != null && map2MapTelemetry != null && pendingMap2Map != null) {
                        String out = PendingMap2MapRegistry.outboundCorr(s.correlationId());
                        var pending = pendingMap2Map.peek(out);
                        if (pending.isPresent()) {
                            map2MapTelemetry.gatedDuringHop();
                            try {
                                Long ewma = null;
                                if (adaptive != null) {
                                    double v = adaptive.observedLatencyMs(s.networkId());
                                    if (v > 0d) {
                                        ewma = Math.round(v);
                                    }
                                }
                                // VirtualSessionBridge already stamped BRIDGED; this row marks
                                // MAP2MAP hop-phase specifically (async-wait + gated XML path).
                                cdr.write(s.correlationId(), CdrPhase.S1_RELEASED, s.msisdn(),
                                        s.shortCode(), Map2MapCdr.GATED_HOP,
                                        Map2MapCdr.detail(pending.get().req(),
                                                "service=BridgeGateScheduler",
                                                "phase=hop-gate",
                                                "reason=BRIDGED"),
                                        s.networkId(), s.tenantId(), "MAP",
                                        Map2MapCdr.gateMs(s), ewma);
                            } catch (Throwable ignored) { }
                        }
                    }
                }
            } catch (Throwable t) {
                LOG.warn("gate tick failed corr={}: {}",
                        s == null ? null : s.correlationId(), t.toString());
            }
        }
        // Quiet heartbeat so Digicom ops can prove the ticker without inventing traffic.
        if (n > 0 && n % 6000 == 0) {
            LOG.info("BridgeGateScheduler heartbeat ticks={} expired={} reclaim={}",
                    n, gateExpired.get(), reclaimCount.get());
        }
    }

    @Scheduled(every = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void reclaimExpiredTx() {
        try {
            int n = store.reclaimExpired(System.currentTimeMillis());
            if (n > 0) {
                reclaimCount.addAndGet(n);
            }
        } catch (Throwable t) {
            LOG.warn("ussdTx TTL reclaim failed: {}", t.toString());
        }
    }

    /**
     * A silent HLR must not pin a correlation forever. An expired NI query fails its saga exactly
     * like a MAP SRI error would ({@code SRI_FAIL} path); an expired HLR-face query aborts the
     * inbound dialog so it does not leak. MAP2MAP hop TTL ends the inbound MO with hard-fail text.
     */
    @Scheduled(every = "${ussd.pending.sweep-ms:5000}ms")
    void sweepPendingCorrelations() {
        long now = System.currentTimeMillis();
        for (NiPushRequestEvent ni : pendingSri.sweepExpired(now)) {
            sriExpired.incrementAndGet();
            Long ewma = null;
            if (adaptive != null) {
                double v = adaptive.observedLatencyMs(ni.networkId());
                if (v > 0d) {
                    ewma = Math.round(v);
                }
            }
            cdr.write(ni.correlationId(), CdrPhase.FAILED, ni.msisdn(), null, "SRI_TIMEOUT",
                    "service=BridgeGateScheduler", ni.networkId(), null, "MAP", null, ewma);
            saga.onNiFailed(ni.correlationId(), "SRI_TIMEOUT");
            try {
                campaigns.onNiDone(ni.correlationId(), false, "SRI_TIMEOUT");
            } catch (RuntimeException ignored) {
                // campaign bookkeeping is best-effort; the saga already compensated
            }
        }
        for (Map2MapRequestEvent req : pendingMap2Map.sweepExpired(now)) {
            map2mapExpired.incrementAndGet();
            VirtualSession session = null;
            try {
                session = store.get(req.correlationId()).orElse(null);
            } catch (Throwable ignored) { }
            // Gate may already have released the UE (async-wait + gated XML). Drop pending
            // without a second hard-fail MAP reply — AS late path may still be in flight.
            boolean alreadyBridged = session != null && (
                    session.state() == VirtualSessionState.S1_RELEASED
                            || session.state() == VirtualSessionState.PUSH_PENDING
                            || session.state() == VirtualSessionState.RESPONDING
                            || !session.dialogAlive()
                            || session.state().terminal());
            Long gateMs = Map2MapCdr.gateMs(session);
            Long ewma = null;
            if (adaptive != null) {
                double v = adaptive.observedLatencyMs(req.networkId());
                if (v > 0d) {
                    ewma = Math.round(v);
                }
            }
            cdr.write(req.correlationId(), CdrPhase.FAILED, req.msisdn(), req.shortCode(),
                    alreadyBridged ? Map2MapCdr.TIMEOUT_AFTER_BRIDGE : Map2MapCdr.TIMEOUT,
                    Map2MapCdr.detail(req, "service=BridgeGateScheduler",
                            alreadyBridged ? "phase=after-bridge" : "phase=hop-ttl"),
                    req.networkId(), req.tenantId(), "MAP", gateMs, ewma);
            if (map2MapTelemetry != null) {
                if (alreadyBridged) {
                    map2MapTelemetry.timeoutAfterBridge();
                } else {
                    map2MapTelemetry.hopTimeout();
                }
            }
            if (alreadyBridged) {
                continue;
            }
            RaCommandPort ss7 = pendingMap2Map.ss7();
            try {
                MapDialogHelper.replyAndEnd(ss7, req.inboundDialogId(), req.inboundInvokeId(),
                        hardFailMessage());
            } catch (Throwable t) {
                LOG.warn("MAP2MAP TTL end inbound failed corr={}: {}", req.correlationId(), t.toString());
            }
            try {
                store.get(req.correlationId()).ifPresent(s -> {
                    s.setDialogAlive(false);
                    store.put(s);
                });
            } catch (Throwable ignored) { }
        }
        hlrProxyExpired.addAndGet(hlrFace.expirePending(now));
    }

    private String hardFailMessage() {
        try {
            String msg = config == null ? null : config.asyncHardFailMessage();
            if (msg != null && !msg.isBlank()) return msg;
        } catch (Throwable ignored) { }
        return "Service temporarily unavailable. Please try again.";
    }

    /** Quarkus scheduler invocations of {@link #tickGates} since boot (proof the gate is alive). */
    public long gateTicks() { return gateTicks.get(); }
    public long gateExpired() { return gateExpired.get(); }
    public long reclaimCount() { return reclaimCount.get(); }
    public long sriExpired() { return sriExpired.get(); }
    public long map2mapExpired() { return map2mapExpired.get(); }
    public long hlrProxyExpired() { return hlrProxyExpired.get(); }
    public long configuredGateTickMs() { return Math.max(1L, gateTickMsProp); }
}
