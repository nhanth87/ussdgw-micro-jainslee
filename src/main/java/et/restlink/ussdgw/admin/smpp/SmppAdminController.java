/*
 */
package et.restlink.ussdgw.admin.smpp;

import et.restlink.ussdgw.ra.smpp.SmppEndpointRegistry;
import et.restlink.ussdgw.ra.smpp.SmppRaEndpoint;
import et.restlink.ussdgw.ra.smpp.SmppServerRaEndpoint;

import com.cloudhopper.smpp.SmppServerSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microjainslee.admin.RaAdminHttpRequest;
import com.microjainslee.admin.RaAdminHttpResponse;
import com.microjainslee.admin.RaAdminJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * SMPP admin APIs. Peer truth: {@code anyPeerUp} (bound ESME session or outbound
 * {@code peerReady}) — never {@code isActive()} / LISTEN alone as link UP.
 */
public final class SmppAdminController {

    private static final ObjectMapper JSON = RaAdminJson.mapper();

    public RaAdminHttpResponse status(RaAdminHttpRequest ignored) {
        return RaAdminJson.ok(statusMap());
    }

    public RaAdminHttpResponse statusHtml(RaAdminHttpRequest ignored) {
        String html = SmppStatusHtml.render(statusMap());
        return RaAdminHttpResponse.text(200, "text/html; charset=utf-8", html)
                .withHeader("Vary", "HX-Request");
    }

    public RaAdminHttpResponse config(RaAdminHttpRequest ignored) {
        Supplier<String> hook = SmppAdminBindings.configJsonHook();
        Map<String, Object> out = new LinkedHashMap<>();
        if (hook == null) {
            out.put("config", null);
            return RaAdminJson.ok(out);
        }
        String json = hook.get();
        if (json == null || json.isBlank()) {
            out.put("config", null);
            return RaAdminJson.ok(out);
        }
        try {
            out.put("config", JSON.readTree(json));
        } catch (Exception ex) {
            out.put("config", json);
        }
        return RaAdminJson.ok(out);
    }

    public RaAdminHttpResponse validate(RaAdminHttpRequest req) {
        Function<String, String> hook = SmppAdminBindings.validateHook();
        if (hook == null) {
            return RaAdminJson.status(503, Map.of("ok", false, "error", "validate hook not bound"));
        }
        String body = req == null ? "" : (req.body() == null ? "" : req.body());
        String result = hook.apply(body);
        return jsonFromHook(result, 200);
    }

    public RaAdminHttpResponse apply(RaAdminHttpRequest req) {
        String body = req == null ? null : req.body();
        Function<String, String> save = SmppAdminBindings.saveConfigHook();
        if (body != null && !body.isBlank() && save != null) {
            String saved = save.apply(body);
            if (saved != null && saved.startsWith("{\"ok\":false")) {
                return jsonFromHook(saved, 400);
            }
        }
        Supplier<String> apply = SmppAdminBindings.applyHook();
        if (apply == null) {
            return RaAdminJson.status(503, Map.of("ok", false, "error", "apply hook not bound"));
        }
        try {
            String detail = apply.get();
            return RaAdminJson.ok(Map.of("ok", true, "detail", detail == null ? "" : detail));
        } catch (RuntimeException ex) {
            return RaAdminJson.status(500, Map.of("ok", false, "error",
                    ex.getMessage() == null ? "apply failed" : ex.getMessage()));
        }
    }

    public RaAdminHttpResponse start(RaAdminHttpRequest ignored) {
        Supplier<String> start = SmppAdminBindings.startHook();
        if (start == null) {
            return RaAdminJson.status(503, Map.of("ok", false, "error", "start hook not bound"));
        }
        try {
            return RaAdminJson.ok(Map.of("ok", true, "detail", nullToEmpty(start.get())));
        } catch (RuntimeException ex) {
            return RaAdminJson.status(500, Map.of("ok", false, "error",
                    ex.getMessage() == null ? "start failed" : ex.getMessage()));
        }
    }

    public RaAdminHttpResponse stop(RaAdminHttpRequest ignored) {
        Supplier<String> stop = SmppAdminBindings.stopHook();
        if (stop == null) {
            return RaAdminJson.status(503, Map.of("ok", false, "error", "stop hook not bound"));
        }
        try {
            return RaAdminJson.ok(Map.of("ok", true, "detail", nullToEmpty(stop.get())));
        } catch (RuntimeException ex) {
            return RaAdminJson.status(500, Map.of("ok", false, "error",
                    ex.getMessage() == null ? "stop failed" : ex.getMessage()));
        }
    }

