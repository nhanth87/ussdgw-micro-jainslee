package et.restlink.ussdgw.service;

import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;

import io.quarkus.scheduler.Scheduled;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Adaptive-gate ticker + ussdTx Profile TTL reclaim — NOT gRPC/HTTP response polling.
 */
@ApplicationScoped
public class BridgeGateScheduler {
    @Inject VirtualSessionStore store;
    @Inject VirtualSessionBridge bridge;

    private final AtomicLong gateExpired = new AtomicLong();
    private final AtomicLong reclaimCount = new AtomicLong();

    @Scheduled(every = "0.2s")
    void tickGates() {
        long now = System.currentTimeMillis();
        for (VirtualSession s : store.awaitingPastDeadline(now)) {
            gateExpired.incrementAndGet();
            bridge.onGateExpired(s);
        }
    }

    @Scheduled(every = "30s")
    void reclaimExpiredTx() {
        int n = store.reclaimExpired(System.currentTimeMillis());
        if (n > 0) {
            reclaimCount.addAndGet(n);
        }
    }

    public long gateExpired() { return gateExpired.get(); }
    public long reclaimCount() { return reclaimCount.get(); }
}
