package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.access.LabMoService;
import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.persist.TenantEntity;
import et.restlink.ussdgw.tenant.TenantService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
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
        return AdminHttpHandler.HttpReply.html("<p class=\"text-ink-mute\">Ready — use the form above.</p>")
                .withHeader("Vary", "HX-Request");
    }

    /** Disk-template seed for {@code lab-mo.html}. */
    public Map<String, String> pageVars(AdminAuthService.Principal who) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("{{DIAM_ON}}", String.valueOf(config.diameterEnabled()));
        m.put("{{SMPP_ON}}", String.valueOf(config.smppUssdEnabled()));
        m.put("{{SIP_ON}}", String.valueOf(config.sipEnabled()));
        m.put("{{LAST_RESULT}}", "Ready — inject an MO below.");
        m.put("{{TENANT_DATALIST}}", tenantDatalist(who));
        return m;
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
            return noticeReply(notice, "ok");
        } catch (RuntimeException ex) {
            return noticeReply("error: " + nullToEmpty(ex.getMessage()), "error");
        }
    }

    private static AdminHttpHandler.HttpReply noticeReply(String message, String kind) {
        String html = "<p class=\"admin-notice\">" + esc(message) + "</p>";
        return AdminHttpHandler.HttpReply.html(html)
                .withHeader("HX-Trigger",
                        "{\"ussdToast\":{\"message\":" + jsonStr(message)
                                + ",\"kind\":" + jsonStr(kind) + "}}")
                .withHeader("Vary", "HX-Request");
    }

    private String tenantDatalist(AdminAuthService.Principal who) {
        if (who != null && who.isTenantScoped()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<datalist id=\"lab-tenant-ids\">");
        for (TenantEntity t : tenants.list()) {
            sb.append("<option value=\"").append(esc(t.tenantId)).append("\"/>");
        }
        sb.append("</datalist>");
        return sb.toString();
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String jsonStr(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
