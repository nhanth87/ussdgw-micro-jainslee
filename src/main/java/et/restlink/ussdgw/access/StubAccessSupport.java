package et.restlink.ussdgw.access;

import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared MO-pull + stub NI helpers for Diameter / SMPP / SIP skeletons.
 */
final class StubAccessSupport {
    private StubAccessSupport() {}

    static VirtualSession acceptMoPull(
            UssdAccessSession access,
            VirtualSessionStore store,
            VirtualSessionBridge bridge,
            AtomicLong moCount) {
        if (access == null || store == null || bridge == null) return null;
        String corr = access.correlationId().isBlank()
                ? UUID.randomUUID().toString() : access.correlationId();
        String dialog = access.dialogHandle().isBlank()
                ? access.originationType().name().toLowerCase() + "-" + corr
                : access.dialogHandle();
        VirtualSession session = new VirtualSession(
                UUID.randomUUID().toString(), corr, UUID.randomUUID().toString(),
                access.msisdn(), access.networkId(), dialog, access.shortCode());
        session.setTenantId(access.tenantId());
        session.setOriginationType(access.originationType());
        session.setDialogAlive(false); // no live MAP dialog on stub bearers
        session.setAdaptiveBridgeArm(true);
        store.put(session);
        bridge.startAwaitingAs(session);
        if (moCount != null) moCount.incrementAndGet();
        return session;
    }

    static void stubNiPush(VirtualSession session, String text, CdrService cdr, AtomicLong niCount,
                           String plane) {
        if (session == null) return;
        if (niCount != null) niCount.incrementAndGet();
        if (cdr != null) {
            cdr.write(session.correlationId(), CdrPhase.S2_PUSH, session.msisdn(),
                    session.shortCode(), "STUB_QUEUED",
                    plane + " NI textLen=" + (text == null ? 0 : text.length()),
                    session.networkId(), session.tenantId(),
                    session.originationType().name());
        }
    }
}
