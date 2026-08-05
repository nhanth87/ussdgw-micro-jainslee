package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.persist.AdminUserEntity;
import et.restlink.ussdgw.persist.TenantEntity;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.routing.ShortCodeRoutingService;
import et.restlink.ussdgw.tenant.AdminUserService;
import et.restlink.ussdgw.tenant.TenantService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTMX admin panels for routing, tenants (networkId), and users — OTA-shaped CRUD.
 */
@ApplicationScoped
public class AdminCatalogHandler {
    @Inject ShortCodeRoutingService routing;
    @Inject TenantService tenants;
    @Inject AdminUserService users;

    public AdminHttpHandler.HttpReply routingGet() {
        return routingGet(null);
    }

    public AdminHttpHandler.HttpReply routingGet(AdminAuthService.Principal who) {
        return AdminHttpHandler.HttpReply.html(routingHtml(null, who));
    }

    public AdminHttpHandler.HttpReply tenantsGet() {
        return tenantsGet(null);
    }

    public AdminHttpHandler.HttpReply tenantsGet(AdminAuthService.Principal who) {
        if (who != null && who.isTenantScoped()) {
            return AdminHttpHandler.HttpReply.text(403, "forbidden for TENANT role");
        }
        return AdminHttpHandler.HttpReply.html(tenantsHtml(null));
    }

    public AdminHttpHandler.HttpReply usersGet() {
        return usersGet(null);
    }

    public AdminHttpHandler.HttpReply usersGet(AdminAuthService.Principal who) {
        if (who != null && who.isTenantScoped()) {
            return AdminHttpHandler.HttpReply.text(403, "forbidden for TENANT role");
        }
        return AdminHttpHandler.HttpReply.html(usersHtml(null));
    }

    public AdminHttpHandler.HttpReply routingPost(String body) {
        return routingPost(body, null);
    }

    public AdminHttpHandler.HttpReply routingPost(String body, AdminAuthService.Principal who) {
        Map<String, String> f = parseForm(body);
        String action = f.getOrDefault("action", "save");
        try {
            if ("delete".equalsIgnoreCase(action)) {
                String sc = f.getOrDefault("shortCode", "");
                if (who != null && who.isTenantScoped()) {
                    boolean owned = routing.listForTenant(who.tenantId()).stream()
                            .anyMatch(r -> sc.equals(r.shortCode()));
                    if (!owned) {
                        return AdminHttpHandler.HttpReply.html(routingHtml("forbidden", who));
                    }
                }
                routing.delete(sc);
                return AdminHttpHandler.HttpReply.html(routingHtml("deleted", who));
            }
            String code = f.getOrDefault("shortCode", "").trim();
            String type = f.getOrDefault("ruleType", "HTTP").trim();
            String url = f.getOrDefault("asUrl", "").trim();
            String tenantId = f.getOrDefault("tenantId", "").trim();
            int networkId = parseInt(f.get("networkId"), 0);
            boolean enabled = !"false".equalsIgnoreCase(f.getOrDefault("enabled", "true"));
            if (code.isEmpty() || url.isEmpty()) {
                return AdminHttpHandler.HttpReply.html(routingHtml("shortCode and asUrl required", who));
            }
            if (who != null && who.isTenantScoped()) {
                tenantId = who.tenantId();
            }
            // Inherit networkId from tenant when set and network left at 0
            if (!tenantId.isEmpty() && networkId == 0) {
                networkId = tenants.byId(tenantId).map(t -> t.networkId).orElse(0);
            }
            routing.putAndPersist(new ShortCodeRule(
                    code, RuleType.valueOf(type.toUpperCase()), url, enabled,
                    tenantId.isEmpty() ? null : tenantId, networkId));
            return AdminHttpHandler.HttpReply.html(routingHtml("saved " + esc(code), who));
        } catch (RuntimeException ex) {
            return AdminHttpHandler.HttpReply.html(routingHtml("error: " + esc(ex.getMessage()), who));
        }
    }

    public AdminHttpHandler.HttpReply tenantsPost(String body) {
        return tenantsPost(body, null);
    }

