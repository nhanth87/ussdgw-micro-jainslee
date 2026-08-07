package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.persist.AppUserEntity;
import et.restlink.ussdgw.persist.TenantEntity;
import et.restlink.ussdgw.tenant.AppUserService;
import et.restlink.ussdgw.tenant.TenantService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API app-users under a tenant (NI key). ADMIN creates for any tenant;
 * TENANT creates/lists only for own tenantId.
 */
@ApplicationScoped
public class AdminAppUserHandler {
    private static final String DEL_BTN =
            "rounded-md border border-ink-line px-2 py-1 text-[0.65rem] uppercase tracking-wider "
                    + "text-ink-mute hover:border-signal hover:text-signal";

    @Inject AppUserService appUsers;
    @Inject TenantService tenants;

    public Map<String, String> pageVars(AdminAuthService.Principal who) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("{{APP_USER_ROWS}}", rowsHtml(who));
        if (who != null && who.isTenantScoped()) {
            m.put("{{APP_TENANT_FIELD}}",
                    "<input type=\"hidden\" name=\"tenantId\" value=\"" + esc(who.tenantId()) + "\"/>"
                            + "<p class=\"text-xs text-ink-mute\">tenant locked to "
                            + "<code class=\"font-mono text-slate-300\">" + esc(who.tenantId())
                            + "</code></p>");
        } else {
            StringBuilder opts = new StringBuilder(
                    "<div><label class=\"block text-xs uppercase tracking-wider text-ink-mute\">tenantId</label>"
                            + "<select name=\"tenantId\" required class=\"mt-1 w-full rounded-md border border-ink-line "
                            + "bg-ink px-3 py-2 text-sm focus:border-signal focus:outline-none\">");
            opts.append("<option value=\"\">—</option>");
            for (TenantEntity t : tenants.list()) {
                opts.append("<option value=\"").append(esc(t.tenantId)).append("\">")
                        .append(esc(t.tenantId)).append("</option>");
            }
            opts.append("</select></div>");
            m.put("{{APP_TENANT_FIELD}}", opts.toString());
        }
        m.put("{{APP_KEY_NOTICE}}", "");
        return m;
    }

    public AdminHttpHandler.HttpReply get(AdminAuthService.Principal who) {
        return AdminHttpHandler.HttpReply.html(rowsHtml(who)).withHeader("Vary", "HX-Request");
    }

    public AdminHttpHandler.HttpReply post(String body, AdminAuthService.Principal who) {
        Map<String, String> f = AdminCatalogHandler.parseForm(body);
        String action = f.getOrDefault("action", "create");
        try {
            if ("delete".equalsIgnoreCase(action)) {
                String user = f.getOrDefault("username", "").trim();
                if (!mayTouch(who, user)) {
                    return rowsErr(who, "forbidden");
                }
                appUsers.delete(user);
                return rowsOk(who, "deleted " + user, null);
            }
            String username = f.getOrDefault("username", "").trim();
            String tenantId = f.getOrDefault("tenantId", "").trim();
            if (who != null && who.isTenantScoped()) {
                tenantId = who.tenantId();
            }
            if (username.isEmpty() || tenantId.isEmpty()) {
                return rowsErr(who, "username and tenantId required");
            }
            if (who != null && who.isTenantScoped() && !who.tenantId().equals(tenantId)) {
                return rowsErr(who, "forbidden");
            }
            if ("update".equalsIgnoreCase(action)) {
                Boolean enabled = null;
                if (f.containsKey("enabled")) {
                    enabled = !"false".equalsIgnoreCase(f.get("enabled"));
                }
                appUsers.update(username, tenantId, f.get("apiKey"), enabled);
                return rowsOk(who, "updated " + username, null);
            }
            var created = appUsers.create(username, tenantId, f.get("apiKey"));
            String notice = "created " + username + " — API key (copy now): " + created.plaintextApiKey();
            return rowsOk(who, notice, created.plaintextApiKey());
        } catch (RuntimeException ex) {
            return rowsErr(who, "error: " + nullToEmpty(ex.getMessage()));
        }
    }

    private boolean mayTouch(AdminAuthService.Principal who, String username) {
        if (who == null || who.isAdminOrOps()) {
            return true;
        }
        if (!who.isTenantScoped()) {
            return false;
        }
        return appUsers.byUsername(username)
                .map(u -> who.tenantId().equals(u.tenantId))
                .orElse(false);
    }

    private String rowsHtml(AdminAuthService.Principal who) {
        String scope = who != null && who.isTenantScoped() ? who.tenantId() : null;
        StringBuilder sb = new StringBuilder();
        var list = appUsers.list(scope);
        if (list.isEmpty()) {
            sb.append("<tr><td colspan=\"5\" class=\"px-3 py-4 text-ink-mute italic\">No app users.</td></tr>");
            return sb.toString();
        }
        for (AppUserEntity u : list) {
            sb.append("<tr><td class=\"px-3 py-2\">").append(esc(u.username)).append("</td>")
                    .append("<td class=\"px-3 py-2\">").append(esc(u.tenantId)).append("</td>")
                    .append("<td class=\"px-3 py-2 font-mono text-xs\">").append(esc(u.apiKeyFp)).append("</td>")
                    .append("<td class=\"px-3 py-2\">").append(u.enabled).append("</td>")
                    .append("<td class=\"px-3 py-2\">")
                    .append("<form hx-post=\"/admin/app-users\" hx-target=\"#app-user-rows\" hx-swap=\"innerHTML\" class=\"inline\">")
                    .append("<input type=\"hidden\" name=\"action\" value=\"delete\"/>")
                    .append("<input type=\"hidden\" name=\"username\" value=\"").append(esc(u.username)).append("\"/>")
                    .append("<button type=\"submit\" class=\"").append(DEL_BTN).append("\">Del</button></form>")
                    .append("</td></tr>");
        }
        return sb.toString();
    }

    private AdminHttpHandler.HttpReply rowsOk(AdminAuthService.Principal who, String message,
                                              String plaintextKey) {
        String html = rowsHtml(who);
        if (plaintextKey != null && !plaintextKey.isBlank()) {
            html = "<div class=\"mb-3 rounded-md border border-signal/50 bg-signal/10 p-3 font-mono text-xs text-slate-100\">"
                    + esc(message) + "</div>" + html;
        }
        return AdminHttpHandler.HttpReply.html(html)
                .withHeader("HX-Trigger", toastJson(message, "ok"))
                .withHeader("Vary", "HX-Request");
    }

    private AdminHttpHandler.HttpReply rowsErr(AdminAuthService.Principal who, String message) {
        return AdminHttpHandler.HttpReply.html(rowsHtml(who))
                .withHeader("HX-Trigger", toastJson(message, "error"))
                .withHeader("Vary", "HX-Request");
    }

    private static String toastJson(String message, String kind) {
        return "{\"ussdToast\":{\"message\":" + jsonStr(message) + ",\"kind\":" + jsonStr(kind) + "}}";
    }

    private static String jsonStr(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
