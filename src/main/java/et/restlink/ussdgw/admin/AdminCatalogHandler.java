package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.persist.AdminUserEntity;
import et.restlink.ussdgw.persist.TenantEntity;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.routing.ShortCodeRoutingService;
import et.restlink.ussdgw.security.AsUrlValidator;
import et.restlink.ussdgw.tenant.AdminUserService;
import et.restlink.ussdgw.tenant.TenantService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * HTMX admin panels for routing, tenants (networkId), and users — OTA-shaped CRUD.
 * Full pages seed {@code {{ROWS}}} in disk templates; POST/partial returns table rows.
 */
@ApplicationScoped
public class AdminCatalogHandler {
    private static final String TD = " class=\"px-3 py-2\"";
    private static final String DEL_BTN =
            "rounded-md border border-ink-line px-2 py-1 text-[0.65rem] uppercase tracking-wider "
                    + "text-ink-mute hover:border-signal hover:text-signal";

    @Inject ShortCodeRoutingService routing;
    @Inject TenantService tenants;
    @Inject AdminUserService users;
    @Inject AsUrlValidator asUrlValidator;

    /** Tests construct this handler directly; fall back to the config-free defaults. */
    private AsUrlValidator asUrls() {
        AsUrlValidator v = asUrlValidator;
        if (v == null) {
            v = new AsUrlValidator();
            asUrlValidator = v;
        }
        return v;
    }

    /** Identity CRUD is ADMIN-only — OPS must not be able to mint an ADMIN principal. */
    private static boolean deniedForIdentityCrud(AdminAuthService.Principal who) {
        return who != null && !"ADMIN".equals(who.role());
    }

    private static AdminHttpHandler.HttpReply identityForbidden(AdminAuthService.Principal who) {
        return AdminHttpHandler.HttpReply.text(403,
                "forbidden — requires role ADMIN (have "
                        + (who == null ? "anonymous" : String.valueOf(who.role())) + ")");
    }

    public AdminHttpHandler.HttpReply routingGet() {
        return routingGet(null);
    }

    /** HTMX fragment or automation: table rows (+ legacy notice wrapper when notice set). */
    public AdminHttpHandler.HttpReply routingGet(AdminAuthService.Principal who) {
        return AdminHttpHandler.HttpReply.html(routingRowsHtml(who));
    }

