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

/** SIP/USSI (TS 24.390) skeleton — MESSAGE ingress later via ra-sip-servlet. */
@ApplicationScoped
public class SipUssiAccessAdapter implements UssdAccessPort {
    private static final Logger LOG = LogManager.getLogger(SipUssiAccessAdapter.class);

    @Inject VirtualSessionStore store;
    @Inject VirtualSessionBridge bridge;
    @Inject CdrService cdr;
    @Inject UssdConfigService config;
    @Inject TenantGuard tenantGuard;

    private final AtomicLong moCount = new AtomicLong();
    private final AtomicLong niCount = new AtomicLong();

    @Override
    public OriginationType type() {
        return OriginationType.SIP;
    }

    @Override
    public void requestNiPush(VirtualSession session, String text) {
        LOG.info("SIP USSI NI stub corr={} (plane enabled={})",
                session == null ? null : session.correlationId(), config.sipEnabled());
        StubAccessSupport.stubNiPush(session, text, cdr, niCount, "SIP");
    }

    @Override
    public VirtualSession acceptMoPull(UssdAccessSession access) {
        if (!config.sipEnabled()) {
            LOG.warn("SIP USSI MO rejected — ussd.sip.enabled=false");
            return null;
        }
        return StubAccessSupport.acceptMoPull(access, store, bridge, moCount, tenantGuard,
                config.httpClientBridgeEnabled());
    }

    public long moCount() { return moCount.get(); }
    public long niCount() { return niCount.get(); }

    public void recordMo() { moCount.incrementAndGet(); }
    public void recordNi() { niCount.incrementAndGet(); }
}
