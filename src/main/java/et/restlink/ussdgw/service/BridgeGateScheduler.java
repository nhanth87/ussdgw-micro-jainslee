package et.restlink.ussdgw.service;

import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;

import io.quarkus.scheduler.Scheduled;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Adaptive-gate ticker + ussdTx Profile TTL reclaim — NOT gRPC/HTTP response polling.
 */
@ApplicationScoped
public class BridgeGateScheduler {
    @Inject VirtualSessionStore store;
    @Inject VirtualSessionBridge bridge;

    @Scheduled(every = "0.2s")
    void tickGates() {
        long now = System.currentTimeMillis();
        for (VirtualSession s : store.awaitingPastDeadline(now)) {
            bridge.onGateExpired(s);
        }
    }

    @Scheduled(every = "30s")
    void reclaimExpiredTx() {
        store.reclaimExpired(System.currentTimeMillis());
    }
}