    public AdminHttpHandler.HttpReply tenantsPost(String body, AdminAuthService.Principal who) {
        if (who != null && who.isTenantScoped()) {
            return AdminHttpHandler.HttpReply.text(403, "forbidden for TENANT role");
        }
        Map<String, String> f = parseForm(body);
        String action = f.getOrDefault("action", "save");
        try {
            if ("delete".equalsIgnoreCase(action)) {
                tenants.delete(f.getOrDefault("tenantId", ""));
                return AdminHttpHandler.HttpReply.html(tenantsHtml("deleted"));
            }
            String tenantId = f.getOrDefault("tenantId", "").trim();
            if (tenantId.isEmpty()) {
                return AdminHttpHandler.HttpReply.html(tenantsHtml("tenantId required"));
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
                    parseInt(f.get("maxTps"), 50));
            String notice = "saved " + esc(e.tenantId) + " networkId=" + e.networkId
                    + " key=" + maskKey(e.httpApiKey);
            return AdminHttpHandler.HttpReply.html(tenantsHtml(notice));
        } catch (RuntimeException ex) {
            return AdminHttpHandler.HttpReply.html(tenantsHtml("error: " + esc(ex.getMessage())));
        }
    }

    public AdminHttpHandler.HttpReply usersPost(String body) {
        return usersPost(body, null);
    }

    public AdminHttpHandler.HttpReply usersPost(String body, AdminAuthService.Principal who) {
        if (who != null && who.isTenantScoped()) {
            return AdminHttpHandler.HttpReply.text(403, "forbidden for TENANT role");
        }
        Map<String, String> f = parseForm(body);
        String action = f.getOrDefault("action", "create");
        try {
            if ("delete".equalsIgnoreCase(action)) {
                users.delete(f.getOrDefault("username", ""));
                return AdminHttpHandler.HttpReply.html(usersHtml("deleted"));
            }
            String username = f.getOrDefault("username", "").trim();
            String password = f.getOrDefault("password", "");
            String role = f.getOrDefault("role", "OPS");
            String tenantId = f.getOrDefault("tenantId", "");
            String display = f.getOrDefault("displayName", "");
            boolean enabled = !"false".equalsIgnoreCase(f.getOrDefault("enabled", "true"));
            if (username.isEmpty()) {
                return AdminHttpHandler.HttpReply.html(usersHtml("username required"));
            }
            if ("update".equalsIgnoreCase(action)) {
                users.update(username, password, role, tenantId, display, enabled);
                return AdminHttpHandler.HttpReply.html(usersHtml("updated " + esc(username)));
            }
            if (password.isBlank()) {
                return AdminHttpHandler.HttpReply.html(usersHtml("password required for create"));
            }
            users.create(username, password, role, tenantId, display, enabled);
            return AdminHttpHandler.HttpReply.html(usersHtml("created " + esc(username)));
        } catch (RuntimeException ex) {
            return AdminHttpHandler.HttpReply.html(usersHtml("error: " + esc(ex.getMessage())));
        }
    }

