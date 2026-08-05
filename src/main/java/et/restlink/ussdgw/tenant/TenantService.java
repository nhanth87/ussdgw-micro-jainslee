package et.restlink.ussdgw.tenant;

import et.restlink.ussdgw.persist.TenantEntity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class TenantService {
    private static final SecureRandom RNG = new SecureRandom();

    public List<TenantEntity> list() {
        return TenantEntity.listAll();
    }

    public Optional<TenantEntity> byId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return Optional.empty();
        return TenantEntity.findByIdOptional(tenantId.trim());
    }

    public Optional<TenantEntity> byNetworkId(int networkId) {
        return TenantEntity.find("networkId = ?1 and enabled = true", networkId).firstResultOptional();
    }

    @Transactional
    public TenantEntity upsert(String tenantId, String displayName, int networkId,
                               boolean enabled, String httpApiKey, String smppSystemId,
                               String asCallbackBase, int maxTps) {
        return upsert(tenantId, displayName, networkId, enabled, httpApiKey,
                smppSystemId, null, asCallbackBase, maxTps);
    }

    @Transactional
    public TenantEntity upsert(String tenantId, String displayName, int networkId,
                               boolean enabled, String httpApiKey, String smppSystemId,
                               String smppPasswordOrBlank, String asCallbackBase, int maxTps) {
        String id = tenantId.trim();
        TenantEntity e = TenantEntity.findById(id);
        Instant now = Instant.now();
        if (e == null) {
            e = new TenantEntity();
            e.tenantId = id;
            e.createdAt = now;
        }
        e.displayName = blank(displayName);
        e.networkId = Math.max(0, networkId);
        e.enabled = enabled;
        if (httpApiKey != null && !httpApiKey.isBlank()) {
            e.httpApiKey = httpApiKey.trim();
        } else if (e.httpApiKey == null || e.httpApiKey.isBlank()) {
            e.httpApiKey = generateKey();
        }
        e.smppSystemId = blank(smppSystemId);
        if (smppPasswordOrBlank != null && !smppPasswordOrBlank.isBlank()) {
            e.smppPassword = smppPasswordOrBlank.trim();
        }
        e.asCallbackBase = blank(asCallbackBase);
        e.maxTps = maxTps <= 0 ? 50 : maxTps;
        e.updatedAt = now;
        e.persist();
        return e;
    }

    @Transactional
    public boolean delete(String tenantId) {
        return TenantEntity.deleteById(tenantId);
    }

    public static String generateKey() {
        byte[] b = new byte[24];
        RNG.nextBytes(b);
        return "ussd_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