    static Map<String, Object> statusMap() {
        SmppEndpointRegistry registry = SmppAdminBindings.registry();
        List<Map<String, Object>> clients = new ArrayList<>();
        if (registry != null) {
            for (Map.Entry<String, SmppRaEndpoint> e : registry.clients().entrySet()) {
                SmppRaEndpoint ep = e.getValue();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", e.getKey());
                row.put("role", "client");
                row.put("host", ep.getHost());
                row.put("port", ep.getPort());
                row.put("systemId", ep.getSystemId());
                row.put("networkId", ep.getNetworkId());
                row.put("active", ep.isActive());
                row.put("bound", ep.isBound());
                row.put("peerReady", ep.isPeerReady());
                row.put("state", ep.isPeerReady() ? "BOUND"
                        : ep.isActive() ? "ACTIVE_UNBOUND" : "DOWN");
                clients.add(row);
            }
        }

        SmppServerRaEndpoint server = registry == null ? null : registry.server();
        Map<String, Object> srv = new LinkedHashMap<>();
        if (server == null) {
            srv.put("enabled", false);
            srv.put("state", "OFF");
            srv.put("sessions", List.of());
            srv.put("sessionCount", 0);
            srv.put("active", false);
            srv.put("peerBound", false);
        } else {
            srv.put("enabled", true);
            srv.put("port", server.getPort());
            srv.put("systemId", server.getSystemId());
            srv.put("networkId", server.getNetworkId());
            srv.put("active", server.isActive());
            srv.put("peerBound", server.isPeerBound());
            srv.put("state", server.isActive() ? "LISTEN" : "DOWN");
            List<Map<String, Object>> sessions = new ArrayList<>();
            for (Map.Entry<String, SmppServerSession> se : server.sessions().snapshot().entrySet()) {
                SmppServerSession s = se.getValue();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("systemId", se.getKey());
                row.put("bound", s != null && s.isBound());
                row.put("state", s != null && s.isBound() ? "BOUND" : "STALE");
                sessions.add(row);
            }
            srv.put("sessions", sessions);
            srv.put("sessionCount", sessions.size());
        }

        SmppRaEndpoint primary = SmppAdminBindings.primaryClient();
        boolean clientActive = primary != null && primary.isActive();
        boolean peerReadyPrimary = primary != null && primary.isPeerReady();
        boolean serverListening = Boolean.TRUE.equals(srv.get("active"));
        boolean peerBoundServer = Boolean.TRUE.equals(srv.get("peerBound"));

        int boundSessions = 0;
        Object sessObj = srv.get("sessions");
        if (sessObj instanceof List<?> sess) {
            for (Object o : sess) {
                if (o instanceof Map<?, ?> row && "BOUND".equals(String.valueOf(row.get("state")))) {
                    boundSessions++;
                }
            }
        }
        boolean outboundBound = false;
        for (Map<String, Object> c : clients) {
            if (Boolean.TRUE.equals(c.get("peerReady"))
                    || "BOUND".equals(String.valueOf(c.get("state")))) {
                outboundBound = true;
                break;
            }
        }
        boolean anyPeerUp = outboundBound || peerBoundServer || boundSessions > 0;
        boolean anyActive = clientActive || serverListening;

        String detail = synthesizeSmppDetail(anyPeerUp, serverListening, anyPeerUp);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", anyActive);
        m.put("clientActive", clientActive);
        m.put("peerReady", peerReadyPrimary);
        m.put("serverListening", serverListening);
        m.put("peerBound", peerBoundServer || boundSessions > 0);
        m.put("anyPeerUp", anyPeerUp);
        m.put("listening", serverListening);
        m.put("boundSessionCount", boundSessions);
        m.put("detail", detail);
        m.put("clients", clients);
        m.put("server", srv);
        m.put("note", "anyPeerUp=green; LISTEN alone=amber");
        return m;
    }

    /** Package-visible for tests. */
    static String synthesizeSmppDetail(boolean anyPeerUp, boolean serverListening,
                                       boolean peerBound) {
        if (anyPeerUp) {
            return "smpp=peer-bound;peer=bound";
        }
        if (serverListening) {
            return "smpp=listening;peer=" + (peerBound ? "bound" : "none");
        }
        return "smpp=n/a";
    }

    private static RaAdminHttpResponse jsonFromHook(String result, int fallbackStatus) {
        if (result == null || result.isBlank()) {
            return RaAdminJson.ok(Map.of("ok", true));
        }
        try {
            var tree = JSON.readTree(result);
            int status = fallbackStatus;
            if (tree.has("ok") && tree.get("ok").isBoolean() && !tree.get("ok").asBoolean()) {
                status = fallbackStatus >= 400 ? fallbackStatus : 400;
            }
            return RaAdminHttpResponse.json(status, result);
        } catch (Exception ex) {
            return RaAdminJson.status(fallbackStatus, Map.of("ok", false, "raw", result));
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
