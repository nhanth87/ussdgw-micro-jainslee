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

    @Transactional
    public List<TenantEntity> list() {
        return TenantEntity.listAll();
    }

    @Transactional
    public Optional<TenantEntity> byId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return Optional.empty();
        return TenantEntity.findByIdOptional(tenantId.trim());
    }

    @Transactional
    public Optional<TenantEntity> byNetworkId(int networkId) {
        return TenantEntity.find("networkId = ?1 and enabled = true", networkId).firstResultOptional();
    }

    /** Lookup by HTTP callback/admin API key (transactional — safe from SLEE threads). */
    @Transactional
    public Optional<TenantEntity> byHttpApiKey(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        String k = key.trim();
        return TenantEntity.find("httpApiKey = ?1 and enabled = true", k).firstResultOptional();
    }

    @Transactional
    public TenantEntity upsert(String tenantId, String displayName, int networkId,
                               boolean enabled, String httpApiKey, String smppSystemId,
                               String asCallbackBase, int maxTps) {
        return upsert(tenantId, displayName, networkId, enabled, httpApiKey,
                smppSystemId, null, asCallbackBase, maxTps, null);
    }

    @Transactional
    public TenantEntity upsert(String tenantId, String displayName, int networkId,
                               boolean enabled, String httpApiKey, String smppSystemId,
                               String smppPasswordOrBlank, String asCallbackBase, int maxTps) {
        return upsert(tenantId, displayName, networkId, enabled, httpApiKey,
                smppSystemId, smppPasswordOrBlank, asCallbackBase, maxTps, null);
    }

    @Transactional
    public TenantEntity upsert(String tenantId, String displayName, int networkId,
                               boolean enabled, String httpApiKey, String smppSystemId,
                               String smppPasswordOrBlank, String asCallbackBase, int maxTps,
                               String httpAsWireFormat) {
        return upsert(tenantId, displayName, networkId, enabled, httpApiKey,
                smppSystemId, smppPasswordOrBlank, asCallbackBase, maxTps, httpAsWireFormat, null);
    }

    @Transactional
    public TenantEntity upsert(String tenantId, String displayName, int networkId,
                               boolean enabled, String httpApiKey, String smppSystemId,
                               String smppPasswordOrBlank, String asCallbackBase, int maxTps,
                               String httpAsWireFormat, String sipTrunkId) {
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
        e.httpAsWireFormat = normalizeHttpAsWireFormat(httpAsWireFormat);
        if (sipTrunkId != null) {
            e.sipTrunkId = blank(sipTrunkId);
        }
        e.updatedAt = now;
        e.persist();
        return e;
    }

    @Transactional
    public boolean delete(String tenantId) {
        return TenantEntity.deleteById(tenantId);
    }

    /**
     * Hot-update AS HTTP wire for an existing tenant ({@code XML}|{@code JSON}).
     * Used by Routing dashboard so integrators can enable JSON without Tenants CRUD.
     * Missing tenant → empty (caller must not invent tenants from routing).
     */
    @Transactional
    public Optional<TenantEntity> updateHttpAsWireFormat(String tenantId, String httpAsWireFormat) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        TenantEntity e = TenantEntity.findById(tenantId.trim());
        if (e == null) {
            return Optional.empty();
        }
        e.httpAsWireFormat = normalizeHttpAsWireFormat(httpAsWireFormat);
        e.updatedAt = Instant.now();
        e.persist();
        return Optional.of(e);
    }

    public static String generateKey() {
        byte[] b = new byte[24];
        RNG.nextBytes(b);
        return "ussd_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Normalize to {@code XML} or {@code JSON}; null/blank/unknown → {@code XML}. */
    public static String normalizeHttpAsWireFormat(String httpAsWireFormat) {
        if (httpAsWireFormat == null || httpAsWireFormat.isBlank()) {
            return "XML";
        }
        String v = httpAsWireFormat.trim().toUpperCase();
        return "JSON".equals(v) ? "JSON" : "XML";
    }
}
