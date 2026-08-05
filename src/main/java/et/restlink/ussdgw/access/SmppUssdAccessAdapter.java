package et.restlink.ussdgw.access;

import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** SMPP USSD (service_op TLV) skeleton. */
@ApplicationScoped
public class SmppUssdAccessAdapter implements UssdAccessPort {
    private static final Logger LOG = LogManager.getLogger(SmppUssdAccessAdapter.class);

    @Inject VirtualSessionStore store;
    @Inject VirtualSessionBridge bridge;
    @Inject CdrService cdr;
    @Inject UssdConfigService config;

    private final AtomicLong moCount = new AtomicLong();
    private final AtomicLong niCount = new AtomicLong();

    @Override
    public OriginationType type() {
        return OriginationType.SMPP;
    }

    @Override
    public void requestNiPush(VirtualSession session, String text) {
        LOG.info("SMPP USSD NI stub corr={} (ussdOverSmpp={})",
                session == null ? null : session.correlationId(), config.smppUssdEnabled());
        StubAccessSupport.stubNiPush(session, text, cdr, niCount, "SMPP");
    }

    @Override
    public VirtualSession acceptMoPull(UssdAccessSession access) {
        if (!config.smppUssdEnabled()) {
            LOG.warn("SMPP USSD MO rejected — ussd.smpp.ussd.enabled=false");
            return null;
        }
        return StubAccessSupport.acceptMoPull(access, store, bridge, moCount);
    }

    public long moCount() { return moCount.get(); }
    public long niCount() { return niCount.get(); }
}
