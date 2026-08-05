package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.campaign.CampaignService;
import et.restlink.ussdgw.campaign.CampaignStatus;
import et.restlink.ussdgw.campaign.CampaignTargetStatus;
import et.restlink.ussdgw.persist.CampaignEntity;
import et.restlink.ussdgw.persist.TenantEntity;
import et.restlink.ussdgw.tenant.TenantService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.UUID;

/**
 * HTMX admin panel for NI USSD push campaigns.
 */
@ApplicationScoped
public class AdminCampaignHandler {
    @Inject CampaignService campaigns;
    @Inject TenantService tenants;

    public AdminHttpHandler.HttpReply get(AdminAuthService.Principal who) {
        return AdminHttpHandler.HttpReply.html(html(null, who));
    }

    public AdminHttpHandler.HttpReply post(String body, AdminAuthService.Principal who) {
        Map<String, String> f = AdminCatalogHandler.parseForm(body);
        String action = f.getOrDefault("action", "create");
        try {
            if ("create".equalsIgnoreCase(action)) {
                String tenantId = f.getOrDefault("tenantId", "").trim();
                if (who != null && who.isTenantScoped()) {
                    tenantId = who.tenantId();
                }
                CampaignEntity c = campaigns.create(
                        f.get("name"),
                        tenantId,
                        f.get("text"),
                        f.getOrDefault("alphabet", "AUTO"),
                        parseInt(f.get("networkId"), 0),
                        parseInt(f.get("maxTps"), 5),
                        f.get("msisdns"));
                return AdminHttpHandler.HttpReply.html(html("created " + esc(c.id.toString()), who));
            }
            UUID id = UUID.fromString(f.getOrDefault("id", "").trim());
            if (who != null && who.isTenantScoped()) {
                CampaignEntity c = campaigns.byId(id).orElseThrow(
                        () -> new IllegalArgumentException("not found"));
                if (!who.tenantId().equals(c.tenantId)) {
                    return AdminHttpHandler.HttpReply.text(403, "forbidden");
                }
            }
            switch (action.toLowerCase()) {
                case "start" -> campaigns.start(id);
                case "pause" -> campaigns.pause(id);
                case "cancel" -> campaigns.cancel(id);
                default -> throw new IllegalArgumentException("unknown action: " + action);
            }
            return AdminHttpHandler.HttpReply.html(html(action + " ok", who));
        } catch (RuntimeException ex) {
            return AdminHttpHandler.HttpReply.html(html("error: " + esc(ex.getMessage()), who));
        }
    }

    private String html(String notice, AdminAuthService.Principal who) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"catalog\">");
        if (notice != null) sb.append("<p class=\"notice\">").append(esc(notice)).append("</p>");
        sb.append("<h2>NI push campaigns</h2>");
        sb.append("<p class=\"hint\">Blast USSD NI text to MSISDN list (SRI→MAP). ")
                .append("Scheduler claims 1/s when SS7 live. One SENDING per MSISDN.</p>");
        sb.append("<form hx-post=\"/admin/campaigns\" hx-target=\"#panel\" hx-swap=\"innerHTML\" ")
                .append("hx-headers='{\"X-USSD-Admin-Key\":\"ussd-admin\"}' class=\"grid-form\">");
        sb.append("<label>Name <input name=\"name\" required/></label>");
        if (who != null && who.isTenantScoped()) {
            sb.append("<input type=\"hidden\" name=\"tenantId\" value=\"")
                    .append(esc(who.tenantId())).append("\"/>");
        } else {
            sb.append("<label>tenantId <input name=\"tenantId\" list=\"tenant-ids\"/></label>");
        }
        sb.append("<label>networkId <input name=\"networkId\" type=\"number\" value=\"0\" min=\"0\"/></label>");
        sb.append("<label>maxTps <input name=\"maxTps\" type=\"number\" value=\"5\" min=\"1\" max=\"100\"/></label>");
        sb.append("<label>alphabet <select name=\"alphabet\"><option>AUTO</option>")
                .append("<option>UCS7</option><option>UCS8</option><option>UNICODE</option></select></label>");
        sb.append("<label style=\"flex:1 1 100%\">Text <input name=\"text\" size=\"60\" maxlength=\"182\" required/></label>");
        sb.append("<label style=\"flex:1 1 100%\">MSISDNs (newline/comma) ")
                .append("<textarea name=\"msisdns\" rows=\"4\" cols=\"50\" required></textarea></label>");
        sb.append("<input type=\"hidden\" name=\"action\" value=\"create\"/>");
        sb.append("<button type=\"submit\">Create draft</button></form>");
        if (who == null || !who.isTenantScoped()) {
            sb.append("<datalist id=\"tenant-ids\">");
            for (TenantEntity t : tenants.list()) {
                sb.append("<option value=\"").append(esc(t.tenantId)).append("\"/>");
            }
            sb.append("</datalist>");
        }
        String scope = who != null && who.isTenantScoped() ? who.tenantId() : null;
        sb.append("<table><tr><th>Name</th><th>tenant</th><th>status</th><th>sent/fail</th>")
                .append("<th>pending</th><th></th></tr>");
        for (CampaignEntity c : campaigns.list(scope)) {
            long pending = campaigns.targetCount(c.id, CampaignTargetStatus.PENDING.name());
            sb.append("<tr><td>").append(esc(c.name)).append("</td><td>")
                    .append(esc(c.tenantId)).append("</td><td>").append(esc(c.status))
                    .append("</td><td>").append(c.sentCount).append('/').append(c.failCount)
                    .append("</td><td>").append(pending).append("</td><td>");
            if (CampaignStatus.DRAFT.name().equals(c.status)
                    || CampaignStatus.PAUSED.name().equals(c.status)) {
                actionBtn(sb, c.id, "start", "Start");
            }
            if (CampaignStatus.RUNNING.name().equals(c.status)) {
                actionBtn(sb, c.id, "pause", "Pause");
            }
            if (!CampaignStatus.CANCELLED.name().equals(c.status)
                    && !CampaignStatus.COMPLETED.name().equals(c.status)) {
                actionBtn(sb, c.id, "cancel", "Cancel");
            }
            sb.append("</td></tr>");
        }
        sb.append("</table></div>");
        return sb.toString();
    }

    private static void actionBtn(StringBuilder sb, UUID id, String action, String label) {
        sb.append("<form hx-post=\"/admin/campaigns\" hx-target=\"#panel\" hx-swap=\"innerHTML\" ")
                .append("hx-headers='{\"X-USSD-Admin-Key\":\"ussd-admin\"}' class=\"inline\">")
                .append("<input type=\"hidden\" name=\"action\" value=\"").append(action).append("\"/>")
                .append("<input type=\"hidden\" name=\"id\" value=\"").append(id).append("\"/>")
                .append("<button type=\"submit\">").append(label).append("</button></form> ");
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
