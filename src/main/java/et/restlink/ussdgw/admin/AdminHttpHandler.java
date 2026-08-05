package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.admin.smpp.SmppAdminBindings;
import et.restlink.ussdgw.admin.smpp.SmppAdminController;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.UssdSagaCoordinator;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.cdr.CdrRecord;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.SmppConfigSupport;
import et.restlink.ussdgw.config.Ss7ConfigSupport;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.ra.smpp.SmppEndpointRegistry;
import et.restlink.ussdgw.service.AsPullClient;
import et.restlink.ussdgw.service.BridgeGateScheduler;
import et.restlink.ussdgw.service.SmppApplyService;
import et.restlink.ussdgw.service.Ss7ApplyService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microjainslee.admin.RaAdminHttpResponse;
import com.microjainslee.monitor.MonitorHandler;
import com.microjainslee.ra.jss7.admin.Ss7AdminBindings;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Admin + Monitor Hub HTTP surface (OTA pattern).
 * Plane GETs {@code /admin/ss7|smpp|http} redirect to Monitor Hub tabs;
 * form editors remain under {@code /admin/ss7/config} (and smpp/http siblings).
 */
@ApplicationScoped
public class AdminHttpHandler {
    private static final Logger LOG = LogManager.getLogger(AdminHttpHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject UssdConfigService config;
    @Inject LinkStatusService linkStatus;
    @Inject CdrService cdr;
    @Inject VirtualSessionBridge bridge;
    @Inject VirtualSessionStore store;
    @Inject AdaptiveTimeout adaptive;
    @Inject AdminCatalogHandler catalog;
    @Inject AdminPlaneHandler planes;
    @Inject AdminCampaignHandler campaigns;
    @Inject SmppApplyService smppApply;
    @Inject Ss7ApplyService ss7Apply;
    @Inject SmppConfigSupport smppConfig;
    @Inject Ss7ConfigSupport ss7Config;
    @Inject SmppEndpointRegistry smppRegistry;
    @Inject AdminAuthService adminAuth;
    @Inject BridgeGateScheduler bridgeGate;
    @Inject AsPullClient asPull;
    @Inject UssdSagaCoordinator saga;

    private volatile MonitorHandler monitorHub;

    public record HttpReply(int status, String contentType, byte[] body, Map<String, String> headers) {
        public static HttpReply html(String html) {
            return new HttpReply(200, "text/html; charset=utf-8",
                    html.getBytes(StandardCharsets.UTF_8), Map.of());
        }
        public static HttpReply json(int status, Object node) {
            try {
                return new HttpReply(status, "application/json",
                        JSON.writeValueAsBytes(node), Map.of());
            } catch (Exception e) {
                return new HttpReply(500, "application/json",
                        "{\"error\":\"serialize\"}".getBytes(StandardCharsets.UTF_8), Map.of());
            }
        }
        public static HttpReply text(int status, String body) {
            return new HttpReply(status, "text/plain; charset=utf-8",
                    body.getBytes(StandardCharsets.UTF_8), Map.of());
        }
        public static HttpReply redirect(String location) {
            return new HttpReply(302, "text/plain; charset=utf-8",
                    ("Redirect: " + location).getBytes(StandardCharsets.UTF_8),
                    Map.of("Location", location));
        }
    }

    /**
     * Bind SMPP + SS7 Monitor Hub hooks (call once after Apply services are ready).
     */
    public void wireRaAdminHub() {
        SmppAdminBindings.bindRegistry(smppRegistry);
        SmppAdminBindings.bindHooks(
                smppApply::apply,
                smppApply::start,
                smppApply::stop,
                smppConfig::validateHookJson,
                smppConfig::activeJsonOrLab,
                smppConfig::saveHookJson);
        Ss7AdminBindings.bindHooks(
                ss7Apply::apply,
                ss7Apply::start,
                ss7Apply::stop,
                ss7Config::validateHookJson,
                ss7Config::activeJsonOrProps,
                json -> {
                    String r = ss7Config.saveHookJson(json);
                    if (r != null && r.startsWith("{\"ok\":true")) {
                        Ss7AdminBindings.setLastConfigJson(json);
                    }
                    return r;
                });
        this.monitorHub = new MonitorHandler(null);
        LOG.info("[admin] RA admin hub wired (jainslee-monitor + local smpp-ra pack)");
    }

    public void clearRaAdminHub() {
        SmppAdminBindings.clear();
        Ss7AdminBindings.clearHooks();
        monitorHub = null;
    }

    private MonitorHandler monitor() {
        MonitorHandler h = monitorHub;
        if (h == null) {
            synchronized (this) {
                h = monitorHub;
                if (h == null) {
                    h = new MonitorHandler(null);
                    monitorHub = h;
                }
            }
        }
        return h;
    }

    public boolean authorized(Map<String, String> headers, Map<String, String> query) {
        return adminAuth.authenticate(headers, query).isPresent();
    }

    public Optional<HttpReply> tryHandle(String method, String path,
                                         Map<String, String> headers,
                                         Map<String, String> query,
                                         String body) {
        if (path == null) return Optional.empty();
        String p = path.startsWith("/") ? path : "/" + path;
        if (p.equals("/health") || p.equals("/healthz")) {
            return Optional.of(HttpReply.json(200, Map.of(
                    "status", "UP",
                    "ss7.live", linkStatus.isM3uaRouteReady())));
        }

        Optional<AdminAuthService.Principal> principal = adminAuth.authenticate(headers, query);

        if (isMonitorHubPath(p)) {
            if (!isPublicMonitorStatic(method, p) && principal.isEmpty()) {
                return Optional.of(HttpReply.text(401, "unauthorized"));
            }
            Optional<RaAdminHttpResponse> hit = monitor().handle(method, p, query, body);
            return hit.map(AdminHttpHandler::toHttpReply);
        }

        if (!(p.startsWith("/admin") || p.equals("/"))) {
            return Optional.empty();
        }
        if (p.equals("/") || p.equals("/admin") || p.equals("/admin/")) {
            if (principal.isEmpty()) {
                return Optional.of(HttpReply.text(401, "unauthorized"));
            }
            return Optional.of(serveFile("admin.html"));
        }
        if (principal.isEmpty() && !isPublicAsset(p)) {
            return Optional.of(HttpReply.text(401, "unauthorized"));
        }

        AdminAuthService.Principal who = principal.orElse(null);

        if ("POST".equalsIgnoreCase(method)) {
            Optional<HttpReply> post = handlePost(p, body, who);
            if (post.isPresent()) return post;
        }

        String tab = query == null ? null : query.get("tab");
        String keyQ = query == null ? null : query.get("key");
        return switch (p) {
            case "/admin/status" -> Optional.of(statusHtml());
            case "/admin/status.json" -> Optional.of(statusJson());
            case "/admin/cdr" -> Optional.of(cdrHtml(query, who));
            case "/admin/bridge" -> Optional.of(planes.bridgeGet());
            case "/admin/ss7" -> Optional.of(hubRedirect("ss7", keyQ));
            case "/admin/smpp" -> Optional.of(hubRedirect("smpp", keyQ));
            case "/admin/http" -> Optional.of(hubRedirect("http", keyQ));
            case "/admin/ss7/config" -> Optional.of(planes.ss7Get());
            case "/admin/smpp/config" -> Optional.of(planes.smppGet());
            case "/admin/http/config" -> Optional.of(planes.httpGet());
            case "/admin/grpc" -> Optional.of(planes.grpcGet());
            case "/admin/routing", "/admin/rules" -> Optional.of(catalog.routingGet(who));
            case "/admin/tenants" -> Optional.of(catalog.tenantsGet(who));
            case "/admin/users" -> Optional.of(catalog.usersGet(who));
            case "/admin/campaigns" -> Optional.of(campaigns.get(who));
            case "/admin/hub", "/admin/links", "/admin/links/ss7", "/admin/links/smpp",
                 "/admin/links/http", "/telemetry/partial" -> Optional.of(
                    HttpReply.html(linkStatus.htmlPartial(resolveLinkTab(p, tab))));
            case "/admin/links/smpp.html" -> Optional.of(HttpReply.html(
                    new SmppAdminController().statusHtml(null).bodyAsString()));
            default -> {
                if (p.startsWith("/admin/")) {
                    String name = p.substring(p.lastIndexOf('/') + 1);
                    yield Optional.of(serveFile(name));
                }
                yield Optional.empty();
            }
        };
    }

    private static String resolveLinkTab(String path, String tab) {
        if (tab != null && !tab.isBlank()) return tab;
        if (path.endsWith("/ss7")) return "ss7";
        if (path.endsWith("/smpp") || path.endsWith("/smpp.html")) return "smpp";
        if (path.endsWith("/http")) return "http";
        return "all";
    }

    private static HttpReply hubRedirect(String tab, String key) {
        StringBuilder loc = new StringBuilder("/telemetry/?tab=").append(tab);
        if (key != null && !key.isBlank()) {
            loc.append("&key=").append(key);
        }
        return HttpReply.redirect(loc.toString());
    }

    static boolean isMonitorHubPath(String path) {
        return path.equals("/telemetry") || path.startsWith("/telemetry/")
                || path.equals("/api/admin/dashboards")
                || path.startsWith("/admin/ra/")
                || path.startsWith("/api/ra/")
                || path.startsWith("/api/telemetry/")
                || path.equals("/api/autonomous/health")
                || path.startsWith("/api/ai");
    }

    static boolean isPublicMonitorStatic(String method, String path) {
        if (method == null || (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method))) {
            return false;
        }
        if (path.equals("/telemetry") || path.equals("/telemetry/")) {
            return true;
        }
        if (path.startsWith("/telemetry/")) {
            String rest = path.substring("/telemetry/".length()).toLowerCase();
            if (rest.startsWith("partial/")) return true;
            return rest.endsWith(".js") || rest.endsWith(".css") || rest.endsWith(".html")
                    || rest.endsWith(".svg") || rest.endsWith(".png") || rest.endsWith(".ico")
                    || rest.endsWith(".map") || rest.isEmpty();
        }
        if (path.startsWith("/admin/ra/")) {
            String lower = path.toLowerCase();
            return lower.endsWith(".html") || lower.endsWith(".js") || lower.endsWith(".css");
        }
        return false;
    }

