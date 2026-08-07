package et.restlink.ussdgw.tenant;

import et.restlink.ussdgw.persist.AdminUserEntity;
import et.restlink.ussdgw.security.PasswordHasher;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class AdminUserService {
    private static final Logger LOG = LogManager.getLogger(AdminUserService.class);

    /** Field initialiser mirrors {@code defaultValue} for non-CDI construction in tests. */
    @ConfigProperty(name = "ussd.admin.password.bcrypt-cost", defaultValue = "10")
    int bcryptCost = PasswordHasher.DEFAULT_COST;

    @Transactional
    public List<AdminUserEntity> list() {
        return AdminUserEntity.listAll();
    }

    @Transactional
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

    /**
     * Verifies a password and transparently upgrades pre-bcrypt (unsalted SHA-256) rows to
     * bcrypt on the way through, so no operator action is needed to migrate existing users.
     */
    public boolean authenticate(String username, String password) {
        Optional<AdminUserEntity> opt = byUsername(username);
        if (opt.isEmpty() || !opt.get().enabled || password == null) return false;
        String stored = opt.get().passwordHash;
        if (!PasswordHasher.matches(password, stored)) return false;
        if (PasswordHasher.needsRehash(stored)) {
            rehash(opt.get().username, password);
        }
        return true;
    }

    /** Rewrites a verified legacy hash as bcrypt. Never throws into the login path. */
    @Transactional
    public void rehash(String username, String password) {
        try {
            AdminUserEntity e = AdminUserEntity.findById(username);
            if (e == null) return;
            e.passwordHash = hashPassword(password);
            e.updatedAt = Instant.now();
            LOG.info("[admin-user] migrated legacy password hash to bcrypt for username={}",
                    username);
        } catch (RuntimeException ex) {
            LOG.warn("[admin-user] bcrypt rehash failed for username={}: {}", username,
                    ex.toString());
        }
    }

    public String hashPassword(String password) {
        return PasswordHasher.hash(password, bcryptCost);
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
}
