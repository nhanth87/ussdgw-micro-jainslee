package et.restlink.ussdgw.access;

import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.service.SipApplyService;
import et.restlink.ussdgw.tenant.TenantGuard;

import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.sipservlet.command.SendMessage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * SIP/USSI (TS 24.390) — MO via MESSAGE ingress; NI live when RA active.
 */
@ApplicationScoped
public class SipUssiAccessAdapter implements UssdAccessPort {
    private static final Logger LOG = LogManager.getLogger(SipUssiAccessAdapter.class);

    @Inject VirtualSessionStore store;
    @Inject VirtualSessionBridge bridge;
    @Inject CdrService cdr;
    @Inject UssdConfigService config;
    @Inject TenantGuard tenantGuard;
    @Inject SipApplyService sipApply;

    @ConfigProperty(name = "ussd.sip.request-uri-template",
            defaultValue = "sip:{msisdn}@ussd.restlink.local")
    String requestUriTemplate;

    private final AtomicLong moCount = new AtomicLong();
    private final AtomicLong niCount = new AtomicLong();
    private volatile Supplier<RaCommandPort> portOverride;

    @Override
    public OriginationType type() {
        return OriginationType.SIP;
    }

    public void setSipPortSupplier(Supplier<RaCommandPort> supplier) {
        this.portOverride = supplier;
    }

    @Override
    public void requestNiPush(VirtualSession session, String text) {
        if (session == null) return;
        niCount.incrementAndGet();
        if (!config.sipEnabled()) {
            StubAccessSupport.stubNiPush(session, text, cdr, null, "SIP");
            return;
        }
        RaCommandPort port = resolvePort();
        boolean live = sipApply != null && sipApply.raActive();
        if (portOverride != null) {
            live = port != null;
        }
        if (port == null || !live) {
            LOG.info("SIP USSI NI stub corr={} (live={})", session.correlationId(), live);
            StubAccessSupport.stubNiPush(session, text, cdr, null, "SIP");
            return;
        }
        try {
            String callId = "ussi-" + (session.correlationId() == null
                    ? UUID.randomUUID() : session.correlationId());
            String toUri = requestUriTemplate.replace("{msisdn}",
                    session.msisdn() == null ? "" : session.msisdn());
            String fromUri = sipApply == null ? "sip:ussdgw@restlink.local" : sipApply.fromUri();
            sendMessage(port, new SendMessage(
                    callId, fromUri, toUri,
                    "application/vnd.3gpp.ussd+xml",
                    text == null ? "" : text));
            cdr.write(session.correlationId(), CdrPhase.S2_PUSH, session.msisdn(),
                    session.shortCode(), "SIP_SENT",
                    "NI textLen=" + (text == null ? 0 : text.length()),
                    session.networkId(), session.tenantId(),
                    session.originationType().name());
            LOG.info("SIP USSI NI sent corr={} to={}", session.correlationId(), toUri);
        } catch (RuntimeException e) {
            LOG.warn("SIP USSI NI fail corr={}: {}", session.correlationId(), e.toString());
            cdr.write(session.correlationId(), CdrPhase.S2_PUSH, session.msisdn(),
                    session.shortCode(), "SIP_FAIL",
                    e.getClass().getSimpleName(),
                    session.networkId(), session.tenantId(),
                    session.originationType().name());
        }
    }

    protected void sendMessage(RaCommandPort port, SendMessage cmd) {
        port.sendCommand(cmd);
    }

    private RaCommandPort resolvePort() {
        if (portOverride != null) {
            return portOverride.get();
        }
        return sipApply == null ? null : sipApply.endpoint();
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
