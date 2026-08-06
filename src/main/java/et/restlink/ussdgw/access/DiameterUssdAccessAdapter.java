package et.restlink.ussdgw.access;

import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.service.DiameterApplyService;
import et.restlink.ussdgw.tenant.TenantGuard;

import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Diameter USSD access — MO via RA inbound / lab inject; NI live when peer ready.
 */
@ApplicationScoped
public class DiameterUssdAccessAdapter implements UssdAccessPort {
    private static final Logger LOG = LogManager.getLogger(DiameterUssdAccessAdapter.class);

    @Inject VirtualSessionStore store;
    @Inject VirtualSessionBridge bridge;
    @Inject CdrService cdr;
    @Inject UssdConfigService config;
    @Inject TenantGuard tenantGuard;
    @Inject DiameterApplyService diameterApply;

    @ConfigProperty(name = "ussd.diameter.destination-host")
    java.util.Optional<String> destHost;
    @ConfigProperty(name = "ussd.diameter.destination-realm", defaultValue = "restlink.local")
    String destRealm;

    private final AtomicLong moCount = new AtomicLong();
    private final AtomicLong niCount = new AtomicLong();
    private volatile Supplier<RaCommandPort> portOverride;

    @Override
    public OriginationType type() {
        return OriginationType.DIAMETER;
    }

    /** Unit tests: inject capturing Diameter port. */
    public void setDiameterPortSupplier(Supplier<RaCommandPort> supplier) {
        this.portOverride = supplier;
    }

    @Override
    public void requestNiPush(VirtualSession session, String text) {
        if (session == null) return;
        niCount.incrementAndGet();
        if (!config.diameterEnabled()) {
            StubAccessSupport.stubNiPush(session, text, cdr, null, "DIAMETER");
            return;
        }
        RaCommandPort port = resolvePort();
        boolean peerReady = diameterApply != null && diameterApply.peerReady();
        if (portOverride != null) {
            peerReady = port != null;
        }
        if (port == null || !peerReady) {
            LOG.info("Diameter NI stub corr={} (peerReady={})", session.correlationId(), peerReady);
            StubAccessSupport.stubNiPush(session, text, cdr, null, "DIAMETER");
            return;
        }
        try {
            String sessionId = "ussd-ni-" + (session.correlationId() == null
                    ? UUID.randomUUID() : session.correlationId());
            Map<Integer, String> avps = new LinkedHashMap<>();
            avps.put(DiameterUssdCodes.AVP_USER_NAME, session.msisdn());
            avps.put(DiameterUssdCodes.AVP_USSD_STRING, text == null ? "" : text);
            avps.put(DiameterUssdCodes.AVP_SERVICE_CODE, session.shortCode());
            avps.put(DiameterUssdCodes.AVP_CORRELATION, session.correlationId());
            String host = destHost == null ? "" : destHost.filter(s -> !s.isBlank()).orElse("");
            sendDiameter(port, new SendDiameterRequest(
                    sessionId,
                    DiameterUssdCodes.USSD_APP_ID,
                    DiameterUssdCodes.USSD_REQUEST,
                    host,
                    destRealm,
                    avps));
            cdr.write(session.correlationId(), CdrPhase.S2_PUSH, session.msisdn(),
                    session.shortCode(), "DIAMETER_SENT",
                    "NI textLen=" + (text == null ? 0 : text.length()),
                    session.networkId(), session.tenantId(),
                    session.originationType().name());
            LOG.info("Diameter NI sent corr={} dest={}", session.correlationId(), session.msisdn());
        } catch (RuntimeException e) {
            LOG.warn("Diameter NI fail corr={}: {}", session.correlationId(), e.toString());
            cdr.write(session.correlationId(), CdrPhase.S2_PUSH, session.msisdn(),
                    session.shortCode(), "DIAMETER_FAIL",
                    e.getClass().getSimpleName(),
                    session.networkId(), session.tenantId(),
                    session.originationType().name());
        }
    }

    protected void sendDiameter(RaCommandPort port, SendDiameterRequest cmd) {
        port.sendCommand(cmd);
    }

    private RaCommandPort resolvePort() {
        if (portOverride != null) {
            return portOverride.get();
        }
        return diameterApply == null ? null : diameterApply.endpoint();
    }

    @Override
    public VirtualSession acceptMoPull(UssdAccessSession access) {
        if (!config.diameterEnabled()) {
            LOG.warn("Diameter MO rejected — ussd.diameter.enabled=false");
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
