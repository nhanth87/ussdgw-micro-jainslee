package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.admin.AdminHttpHandler;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.service.SbbServices;

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

/** HTTP admin + AS async callback ingress. */
public final class HttpServerSbb implements Sbb, SleeEventHandler {
    private static final ObjectMapper JSON = new ObjectMapper();
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
        if (path.startsWith(callbackPath) && "POST".equalsIgnoreCase(req.getMethod())) {
            if (!svc().config().httpServerEnabled()) {
                replyJson(req.getSessionId(), 503, Map.of("error", "http callback server disabled"));
                return "as-callback-disabled";
            }
            AsResponse resp = JSON.readValue(req.getBody() == null ? "{}" : req.getBody(), AsResponse.class);
            svc().bridge().onAsResponse(resp, -1);
            replyJson(req.getSessionId(), 202, Map.of("accepted", true));
            return "as-callback";
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
