package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.access.LabMoService;
import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.api.UssdAlphabet;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.config.RuntimeConfigStore;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.service.HttpApplyService;

import com.microjainslee.admin.RaAdminHttpRequest;
import com.microjainslee.admin.RaAdminHttpResponse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * HTMX panels for HTTP AS modes: Sync / Async / Callback — config (ADMIN/OPS)
 * + lab inject (ADMIN + TENANT scoped). Also serves Monitor Hub via
 * {@link com.microjainslee.ra.httpserver.admin.HttpServerAdminBindings#bindAppPanels}.
 */
@ApplicationScoped
public class AdminHttpAsModeHandler {
    @Inject UssdConfigService config;
    @Inject RuntimeConfigStore store;
    @Inject HttpApplyService httpApply;
    @Inject VirtualSessionBridge bridge;
    @Inject VirtualSessionStore sessions;
    @Inject LabMoService labMo;

    public AdminHttpHandler.HttpReply get(String panel, AdminAuthService.Principal who) {
        return AdminHttpHandler.HttpReply.html(html(panel, null, who));
    }

    public AdminHttpHandler.HttpReply post(String panel, String body, AdminAuthService.Principal who) {
        try {
            Map<String, String> f = AdminCatalogHandler.parseForm(body);
            String action = f.getOrDefault("action", "save");
            if (isConfigAction(action) && who != null && who.isTenantScoped()) {
                return AdminHttpHandler.HttpReply.text(403, "forbidden for TENANT role");
            }
            String notice = switch (panel.toLowerCase()) {
                case "sync" -> handleSync(f, action, who);
                case "async" -> handleAsync(f, action, who);
                case "callback" -> handleCallback(f, action, who);
                default -> throw new IllegalArgumentException("unknown panel: " + panel);
            };
            return AdminHttpHandler.HttpReply.html(html(panel, notice, who));
        } catch (RuntimeException ex) {
            return AdminHttpHandler.HttpReply.html(html(panel, "error: " + esc(ex.getMessage()), who));
        }
    }

    /** Monitor Hub GET hook — panel name sync|async|callback. */
    public RaAdminHttpResponse hubGet(String panel, RaAdminHttpRequest req) {
        AdminAuthService.Principal who = AdminRequestContext.get();
        return toRa(get(panel, who));
    }

    /** Monitor Hub POST hook. */
    public RaAdminHttpResponse hubPost(String panel, RaAdminHttpRequest req) {
        AdminAuthService.Principal who = AdminRequestContext.get();
        String body = req == null ? null : req.body();
        return toRa(post(panel, body, who));
    }

    private static boolean isConfigAction(String action) {
        return "save".equalsIgnoreCase(action)
                || "saveApply".equalsIgnoreCase(action)
                || "apply".equalsIgnoreCase(action)
                || "start".equalsIgnoreCase(action)
                || "stop".equalsIgnoreCase(action);
    }

    private String handleSync(Map<String, String> f, String action, AdminAuthService.Principal who) {
        if ("labInject".equalsIgnoreCase(action)) {
            return labInject(f, who, false);
        }
        if ("labMo".equalsIgnoreCase(action)) {
            return labMoStart(f, who);
        }
        if ("save".equalsIgnoreCase(action) || "saveApply".equalsIgnoreCase(action)) {
            Map<String, String> kv = new LinkedHashMap<>();
            put(kv, RuntimeConfigStore.Keys.HTTP_CLIENT_ENABLED, f.get("clientEnabled"));
            put(kv, RuntimeConfigStore.Keys.HTTP_CONNECT_MS, f.get("connectTimeoutMs"));
            put(kv, RuntimeConfigStore.Keys.HTTP_REQUEST_MS, f.get("requestTimeoutMs"));
            store.putAll(kv);
        }
        if ("saveApply".equalsIgnoreCase(action) || "apply".equalsIgnoreCase(action)) {
            return httpApply.apply();
        }
        if ("start".equalsIgnoreCase(action)) return httpApply.start();
        if ("stop".equalsIgnoreCase(action)) return httpApply.stop();
        return "saved";
    }

    private String handleAsync(Map<String, String> f, String action, AdminAuthService.Principal who) {
        if ("labInject".equalsIgnoreCase(action)) {
            return labInject(f, who, true);
        }
        if ("save".equalsIgnoreCase(action) || "saveApply".equalsIgnoreCase(action)) {
            Map<String, String> kv = new LinkedHashMap<>();
            put(kv, RuntimeConfigStore.Keys.ASYNC_GATE_MS, f.get("asyncGateTimeoutMs"));
            put(kv, RuntimeConfigStore.Keys.ASYNC_WAIT_MSG, f.get("asyncWaitMessage"));
            put(kv, RuntimeConfigStore.Keys.ASYNC_HARD_FAIL_MSG, f.get("asyncHardFailMessage"));
            put(kv, RuntimeConfigStore.Keys.HTTP_CLIENT_BRIDGE, f.get("httpClientBridgeEnabled"));
            store.putAll(kv);
            long gate = config.asyncGateTimeoutMs();
            long dialog = config.dialogTimeoutMs();
            if (gate > 0 && gate >= dialog) {
                return "saved (warn: asyncGate must be < dialogTimeout)";
            }
        }
        return "saved";
    }

    private String handleCallback(Map<String, String> f, String action, AdminAuthService.Principal who) {
        if ("labInject".equalsIgnoreCase(action)) {
            return labInject(f, who, false);
        }
        if ("save".equalsIgnoreCase(action) || "saveApply".equalsIgnoreCase(action)) {
            Map<String, String> kv = new LinkedHashMap<>();
            put(kv, RuntimeConfigStore.Keys.HTTP_SERVER_ENABLED, f.get("serverEnabled"));
            put(kv, RuntimeConfigStore.Keys.HTTP_RA_HOST, f.get("listenHost"));
            put(kv, RuntimeConfigStore.Keys.HTTP_RA_PORT, f.get("listenPort"));
            put(kv, RuntimeConfigStore.Keys.HTTP_CALLBACK_PATH, f.get("callbackPath"));
            store.putAll(kv);
        }
        if ("saveApply".equalsIgnoreCase(action) || "apply".equalsIgnoreCase(action)) {
            return httpApply.apply();
        }
        if ("start".equalsIgnoreCase(action)) return httpApply.start();
        if ("stop".equalsIgnoreCase(action)) return httpApply.stop();
        return "saved";
    }

    private String labInject(Map<String, String> f, AdminAuthService.Principal who, boolean asyncAck) {
        String corr = f.getOrDefault("correlationId", "").trim();
        if (corr.isEmpty()) {
            throw new IllegalArgumentException("correlationId required");
        }
        Optional<VirtualSession> opt = sessions.get(corr);
        if (opt.isEmpty()) {
            throw new IllegalArgumentException("no session for corr=" + corr);
        }
        VirtualSession s = opt.get();
        if (who != null && who.isTenantScoped()) {
            String tid = s.tenantId() == null ? "" : s.tenantId();
            if (!who.tenantId().equals(tid)) {
                throw new IllegalStateException("forbidden: session tenant mismatch");
            }
        }
        int gen = parseInt(f.get("generation"), s.generation());
        String text = f.getOrDefault("text", asyncAck ? "" : "lab-ok");
        AsAction action = parseAction(f.get("asAction"));
        AsResponse resp = new AsResponse(
                corr, s.requestId(), gen, text, action, asyncAck, UssdAlphabet.AUTO);
        // async ACK: latencyMs ignored for EWMA; content uses -1 → derive from pull start
        bridge.onAsResponse(resp, asyncAck ? 0L : -1L);
        return (asyncAck ? "async ACK" : "content") + " injected corr=" + corr
                + " gen=" + gen + " state=" + s.state();
    }

    private String labMoStart(Map<String, String> f, AdminAuthService.Principal who) {
        String tenantId = f.getOrDefault("tenantId", "").trim();
        if (who != null && who.isTenantScoped()) {
            tenantId = who.tenantId();
        }
        // Lab MO for HTTP AS path uses SIP/SMPP/DIAMETER planes — prefer SMPP if enabled else DIAMETER
        OriginationType plane = pickLabPlane();
        LabMoService.Result r = labMo.start(
                plane,
                f.getOrDefault("msisdn", "").trim(),
                f.getOrDefault("shortCode", "").trim(),
                f.getOrDefault("ussd", "").trim(),
                tenantId.isEmpty() ? null : tenantId,
                parseInt(f.get("networkId"), 0));
        return "MO " + plane + " corr=" + r.session().correlationId() + " " + r.routeDetail();
    }

    private OriginationType pickLabPlane() {
        if (config.smppUssdEnabled()) return OriginationType.SMPP;
        if (config.diameterEnabled()) return OriginationType.DIAMETER;
        if (config.sipEnabled()) return OriginationType.SIP;
        throw new IllegalStateException("enable SMPP/Diameter/SIP plane for lab MO");
    }

    private String html(String panel, String notice, AdminAuthService.Principal who) {
        String p = panel == null ? "sync" : panel.toLowerCase();
        boolean tenant = who != null && who.isTenantScoped();
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"catalog http-as-").append(esc(p)).append("-panel\">");
        if (notice != null) sb.append("<p class=\"notice\">").append(esc(notice)).append("</p>");
        switch (p) {
            case "async" -> asyncHtml(sb, tenant);
            case "callback" -> callbackHtml(sb, tenant);
            default -> syncHtml(sb, tenant);
        }
        sb.append("</div>");
        return sb.toString();
    }

    private void syncHtml(StringBuilder sb, boolean tenant) {
        sb.append("<h2>HTTP AS · Sync</h2>");
        sb.append("<p class=\"hint\">SYNC pull: AS returns <code>async=false</code> with CONTINUE/END text. ")
                .append("Configures HTTP pull client timeouts.</p>");
        String path = "/admin/http/sync";
        if (!tenant) {
            sb.append(formOpen(path));
            sb.append(field("clientEnabled", "clientEnabled",
                    String.valueOf(config.httpClientEnabled()), "true|false"));
            sb.append(field("connectTimeoutMs", "connectMs",
                    String.valueOf(config.httpConnectTimeoutMs()), null));
            sb.append(field("requestTimeoutMs", "requestMs",
                    String.valueOf(config.httpRequestTimeoutMs()), null));
            sb.append(planeButtons());
            sb.append("</form>");
        } else {
            sb.append("<p class=\"hint\">TENANT: config read-only. clientEnabled=")
                    .append(config.httpClientEnabled()).append(" requestMs=")
                    .append(config.httpRequestTimeoutMs()).append("</p>");
        }
        labInjectForm(sb, path, false);
        labMoForm(sb, path, tenant);
    }

    private void asyncHtml(StringBuilder sb, boolean tenant) {
        sb.append("<h2>HTTP AS · Async ACK</h2>");
        sb.append("<p class=\"hint\">ASYNC_ACK: AS returns <code>async=true</code> (no EWMA feed). ")
                .append("Content arrives later via callback. Gate / wait messages below.</p>");
        String path = "/admin/http/async";
        if (!tenant) {
            sb.append(formOpen(path));
            sb.append(field("asyncGateTimeoutMs", "asyncGateMs",
                    String.valueOf(config.asyncGateTimeoutMs()), null));
            sb.append(field("asyncWaitMessage", "waitMsg", config.asyncWaitMessage(), null));
            sb.append(field("asyncHardFailMessage", "hardFailMsg",
                    config.asyncHardFailMessage(), null));
            sb.append(field("httpClientBridgeEnabled", "httpBridge",
                    String.valueOf(config.httpClientBridgeEnabled()), "true|false"));
            sb.append("<button type=\"submit\" name=\"action\" value=\"save\">Save</button>");
            sb.append("</form>");
        } else {
            sb.append("<p class=\"hint\">TENANT: gateMs=").append(config.asyncGateTimeoutMs())
                    .append(" bridge=").append(config.httpClientBridgeEnabled()).append("</p>");
        }
        labInjectForm(sb, path, true);
    }

    private void callbackHtml(StringBuilder sb, boolean tenant) {
        sb.append("<h2>HTTP AS · Callback</h2>");
        sb.append("<p class=\"hint\">Callback server (http-server-ra) accepts ")
                .append("<code>POST ").append(esc(config.httpCallbackPath()))
                .append("</code> with AsResponse JSON (content after async ACK).</p>");
        String path = "/admin/http/callback";
        if (!tenant) {
            sb.append(formOpen(path));
            sb.append(field("serverEnabled", "serverEnabled",
                    String.valueOf(config.httpServerEnabled()), "true|false"));
            sb.append(field("listenHost", "listenHost", httpApply.listenHost(), null));
            sb.append(field("listenPort", "listenPort",
                    String.valueOf(httpApply.listenPort()), null));
            sb.append(field("callbackPath", "callbackPath", config.httpCallbackPath(), null));
            sb.append(planeButtons());
            sb.append("</form>");
        } else {
            sb.append("<p class=\"hint\">TENANT: callbackPath=")
                    .append(esc(config.httpCallbackPath())).append(" listen=")
                    .append(esc(httpApply.listenHost())).append(':')
                    .append(httpApply.listenPort()).append("</p>");
        }
        labInjectForm(sb, path, false);
    }

    private static void labInjectForm(StringBuilder sb, String path, boolean asyncAck) {
        sb.append("<h3>Lab inject</h3>");
        sb.append("<p class=\"hint\">Inject AsResponse into VirtualSessionBridge for an existing ")
                .append("correlationId").append(asyncAck ? " (async=true ACK)." : " (content).")
                .append("</p>");
        sb.append(formOpen(path));
        sb.append(field("correlationId", "correlationId", "", null));
        sb.append(field("generation", "generation", "0", "0=session gen"));
        if (!asyncAck) {
            sb.append(field("text", "text", "lab-ok", null));
            sb.append("<label>asAction <select name=\"asAction\">")
                    .append("<option>END</option><option>CONTINUE</option><option>ABORT</option>")
                    .append("</select></label>");
        }
        sb.append("<input type=\"hidden\" name=\"action\" value=\"labInject\"/>");
        sb.append("<button type=\"submit\">Inject ").append(asyncAck ? "ACK" : "content")
                .append("</button></form>");
    }

    private void labMoForm(StringBuilder sb, String path, boolean tenant) {
        sb.append("<h3>Lab MO (route AS pull)</h3>");
        sb.append("<p class=\"hint\">Starts stub MO on an enabled non-MAP plane and routes PullHttp ")
                .append("like MAP. Expect SYNC reply from AS URL.</p>");
        sb.append(formOpen(path));
        sb.append(field("msisdn", "msisdn", "", null));
        sb.append(field("shortCode", "shortCode", "*123#", null));
        sb.append(field("ussd", "ussd", "*123#", null));
        if (tenant) {
            sb.append("<input type=\"hidden\" name=\"tenantId\" value=\"\"/>");
        } else {
            sb.append(field("tenantId", "tenantId", "", null));
        }
        sb.append(field("networkId", "networkId", "0", null));
        sb.append("<input type=\"hidden\" name=\"action\" value=\"labMo\"/>");
        sb.append("<button type=\"submit\">Start lab MO</button></form>");
    }

    private static String formOpen(String path) {
        return "<form hx-post=\"" + path + "\" hx-target=\"closest .catalog\" hx-swap=\"outerHTML\" "
                + "hx-headers='{\"X-USSD-Admin-Key\":\"ussd-admin\"}' class=\"grid-form\">";
    }

    private static String field(String name, String label, String value, String hint) {
        StringBuilder sb = new StringBuilder();
        sb.append("<label>").append(esc(label));
        if (hint != null) sb.append(" <span class=\"hint\">(").append(esc(hint)).append(")</span>");
        sb.append(" <input name=\"").append(esc(name)).append("\" value=\"")
                .append(esc(value == null ? "" : value)).append("\"/></label>");
        return sb.toString();
    }

    private static String planeButtons() {
        return "<div class=\"plane-actions\">"
                + "<button type=\"submit\" name=\"action\" value=\"save\">Save</button> "
                + "<button type=\"submit\" name=\"action\" value=\"saveApply\">Save &amp; Apply</button> "
                + "<button type=\"submit\" name=\"action\" value=\"apply\">Apply</button> "
                + "<button type=\"submit\" name=\"action\" value=\"start\">Start</button> "
                + "<button type=\"submit\" name=\"action\" value=\"stop\">Stop</button>"
                + "</div>";
    }

    private static RaAdminHttpResponse toRa(AdminHttpHandler.HttpReply r) {
        if (r == null) {
            return RaAdminHttpResponse.text(500, "text/plain", "null");
        }
        String ct = r.contentType() == null ? "text/html; charset=utf-8" : r.contentType();
        String body = r.body() == null ? "" : new String(r.body(), StandardCharsets.UTF_8);
        return RaAdminHttpResponse.text(r.status(), ct, body).withHeader("Vary", "HX-Request");
    }

    private static AsAction parseAction(String s) {
        if (s == null || s.isBlank()) return AsAction.END;
        try {
            return AsAction.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return AsAction.END;
        }
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static void put(Map<String, String> kv, String key, String value) {
        if (value != null) kv.put(key, value.trim());
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
