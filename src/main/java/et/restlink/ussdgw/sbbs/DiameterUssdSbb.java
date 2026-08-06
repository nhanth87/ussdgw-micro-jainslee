package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.access.DiameterUssdAccessAdapter;
import et.restlink.ussdgw.access.DiameterUssdCodes;
import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.access.UssdAccessSession;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.routing.ShortCodeRoutingService;
import et.restlink.ussdgw.service.SbbServices;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Inbound Diameter USSD-Request → MO pull → AS router (same path as lab MO).
 */
public final class DiameterUssdSbb implements Sbb, SleeEventHandler {
    private final SbbServices services;
    private final DiameterUssdAccessAdapter diameter;

    public DiameterUssdSbb() { this(null, null); }
    public DiameterUssdSbb(SbbServices services, DiameterUssdAccessAdapter diameter) {
        this.services = services;
        this.diameter = diameter;
    }

    private SbbServices svc() { return services != null ? services : SbbServices.get(); }
    private DiameterUssdAccessAdapter diam() {
        return diameter != null ? diameter : svc().diameterAccess();
    }

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (!(event instanceof DiameterRequestEvent req)) return;
        SleeEventTrace.inSbb("DiameterUssdSbb", event,
                "app=" + req.applicationId() + " cmd=" + req.commandCode());
        String detail;
        try {
            detail = handle(req);
        } catch (Throwable t) {
            detail = "error=" + t.getClass().getSimpleName();
        }
        SleeEventTrace.outSbb("DiameterUssdSbb", event, detail);
    }

    private String handle(DiameterRequestEvent req) {
        if (req.applicationId() != DiameterUssdCodes.USSD_APP_ID
                || req.commandCode() != DiameterUssdCodes.USSD_REQUEST) {
            return "ignored";
        }
        if (!svc().config().diameterEnabled()) {
            return "plane-off";
        }
        Map<Integer, String> avps = req.avps() == null ? Map.of() : req.avps();
        String msisdn = avps.getOrDefault(DiameterUssdCodes.AVP_USER_NAME, "");
        String ussd = avps.getOrDefault(DiameterUssdCodes.AVP_USSD_STRING, "");
        String sc = avps.getOrDefault(DiameterUssdCodes.AVP_SERVICE_CODE, "");
        if (sc.isBlank()) {
            sc = ShortCodeRoutingService.extractShortCode(ussd);
        }
        String corr = avps.getOrDefault(DiameterUssdCodes.AVP_CORRELATION, "");
        if (corr.isBlank()) {
            corr = UUID.randomUUID().toString();
        }
        VirtualSession session = diam().acceptMoPull(new UssdAccessSession(
                corr, msisdn, sc, 0, null, OriginationType.DIAMETER, req.sessionId()));
        if (session == null) {
            return "mo-rejected";
        }
        var ruleOpt = svc().routing().find(sc);
        if (ruleOpt.isEmpty()) {
            return "no-route sc=" + sc;
        }
        AsRequest asReq = new AsRequest(
                session.virtualSessionId(), session.correlationId(), session.requestId(),
                session.generation(), msisdn, sc, ussd.isBlank() ? sc : ussd, session.networkId());
        String route = svc().asPullRouter().route(ruleOpt.get(), asReq, session.correlationId());
        return "mo-ok " + route;
    }
}
