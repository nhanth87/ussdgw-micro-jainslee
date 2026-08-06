package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.access.SipUssiAccessAdapter;
import et.restlink.ussdgw.access.UssdAccessSession;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.routing.ShortCodeRoutingService;
import et.restlink.ussdgw.service.SbbServices;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ra.sipservlet.command.SendResponse;
import com.microjainslee.ra.sipservlet.events.SipMessageEvent;

import java.util.UUID;

/**
 * Inbound SIP MESSAGE (USSI) → MO pull → AS router; 200 OK to peer when RA present.
 */
public final class SipUssiSbb implements Sbb, SleeEventHandler {
    private final SbbServices services;
    private final SipUssiAccessAdapter sip;

    @InjectRa(name = "sip-servlet-ra")
    private volatile RaCommandPort sipRa;

    public SipUssiSbb() { this(null, null); }
    public SipUssiSbb(SbbServices services, SipUssiAccessAdapter sip) {
        this.services = services;
        this.sip = sip;
    }

    private SbbServices svc() { return services != null ? services : SbbServices.get(); }
    private SipUssiAccessAdapter ussi() {
        return sip != null ? sip : svc().sipAccess();
    }

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (!(event instanceof SipMessageEvent msg)) return;
        SleeEventTrace.inSbb("SipUssiSbb", event, "from=" + msg.fromUri());
        String detail;
        try {
            detail = handle(msg);
        } catch (Throwable t) {
            detail = "error=" + t.getClass().getSimpleName();
        }
        SleeEventTrace.outSbb("SipUssiSbb", event, detail);
    }

    private String handle(SipMessageEvent msg) {
        if (!svc().config().sipEnabled()) {
            return "plane-off";
        }
        String body = msg.body() == null ? "" : msg.body().trim();
        String msisdn = extractUser(msg.fromUri());
        String sc = ShortCodeRoutingService.extractShortCode(body);
        String corr = UUID.randomUUID().toString();
        VirtualSession session = ussi().acceptMoPull(new UssdAccessSession(
                corr, msisdn, sc, 0, null, OriginationType.SIP, msg.callId()));
        if (session == null) {
            return "mo-rejected";
        }
        reply200(msg.callId());
        var ruleOpt = svc().routing().find(sc);
        if (ruleOpt.isEmpty()) {
            return "no-route sc=" + sc;
        }
        AsRequest asReq = new AsRequest(
                session.virtualSessionId(), session.correlationId(), session.requestId(),
                session.generation(), msisdn, sc, body.isBlank() ? sc : body, session.networkId());
        String route = svc().asPullRouter().route(ruleOpt.get(), asReq, session.correlationId());
        return "mo-ok " + route;
    }

    private void reply200(String callId) {
        RaCommandPort port = sipRa;
        if (port == null || callId == null) return;
        try {
            port.sendCommand(new SendResponse(callId, 200, "OK"));
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    private static String extractUser(String uri) {
        if (uri == null) return "";
        int colon = uri.indexOf(':');
        int at = uri.indexOf('@');
        if (colon >= 0 && at > colon + 1) {
            return uri.substring(colon + 1, at);
        }
        return uri;
    }
}