    public Map<String, String> routingPageVars(AdminAuthService.Principal who) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("{{ROWS}}", routingRowsHtml(who));
        if (who != null && who.isTenantScoped()) {
            m.put("{{TENANT_FIELD}}",
                    "<input type=\"hidden\" name=\"tenantId\" value=\"" + esc(who.tenantId()) + "\"/>"
                            + "<p class=\"text-xs text-ink-mute\">tenantId locked to "
                            + "<code class=\"font-mono text-slate-300\">" + esc(who.tenantId())
                            + "</code></p>");
        } else {
            m.put("{{TENANT_FIELD}}",
                    "<div><label class=\"block text-xs uppercase tracking-wider text-ink-mute\">tenantId</label>"
                            + "<input name=\"tenantId\" list=\"tenant-ids\" "
                            + "class=\"mt-1 w-full rounded-md border border-ink-line bg-ink px-3 py-2 text-sm "
                            + "focus:border-signal focus:outline-none focus:ring-1 focus:ring-signal/40\"/>"
                            + tenantDatalistHtml() + "</div>");
        }
        return m;
    }

    public AdminHttpHandler.HttpReply tenantsGet() {
        return tenantsGet(null);
    }

    public AdminHttpHandler.HttpReply tenantsGet(AdminAuthService.Principal who) {
        if (deniedForIdentityCrud(who)) {
            return identityForbidden(who);
        }
        return AdminHttpHandler.HttpReply.html(tenantRowsHtml());
    }

    public Map<String, String> tenantsPageVars() {
        return Map.of("{{ROWS}}", tenantRowsHtml());
    }

    public AdminHttpHandler.HttpReply usersGet() {
        return usersGet(null);
    }

    public AdminHttpHandler.HttpReply usersGet(AdminAuthService.Principal who) {
        if (deniedForIdentityCrud(who)) {
            return identityForbidden(who);
        }
        return AdminHttpHandler.HttpReply.html(userRowsHtml());
    }

    public Map<String, String> usersPageVars() {
        return Map.of(
                "{{ROWS}}", userRowsHtml(),
                "{{TENANT_OPTS}}", tenantOptionsHtml());
    }

    public AdminHttpHandler.HttpReply routingPost(String body) {
        return routingPost(body, null);
    }

    public AdminHttpHandler.HttpReply routingPost(String body, AdminAuthService.Principal who) {
        Map<String, String> f = parseForm(body);
        String action = f.getOrDefault("action", "save");
        try {
            if ("reload".equalsIgnoreCase(action)) {
                routing.reloadFromDb();
                int n = routing.list().size();
                return rowsOk(routingRowsHtml(who), "reloaded " + n + " rules — live");
            }
            if ("delete".equalsIgnoreCase(action)) {
                String sc = f.getOrDefault("shortCode", "");
                if (who != null && who.isTenantScoped()) {
                    boolean owned = routing.listForTenant(who.tenantId()).stream()
                            .anyMatch(r -> sc.equals(r.shortCode()));
                    if (!owned) {
                        return rowsErr(routingRowsHtml(who), "forbidden");
                    }
                }
                routing.delete(sc);
                return rowsOk(routingRowsHtml(who), "deleted — live");
            }
            String code = f.getOrDefault("shortCode", "").trim();
            String type = f.getOrDefault("ruleType", "HTTP").trim();
            String url = f.getOrDefault("asUrl", "").trim();
            String tenantId = f.getOrDefault("tenantId", "").trim();
            int networkId = parseInt(f.get("networkId"), 0);
            boolean enabled = !"false".equalsIgnoreCase(f.getOrDefault("enabled", "true"));
            boolean mark = "true".equalsIgnoreCase(f.getOrDefault("mark", "false"));
            if (code.isEmpty() || url.isEmpty()) {
                return rowsErr(routingRowsHtml(who), "shortCode and asUrl required");
            }
            if (RuleType.HTTP.name().equalsIgnoreCase(type)) {
                Optional<String> ssrf = asUrls().reject(url);
                if (ssrf.isPresent()) {
                    return rowsErr(routingRowsHtml(who), ssrf.get());
                }
            }
            if (who != null && who.isTenantScoped()) {
                tenantId = who.tenantId();
            }
            if (!tenantId.isEmpty() && networkId == 0) {
                networkId = tenants.byId(tenantId).map(t -> t.networkId).orElse(0);
            }
            routing.putAndPersist(new ShortCodeRule(
                    code, RuleType.valueOf(type.toUpperCase()), url, enabled,
                    tenantId.isEmpty() ? null : tenantId, networkId, mark));
            return rowsOk(routingRowsHtml(who), "saved " + code + " — live");
        } catch (RuntimeException ex) {
            return rowsErr(routingRowsHtml(who), "error: " + nullToEmpty(ex.getMessage()));
        }
    }

    public AdminHttpHandler.HttpReply tenantsPost(String body) {
        return tenantsPost(body, null);
    }

    public AdminHttpHandler.HttpReply tenantsPost(String body, AdminAuthService.Principal who) {
        if (deniedForIdentityCrud(who)) {
            return identityForbidden(who);
        }
        Map<String, String> f = parseForm(body);
        String action = f.getOrDefault("action", "save");
        try {
            if ("delete".equalsIgnoreCase(action)) {
                tenants.delete(f.getOrDefault("tenantId", ""));
                return rowsOk(tenantRowsHtml(), "deleted");
            }
            String tenantId = f.getOrDefault("tenantId", "").trim();
            if (tenantId.isEmpty()) {
                return rowsErr(tenantRowsHtml(), "tenantId required");
            }
            TenantEntity e = tenants.upsert(
                    tenantId,
                    f.get("displayName"),
                    parseInt(f.get("networkId"), 0),
                    !"false".equalsIgnoreCase(f.getOrDefault("enabled", "true")),
                    f.get("httpApiKey"),
                    f.get("smppSystemId"),
                    f.get("smppPassword"),
                    f.get("asCallbackBase"),
                    parseInt(f.get("maxTps"), 50),
                    f.get("httpAsWireFormat"));
            String notice = "saved " + e.tenantId + " networkId=" + e.networkId
                    + " wire=" + nullToEmpty(e.httpAsWireFormat)
                    + " key=" + maskKey(e.httpApiKey);
            return rowsOk(tenantRowsHtml(), notice);
        } catch (RuntimeException ex) {
            return rowsErr(tenantRowsHtml(), "error: " + nullToEmpty(ex.getMessage()));
        }
    }

    public AdminHttpHandler.HttpReply usersPost(String body) {
        return usersPost(body, null);
    }

    public AdminHttpHandler.HttpReply usersPost(String body, AdminAuthService.Principal who) {
        if (deniedForIdentityCrud(who)) {
            return identityForbidden(who);
        }
        Map<String, String> f = parseForm(body);
        String action = f.getOrDefault("action", "create");
        try {
            if ("delete".equalsIgnoreCase(action)) {
                users.delete(f.getOrDefault("username", ""));
                return rowsOk(userRowsHtml(), "deleted");
            }
            String username = f.getOrDefault("username", "").trim();
            String password = f.getOrDefault("password", "");
            String role = f.getOrDefault("role", "OPS");
            String tenantId = f.getOrDefault("tenantId", "");
            String display = f.getOrDefault("displayName", "");
            boolean enabled = !"false".equalsIgnoreCase(f.getOrDefault("enabled", "true"));
            if (username.isEmpty()) {
                return rowsErr(userRowsHtml(), "username required");
            }
            if ("update".equalsIgnoreCase(action)) {
                users.update(username, password, role, tenantId, display, enabled);
                return rowsOk(userRowsHtml(), "updated " + username);
            }
            if (password.isBlank()) {
                return rowsErr(userRowsHtml(), "password required for create");
            }
            users.create(username, password, role, tenantId, display, enabled);
            return rowsOk(userRowsHtml(), "created " + username);
        } catch (RuntimeException ex) {
            return rowsErr(userRowsHtml(), "error: " + nullToEmpty(ex.getMessage()));
        }
    }

    String routingRowsHtml(AdminAuthService.Principal who) {
        StringBuilder sb = new StringBuilder();
        var rules = who != null && who.isTenantScoped()
                ? routing.listForTenant(who.tenantId()) : routing.list();
        if (rules.isEmpty()) {
            sb.append("<tr><td colspan=\"8\" class=\"px-3 py-4 text-ink-mute italic\">No short-code rules.</td></tr>");
            return sb.toString();
        }
        for (ShortCodeRule r : rules) {
            sb.append("<tr><td").append(TD).append(">").append(esc(r.shortCode())).append("</td><td")
                    .append(TD).append(">").append(esc(String.valueOf(r.ruleType()))).append("</td><td")
                    .append(TD).append(">").append(esc(r.asUrl())).append("</td><td")
                    .append(TD).append(">").append(esc(r.tenantId())).append("</td><td")
                    .append(TD).append(">").append(r.networkId()).append("</td><td")
                    .append(TD).append(">").append(r.mark()).append("</td><td")
                    .append(TD).append(">").append(r.enabled()).append("</td><td").append(TD).append(">");
            sb.append("<form hx-post=\"/admin/routing\" hx-target=\"#rule-rows\" hx-swap=\"innerHTML\" class=\"inline\">")
                    .append("<input type=\"hidden\" name=\"action\" value=\"delete\"/>")
                    .append("<input type=\"hidden\" name=\"shortCode\" value=\"").append(esc(r.shortCode())).append("\"/>")
                    .append("<button type=\"submit\" class=\"").append(DEL_BTN).append("\">Del</button></form></td></tr>");
        }
        return sb.toString();
    }

    String tenantRowsHtml() {
        StringBuilder sb = new StringBuilder();
        var list = tenants.list();
        if (list.isEmpty()) {
            sb.append("<tr><td colspan=\"9\" class=\"px-3 py-4 text-ink-mute italic\">No tenants.</td></tr>");
            return sb.toString();
        }
        for (TenantEntity t : list) {
            sb.append("<tr><td").append(TD).append(">").append(esc(t.tenantId)).append("</td><td")
                    .append(TD).append(">").append(esc(t.displayName)).append("</td><td")
                    .append(TD).append(">").append(t.networkId).append("</td><td")
                    .append(TD).append(">").append(esc(t.httpAsWireFormat)).append("</td><td")
                    .append(TD).append(">").append(esc(t.smppSystemId)).append("</td><td")
                    .append(TD).append(">").append(esc(maskKey(t.httpApiKey))).append("</td><td")
                    .append(TD).append(">").append(t.maxTps).append("</td><td")
                    .append(TD).append(">").append(t.enabled).append("</td><td").append(TD).append(">");
            sb.append("<form hx-post=\"/admin/tenants\" hx-target=\"#tenant-rows\" hx-swap=\"innerHTML\" class=\"inline\">")
                    .append("<input type=\"hidden\" name=\"action\" value=\"delete\"/>")
                    .append("<input type=\"hidden\" name=\"tenantId\" value=\"").append(esc(t.tenantId)).append("\"/>")
                    .append("<button type=\"submit\" class=\"").append(DEL_BTN).append("\">Del</button></form></td></tr>");
        }
        return sb.toString();
    }

    String userRowsHtml() {
        StringBuilder sb = new StringBuilder();
        var list = users.list();
        if (list.isEmpty()) {
            sb.append("<tr><td colspan=\"6\" class=\"px-3 py-4 text-ink-mute italic\">No users.</td></tr>");
            return sb.toString();
        }
        for (AdminUserEntity u : list) {
            sb.append("<tr><td").append(TD).append(">").append(esc(u.username)).append("</td><td")
                    .append(TD).append(">").append(esc(u.role)).append("</td><td")
                    .append(TD).append(">").append(esc(u.tenantId)).append("</td><td")
                    .append(TD).append(">").append(esc(u.displayName)).append("</td><td")
                    .append(TD).append(">").append(u.enabled).append("</td><td").append(TD).append(">");
            sb.append("<form hx-post=\"/admin/users\" hx-target=\"#user-rows\" hx-swap=\"innerHTML\" class=\"inline\">")
                    .append("<input type=\"hidden\" name=\"action\" value=\"delete\"/>")
                    .append("<input type=\"hidden\" name=\"username\" value=\"").append(esc(u.username)).append("\"/>")
                    .append("<button type=\"submit\" class=\"").append(DEL_BTN).append("\">Del</button></form></td></tr>");
        }
        return sb.toString();
    }

    private String tenantDatalistHtml() {
        StringBuilder sb = new StringBuilder("<datalist id=\"tenant-ids\">");
        for (TenantEntity t : tenants.list()) {
            sb.append("<option value=\"").append(esc(t.tenantId)).append("\"/>");
        }
        sb.append("</datalist>");
        return sb.toString();
    }

    private String tenantOptionsHtml() {
        StringBuilder sb = new StringBuilder("<option value=\"\">—</option>");
        for (TenantEntity t : tenants.list()) {
            sb.append("<option value=\"").append(esc(t.tenantId)).append("\">")
                    .append(esc(t.tenantId)).append("</option>");
        }
        return sb.toString();
    }

    private static AdminHttpHandler.HttpReply rowsOk(String rows, String message) {
        return AdminHttpHandler.HttpReply.html(rows)
                .withHeader("HX-Trigger", toastJson(message, "ok"))
                .withHeader("Vary", "HX-Request");
    }

    private static AdminHttpHandler.HttpReply rowsErr(String rows, String message) {
        return AdminHttpHandler.HttpReply.html(rows)
                .withHeader("HX-Trigger", toastJson(message, "error"))
                .withHeader("Vary", "HX-Request");
    }

    private static String toastJson(String message, String kind) {
        return "{\"ussdToast\":{\"message\":" + jsonStr(message) + ",\"kind\":" + jsonStr(kind) + "}}";
    }

    private static String jsonStr(String s) {
        if (s == null) {
            return "\"\"";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private static String maskKey(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "…" + key.substring(key.length() - 4);
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return m;
        for (String part : body.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8);
            String v = URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
