package et.restlink.ussdgw.access;

import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.routing.ShortCodeRoutingService;
import et.restlink.ussdgw.service.AsPullRouter;
import et.restlink.ussdgw.tenant.TenantGuard;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.UUID;

/**
 * Lab / stub MO: create session, arm adaptive bridge, startAwaitingAs, route AS pull.
 */
@ApplicationScoped
public class LabMoService {
    @Inject ShortCodeRoutingService routing;
    @Inject AsPullRouter asPullRouter;
    @Inject VirtualSessionStore store;
    @Inject VirtualSessionBridge bridge;
    @Inject UssdConfigService config;
    @Inject TenantGuard tenantGuard;
    @Inject DiameterUssdAccessAdapter diameter;
    @Inject SmppUssdAccessAdapter smpp;
    @Inject SipUssiAccessAdapter sip;

    public record Result(VirtualSession session, String routeDetail) {}

    public Result start(OriginationType plane, String msisdn, String shortCode, String ussd,
                        String tenantId, int networkId) {
        if (plane == null || plane == OriginationType.MAP) {
            throw new IllegalArgumentException("lab MO plane must be DIAMETER, SMPP, or SIP");
        }
        ensurePlaneEnabled(plane);
        String sc = shortCode == null ? "" : shortCode.trim();
        if (sc.isEmpty()) {
            sc = ShortCodeRoutingService.extractShortCode(ussd);
        }
        Optional<ShortCodeRule> ruleOpt = routing.find(sc);
        if (ruleOpt.isEmpty()) {
            throw new IllegalArgumentException("no route for shortCode=" + sc);
        }
        ShortCodeRule rule = ruleOpt.get();
        String tid = blank(tenantId) != null ? tenantId.trim() : rule.tenantId();
        TenantGuard.Decision admit = tenantGuard.admit(tid);
        if (!admit.allowed()) {
            throw new IllegalStateException("tenant reject: " + admit.reason());
        }
        int net = networkId;
        if (net == 0) {
            net = rule.networkId();
        }
        if (net == 0 && admit.tenant() != null) {
            net = admit.tenant().networkId;
        }
        String ussdText = ussd == null || ussd.isBlank() ? sc : ussd.trim();
        String corr = UUID.randomUUID().toString();
        String reqId = UUID.randomUUID().toString();
        String dialog = plane.name().toLowerCase() + "-" + corr;
        VirtualSession session = new VirtualSession(
                UUID.randomUUID().toString(), corr, reqId, msisdn, net, dialog, sc);
        session.setTenantId(tid);
        session.setOriginationType(plane);
        session.setDialogAlive(false);
        session.setAdaptiveBridgeArm(rule.ruleType() == RuleType.HTTP
                ? config.httpClientBridgeEnabled()
                : config.grpcClientBridgeEnabled());
        store.put(session);
        bridge.startAwaitingAs(session);
        bumpMo(plane);
        AsRequest asReq = new AsRequest(
                session.virtualSessionId(), corr, reqId, session.generation(),
                msisdn, sc, ussdText, net);
        String detail = asPullRouter.route(rule, asReq, corr);
        return new Result(session, detail);
    }

    private void ensurePlaneEnabled(OriginationType plane) {
        boolean on = switch (plane) {
            case DIAMETER -> config.diameterEnabled();
            case SMPP -> config.smppUssdEnabled();
            case SIP -> config.sipEnabled();
            case MAP -> false;
        };
        if (!on) {
            throw new IllegalStateException(plane + " plane disabled");
        }
    }

    private void bumpMo(OriginationType plane) {
        switch (plane) {
            case DIAMETER -> diameter.recordMo();
            case SMPP -> smpp.recordMo();
            case SIP -> sip.recordMo();
            case MAP -> { }
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
