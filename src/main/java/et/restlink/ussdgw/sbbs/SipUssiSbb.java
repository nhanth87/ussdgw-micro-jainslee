package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.access.SipUssiAccessAdapter;
import et.restlink.ussdgw.access.UssdAccessSession;
import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.api.UssdAlphabet;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionState;
import et.restlink.ussdgw.events.NiPushRequestEvent;
import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.persist.SipTrunkEntity;
import et.restlink.ussdgw.routing.ShortCodeRoutingService;
import et.restlink.ussdgw.service.SbbServices;
import et.restlink.ussdgw.sip.SipTrunkService;
import et.restlink.ussdgw.sip.SipUssdBodyCodec;
import et.restlink.ussdgw.tenant.TenantGuard;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ra.sipservlet.command.SendResponse;
import com.microjainslee.ra.sipservlet.events.SipMessageEvent;

import java.util.Optional;
import java.util.UUID;

/**
 * Inbound SIP MESSAGE:
 * <ul>
 *   <li>AS trunk pull reply (menu/text) → {@code VirtualSessionBridge.onAsResponse}</li>
 *   <li>AS trunk NI push (SDP / explicit NI) → MAP NI to UE</li>
 *   <li>dial-shaped MO → AS pull router (HTTP/gRPC/SIP trunk)</li>
 * </ul>
 *
 * <p><b>Auth note:</b> inbound NI is fail-closed on a matched <em>enabled</em> trunk ({@code peer_host}
 * vs From-host). That host match is <em>not</em> SIP digest / TLS client auth — digest and
 * {@code allowed_source_cidrs} are deferred. Listen alone ≠ LIVE trunk.
 *
 * <p>SIP pull reply correlation is <b>best-effort V1</b>: Call-ID {@code pull-{corr}}, wire body
 * fields, then MSISDN/{@code AWAITING_AS} lookup.
 */
public final class SipUssiSbb implements Sbb, SleeEventHandler {
    private final SbbServices services;
    private final SipUssiAccessAdapter sip;

    /** Test hook: when set, replaces container.routeEvent for NI push. */
    volatile java.util.function.BiConsumer<NiPushRequestEvent, String> niRouteOverride;

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
        String peerHost = extractHost(msg.fromUri());
        Optional<SipTrunkEntity> trunk = Optional.empty();
        SipTrunkService trunks = svc().sipTrunks();
        if (trunks != null) {
            trunk = trunks.matchPeer(peerHost);
        }
        String inboundMode = trunk.map(t -> t.inboundBody).orElse("BODY");
        boolean niHdr = false; // RA event has no headers map yet — SDP/body classification
        SipUssdBodyCodec.Decoded decoded = SipUssdBodyCodec.decode(
                msg.contentType(), msg.body(), niHdr, inboundMode);

        if (decoded.kind() == SipUssdBodyCodec.InboundKind.MO_PULL) {
            return handleMoPull(msg, decoded);
        }

        // Trunk-matched non-dial: try AS pull reply before NI (best-effort V1 correlation).
        // Explicit NI still yields to a matching parked pull session.
        if (trunk.isPresent()
                && decoded.kind() != SipUssdBodyCodec.InboundKind.UNKNOWN) {
            Optional<String> pull = tryAsPullReply(msg, decoded, trunk.get());
            if (pull.isPresent()) {
                return pull.get();
            }
        }

