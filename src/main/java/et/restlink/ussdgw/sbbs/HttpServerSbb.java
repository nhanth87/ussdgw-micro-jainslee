package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.admin.AdminHttpHandler;
import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.api.UssdAlphabet;
import et.restlink.ussdgw.api.classic.ClassicNiHttpPark;
import et.restlink.ussdgw.api.classic.ClassicNiIngress;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionState;
import et.restlink.ussdgw.events.NiPushRequestEvent;
import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.service.SbbServices;
import et.restlink.ussdgw.tenant.CallbackAuthService;
import et.restlink.ussdgw.tenant.TenantGuard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ra.httpserver.command.HttpServerCommand;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** HTTP admin + AS async callback + classic NI sync ingress. */
public final class HttpServerSbb implements Sbb, SleeEventHandler {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long LAB_ECHO_DELAY_MS = 50L;

    private final SbbServices services;

    @InjectRa(name = "http-server-ra")
    private volatile RaCommandPort http;

    public HttpServerSbb() { this(null); }
    public HttpServerSbb(SbbServices services) { this.services = services; }
    private SbbServices svc() { return services != null ? services : SbbServices.get(); }

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (!(event instanceof HttpWebRequestEvent req)) return;
        SleeEventTrace.inSbb("HttpServerSbb", event, req.getMethod() + " " + req.getPath());
        String detail;
        try {
            svc().niHttpPark().bindHttp(() -> http);
            detail = handle(req);
        } catch (Throwable t) {
            detail = "error=" + t.getClass().getSimpleName();
            replyText(req.getSessionId(), 500, "internal error");
        }
        SleeEventTrace.outSbb("HttpServerSbb", event, detail);
    }

    private String handle(HttpWebRequestEvent req) throws Exception {
        String path = req.getPath() == null ? "/" : req.getPath();
        String callbackPath = svc().config().httpCallbackPath();
        if (callbackPath == null || callbackPath.isBlank()) callbackPath = "/as/callback";
        String niPath = svc().config().httpNiPath();
        if (niPath == null || niPath.isBlank()) niPath = "/ussd";

        if (pathStartsWith(path, callbackPath) && "POST".equalsIgnoreCase(req.getMethod())) {
            return handleCallback(req);
        }
        if (pathStartsWith(path, niPath) && "POST".equalsIgnoreCase(req.getMethod())) {
            return handleNi(req);
        }
        Optional<AdminHttpHandler.HttpReply> reply = svc().adminHttp().tryHandle(
                req.getMethod(), path, req.getHeaders(), req.getQueryParams(), req.getBody());
        if (reply.isPresent()) {
            replyBytes(req.getSessionId(), reply.get());
            return "admin " + path;
        }
        replyJson(req.getSessionId(), 404, Map.of("error", "not found"));
        return "404";
    }

    private String handleCallback(HttpWebRequestEvent req) throws Exception {
        if (!svc().config().httpServerEnabled()) {
            replyJson(req.getSessionId(), 503, Map.of("error", "http callback server disabled"));
            return "as-callback-disabled";
        }
        String body = req.getBody() == null ? "" : req.getBody();
        AsHttpWireFormat format = detectWireFormat(contentType(req), body);
        AsWireFacade facade = svc().wireFacade();
        AsResponse resp = facade.decodeCallback(body, format);
        String pushBack = resp.resolvePushBackId();
        var auth = svc().callbackAuth().authorizeCallback(pushBack, req.getHeaders());
        if (auth != et.restlink.ussdgw.tenant.CallbackAuthService.Result.OK) {
            if (format == AsHttpWireFormat.XML) {
                replyEx(req.getSessionId(), 401, format.contentType(),
                        facade.encodeNiResponse(pushBack, "unauthorized", AsAction.ABORT, false, format),
                        Map.of());
            } else {
                replyJson(req.getSessionId(), 401, Map.of("error", "unauthorized"));
            }
            return "as-callback-401";
        }
        svc().bridge().onAsResponse(resp, -1);
        if (format == AsHttpWireFormat.XML) {
            String ack = facade.encodeCallbackAck(
                    new AsResponse(resp.correlationId(), resp.requestId(), resp.generation(),
                            "", AsAction.END, false));
            replyEx(req.getSessionId(), 202, format.contentType(), ack, Map.of());
        } else {
            replyJson(req.getSessionId(), 202, Map.of("accepted", true));
        }
        return "as-callback-" + format.name().toLowerCase();
    }

    private String handleNi(HttpWebRequestEvent req) {
        if (!svc().config().httpServerEnabled()) {
            replyJson(req.getSessionId(), 503, Map.of("error", "http ni server disabled"));
            return "ni-disabled";
        }
        String body = req.getBody() == null ? "" : req.getBody();
        AsHttpWireFormat format = detectWireFormat(contentType(req), body);
        ClassicNiIngress ingress = svc().wireFacade().decodeNiRequest(body, format);

        // An unauthenticated POST here would trigger a real UnstructuredSS-Request to any MSISDN.
        CallbackAuthService.NiAuth auth = svc().callbackAuth().authorizeNi(
                req.getHeaders(), svc().config().httpNiAuthRequired());
        if (!auth.ok()) {
            replyNiError(req, format, 401, "unauthorized");
            return "ni-401";
        }
        TenantGuard.Decision admit = svc().tenantGuard().admit(auth.tenantId());
        if (!admit.allowed()) {
            boolean rateLimited = admit.reason() == TenantGuard.Reason.RATE_LIMITED;
            replyNiError(req, format, rateLimited ? 429 : 403,
                    rateLimited ? "rate limited" : "tenant unavailable");
            return "ni-tenant-reject reason=" + admit.reason();
        }

        String jsession = resolveJsession(req);
        if (jsession != null && !jsession.isBlank()) {
            return handleNiContinue(req, ingress, format, jsession.trim());
        }
        return handleNiFirst(req, ingress, format, auth);
    }

    /**
     * networkId for this push, in classic's order of authority: the {@code networkId} the AS put on
     * the dialog first (classic read {@code xmlMAPDialog.getNetworkId()}), then the authenticated
     * tenant's network, then the configured default. Never an implicit 0.
     */
    private int resolveNiNetworkId(ClassicNiIngress ingress, CallbackAuthService.NiAuth auth) {
        if (ingress != null && ingress.networkId() != null) {
            return ingress.networkId();
        }
        if (auth != null && auth.networkId() != null) {
            return auth.networkId();
        }
        return svc().config().httpNiDefaultNetworkId();
    }

    private void replyNiError(HttpWebRequestEvent req, AsHttpWireFormat format,
                              int status, String message) {
        if (format == AsHttpWireFormat.XML) {
            replyEx(req.getSessionId(), status, format.contentType(),
                    svc().wireFacade().encodeNiResponse(null, message, AsAction.ABORT, false, format),
                    Map.of());
        } else {
            replyJson(req.getSessionId(), status, Map.of("error", message));
        }
    }

    private String handleNiFirst(HttpWebRequestEvent req, ClassicNiIngress ingress,
                                 AsHttpWireFormat format, CallbackAuthService.NiAuth auth) {
        String jsessionId = UUID.randomUUID().toString();
        String corr = (ingress.correlationId() != null && !ingress.correlationId().isBlank())
                ? ingress.correlationId().trim()
                : UUID.randomUUID().toString();
        String msisdn = ingress.msisdn() == null ? "" : ingress.msisdn().trim();
        String text = ingress.text() == null ? "" : ingress.text();
        int networkId = resolveNiNetworkId(ingress, auth);

        // dialogId == correlationId so MapUssdParent (MAP NI dialog id) can resolve the session.
        VirtualSession session = new VirtualSession(
                UUID.randomUUID().toString(), corr, UUID.randomUUID().toString(),
                msisdn, networkId, corr, "");
        session.setOriginationType(OriginationType.MAP);
        session.setState(VirtualSessionState.ACTIVE);
        session.setPendingText(text);
        session.setDialogAlive(true);
        session.setTenantId(auth.tenantId());
        svc().store().put(session);

        ClassicNiHttpPark park = svc().niHttpPark();
        if (ingress.emptyDialogHandshake()) {
            // Immediate empty handshake reply; still start MAP NI async (no HTTP park wait).
            park.park(null, jsessionId, corr, format, networkId, true);
            String handshake = svc().wireFacade().encodeNiResponse(
                    corr, "", AsAction.CONTINUE, true, format);
            replyEx(req.getSessionId(), 200, format.contentType(), handshake, setCookie(jsessionId));
            routeNiPush(corr, msisdn, text, networkId);
            if (!svc().config().mapEnabled()) {
                // No parked HTTP to echo; MAP-disabled lab still routes for side effects.
            }
            return "ni-handshake";
        }

        ClassicNiHttpPark.ParkRecord rec = park.park(
                req.getSessionId(), jsessionId, corr, format, networkId, false);
        routeNiPush(corr, msisdn, text, networkId);
        if (!svc().config().mapEnabled()) {
            park.scheduleLabEcho(corr, text, LAB_ECHO_DELAY_MS);
        } else {
            park.scheduleAdaptiveGate(rec);
        }
        return "ni-parked";
    }

    private String handleNiContinue(HttpWebRequestEvent req, ClassicNiIngress ingress,
                                    AsHttpWireFormat format, String jsession) {
        ClassicNiHttpPark park = svc().niHttpPark();
        Optional<ClassicNiHttpPark.ParkRecord> opt = park.findByJsession(jsession);
        if (opt.isEmpty()) {
            replyJson(req.getSessionId(), 404, Map.of("error", "unknown JSESSIONID"));
            return "ni-continue-404";
        }
        ClassicNiHttpPark.ParkRecord prior = opt.get();
        String corr = prior.correlationId();
        String text = ingress.text() == null ? "" : ingress.text();
        boolean endOrEmpty = text.isBlank()
                || looksLikeEndDialog(req.getBody());

        if (endOrEmpty) {
            park.unpark(corr);
            svc().store().get(corr).ifPresent(s -> {
                s.setState(VirtualSessionState.COMPLETED);
                s.setDialogAlive(false);
                svc().store().put(s);
                svc().store().remove(corr);
            });
            String endBody = svc().wireFacade().encodeNiResponse(
                    corr, text, AsAction.END, false, prior.format());
            replyEx(req.getSessionId(), 200, prior.format().contentType(), endBody, setCookie(jsession));
            return "ni-end";
        }

        AsResponse asResp = new AsResponse(corr, corr, 1, text, AsAction.CONTINUE, false);
        svc().store().get(corr).ifPresent(s -> {
            s.setState(VirtualSessionState.AWAITING_AS);
            svc().store().put(s);
        });
        svc().bridge().onAsResponse(asResp, -1);

        ClassicNiHttpPark.ParkRecord rec = park.park(
                req.getSessionId(), jsession, corr, prior.format(), prior.networkId(), false);
        String msisdn = svc().store().get(corr).map(VirtualSession::msisdn).orElse("");
        routeNiPush(corr, msisdn, text, prior.networkId());
        if (!svc().config().mapEnabled()) {
            park.scheduleLabEcho(corr, text, LAB_ECHO_DELAY_MS);
        } else {
            park.scheduleAdaptiveGate(rec);
        }
        return "ni-continue-parked";
    }

    private void routeNiPush(String corr, String msisdn, String text, int networkId) {
        try {
            svc().container().routeEvent(
                    new NiPushRequestEvent(corr, msisdn, text, networkId, UssdAlphabet.AUTO),
                    svc().container().createActivityContext("http-ni-" + corr));
        } catch (RuntimeException e) {
            // Lab / bootstrap without container — park gate or lab echo still covers reply.
        }
    }

    /**
     * Content-Type contains {@code xml} OR body trimmed starts with {@code <} → XML; else JSON.
     */
    public static AsHttpWireFormat detectWireFormat(String contentType, String body) {
        if (contentType != null && contentType.toLowerCase().contains("xml")) {
            return AsHttpWireFormat.XML;
        }
        if (body != null) {
            String t = body.trim();
            if (!t.isEmpty() && t.charAt(0) == '<') {
                return AsHttpWireFormat.XML;
            }
        }
        return AsHttpWireFormat.JSON;
    }

    private static String contentType(HttpWebRequestEvent req) {
        Map<String, String> h = req.getHeaders();
        if (h == null || h.isEmpty()) return null;
        String ct = h.get("Content-Type");
        if (ct == null) ct = h.get("content-type");
        return ct;
    }

    private static String resolveJsession(HttpWebRequestEvent req) {
        String c = req.getCookie("JSESSIONID");
        if (c != null && !c.isBlank()) return c;
        Map<String, String> cookies = req.getCookies();
        if (cookies != null) {
            for (Map.Entry<String, String> e : cookies.entrySet()) {
                if (e.getKey() != null && "JSESSIONID".equalsIgnoreCase(e.getKey())) {
                    return e.getValue();
                }
            }
        }
        Map<String, String> headers = req.getHeaders();
        if (headers != null) {
            String cookie = headers.get("Cookie");
            if (cookie == null) cookie = headers.get("cookie");
            if (cookie != null) {
                for (String part : cookie.split(";")) {
                    String p = part.trim();
                    int eq = p.indexOf('=');
                    if (eq > 0 && "JSESSIONID".equalsIgnoreCase(p.substring(0, eq).trim())) {
                        return p.substring(eq + 1).trim();
                    }
                }
            }
        }
        return null;
    }

    private static boolean looksLikeEndDialog(String body) {
        if (body == null || body.isBlank()) return true;
        String t = body.trim().toLowerCase();
        return t.contains("mapmessagessize=\"0\"")
                || t.contains("prearrangedend=\"true\"")
                || t.contains("\"action\":\"end\"")
                || t.contains("\"action\": \"end\"");
    }

    private static boolean pathStartsWith(String path, String prefix) {
        if (path == null || prefix == null) return false;
        if (path.equals(prefix)) return true;
        String withSlash = prefix.endsWith("/") ? prefix : prefix + "/";
        return path.startsWith(withSlash);
    }

    private static Map<String, String> setCookie(String jsessionId) {
        return Map.of("Set-Cookie", "JSESSIONID=" + jsessionId + "; Path=/; HttpOnly");
    }

    private void replyJson(String sessionId, int status, Object body) {
        RaCommandPort port = http;
        if (port == null) return;
        try {
            port.sendCommand(new HttpServerCommand.HttpResponseCommand(
                    sessionId, status, "application/json", JSON.writeValueAsString(body)));
        } catch (Exception e) {
            port.sendCommand(new HttpServerCommand.HttpResponseCommand(
                    sessionId, 500, "application/json", "{\"error\":\"serialize\"}"));
        }
    }

    private void replyEx(String sessionId, int status, String contentType, String body,
                         Map<String, String> headers) {
        RaCommandPort port = http;
        if (port == null) return;
        port.sendCommand(new HttpServerCommand.HttpResponseExCommand(
                sessionId, status, contentType, body, null,
                headers == null ? Map.of() : headers));
    }

    private void replyText(String sessionId, int status, String body) {
        RaCommandPort port = http;
        if (port == null) return;
        port.sendCommand(new HttpServerCommand.HttpResponseCommand(
                sessionId, status, "text/plain", body));
    }

    private void replyBytes(String sessionId, AdminHttpHandler.HttpReply r) {
        RaCommandPort port = http;
        if (port == null) return;
        String text = null;
        byte[] binary = r.body();
        String ct = r.contentType();
        if (ct != null && (ct.startsWith("text/") || ct.contains("json") || ct.contains("javascript"))) {
            text = new String(binary == null ? new byte[0] : binary, StandardCharsets.UTF_8);
            binary = null;
        }
        port.sendCommand(new HttpServerCommand.HttpResponseExCommand(
                sessionId, r.status(), ct, text, binary,
                r.headers() == null ? Map.of() : r.headers()));
    }
}
