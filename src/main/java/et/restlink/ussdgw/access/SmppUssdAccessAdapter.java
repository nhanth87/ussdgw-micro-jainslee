package et.restlink.ussdgw.access;

import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.codec.SmsTextCodec;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.ra.smpp.SmppEndpointRegistry;
import et.restlink.ussdgw.ra.smpp.SmppRaEndpoint;
import et.restlink.ussdgw.ra.smpp.command.SmppCommand;
import et.restlink.ussdgw.tenant.TenantGuard;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** SMPP USSD — MO lab + NI via SMSC client submit_sm when enabled. */
@ApplicationScoped
public class SmppUssdAccessAdapter implements UssdAccessPort {
    private static final Logger LOG = LogManager.getLogger(SmppUssdAccessAdapter.class);

    @Inject VirtualSessionStore store;
    @Inject VirtualSessionBridge bridge;
    @Inject CdrService cdr;
    @Inject UssdConfigService config;
    @Inject TenantGuard tenantGuard;
    @Inject SmppEndpointRegistry smppRegistry;

    private final AtomicLong moCount = new AtomicLong();
    private final AtomicLong niCount = new AtomicLong();

    @Override
    public OriginationType type() {
        return OriginationType.SMPP;
    }

    @Override
    public void requestNiPush(VirtualSession session, String text) {
        if (session == null) return;
        niCount.incrementAndGet();
        if (!config.smppUssdEnabled()) {
            LOG.info("SMPP USSD NI stub corr={} (ussdOverSmpp=false)", session.correlationId());
            StubAccessSupport.stubNiPush(session, text, cdr, null, "SMPP");
            return;
        }
        SmppRaEndpoint client = smppRegistry == null ? null : smppRegistry.anyClient();
        if (client == null) {
            LOG.warn("SMPP NI no client corr={}", session.correlationId());
            cdr.write(session.correlationId(), CdrPhase.S2_PUSH, session.msisdn(),
                    session.shortCode(), "NO_SMPP_CLIENT",
                    "NI textLen=" + (text == null ? 0 : text.length()),
                    session.networkId(), session.tenantId(),
                    session.originationType().name());
            return;
        }
        try {
            var encoded = SmsTextCodec.encode(
                    text == null ? "" : text, session.pendingAlphabet(), 1);
            byte[] tpUd = encoded.parts().isEmpty()
                    ? new byte[0] : encoded.parts().getFirst().tpUd();
            boolean udhi = !encoded.parts().isEmpty() && encoded.parts().getFirst().udhi();
            SmppCommand.SubmitSm cmd = SmppCommand.SubmitSm.text(
                    session.msisdn(), tpUd, udhi, encoded.dataCoding());
            sendSubmitSm(client, cmd);
            cdr.write(session.correlationId(), CdrPhase.S2_PUSH, session.msisdn(),
                    session.shortCode(), "SUBMITTED",
                    "SMPP submit_sm dcs=" + (encoded.dataCoding() & 0xFF),
                    session.networkId(), session.tenantId(),
                    session.originationType().name());
            LOG.info("SMPP NI submitted corr={} dest={}", session.correlationId(), session.msisdn());
        } catch (RuntimeException e) {
            LOG.warn("SMPP NI submit fail corr={}: {}", session.correlationId(), e.toString());
            cdr.write(session.correlationId(), CdrPhase.S2_PUSH, session.msisdn(),
                    session.shortCode(), "SUBMIT_FAIL",
                    e.getClass().getSimpleName(),
                    session.networkId(), session.tenantId(),
                    session.originationType().name());
        }
    }

    /** Hook for unit tests / future wrappers. */
    protected void sendSubmitSm(SmppRaEndpoint client, SmppCommand.SubmitSm cmd) {
        client.sendCommand(cmd);
    }

    @Override
    public VirtualSession acceptMoPull(UssdAccessSession access) {
        if (!config.smppUssdEnabled()) {
            LOG.warn("SMPP USSD MO rejected — ussd.smpp.ussd.enabled=false");
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
