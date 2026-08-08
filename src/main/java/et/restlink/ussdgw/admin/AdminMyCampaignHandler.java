package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.campaign.CampaignService;
import et.restlink.ussdgw.campaign.CampaignStatus;
import et.restlink.ussdgw.campaign.CampaignTargetStatus;
import et.restlink.ussdgw.persist.CampaignEntity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** TENANT portal: create draft, submit for approval — no approve/start. */
@ApplicationScoped
public class AdminMyCampaignHandler {
    private static final String BTN =
            "rounded-md border border-ink-line px-2 py-1 text-xs text-ink-mute hover:border-signal hover:text-signal";

    @Inject CampaignService campaigns;

    public Map<String, String> pageVars(AdminAuthService.Principal who) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("{{ROWS}}", rowsHtml(who));
        String tid = who != null && who.isTenantScoped() ? who.tenantId() : "";
        m.put("{{TENANT_ID}}", esc(tid));
        return m;
    }

    public AdminHttpHandler.HttpReply get(AdminAuthService.Principal who) {
        return AdminHttpHandler.HttpReply.html(rowsHtml(who)).withHeader("Vary", "HX-Request");
    }

    public AdminHttpHandler.HttpReply post(String body, AdminAuthService.Principal who) {
        if (who == null || !who.isTenantScoped()) {
            return AdminHttpHandler.HttpReply.text(403, "forbidden — TENANT only");
        }
        Map<String, String> f = AdminCatalogHandler.parseForm(body);
        String action = f.getOrDefault("action", "create");
        try {
            if ("create".equalsIgnoreCase(action)) {
                CampaignEntity c = campaigns.create(
                        f.get("name"),
                        who.tenantId(),
                        f.get("text"),
                        f.getOrDefault("alphabet", "AUTO"),
                        parseInt(f.get("networkId"), 0),
                        parseInt(f.get("maxTps"), 5),
                        f.get("msisdns"),
                        who.username() != null ? who.username() : who.tenantId());
                return rowsReply(who, "created " + c.id, "ok");
            }
            UUID id = UUID.fromString(f.getOrDefault("id", "").trim());
            CampaignEntity c = campaigns.byId(id).orElseThrow(
                    () -> new IllegalArgumentException("not found"));
            if (!who.tenantId().equals(c.tenantId)) {
                return AdminHttpHandler.HttpReply.text(403, "forbidden");
            }
            switch (action.toLowerCase()) {
                case "submit" -> campaigns.submit(id,
                        who.username() != null ? who.username() : who.tenantId());
                case "cancel" -> {
                    if (!CampaignStatus.DRAFT.name().equals(c.status)
                            && !CampaignStatus.REJECTED.name().equals(c.status)
                            && !CampaignStatus.PENDING_APPROVAL.name().equals(c.status)) {
                        throw new IllegalStateException("cancel only draft/pending/rejected");
                    }
                    campaigns.cancel(id);
                }
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
                .withHeader("HX-Trigger", AdminHtmx.triggerToast(
                        message, kind, "/admin/my-campaigns", "#my-campaign-rows"))
                .withHeader("Vary", "HX-Request");
    }

    private String rowsHtml(AdminAuthService.Principal who) {
        String scope = who != null && who.isTenantScoped() ? who.tenantId() : null;
        if (scope == null) {
            return "<tr><td colspan=\"6\" class=\"px-3 py-4 text-ink-mute\">Login as TENANT</td></tr>";
        }
        StringBuilder sb = new StringBuilder();
        for (CampaignEntity c : campaigns.list(scope)) {
            long pending = campaigns.targetCount(c.id, CampaignTargetStatus.PENDING.name());
            sb.append("<tr><td>").append(esc(c.name)).append("</td><td>")
                    .append(esc(c.status)).append("</td><td>")
                    .append(c.sentCount).append('/').append(c.failCount)
                    .append("</td><td>").append(pending).append("</td><td>")
                    .append(esc(c.reviewNote)).append("</td><td>");
            if (CampaignStatus.DRAFT.name().equals(c.status)
                    || CampaignStatus.REJECTED.name().equals(c.status)) {
                actionBtn(sb, c.id, "submit", "Submit");
            }
            if (CampaignStatus.DRAFT.name().equals(c.status)
                    || CampaignStatus.REJECTED.name().equals(c.status)
                    || CampaignStatus.PENDING_APPROVAL.name().equals(c.status)) {
                actionBtn(sb, c.id, "cancel", "Cancel");
            }
            sb.append("</td></tr>");
        }
        if (sb.isEmpty()) {
            sb.append("<tr><td colspan=\"6\" class=\"px-3 py-4 text-ink-mute\">No campaigns</td></tr>");
        }
        return sb.toString();
    }

    private static void actionBtn(StringBuilder sb, UUID id, String action, String label) {
        sb.append("<form hx-post=\"/admin/my-campaigns\" hx-target=\"#my-campaign-rows\" hx-swap=\"innerHTML\" ")
                .append("class=\"inline\">")
                .append("<input type=\"hidden\" name=\"action\" value=\"").append(action).append("\"/>")
                .append("<input type=\"hidden\" name=\"id\" value=\"").append(id).append("\"/>")
                .append("<button type=\"submit\" class=\"").append(BTN).append("\">")
                .append(label).append("</button></form> ");
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
