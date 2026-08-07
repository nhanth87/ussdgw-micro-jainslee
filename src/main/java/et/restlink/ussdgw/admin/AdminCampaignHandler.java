package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.campaign.CampaignService;
import et.restlink.ussdgw.campaign.CampaignStatus;
import et.restlink.ussdgw.campaign.CampaignTargetStatus;
import et.restlink.ussdgw.persist.CampaignEntity;
import et.restlink.ussdgw.persist.TenantEntity;
import et.restlink.ussdgw.tenant.TenantService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * HTMX admin panel for NI USSD push campaigns.
 */
@ApplicationScoped
public class AdminCampaignHandler {
    private static final String DEL_BTN =
            "rounded-md border border-ink-line px-2 py-1 text-xs text-ink-mute hover:border-signal hover:text-signal";

    @Inject CampaignService campaigns;
    @Inject TenantService tenants;

    public AdminHttpHandler.HttpReply get(AdminAuthService.Principal who) {
        return AdminHttpHandler.HttpReply.html(rowsHtml(who)).withHeader("Vary", "HX-Request");
    }

    /** Disk-template seed for {@code campaigns.html}. */
    public Map<String, String> pageVars(AdminAuthService.Principal who) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("{{ROWS}}", rowsHtml(who));
        m.put("{{TENANT_DATALIST}}", tenantDatalist(who));
        return m;
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
                return rowsReply(who, "created " + c.id, "ok");
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
            return rowsReply(who, action + " ok", "ok");
        } catch (RuntimeException ex) {
            return rowsReply(who, "error: " + nullToEmpty(ex.getMessage()), "error");
        }
    }

    private AdminHttpHandler.HttpReply rowsReply(AdminAuthService.Principal who,
                                                 String message, String kind) {
        return AdminHttpHandler.HttpReply.html(rowsHtml(who))
                .withHeader("HX-Trigger",
                        "{\"ussdToast\":{\"message\":" + jsonStr(message)
                                + ",\"kind\":" + jsonStr(kind) + "}}")
                .withHeader("Vary", "HX-Request");
    }

    private String rowsHtml(AdminAuthService.Principal who) {
        StringBuilder sb = new StringBuilder();
        String scope = who != null && who.isTenantScoped() ? who.tenantId() : null;
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
        if (sb.isEmpty()) {
            sb.append("<tr><td colspan=\"6\" class=\"px-3 py-4 text-ink-mute\">No campaigns</td></tr>");
        }
        return sb.toString();
    }

    private String tenantDatalist(AdminAuthService.Principal who) {
        if (who != null && who.isTenantScoped()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<datalist id=\"tenant-ids\">");
        for (TenantEntity t : tenants.list()) {
            sb.append("<option value=\"").append(esc(t.tenantId)).append("\"/>");
        }
        sb.append("</datalist>");
        return sb.toString();
    }

    private static void actionBtn(StringBuilder sb, UUID id, String action, String label) {
        sb.append("<form hx-post=\"/admin/campaigns\" hx-target=\"#campaign-rows\" hx-swap=\"innerHTML\" ")
                .append("class=\"inline\">")
                .append("<input type=\"hidden\" name=\"action\" value=\"").append(action).append("\"/>")
                .append("<input type=\"hidden\" name=\"id\" value=\"").append(id).append("\"/>")
                .append("<button type=\"submit\" class=\"").append(DEL_BTN).append("\">")
                .append(label).append("</button></form> ");
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