        if (decoded.kind() == SipUssdBodyCodec.InboundKind.NI_PUSH) {
            // NI only with trunk after pull correlation misses.
            if (trunk.isEmpty()) {
                reply200(msg.callId());
                return "ni-no-trunk peer=" + peerHost;
            }
            return handleNiPush(msg, decoded, trunk.get());
        }
        reply200(msg.callId());
        return "ignored-empty";
    }

    /**
     * Correlate inbound MESSAGE as an AS pull response. Does not fire {@link NiPushRequestEvent}.
     */
    Optional<String> tryAsPullReply(SipMessageEvent msg, SipUssdBodyCodec.Decoded decoded,
                                    SipTrunkEntity trunk) {
        if (msg == null || decoded == null || trunk == null) {
            return Optional.empty();
        }
        String corr = resolvePullCorrelation(msg, decoded, trunk);
        if (corr == null || corr.isBlank()) {
            return Optional.empty();
        }
        Optional<VirtualSession> parked = parkedPullSession(corr);
        if (parked.isEmpty()) {
            return Optional.empty();
        }
        VirtualSession session = parked.get();
        AsResponse resp = decodePullReplyBody(msg, decoded, trunk, session);
        if (resp == null || resp.resolvePushBackId() == null) {
            return Optional.empty();
        }
        reply200(msg.callId());
        svc().bridge().onAsResponse(resp, -1L);
        return Optional.of("as-pull-reply corr=" + corr);
    }

    /** Only AWAITING_AS / S1_RELEASED — never ACTIVE NI or RESPONDING rows. */
    private Optional<VirtualSession> parkedPullSession(String corr) {
        if (corr == null || corr.isBlank()) {
            return Optional.empty();
        }
        Optional<VirtualSession> opt = svc().store().get(corr);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        VirtualSessionState st = opt.get().state();
        if (st != VirtualSessionState.AWAITING_AS && st != VirtualSessionState.S1_RELEASED) {
            return Optional.empty();
        }
        return opt;
    }

    private String resolvePullCorrelation(SipMessageEvent msg, SipUssdBodyCodec.Decoded decoded,
                                          SipTrunkEntity trunk) {
        String fromCallId = corrFromPullCallId(msg.callId());
        if (fromCallId != null && parkedPullSession(fromCallId).isPresent()) {
            return fromCallId;
        }
        AsResponse wireProbe = probeWireCorr(msg, trunk, fromCallId);
        if (wireProbe != null) {
            String fromBody = wireProbe.resolvePushBackId();
            if (fromBody != null && !fromBody.isBlank()
                    && parkedPullSession(fromBody.trim()).isPresent()) {
                return fromBody.trim();
            }
        }
        String msisdn = resolveMsisdnHint(msg, decoded);
        if (msisdn != null && !msisdn.isBlank()) {
            return svc().store()
                    .findAwaitingAsByMsisdn(msisdn, trunk.tenantId)
                    .map(VirtualSession::correlationId)
                    .orElse(null);
        }
        return null;
    }

    private AsResponse decodePullReplyBody(SipMessageEvent msg, SipUssdBodyCodec.Decoded decoded,
                                           SipTrunkEntity trunk, VirtualSession session) {
        String corr = session.correlationId();
        String body = msg.body() == null ? "" : msg.body();
        String ct = msg.contentType() == null ? "" : msg.contentType().toLowerCase();
        AsWireFacade facade = svc().wireFacade();
        AsHttpWireFormat fmt = resolveReplyFormat(ct, body, trunk.tenantId);
        AsResponse resp;
        if (facade != null && (fmt == AsHttpWireFormat.JSON || fmt == AsHttpWireFormat.XML)
                && looksLikeWireBody(ct, body)) {
            try {
                resp = facade.decodePullResponse(body, fmt, corr);
            } catch (RuntimeException ex) {
                // Soft free-text menu reply
                resp = plainTextReply(corr, session.generation(),
                        decoded.ussdText() == null ? body : decoded.ussdText());
            }
        } else {
            String text = decoded.ussdText() == null || decoded.ussdText().isBlank()
                    ? body.trim() : decoded.ussdText();
            resp = plainTextReply(corr, session.generation(), text);
        }
        resp = resp.stampedToSessionGeneration(session.generation());
        if (resp.correlationId() == null || resp.correlationId().isBlank()) {
            return new AsResponse(corr, resp.requestId() == null ? corr : resp.requestId(),
                    resp.generation(), resp.text(), resp.action(), resp.async(), resp.alphabet(),
                    resp.sessionId(), resp.virtualBridgeId(), resp.adaptiveTimeoutMs());
        }
        return resp;
    }

    private AsResponse probeWireCorr(SipMessageEvent msg, SipTrunkEntity trunk, String fallback) {
        AsWireFacade facade = svc().wireFacade();
        if (facade == null || msg.body() == null || msg.body().isBlank()) {
            return null;
        }
        String ct = msg.contentType() == null ? "" : msg.contentType().toLowerCase();
        String body = msg.body();
        if (!looksLikeWireBody(ct, body)) {
            return null;
        }
        AsHttpWireFormat fmt = resolveReplyFormat(ct, body, trunk.tenantId);
        try {
            return facade.decodePullResponse(body, fmt, fallback);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private AsHttpWireFormat resolveReplyFormat(String ct, String body, String tenantId) {
        if (ct.contains("json") || (body != null && body.trim().startsWith("{"))) {
            return AsHttpWireFormat.JSON;
        }
        if (ct.contains("xml") || (body != null && body.trim().startsWith("<"))) {
            return AsHttpWireFormat.XML;
        }
        if (svc().wireFormatResolver() != null) {
            return svc().wireFormatResolver().resolve(tenantId);
        }
        return AsHttpWireFormat.XML;
    }

    private static boolean looksLikeWireBody(String ct, String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String t = body.trim();
        if (ct != null && (ct.contains("json") || ct.contains("xml"))) {
            return true;
        }
        return t.startsWith("{") || t.startsWith("<");
    }

    private static AsResponse plainTextReply(String corr, int generation, String text) {
        return new AsResponse(corr, corr, generation <= 0 ? 1 : generation,
                text == null ? "" : text, AsAction.CONTINUE, false);
    }

    /** {@code pull-{corr}} Call-ID from {@link AsPullRouter#routeSip}. */
    static String corrFromPullCallId(String callId) {
        if (callId == null || callId.isBlank()) {
            return null;
        }
        String c = callId.trim();
        if (c.regionMatches(true, 0, "pull-", 0, 5) && c.length() > 5) {
            return c.substring(5);
        }
        return null;
    }

    private static String resolveMsisdnHint(SipMessageEvent msg, SipUssdBodyCodec.Decoded decoded) {
        if (decoded.msisdnHint() != null && !decoded.msisdnHint().isBlank()) {
            return SipUssdBodyCodec.normalizeMsisdn(decoded.msisdnHint()).orElse(null);
        }
        Optional<String> to = SipUssdBodyCodec.normalizeMsisdn(extractUser(msg.toUri()));
        if (to.isPresent()) {
            return to.get();
        }
        return SipUssdBodyCodec.normalizeMsisdn(extractUser(msg.fromUri())).orElse(null);
    }

    private String handleNiPush(SipMessageEvent msg, SipUssdBodyCodec.Decoded decoded,
                                SipTrunkEntity trunk) {
        if (trunk == null || !trunk.enabled) {
            reply200(msg.callId());
            return "ni-no-trunk";
        }
        String rawMsisdn = decoded.msisdnHint();
        if (rawMsisdn == null || rawMsisdn.isBlank()) {
            rawMsisdn = extractUser(msg.toUri());
            if (rawMsisdn.isBlank()) {
                rawMsisdn = extractUser(msg.fromUri());
            }
        }
        Optional<String> msisdnOpt = SipUssdBodyCodec.normalizeMsisdn(rawMsisdn);
        if (msisdnOpt.isEmpty()) {
            reply200(msg.callId());
            return "ni-bad-msisdn";
        }
        String msisdn = msisdnOpt.get();
        String text = decoded.ussdText() == null ? "" : decoded.ussdText();
        String tenantId = trunk.tenantId;
        // Always admit — blank tenantId uses TenantGuard global/default bucket (HTTP NI parity).
        TenantGuard.Decision admit = svc().tenantGuard().admit(tenantId);
        if (!admit.allowed()) {
            reply200(msg.callId());
            return "ni-tenant-reject reason=" + admit.reason();
        }
        int networkId = admit.tenant() != null
                ? admit.tenant().networkId
                : svc().config().httpNiDefaultNetworkId();
        String corr = UUID.randomUUID().toString();
        VirtualSession session = new VirtualSession(
                UUID.randomUUID().toString(), corr, UUID.randomUUID().toString(),
                msisdn, networkId, corr, "");
        session.setOriginationType(OriginationType.MAP);
        session.setState(VirtualSessionState.ACTIVE);
        session.setPendingText(text);
        session.setDialogAlive(true);
        session.setTenantId(tenantId);
        svc().store().put(session);
        reply200(msg.callId());
        try {
            routeNiPushEvent(
                    new NiPushRequestEvent(corr, msisdn, text, networkId, UssdAlphabet.AUTO),
                    "sip-ni-" + corr);
        } catch (Throwable ex) {
            svc().store().remove(corr);
            return "ni-route-fail";
        }
        return "ni-push msisdn=" + msisdn;
    }

    /** Package-visible for unit tests (route failure cleanup). */
    void routeNiPushEvent(NiPushRequestEvent event, String activityName) {
        var override = niRouteOverride;
        if (override != null) {
            override.accept(event, activityName);
            return;
        }
        svc().container().routeEvent(
                event, svc().container().createActivityContext(activityName));
    }

    private String handleMoPull(SipMessageEvent msg, SipUssdBodyCodec.Decoded decoded) {
        String body = decoded.ussdText() == null ? "" : decoded.ussdText().trim();
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

    private static String extractHost(String uri) {
        if (uri == null) return "";
        int at = uri.indexOf('@');
        if (at < 0 || at + 1 >= uri.length()) {
            return "";
        }
        String hostPort = uri.substring(at + 1);
        int semi = hostPort.indexOf(';');
        if (semi > 0) {
            hostPort = hostPort.substring(0, semi);
        }
        int colon = hostPort.indexOf(':');
        if (colon > 0) {
            return hostPort.substring(0, colon);
        }
        return hostPort;
    }
}
