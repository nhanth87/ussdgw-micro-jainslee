package et.restlink.ussdgw.api;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-USSD-session cookie jar for the HTTP AS — the classic gateway's per-dialog
 * {@code BasicCookieStore} ("Maintain HttpSession on server side", restcomm http-client RA).
 *
 * <p>A servlet AS keeps its menu state in {@code HttpSession}, found through the
 * {@code JSESSIONID} cookie it sets on the first response. Every later turn of the SAME USSD
 * dialog must send that cookie back, and no other dialog may ever see it. The jar is stored as a
 * {@code Cookie} header value ({@code a=1; b=2}) on the session's {@code ussdTx} row, so it lives
 * and dies with the dialog and survives a node handover.</p>
 *
 * <p>One AS URL per session, so Domain/Path scoping is not needed; deletion is honoured
 * ({@code Max-Age<=0}, an {@code Expires} in the past).</p>
 */
public final class AsCookieJar {

    private AsCookieJar() { }

    /**
     * Fold {@code Set-Cookie} response values into the current jar.
     *
     * @param current  jar as a {@code Cookie} header value, may be null/blank
     * @param setCookies raw {@code Set-Cookie} header values from the AS response
     * @return the new jar, or null when it is empty
     */
    public static String merge(String current, List<String> setCookies) {
        Map<String, String> jar = parse(current);
        if (setCookies != null) {
            for (String sc : setCookies) {
                apply(jar, sc);
            }
        }
        return format(jar);
    }

    /** {@code Cookie} header value → ordered name/value map. */
    static Map<String, String> parse(String cookieHeader) {
        Map<String, String> jar = new LinkedHashMap<>();
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return jar;
        }
        for (String part : cookieHeader.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                jar.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return jar;
    }

    private static void apply(Map<String, String> jar, String setCookie) {
        if (setCookie == null || setCookie.isBlank()) {
            return;
        }
        String[] parts = setCookie.split(";");
        String nv = parts[0];
        int eq = nv.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String name = nv.substring(0, eq).trim();
        String value = nv.substring(eq + 1).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        Long maxAge = null;
        boolean expiredByDate = false;
        for (int i = 1; i < parts.length; i++) {
            String attr = parts[i].trim();
            String lower = attr.toLowerCase(Locale.ROOT);
            if (lower.startsWith("max-age=")) {
                try {
                    maxAge = Long.parseLong(attr.substring(8).trim());
                } catch (NumberFormatException ignored) {
                    // malformed Max-Age: ignore the attribute (RFC 6265 §5.2.2)
                }
            } else if (lower.startsWith("expires=")) {
                try {
                    ZonedDateTime exp = ZonedDateTime.parse(attr.substring(8).trim(),
                            DateTimeFormatter.RFC_1123_DATE_TIME);
                    expiredByDate = exp.toInstant().isBefore(java.time.Instant.now());
                } catch (DateTimeParseException ignored) {
                    // unparseable Expires: treat as a session cookie
                }
            }
        }
        // RFC 6265 §5.3 step 3: Max-Age wins over Expires whatever their order.
        boolean delete = maxAge != null ? maxAge <= 0 : expiredByDate;
        if (delete) {
            jar.remove(name);
        } else {
            jar.put(name, value);
        }
    }

    private static String format(Map<String, String> jar) {
        if (jar.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : jar.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
