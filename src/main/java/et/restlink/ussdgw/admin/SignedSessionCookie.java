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

    public static String setCookieHeader(String token) {
        return COOKIE_NAME + "=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=86400";
    }

    public static String clearCookieHeader() {
        return COOKIE_NAME + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0";
    }

    public static Optional<String> extractFromCookieHeader(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return Optional.empty();
        }
        for (String part : cookieHeader.split(";")) {
            String p = part.trim();
            if (p.startsWith(COOKIE_NAME + "=")) {
                return Optional.of(p.substring(COOKIE_NAME.length() + 1));
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
