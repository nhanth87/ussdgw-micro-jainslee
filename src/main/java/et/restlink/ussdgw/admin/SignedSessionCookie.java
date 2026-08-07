package et.restlink.ussdgw.admin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * HMAC-SHA256 signed session cookie — survives JVM restart without a session table.
 * Format: {@code v1.<base64url(payload)>.<base64url(mac)>}
 * Payload lines: username, role, tenantId (may be empty), expiresEpochSeconds.
 */
public final class SignedSessionCookie {

    public static final String COOKIE_NAME = "ussd_admin_session";
    /**
     * Double-submit CSRF companion. Readable by JS on purpose — {@code ussd-shell.js} echoes it
     * back in {@link #CSRF_HEADER} so a cross-site form POST (which cannot set headers) fails.
     */
    public static final String CSRF_COOKIE_NAME = "ussd_admin_csrf";
    public static final String CSRF_HEADER = "X-USSD-CSRF";
    private static final String VERSION = "v1";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private SignedSessionCookie() {
    }

    public static String issue(String hmacSecret, String username, String role, String tenantId,
                               Instant expiresAt) {
        if (hmacSecret == null || hmacSecret.isBlank()) {
            throw new IllegalStateException("ussd.admin.session-hmac-secret required");
        }
        String tenant = tenantId == null ? "" : tenantId;
        String payload = username + "\n" + role + "\n" + tenant + "\n" + expiresAt.getEpochSecond();
        String body = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String mac = B64.encodeToString(hmac(hmacSecret, VERSION + "." + body));
        return VERSION + "." + body + "." + mac;
    }

    public static Optional<Claims> verify(String hmacSecret, String token) {
        if (hmacSecret == null || hmacSecret.isBlank() || token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.", 3);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            return Optional.empty();
        }
        String expectedMac = B64.encodeToString(hmac(hmacSecret, parts[0] + "." + parts[1]));
        if (!constantTimeEquals(expectedMac, parts[2])) {
            return Optional.empty();
        }
        try {
            String payload = new String(B64D.decode(parts[1]), StandardCharsets.UTF_8);
            String[] lines = payload.split("\n", -1);
            if (lines.length != 4) {
                return Optional.empty();
            }
            Instant exp = Instant.ofEpochSecond(Long.parseLong(lines[3]));
            if (Instant.now().isAfter(exp)) {
                return Optional.empty();
            }
            String tenant = lines[2].isEmpty() ? null : lines[2];
            return Optional.of(new Claims(lines[0], lines[1], tenant, exp));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /**
     * @param secure emit the {@code Secure} attribute. True whenever the admin surface is
     *               reached over TLS (nginx 443 → 8088 counts, terminate there). The plain-HTTP
     *               Digicom lab sets {@code ussd.admin.cookie-secure=false}.
     */
    public static String setCookieHeader(String token, boolean secure) {
        return COOKIE_NAME + "=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=86400"
                + (secure ? "; Secure" : "");
    }

    /** CSRF companion — deliberately not {@code HttpOnly} so the shell script can read it. */
    public static String setCsrfCookieHeader(String csrfToken, boolean secure) {
        return CSRF_COOKIE_NAME + "=" + csrfToken + "; Path=/; SameSite=Lax; Max-Age=86400"
                + (secure ? "; Secure" : "");
    }

    public static String clearCookieHeader(boolean secure) {
        return COOKIE_NAME + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0"
                + (secure ? "; Secure" : "");
    }

    public static String clearCsrfCookieHeader(boolean secure) {
        return CSRF_COOKIE_NAME + "=; Path=/; SameSite=Lax; Max-Age=0"
                + (secure ? "; Secure" : "");
    }

    /** Stateless per-session CSRF token: HMAC of the session token under the same secret. */
    public static String csrfToken(String hmacSecret, String sessionToken) {
        if (hmacSecret == null || hmacSecret.isBlank()
                || sessionToken == null || sessionToken.isBlank()) {
            return "";
        }
        return B64.encodeToString(hmac(hmacSecret, "csrf." + sessionToken));
    }

    public static boolean csrfMatches(String hmacSecret, String sessionToken, String presented) {
        String expected = csrfToken(hmacSecret, sessionToken);
        return !expected.isEmpty() && constantTimeEquals(expected, presented);
    }

    public static Optional<String> extractFromCookieHeader(String cookieHeader) {
        return extractCookie(cookieHeader, COOKIE_NAME);
    }

    public static Optional<String> extractCookie(String cookieHeader, String name) {
        if (cookieHeader == null || cookieHeader.isBlank() || name == null) {
            return Optional.empty();
        }
        String prefix = name + "=";
        for (String part : cookieHeader.split(";")) {
            String p = part.trim();
            if (p.startsWith(prefix)) {
                return Optional.of(p.substring(prefix.length()));
            }
        }
        return Optional.empty();
    }

    public record Claims(String username, String role, String tenantId, Instant expiresAt) {
    }

    private static byte[] hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < x.length; i++) {
            r |= x[i] ^ y[i];
        }
        return r == 0;
    }
}
