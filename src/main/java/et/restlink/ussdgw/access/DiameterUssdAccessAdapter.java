package et.restlink.ussdgw.access;

import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.tenant.TenantGuard;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Diameter USSD skeleton — MO lab inject + NI STUB_QUEUED until ra-diameter AVPs land. */
@ApplicationScoped
public class DiameterUssdAccessAdapter implements UssdAccessPort {
    private static final Logger LOG = LogManager.getLogger(DiameterUssdAccessAdapter.class);

    @Inject VirtualSessionStore store;
    @Inject VirtualSessionBridge bridge;
    @Inject CdrService cdr;
    @Inject UssdConfigService config;
    @Inject TenantGuard tenantGuard;

    private final AtomicLong moCount = new AtomicLong();
    private final AtomicLong niCount = new AtomicLong();

    @Override
    public OriginationType type() {
        return OriginationType.DIAMETER;
    }

    @Override
    public void requestNiPush(VirtualSession session, String text) {
        LOG.info("Diameter NI stub corr={} (plane enabled={})",
                session == null ? null : session.correlationId(), config.diameterEnabled());
        StubAccessSupport.stubNiPush(session, text, cdr, niCount, "DIAMETER");
    }

    @Override
    public VirtualSession acceptMoPull(UssdAccessSession access) {
        if (!config.diameterEnabled()) {
            LOG.warn("Diameter MO rejected — ussd.diameter.enabled=false");
            return null;
        }
        return StubAccessSupport.acceptMoPull(access, store, bridge, moCount, tenantGuard);
    }

    public long moCount() { return moCount.get(); }
    public long niCount() { return niCount.get(); }
}
