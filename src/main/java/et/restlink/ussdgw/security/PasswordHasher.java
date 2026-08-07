package et.restlink.ussdgw.security;

import io.quarkus.elytron.security.common.BcryptUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * bcrypt hashing for {@code ussd_admin_user.password_hash}, with read-only support for the
 * legacy unsalted SHA-256 hex rows so existing installs keep logging in.
 *
 * <p>Legacy rows are upgraded on the next successful login ({@code AdminUserService}); nothing
 * ever writes SHA-256 again. Both forms fit the existing {@code VARCHAR(256)} column
 * (bcrypt modular-crypt is 60 chars, SHA-256 hex is 64), so no migration is required.
 */
public final class PasswordHasher {

    /** OWASP floor for bcrypt at time of writing; overridable via {@code #hash(String, int)}. */
    public static final int DEFAULT_COST = 10;

    private static final int LEGACY_SHA256_HEX_LENGTH = 64;

    private PasswordHasher() {
    }

    public static String hash(String password) {
        return hash(password, DEFAULT_COST);
    }

    /** @param cost bcrypt iteration exponent, clamped to the range bcrypt itself accepts. */
    public static String hash(String password, int cost) {
        if (password == null) {
            throw new IllegalArgumentException("password required");
        }
        return BcryptUtil.bcryptHash(password, Math.clamp(cost, 4, 16));
    }

    public static boolean matches(String password, String storedHash) {
        if (password == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        if (isLegacySha256(storedHash)) {
            return MessageDigest.isEqual(
                    legacySha256Hex(password).getBytes(StandardCharsets.UTF_8),
                    storedHash.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        }
        try {
            return BcryptUtil.matches(password, storedHash);
        } catch (RuntimeException ex) {
            // Unparseable hash — treat as a failed login, never as a match.
            return false;
        }
    }

    /** True when the stored hash should be rewritten with bcrypt after a successful login. */
    public static boolean needsRehash(String storedHash) {
        return storedHash == null || storedHash.isBlank() || isLegacySha256(storedHash);
    }

    static boolean isLegacySha256(String storedHash) {
        String s = storedHash == null ? "" : storedHash.trim();
        if (s.length() != LEGACY_SHA256_HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /** Pre-2026-08 format: unsalted single-round SHA-256 hex. Verification only. */
    static String legacySha256Hex(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
