package et.restlink.ussdgw.admin;

/**
 * HTMX response helpers for admin catalog CRUD (OTA parity).
 *
 * <p>{@code HX-Trigger} must stay ASCII — HTTP/1.1 header values are Latin-1; em-dashes
 * and other non-ASCII in toast text corrupt the header (seen as {@code ?} on Digicom) and
 * can abort HTMX response handling so the row fragment never swaps.
 *
 * <p>Optional {@code ussdCatalogChanged} re-GETs the list partial (fleet-approvals pattern)
 * so the directory updates even when the POST body swap into {@code <tbody>} is flaky.
 */
public final class AdminHtmx {
    private AdminHtmx() {
    }

    public static String triggerToast(String message, String kind) {
        return triggerToast(message, kind, null, null);
    }

    /**
     * @param partial GET path for list rows (e.g. {@code /admin/routing/partial}); null skips
     * @param target  CSS selector for the list (e.g. {@code #rule-rows}); null skips
     */
    public static String triggerToast(String message, String kind, String partial, String target) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"ussdToast\":{\"message\":").append(jsonStr(headerSafe(message)))
                .append(",\"kind\":").append(jsonStr(headerSafe(kind == null ? "info" : kind)))
                .append('}');
        if (partial != null && !partial.isBlank() && target != null && !target.isBlank()) {
            sb.append(",\"ussdCatalogChanged\":{\"partial\":").append(jsonStr(partial.trim()))
                    .append(",\"target\":").append(jsonStr(target.trim())).append('}');
        }
        sb.append('}');
        return sb.toString();
    }

    /** Strip / replace non-ASCII so HX-Trigger remains a valid HTTP header value. */
    static String headerSafe(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u2014' || c == '\u2013') { // em / en dash
                out.append('-');
            } else if (c == '\u2026') {
                out.append("...");
            } else if (c >= 0x20 && c <= 0x7E) {
                out.append(c);
            } else if (c == '\n' || c == '\r' || c == '\t') {
                out.append(' ');
            } else {
                out.append('?');
            }
        }
        return out.toString();
    }

    static String jsonStr(String s) {
        if (s == null) {
            return "\"\"";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }
}