    private static HttpReply toHttpReply(RaAdminHttpResponse r) {
        String ct = r.contentType();
        Map<String, String> headers = r.headers() == null ? Map.of() : r.headers();
        if (ct != null && (ct.startsWith("image/") || ct.equals("application/octet-stream"))) {
            return new HttpReply(r.status(), ct, r.body(), headers);
        }
        return new HttpReply(r.status(), ct,
                r.bodyAsString().getBytes(StandardCharsets.UTF_8), headers);
    }

    private Optional<HttpReply> handlePost(String p, String body, AdminAuthService.Principal who) {
        try {
            if (who != null && who.isTenantScoped()) {
                // TENANT may mutate own routing + campaigns; no plane/users/tenants CRUD
                return switch (p) {
                    case "/admin/routing", "/admin/rules" -> Optional.of(catalog.routingPost(body, who));
                    case "/admin/campaigns" -> Optional.of(campaigns.post(body, who));
                    case "/admin/tenants", "/admin/users",
                         "/admin/ss7", "/admin/ss7/config", "/admin/ss7/apply", "/admin/ss7/start", "/admin/ss7/stop",
                         "/admin/smpp", "/admin/smpp/config", "/admin/smpp/apply", "/admin/smpp/start", "/admin/smpp/stop",
                         "/admin/http", "/admin/http/config", "/admin/http/apply", "/admin/http/start", "/admin/http/stop",
                         "/admin/grpc", "/admin/grpc/apply", "/admin/grpc/start", "/admin/grpc/stop",
                         "/admin/bridge" ->
                            Optional.of(HttpReply.text(403, "forbidden for TENANT role"));
                    default -> Optional.empty();
                };
            }
            return switch (p) {
                case "/admin/ss7", "/admin/ss7/config",
                     "/admin/ss7/apply", "/admin/ss7/start", "/admin/ss7/stop" ->
                        Optional.of(delegatePlanePost("ss7", p, body));
                case "/admin/smpp", "/admin/smpp/config",
                     "/admin/smpp/apply", "/admin/smpp/start", "/admin/smpp/stop" ->
                        Optional.of(delegatePlanePost("smpp", p, body));
                case "/admin/http", "/admin/http/config",
                     "/admin/http/apply", "/admin/http/start", "/admin/http/stop" ->
                        Optional.of(delegatePlanePost("http", p, body));
                case "/admin/grpc", "/admin/grpc/apply", "/admin/grpc/start", "/admin/grpc/stop" ->
                        Optional.of(delegatePlanePost("grpc", p, body));
                case "/admin/bridge" -> Optional.of(planes.bridgePost(body));
                case "/admin/routing", "/admin/rules" -> Optional.of(catalog.routingPost(body, who));
                case "/admin/tenants" -> Optional.of(catalog.tenantsPost(body, who));
                case "/admin/users" -> Optional.of(catalog.usersPost(body, who));
                case "/admin/campaigns" -> Optional.of(campaigns.post(body, who));
                default -> Optional.empty();
            };
        } catch (RuntimeException ex) {
            return Optional.of(HttpReply.html("<pre>" + esc("error: " + ex.getMessage()) + "</pre>"));
        }
    }

