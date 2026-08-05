package et.restlink.ussdgw.tenant;

import et.restlink.ussdgw.persist.AdminUserEntity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class AdminUserService {
    private static final SecureRandom RNG = new SecureRandom();

    public List<AdminUserEntity> list() {
        return AdminUserEntity.listAll();
    }

    public Optional<AdminUserEntity> byUsername(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        return AdminUserEntity.findByIdOptional(username.trim());
    }

    @Transactional
    public AdminUserEntity create(String username, String password, String role,
                                  String tenantId, String displayName, boolean enabled) {
        String u = username.trim();
        if (AdminUserEntity.findById(u) != null) {
            throw new IllegalArgumentException("username already exists: " + u);
        }
        AdminUserEntity e = new AdminUserEntity();
        e.username = u;
        e.passwordHash = hashPassword(password);
        e.role = normalizeRole(role);
        e.tenantId = blank(tenantId);
        enforceTenantUsername(e.role, u, e.tenantId);
        e.displayName = blank(displayName);
        e.enabled = enabled;
        Instant now = Instant.now();
        e.createdAt = now;
        e.updatedAt = now;
        e.persist();
        return e;
    }

    @Transactional
    public AdminUserEntity update(String username, String passwordOrBlank, String role,
                                  String tenantId, String displayName, boolean enabled) {
        AdminUserEntity e = AdminUserEntity.findById(username.trim());
        if (e == null) throw new IllegalArgumentException("user not found: " + username);
        if (passwordOrBlank != null && !passwordOrBlank.isBlank()) {
            e.passwordHash = hashPassword(passwordOrBlank);
        }
        e.role = normalizeRole(role);
        e.tenantId = blank(tenantId);
        enforceTenantUsername(e.role, e.username, e.tenantId);
        e.displayName = blank(displayName);
        e.enabled = enabled;
        e.updatedAt = Instant.now();
        return e;
    }

    @Transactional
    public boolean delete(String username) {
        return AdminUserEntity.deleteById(username);
    }

    public boolean authenticate(String username, String password) {
        Optional<AdminUserEntity> opt = byUsername(username);
        if (opt.isEmpty() || !opt.get().enabled) return false;
        return constantTimeEquals(opt.get().passwordHash, hashPassword(password));
    }

    public static String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            throw new IllegalStateException("hash failed", e);
        }
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) return "OPS";
        String r = role.trim().toUpperCase();
        return switch (r) {
            case "ADMIN", "OPS", "TENANT" -> r;
            default -> "OPS";
        };
    }

    /** TENANT login username must equal tenantId (RestLink is never a required username). */
    static void enforceTenantUsername(String role, String username, String tenantId) {
        if (!"TENANT".equals(role)) return;
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("TENANT role requires tenantId");
        }
        if (username == null || !username.equals(tenantId)) {
            throw new IllegalArgumentException(
                    "TENANT username must equal tenantId (got username=" + username
                            + " tenantId=" + tenantId + ")");
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
