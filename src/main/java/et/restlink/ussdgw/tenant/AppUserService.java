package et.restlink.ussdgw.tenant;

import et.restlink.ussdgw.persist.AppUserEntity;
import et.restlink.ussdgw.persist.TenantEntity;
import et.restlink.ussdgw.security.PasswordHasher;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * CRUD for {@code ussd_app_user} — API keys only (NI push / routing ownership).
 * Portal TENANT login remains {@code admin_user} with username === tenantId.
 */
@ApplicationScoped
public class AppUserService {
    private static final Logger LOG = LogManager.getLogger(AppUserService.class);
    private static final SecureRandom RNG = new SecureRandom();

    @Inject TenantService tenants;

    public record CreatedAppUser(AppUserEntity entity, String plaintextApiKey) {}

    @Transactional
    public List<AppUserEntity> list(String tenantScope) {
        if (tenantScope != null && !tenantScope.isBlank()) {
            return AppUserEntity.find("tenantId = ?1 order by username", tenantScope.trim()).list();
        }
        return AppUserEntity.find("order by tenantId, username").list();
    }

    @Transactional
    public Optional<AppUserEntity> byUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return AppUserEntity.findByIdOptional(username.trim());
    }

    @Transactional
    public Optional<AppUserEntity> byApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        String key = apiKey.trim();
        String fp = fingerprint(key);
        List<AppUserEntity> candidates =
                AppUserEntity.find("apiKeyFp = ?1 and enabled = true", fp).list();
        for (AppUserEntity u : candidates) {
            if (PasswordHasher.matches(key, u.apiKeyHash)) {
                return Optional.of(u);
            }
        }
        // Fingerprint miss (legacy/blank fp): slow scan enabled rows only.
        List<AppUserEntity> enabled = AppUserEntity.find("enabled = true").list();
        for (AppUserEntity u : enabled) {
            if (PasswordHasher.matches(key, u.apiKeyHash)) {
                return Optional.of(u);
            }
        }
        return Optional.empty();
    }

    @Transactional
    public CreatedAppUser create(String username, String tenantId, String apiKeyOrBlank) {
        String user = requireUsername(username);
        String tid = requireTenant(tenantId);
        if (AppUserEntity.findById(user) != null) {
            throw new IllegalArgumentException("app user exists: " + user);
        }
        if (user.equalsIgnoreCase(tid)) {
            throw new IllegalArgumentException(
                    "app username must not equal tenantId (portal login is separate)");
        }
        String plain = (apiKeyOrBlank == null || apiKeyOrBlank.isBlank())
                ? generateApiKey() : apiKeyOrBlank.trim();
        AppUserEntity e = new AppUserEntity();
        e.username = user;
        e.tenantId = tid;
        e.apiKeyHash = PasswordHasher.hash(plain);
        e.apiKeyFp = fingerprint(plain);
        e.enabled = true;
        e.createdAt = Instant.now();
        e.persist();
        LOG.info("App user created username={} tenant={} fp={}", user, tid, e.apiKeyFp);
        return new CreatedAppUser(e, plain);
    }

    @Transactional
    public AppUserEntity update(String username, String tenantId, String newApiKeyOrBlank,
                                Boolean enabled) {
        AppUserEntity e = AppUserEntity.findById(requireUsername(username));
        if (e == null) {
            throw new IllegalArgumentException("app user not found: " + username);
        }
        if (tenantId != null && !tenantId.isBlank()) {
            e.tenantId = requireTenant(tenantId);
        }
        if (newApiKeyOrBlank != null && !newApiKeyOrBlank.isBlank()) {
            String plain = newApiKeyOrBlank.trim();
            e.apiKeyHash = PasswordHasher.hash(plain);
            e.apiKeyFp = fingerprint(plain);
        }
        if (enabled != null) {
            e.enabled = enabled;
        }
        return e;
    }

    @Transactional
    public boolean delete(String username) {
        return AppUserEntity.deleteById(requireUsername(username));
    }

    static String fingerprint(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        // First 8 hex of a cheap hash — index aid only; auth still uses bcrypt.
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig).substring(0, 8);
        } catch (Exception e) {
            return null;
        }
    }

    static String generateApiKey() {
        byte[] buf = new byte[24];
        RNG.nextBytes(buf);
        return "ussd_" + HexFormat.of().formatHex(buf);
    }

    private String requireUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username required");
        }
        String u = username.trim();
        if (u.length() > 64 || !u.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid username");
        }
        return u;
    }

    private String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId required");
        }
        String tid = tenantId.trim();
        Optional<TenantEntity> t = tenants.byId(tid);
        if (t.isEmpty()) {
            throw new IllegalArgumentException("unknown tenant: " + tid);
        }
        return tid;
    }
}
