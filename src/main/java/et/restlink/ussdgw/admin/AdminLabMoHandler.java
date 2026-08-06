package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.access.LabMoService;
import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.persist.TenantEntity;
import et.restlink.ussdgw.tenant.TenantService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * HTMX lab panel: inject stub MO on Diameter / SMPP / SIP planes (routes AS pull).
 */
@ApplicationScoped
public class AdminLabMoHandler {
    @Inject LabMoService labMo;
    @Inject TenantService tenants;
    @Inject UssdConfigService config;

    public AdminHttpHandler.HttpReply get(AdminAuthService.Principal who) {
        return AdminHttpHandler.HttpReply.html(html(null, who));
    }

    public AdminHttpHandler.HttpReply post(String body, AdminAuthService.Principal who) {
        Map<String, String> f = AdminCatalogHandler.parseForm(body);
        try {
            String planeRaw = f.getOrDefault("plane", "SMPP").trim().toUpperCase();
            OriginationType plane = OriginationType.valueOf(planeRaw);
            String tenantId = f.getOrDefault("tenantId", "").trim();
            if (who != null && who.isTenantScoped()) {
                tenantId = who.tenantId();
            }
            LabMoService.Result r = labMo.start(
                    plane,
                    f.getOrDefault("msisdn", "").trim(),
                    f.getOrDefault("shortCode", "").trim(),
                    f.getOrDefault("ussd", "").trim(),
                    tenantId.isEmpty() ? null : tenantId,
                    parseInt(f.get("networkId"), 0));
            String notice = "MO ok corr=" + r.session().correlationId()
                    + " " + r.routeDetail();
            return AdminHttpHandler.HttpReply.html(html(notice, who));
        } catch (RuntimeException ex) {
            return AdminHttpHandler.HttpReply.html(html("error: " + esc(ex.getMessage()), who));
        }
    }

    private String html(String notice, AdminAuthService.Principal who) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"catalog lab-mo-panel\">");
        if (notice != null) sb.append("<p class=\"notice\">").append(esc(notice)).append("</p>");
        sb.append("<h2>Lab MO inject</h2>");
        sb.append("<p class=\"hint\">Starts a stub MO session on Diameter/SMPP/SIP, arms AS await, ")
                .append("and routes PullHttp/PullGrpc like MAP. Plane must be enabled in config. ")
                .append("Diameter=").append(config.diameterEnabled())
                .append(" SMPP=").append(config.smppUssdEnabled())
                .append(" SIP=").append(config.sipEnabled()).append(".</p>");
        sb.append("<form hx-post=\"/admin/lab/mo\" hx-target=\"#panel\" hx-swap=\"innerHTML\" ")
                .append("hx-headers='{\"X-USSD-Admin-Key\":\"ussd-admin\"}' class=\"grid-form\">");
        sb.append("<label>plane <select name=\"plane\">")
                .append("<option>SMPP</option><option>DIAMETER</option><option>SIP</option>")
                .append("</select></label>");
        sb.append("<label>msisdn <input name=\"msisdn\" required placeholder=\"251911000000\"/></label>");
        sb.append("<label>shortCode <input name=\"shortCode\" placeholder=\"*123#\"/></label>");
        sb.append("<label style=\"flex:1 1 100%\">ussd text <input name=\"ussd\" size=\"40\" ")
                .append("placeholder=\"*123#\"/></label>");
        if (who != null && who.isTenantScoped()) {
            sb.append("<input type=\"hidden\" name=\"tenantId\" value=\"")
                    .append(esc(who.tenantId())).append("\"/>");
        } else {
            sb.append("<label>tenantId <input name=\"tenantId\" list=\"lab-tenant-ids\"/></label>");
        }
        sb.append("<label>networkId <input name=\"networkId\" type=\"number\" value=\"0\" min=\"0\"/></label>");
        sb.append("<button type=\"submit\">Inject MO</button></form>");
        if (who == null || !who.isTenantScoped()) {
            sb.append("<datalist id=\"lab-tenant-ids\">");
            for (TenantEntity t : tenants.list()) {
                sb.append("<option value=\"").append(esc(t.tenantId)).append("\"/>");
            }
            sb.append("</datalist>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
