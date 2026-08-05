/*
 */
package et.restlink.ussdgw.admin.smpp;

import com.microjainslee.admin.RaAdminJson;

import java.util.List;
import java.util.Map;

/** RestLink HTMX fragment for SMPP status (XSS-escaped). */
final class SmppStatusHtml {

    private SmppStatusHtml() {
    }

    @SuppressWarnings("unchecked")
    static String render(Map<String, Object> st) {
        boolean anyPeer = Boolean.TRUE.equals(st.get("anyPeerUp"));
        boolean listening = Boolean.TRUE.equals(st.get("listening"))
                || Boolean.TRUE.equals(st.get("serverListening"));
        String detail = String.valueOf(st.getOrDefault("detail", ""));
        String badge = anyPeer ? badge("LIVE", true)
                : listening ? badge("LISTEN", false)
                : badge("OFF", false);

        Map<String, Object> server = st.get("server") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : Map.of();
        List<Map<String, Object>> sessions = server.get("sessions") instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();
        List<Map<String, Object>> clients = st.get("clients") instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();

        StringBuilder sb = new StringBuilder(2048);
        sb.append("<div class=\"link-status-panel\">");
        sb.append("<div class=\"link-status-head\"><h3>SMPP</h3>").append(badge).append("</div>");
        sb.append("<p class=\"link-status-detail\">").append(esc(detail)).append("</p>");

        sb.append(table("ESME server", headers("Name", "Bind", "SystemId", "State"),
                serverRows(server)));
        sb.append(table("Bound ESME sessions", headers("SystemId", "State"),
                sessionRows(sessions)));
        sb.append(table("Outbound clients", headers("Name", "Host:Port", "SystemId", "State"),
                clientRows(clients)));

        sb.append("<div class=\"kv\" style=\"margin-top:0.75rem\">")
                .append("<span class=\"k\">anyPeerUp</span>")
                .append("<span class=\"v ").append(anyPeer ? "ok" : "bad").append("\">")
                .append(anyPeer).append("</span></div>");
        sb.append("</div>");
        return sb.toString();
    }

    private static String serverRows(Map<String, Object> server) {
        if (server == null || !Boolean.TRUE.equals(server.get("enabled"))) {
            return empty(4);
        }
        return "<tr>"
                + td("server")
                + tdMono("0.0.0.0:" + server.get("port"))
                + tdMono(server.get("systemId"))
                + tdState(server.get("state"))
                + "</tr>";
    }

    private static String sessionRows(List<Map<String, Object>> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return empty(2);
        }
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> r : sessions) {
            b.append("<tr>")
                    .append(tdMono(r.get("systemId")))
                    .append(tdState(r.get("state")))
                    .append("</tr>");
        }
        return b.toString();
    }

    private static String clientRows(List<Map<String, Object>> clients) {
        if (clients == null || clients.isEmpty()) {
            return empty(4);
        }
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> r : clients) {
            b.append("<tr>")
                    .append(td(r.get("name")))
                    .append(tdMono(r.get("host") + ":" + r.get("port")))
                    .append(tdMono(r.get("systemId")))
                    .append(tdState(r.get("state")))
                    .append("</tr>");
        }
        return b.toString();
    }

    private static String table(String caption, String headersHtml, String rows) {
        return "<div class=\"link-status-table-wrap\">"
                + "<p class=\"link-status-caption\">" + esc(caption) + "</p>"
                + "<table class=\"link-status-table\"><thead><tr>"
                + headersHtml + "</tr></thead><tbody>" + rows + "</tbody></table></div>";
    }

    private static String headers(String... hs) {
        StringBuilder b = new StringBuilder();
        for (String h : hs) {
            b.append("<th>").append(esc(h)).append("</th>");
        }
        return b.toString();
    }

    private static String empty(int cols) {
        return "<tr><td colspan=\"" + cols + "\" class=\"link-status-empty\">(none)</td></tr>";
    }

    private static String td(Object v) {
        return "<td>" + esc(v == null ? "—" : String.valueOf(v)) + "</td>";
    }

    private static String tdMono(Object v) {
        return "<td class=\"link-status-mono\">"
                + esc(v == null ? "—" : String.valueOf(v)) + "</td>";
    }

    private static String tdState(Object v) {
        String s = v == null ? "?" : String.valueOf(v);
        String tone = switch (s) {
            case "UP", "BOUND", "LIVE", "ACTIVE" -> "ok";
            case "LISTEN", "STARTED", "COMM_DOWN", "ACTIVE_UNBOUND", "INACTIVE" -> "warn";
            default -> "mute";
        };
        return "<td class=\"link-status-state link-status-state--" + tone
                + "\"><span>" + esc(s) + "</span></td>";
    }

    private static String badge(String text, boolean ok) {
        String cls = ok ? "link-status-badge--ok" : "link-status-badge--mute";
        return "<span class=\"link-status-badge " + cls + "\">" + esc(text) + "</span>";
    }

    private static String esc(String s) {
        return RaAdminJson.escHtml(s);
    }
}