    private HttpReply delegatePlanePost(String plane, String path, String body) {
        String action = null;
        if (path.endsWith("/apply")) action = "apply";
        else if (path.endsWith("/start")) action = "start";
        else if (path.endsWith("/stop")) action = "stop";
        String effective = body == null ? "" : body;
        if (action != null) {
            effective = (effective.isBlank() ? "" : effective + "&") + "action=" + action;
        }
        return switch (plane) {
            case "smpp" -> planes.smppPost(effective);
            case "http" -> planes.httpPost(effective);
            case "grpc" -> planes.grpcPost(effective);
            default -> planes.ss7Post(effective);
        };
    }

    private boolean isPublicAsset(String p) {
        return p.endsWith(".css") || p.endsWith(".js") || p.endsWith(".svg");
    }

    private HttpReply statusHtml() {
        Map<String, Object> m = new LinkedHashMap<>(linkStatus.snapshot());
        m.put("sessions", store.size());
        m.put("bridge.count", bridge.bridgeCount());
        m.put("bridge.recover", bridge.recoverCount());
        m.put("bridge.zombieDrop", bridge.zombieDrop());
        m.put("bridge.enabled", config.bridgeEnabled());
        if (bridgeGate != null) {
            m.put("scheduler.gateExpired", bridgeGate.gateExpired());
            m.put("scheduler.reclaimCount", bridgeGate.reclaimCount());
        }
        if (asPull != null) {
            m.put("as.circuitOpenRejects", asPull.openRejects());
        }
        if (saga != null) {
            m.put("saga.niFail", saga.niFailCount());
            m.put("saga.pullFail", saga.pullFailCount());
        }
        m.put("adaptive.gateCeilingMs", config.asyncGateTimeoutMs());
        m.put("adaptive.dialogTimeoutMs", config.dialogTimeoutMs());
        m.put("adaptive.floorMs", AdaptiveTimeout.FLOOR_MS);
        m.put("adaptive.ewma", adaptive.snapshot());
        StringBuilder sb = new StringBuilder("<div class=\"status\">");
        sb.append("<p class=\"hint\">Plane live status: ")
                .append("<a href=\"/telemetry/?tab=ss7\">SS7</a> · ")
                .append("<a href=\"/telemetry/?tab=smpp\">SMPP</a> · ")
                .append("<a href=\"/telemetry/?tab=http\">HTTP</a>")
                .append(" (Monitor Hub). Quick forms: ")
                .append("<a href=\"#\" hx-get=\"/admin/ss7/config\" hx-target=\"#panel\" ")
                .append("hx-headers='{\"X-USSD-Admin-Key\":\"ussd-admin\"}'>SS7 form</a> · ")
                .append("<a href=\"#\" hx-get=\"/admin/smpp/config\" hx-target=\"#panel\" ")
                .append("hx-headers='{\"X-USSD-Admin-Key\":\"ussd-admin\"}'>SMPP form</a> · ")
                .append("<a href=\"#\" hx-get=\"/admin/http/config\" hx-target=\"#panel\" ")
                .append("hx-headers='{\"X-USSD-Admin-Key\":\"ussd-admin\"}'>HTTP form</a>")
                .append("</p>");
        sb.append(linkStatus.htmlPartial("all"));
        sb.append("<pre>");
        m.forEach((k, v) -> {
            if (String.valueOf(k).startsWith("ss7.") || String.valueOf(k).startsWith("http.")
                    || String.valueOf(k).startsWith("grpc.") || String.valueOf(k).startsWith("smpp.")) {
                return;
            }
            sb.append(esc(String.valueOf(k))).append(" = ")
                    .append(esc(String.valueOf(v))).append('\n');
        });
        sb.append("</pre></div>");
        return HttpReply.html(sb.toString());
    }

