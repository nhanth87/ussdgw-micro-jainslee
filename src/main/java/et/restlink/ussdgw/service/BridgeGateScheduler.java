package et.restlink.ussdgw.service;

import et.restlink.ussdgw.bridge.UssdSagaCoordinator;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.campaign.CampaignService;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.events.NiPushRequestEvent;
import et.restlink.ussdgw.hlr.HlrFaceService;

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
 * Adaptive-gate ticker + TTL reclaim for ussdTx profiles and pending SRI correlations —
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
    @Inject PendingSriRegistry pendingSri;
    @Inject HlrFaceService hlrFace;
    @Inject UssdSagaCoordinator saga;
    @Inject CdrService cdr;
    @Inject CampaignService campaigns;

    @ConfigProperty(name = "ussd.bridge.gate-tick-ms", defaultValue = "100")
    long gateTickMsProp;

    private final AtomicLong gateTicks = new AtomicLong();
    private final AtomicLong gateExpired = new AtomicLong();
    private final AtomicLong reclaimCount = new AtomicLong();
    private final AtomicLong sriExpired = new AtomicLong();
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
     * inbound dialog so it does not leak.
     */
    @Scheduled(every = "${ussd.pending.sweep-ms:5000}ms")
    void sweepPendingCorrelations() {
        long now = System.currentTimeMillis();
        for (NiPushRequestEvent ni : pendingSri.sweepExpired(now)) {
            sriExpired.incrementAndGet();
            cdr.write(ni.correlationId(), CdrPhase.FAILED, ni.msisdn(), null, "SRI_TIMEOUT", null);
            saga.onNiFailed(ni.correlationId(), "SRI_TIMEOUT");
            try {
                campaigns.onNiDone(ni.correlationId(), false, "SRI_TIMEOUT");
            } catch (RuntimeException ignored) {
                // campaign bookkeeping is best-effort; the saga already compensated
            }
        }
        hlrProxyExpired.addAndGet(hlrFace.expirePending(now));
    }

    /** Quarkus scheduler invocations of {@link #tickGates} since boot (proof the gate is alive). */
    public long gateTicks() { return gateTicks.get(); }
    public long gateExpired() { return gateExpired.get(); }
    public long reclaimCount() { return reclaimCount.get(); }
    public long sriExpired() { return sriExpired.get(); }
    public long hlrProxyExpired() { return hlrProxyExpired.get(); }
    public long configuredGateTickMs() { return Math.max(1L, gateTickMsProp); }
}
