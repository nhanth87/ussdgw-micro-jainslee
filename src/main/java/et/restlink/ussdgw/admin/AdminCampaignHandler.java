package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.campaign.CampaignService;
import et.restlink.ussdgw.campaign.CampaignStatus;
import et.restlink.ussdgw.campaign.CampaignTargetStatus;
import et.restlink.ussdgw.persist.CampaignEntity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin campaign approvals queue (ADMIN/OPS) — no create form.
 * TENANT create/submit lives on {@code /admin/my-campaigns}.
 */
@ApplicationScoped
public class AdminCampaignHandler {
    private static final String DEL_BTN =
            "rounded-md border border-ink-line px-2 py-1 text-xs text-ink-mute hover:border-signal hover:text-signal";

    @Inject CampaignService campaigns;

    public AdminHttpHandler.HttpReply get(AdminAuthService.Principal who) {
        return AdminHttpHandler.HttpReply.html(rowsHtml(who)).withHeader("Vary", "HX-Request");
    }

    public Map<String, String> pageVars(AdminAuthService.Principal who) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("{{ROWS}}", rowsHtml(who));
        m.put("{{PENDING_ROWS}}", pendingRowsHtml(who));
        return m;
    }

    public AdminHttpHandler.HttpReply post(String body, AdminAuthService.Principal who) {
        if (who != null && who.isTenantScoped()) {
            return AdminHttpHandler.HttpReply.text(403,
                    "forbidden — TENANT uses /admin/my-campaigns");
        }
        Map<String, String> f = AdminCatalogHandler.parseForm(body);
        String action = f.getOrDefault("action", "").trim();
        try {
            UUID id = UUID.fromString(f.getOrDefault("id", "").trim());
            String reviewer = who == null ? "admin" : nullToEmpty(who.username());
            if (reviewer.isBlank()) {
                reviewer = who == null ? "admin" : who.role();
            }
            switch (action.toLowerCase()) {
                case "approve" -> campaigns.approve(id, reviewer, f.get("note"));
                case "reject" -> campaigns.reject(id, reviewer, f.get("note"));
                case "start" -> campaigns.start(id);
                case "pause" -> campaigns.pause(id);
                case "cancel" -> campaigns.cancel(id);
                case "create" -> throw new IllegalArgumentException(
                        "create is on /admin/my-campaigns (TENANT) only");
                default -> throw new IllegalArgumentException("unknown action: " + action);
            }
            return rowsReply(who, action + " ok", "ok");
        } catch (RuntimeException ex) {
            return rowsReply(who, "error: " + nullToEmpty(ex.getMessage()), "error");
        }
    }

    private AdminHttpHandler.HttpReply rowsReply(AdminAuthService.Principal who,
                                                 String message, String kind) {
        String html = "<div id=\"pending-rows\" hx-swap-oob=\"innerHTML\">"
                + pendingRowsHtml(who) + "</div>" + rowsHtml(who);
        return AdminHttpHandler.HttpReply.html(html)
                .withHeader("HX-Trigger",
                        "{\"ussdToast\":{\"message\":" + jsonStr(message)
                                + ",\"kind\":" + jsonStr(kind) + "}}")
                .withHeader("Vary", "HX-Request");
    }

    private String pendingRowsHtml(AdminAuthService.Principal who) {
        if (who != null && who.isTenantScoped()) {
            return "<tr><td colspan=\"6\" class=\"px-3 py-4 text-ink-mute\">N/A for TENANT</td></tr>";
        }
        StringBuilder sb = new StringBuilder();
        for (CampaignEntity c : campaigns.listPendingApproval()) {
            long pending = campaigns.targetCount(c.id, CampaignTargetStatus.PENDING.name());
            sb.append("<tr><td>").append(esc(c.name)).append("</td><td>")
                    .append(esc(c.tenantId)).append("</td><td>")
                    .append(esc(c.createdBy)).append("</td><td>")
                    .append(esc(String.valueOf(c.submittedAt))).append("</td><td>")
                    .append(pending).append("</td><td>");
            actionBtn(sb, c.id, "approve", "Approve");
            actionBtn(sb, c.id, "reject", "Reject");
            sb.append("</td></tr>");
        }
        if (sb.isEmpty()) {
            sb.append("<tr><td colspan=\"6\" class=\"px-3 py-4 text-ink-mute\">No pending approvals</td></tr>");
        }
        return sb.toString();
    }

    private String rowsHtml(AdminAuthService.Principal who) {
        StringBuilder sb = new StringBuilder();
        String scope = who != null && who.isTenantScoped() ? who.tenantId() : null;
        List<CampaignEntity> list = campaigns.list(scope);
        for (CampaignEntity c : list) {
            long pending = campaigns.targetCount(c.id, CampaignTargetStatus.PENDING.name());
            sb.append("<tr><td>").append(esc(c.name)).append("</td><td>")
                    .append(esc(c.tenantId)).append("</td><td>").append(esc(c.status))
                    .append("</td><td>").append(c.sentCount).append('/').append(c.failCount)
                    .append("</td><td>").append(pending).append("</td><td>");
            if (who == null || who.isAdminOrOps()) {
                if (CampaignStatus.PENDING_APPROVAL.name().equals(c.status)) {
                    actionBtn(sb, c.id, "approve", "Approve");
                    actionBtn(sb, c.id, "reject", "Reject");
                }
                if (CampaignStatus.PAUSED.name().equals(c.status)
                        || CampaignStatus.DRAFT.name().equals(c.status)) {
                    actionBtn(sb, c.id, "start", "Start");
                }
                if (CampaignStatus.RUNNING.name().equals(c.status)) {
                    actionBtn(sb, c.id, "pause", "Pause");
                }
                if (!CampaignStatus.CANCELLED.name().equals(c.status)
                        && !CampaignStatus.COMPLETED.name().equals(c.status)) {
                    actionBtn(sb, c.id, "cancel", "Cancel");
                }
            }
            sb.append("</td></tr>");
        }
        if (sb.isEmpty()) {
            sb.append("<tr><td colspan=\"6\" class=\"px-3 py-4 text-ink-mute\">No campaigns</td></tr>");
        }
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