    private String routingHtml(String notice, AdminAuthService.Principal who) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"catalog\">");
        if (notice != null) sb.append("<p class=\"notice\">").append(esc(notice)).append("</p>");
        sb.append("<h2>Short-code routing</h2>");
        sb.append("<p class=\"hint\">Bind short code → HTTP/gRPC AS URL. Optional tenantId / networkId ")
                .append("(inherits networkId from tenant when left 0).</p>");
        sb.append("<form hx-post=\"/admin/routing\" hx-target=\"#panel\" hx-swap=\"innerHTML\" ")
                .append("hx-headers='{\"X-USSD-Admin-Key\":\"ussd-admin\"}' class=\"grid-form\">");
        sb.append("<label>Code <input name=\"shortCode\" placeholder=\"*123#\" required/></label>");
        sb.append("<label>Type <select name=\"ruleType\"><option>HTTP</option><option>GRPC</option></select></label>");
        sb.append("<label>AS URL <input name=\"asUrl\" size=\"40\" required/></label>");
        if (who != null && who.isTenantScoped()) {
            sb.append("<input type=\"hidden\" name=\"tenantId\" value=\"").append(esc(who.tenantId())).append("\"/>");
        } else {
            sb.append("<label>tenantId <input name=\"tenantId\" list=\"tenant-ids\"/></label>");
        }
        sb.append("<label>networkId <input name=\"networkId\" type=\"number\" value=\"0\" min=\"0\"/></label>");
        sb.append("<label>enabled <select name=\"enabled\"><option>true</option><option>false</option></select></label>");
        sb.append("<input type=\"hidden\" name=\"action\" value=\"save\"/>");
        sb.append("<button type=\"submit\">Save rule</button></form>");
        if (who == null || !who.isTenantScoped()) {
            tenantDatalist(sb);
        }
        sb.append("<table><tr><th>Code</th><th>Type</th><th>URL</th><th>tenant</th><th>net</th><th>on</th><th></th></tr>");
        var rules = who != null && who.isTenantScoped()
                ? routing.listForTenant(who.tenantId()) : routing.list();
        for (ShortCodeRule r : rules) {
            sb.append("<tr><td>").append(esc(r.shortCode())).append("</td><td>")
                    .append(r.ruleType()).append("</td><td>").append(esc(r.asUrl())).append("</td><td>")
                    .append(esc(r.tenantId())).append("</td><td>").append(r.networkId()).append("</td><td>")
                    .append(r.enabled()).append("</td><td>");
            sb.append("<form hx-post=\"/admin/routing\" hx-target=\"#panel\" hx-swap=\"innerHTML\" ")
                    .append("hx-headers='{\"X-USSD-Admin-Key\":\"ussd-admin\"}' class=\"inline\">")
                    .append("<input type=\"hidden\" name=\"action\" value=\"delete\"/>")
                    .append("<input type=\"hidden\" name=\"shortCode\" value=\"").append(esc(r.shortCode())).append("\"/>")
                    .append("<button type=\"submit\">Del</button></form></td></tr>");
        }
        sb.append("</table></div>");
        return sb.toString();
    }

    private String tenantsHtml(String notice) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"catalog\">");
        if (notice != null) sb.append("<p class=\"notice\">").append(esc(notice)).append("</p>");
        sb.append("<h2>Tenants (networkId)</h2>");
        sb.append("<p class=\"hint\">tenantId ↔ networkId (jSS7 setNetworkId / CDR / short-code inherit). ")
                .append("Blank HTTP key generates one. SMPP password is write-only. ")
                .append("After smppSystemId, Apply SMPP allowlist.</p>");
        sb.append("<form hx-post=\"/admin/tenants\" hx-target=\"#panel\" hx-swap=\"innerHTML\" ")
                .append("hx-headers='{\"X-USSD-Admin-Key\":\"ussd-admin\"}' class=\"grid-form\">");
        sb.append("<label>tenantId <input name=\"tenantId\" required placeholder=\"ethio-1\"/></label>");
        sb.append("<label>Display <input name=\"displayName\"/></label>");
        sb.append("<label>networkId <input name=\"networkId\" type=\"number\" value=\"0\" min=\"0\"/></label>");
        sb.append("<label>HTTP API key <input name=\"httpApiKey\" placeholder=\"blank=generate\" autocomplete=\"off\"/></label>");
        sb.append("<label>SMPP systemId <input name=\"smppSystemId\"/></label>");
        sb.append("<label>SMPP password <input name=\"smppPassword\" type=\"password\" autocomplete=\"new-password\" placeholder=\"write-only\"/></label>");
        sb.append("<label>AS callback base <input name=\"asCallbackBase\" size=\"40\"/></label>");
        sb.append("<label>maxTps <input name=\"maxTps\" type=\"number\" value=\"50\" min=\"1\"/></label>");
        sb.append("<label>enabled <select name=\"enabled\"><option>true</option><option>false</option></select></label>");
        sb.append("<input type=\"hidden\" name=\"action\" value=\"save\"/>");
        sb.append("<button type=\"submit\">Save tenant</button></form>");
        sb.append("<table><tr><th>tenantId</th><th>name</th><th>networkId</th><th>smpp</th><th>key</th><th>tps</th><th>on</th><th></th></tr>");
        for (TenantEntity t : tenants.list()) {
            sb.append("<tr><td>").append(esc(t.tenantId)).append("</td><td>")
                    .append(esc(t.displayName)).append("</td><td>").append(t.networkId).append("</td><td>")
                    .append(esc(t.smppSystemId)).append("</td><td>").append(esc(maskKey(t.httpApiKey))).append("</td><td>")
                    .append(t.maxTps).append("</td><td>").append(t.enabled).append("</td><td>");
            sb.append("<form hx-post=\"/admin/tenants\" hx-target=\"#panel\" hx-swap=\"innerHTML\" ")
                    .append("hx-headers='{\"X-USSD-Admin-Key\":\"ussd-admin\"}' class=\"inline\">")
                    .append("<input type=\"hidden\" name=\"action\" value=\"delete\"/>")
                    .append("<input type=\"hidden\" name=\"tenantId\" value=\"").append(esc(t.tenantId)).append("\"/>")
                    .append("<button type=\"submit\">Del</button></form></td></tr>");
        }
        sb.append("</table></div>");
        return sb.toString();
    }

    private String usersHtml(String notice) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"catalog\">");
        if (notice != null) sb.append("<p class=\"notice\">").append(esc(notice)).append("</p>");
        sb.append("<h2>Admin users</h2>");
        sb.append("<p class=\"hint\">Roles: ADMIN | OPS | TENANT. TENANT login <b>username must equal tenantId</b> ")
                .append("(e.g. ethio-bank) — RestLink is only a dist brand, not a required username. ")
                .append("Passwords stored as SHA-256 hex (lab). ")
                .append("Use action=update with existing username to change role/password.</p>");
        sb.append("<form hx-post=\"/admin/users\" hx-target=\"#panel\" hx-swap=\"innerHTML\" ")
                .append("hx-headers='{\"X-USSD-Admin-Key\":\"ussd-admin\"}' class=\"grid-form\">");
        sb.append("<label>Username <input name=\"username\" required/></label>");
        sb.append("<label>Password <input name=\"password\" type=\"password\" placeholder=\"required on create\"/></label>");
        sb.append("<label>Role <select name=\"role\"><option>OPS</option><option>ADMIN</option><option>TENANT</option></select></label>");
        sb.append("<label>tenantId <input name=\"tenantId\" list=\"tenant-ids\"/></label>");
        sb.append("<label>Display <input name=\"displayName\"/></label>");
        sb.append("<label>enabled <select name=\"enabled\"><option>true</option><option>false</option></select></label>");
        sb.append("<label>action <select name=\"action\"><option value=\"create\">create</option>")
                .append("<option value=\"update\">update</option></select></label>");
        sb.append("<button type=\"submit\">Submit</button></form>");
        tenantDatalist(sb);
        sb.append("<table><tr><th>user</th><th>role</th><th>tenant</th><th>display</th><th>on</th><th></th></tr>");
        for (AdminUserEntity u : users.list()) {
            sb.append("<tr><td>").append(esc(u.username)).append("</td><td>")
                    .append(esc(u.role)).append("</td><td>").append(esc(u.tenantId)).append("</td><td>")
                    .append(esc(u.displayName)).append("</td><td>").append(u.enabled).append("</td><td>");
            sb.append("<form hx-post=\"/admin/users\" hx-target=\"#panel\" hx-swap=\"innerHTML\" ")
                    .append("hx-headers='{\"X-USSD-Admin-Key\":\"ussd-admin\"}' class=\"inline\">")
                    .append("<input type=\"hidden\" name=\"action\" value=\"delete\"/>")
                    .append("<input type=\"hidden\" name=\"username\" value=\"").append(esc(u.username)).append("\"/>")
                    .append("<button type=\"submit\">Del</button></form></td></tr>");
        }
        sb.append("</table></div>");
        return sb.toString();
    }

    private void tenantDatalist(StringBuilder sb) {
        sb.append("<datalist id=\"tenant-ids\">");
        for (TenantEntity t : tenants.list()) {
            sb.append("<option value=\"").append(esc(t.tenantId)).append("\"/>");
        }
        sb.append("</datalist>");
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

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