    public HttpReply statusJson() {
        Map<String, Object> m = new LinkedHashMap<>(linkStatus.snapshot());
        m.put("sessions", store.size());
        m.put("bridge.count", bridge.bridgeCount());
        m.put("bridge.recover", bridge.recoverCount());
        m.put("bridge.zombieDrop", bridge.zombieDrop());
        if (bridgeGate != null) {
            m.put("scheduler.gateExpired", bridgeGate.gateExpired());
            m.put("scheduler.reclaimCount", bridgeGate.reclaimCount());
        }
        if (asPull != null) {
            m.put("as.circuitOpenRejects", asPull.openRejects());
        }
        if (saga != null) {
            m.put("saga.niFail", saga.niFailCount());
            m.put("saga.pullFail", saga.pullFailCount());
        }
        return HttpReply.json(200, m);
    }

    private HttpReply cdrHtml(Map<String, String> query, AdminAuthService.Principal who) {
        int limit = CdrService.DEFAULT_LIMIT;
        if (query != null && query.get("limit") != null) {
            try { limit = Integer.parseInt(query.get("limit")); } catch (NumberFormatException ignored) {}
        }
        String scope = who != null && who.isTenantScoped() ? who.tenantId() : null;
        StringBuilder sb = new StringBuilder("<table><tr><th>When</th><th>Corr</th><th>Phase</th><th>MSISDN</th><th>Status</th></tr>");
        for (CdrRecord r : cdr.listRecords(limit, scope)) {
            sb.append("<tr><td>").append(r.createdAt).append("</td><td>")
                    .append(esc(r.correlationId)).append("</td><td>")
                    .append(esc(r.phase)).append("</td><td>")
                    .append(esc(r.msisdn)).append("</td><td>")
                    .append(esc(r.status)).append("</td></tr>");
        }
        sb.append("</table>");
        return HttpReply.html(sb.toString());
    }

    private HttpReply serveFile(String relative) {
        Path[] roots = {
                Path.of("app/html"),
                Path.of("dist/app/html"),
                Path.of("../app/html")
        };
        for (Path root : roots) {
            Path f = root.resolve(relative).normalize();
            if (Files.isRegularFile(f)) {
                try {
                    byte[] bytes = Files.readAllBytes(f);
                    String ct = relative.endsWith(".js") ? "application/javascript"
                            : relative.endsWith(".css") ? "text/css"
                            : "text/html; charset=utf-8";
                    return new HttpReply(200, ct, bytes, Map.of());
                } catch (IOException e) {
                    return HttpReply.text(500, "read error");
                }
            }
        }
        return HttpReply.text(404, "not found: " + relative);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
