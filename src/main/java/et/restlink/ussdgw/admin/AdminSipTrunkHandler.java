package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.persist.SipTrunkEntity;
import et.restlink.ussdgw.persist.TenantEntity;
import et.restlink.ussdgw.security.AsUrlValidator;
import et.restlink.ussdgw.sip.SipTrunkService;
import et.restlink.ussdgw.tenant.TenantService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** HTMX CRUD for AS-facing SIP trunks on `/admin/sip`. ADMIN/OPS only. */
@ApplicationScoped
public class AdminSipTrunkHandler {
    private static final String DEL_BTN =
            "rounded-md border border-ink-line px-2 py-1 text-[0.65rem] uppercase tracking-wider "
                    + "text-ink-mute hover:border-signal hover:text-signal";

    @Inject SipTrunkService trunks;
    @Inject TenantService tenants;
    @Inject AsUrlValidator asUrlValidator;

    public Map<String, String> pageVars() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("{{TRUNK_ROWS}}", rowsHtml());
        m.put("{{TRUNK_TENANT_OPTS}}", tenantOptions());
        return m;
    }

    public AdminHttpHandler.HttpReply get() {
        return AdminHttpHandler.HttpReply.html(rowsHtml()).withHeader("Vary", "HX-Request");
    }

    public AdminHttpHandler.HttpReply post(String body) {
        Map<String, String> f = AdminCatalogHandler.parseForm(body);
        String action = f.getOrDefault("action", "save");
        try {
            if ("delete".equalsIgnoreCase(action)) {
                trunks.delete(f.getOrDefault("trunkId", ""));
                return rowsOk("deleted");
            }
            String peerHost = f.get("peerHost");
            Optional<String> peerSsrf = asUrls().rejectSipPeerHost(peerHost);
            if (peerSsrf.isPresent()) {
                return rowsErr(peerSsrf.get());
            }
            String template = f.get("requestUriTemplate");
            Optional<String> tplSsrf = asUrls().rejectSipRequestUriTemplate(template);
            if (tplSsrf.isPresent()) {
                return rowsErr(tplSsrf.get());
            }
            trunks.upsert(
                    f.get("trunkId"),
                    f.get("displayName"),
                    peerHost,
                    parseInt(f.get("peerPort"), 5060),
                    f.get("transport"),
                    f.get("fromUri"),
                    template,
                    f.get("inboundBody"),
                    f.get("tenantId"),
                    !"false".equalsIgnoreCase(f.getOrDefault("enabled", "true")));
            return rowsOk("saved " + f.get("trunkId"));
        } catch (RuntimeException ex) {
            return rowsErr("error: " + (ex.getMessage() == null ? "" : ex.getMessage()));
        }
    }

    private AsUrlValidator asUrls() {
        AsUrlValidator v = asUrlValidator;
        if (v == null) {
            v = new AsUrlValidator();
            asUrlValidator = v;
        }
        return v;
    }

    private String rowsHtml() {
        StringBuilder sb = new StringBuilder();
        var list = trunks.list();
        if (list.isEmpty()) {
            sb.append("<tr><td colspan=\"7\" class=\"px-3 py-4 text-ink-mute italic\">No SIP trunks.</td></tr>");
            return sb.toString();
        }
        for (SipTrunkEntity t : list) {
            sb.append("<tr>")
                    .append("<td class=\"px-3 py-2\">").append(esc(t.trunkId)).append("</td>")
                    .append("<td class=\"px-3 py-2 font-mono text-xs\">").append(esc(t.peerHost))
                    .append(':').append(t.peerPort).append("</td>")
                    .append("<td class=\"px-3 py-2\">").append(esc(t.transport)).append("</td>")
                    .append("<td class=\"px-3 py-2\">").append(esc(t.inboundBody)).append("</td>")
                    .append("<td class=\"px-3 py-2\">").append(esc(t.tenantId)).append("</td>")
                    .append("<td class=\"px-3 py-2\">").append(t.enabled).append("</td>")
                    .append("<td class=\"px-3 py-2\">")
                    .append("<form hx-post=\"/admin/sip/trunks\" hx-target=\"#sip-trunk-rows\" hx-swap=\"innerHTML\" class=\"inline\">")
                    .append("<input type=\"hidden\" name=\"action\" value=\"delete\"/>")
                    .append("<input type=\"hidden\" name=\"trunkId\" value=\"").append(esc(t.trunkId)).append("\"/>")
                    .append("<button type=\"submit\" class=\"").append(DEL_BTN).append("\">Del</button></form>")
                    .append("</td></tr>");
        }
        return sb.toString();
    }

    private String tenantOptions() {
        StringBuilder sb = new StringBuilder("<option value=\"\">— shared —</option>");
        for (TenantEntity t : tenants.list()) {
            sb.append("<option value=\"").append(esc(t.tenantId)).append("\">")
                    .append(esc(t.tenantId)).append("</option>");
        }
        return sb.toString();
    }

    /** Options for tenants form sipTrunkId select. */
    public String trunkOptionsHtml(String selected) {
        StringBuilder sb = new StringBuilder("<option value=\"\">— none —</option>");
        for (SipTrunkEntity t : trunks.list()) {
            boolean sel = selected != null && selected.equals(t.trunkId);
            sb.append("<option value=\"").append(esc(t.trunkId)).append("\"")
                    .append(sel ? " selected" : "").append('>')
                    .append(esc(t.trunkId)).append("</option>");
        }
        return sb.toString();
    }

    private AdminHttpHandler.HttpReply rowsOk(String message) {
        return AdminHttpHandler.HttpReply.html(rowsHtml())
                .withHeader("HX-Trigger", AdminHtmx.triggerToast(
                        message, "ok", "/admin/sip/trunks", "#sip-trunk-rows"))
                .withHeader("Vary", "HX-Request");
    }

    private AdminHttpHandler.HttpReply rowsErr(String message) {
        return AdminHttpHandler.HttpReply.html(rowsHtml())
                .withHeader("HX-Trigger", AdminHtmx.triggerToast(
                        message, "error", "/admin/sip/trunks", "#sip-trunk-rows"))
                .withHeader("Vary", "HX-Request");
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
