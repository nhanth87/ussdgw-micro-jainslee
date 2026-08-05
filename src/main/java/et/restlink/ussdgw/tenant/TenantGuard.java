package et.restlink.ussdgw.tenant;

import et.restlink.ussdgw.persist.TenantEntity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Hot-path multi-tenant gate: enabled check + per-tenant maxTps (1s CAS window).
 * Blank tenantId = lab/legacy admit (no TPS). Bound tenant must exist and be enabled.
 */
@ApplicationScoped
public class TenantGuard {
    private static final Logger LOG = LogManager.getLogger(TenantGuard.class);

    public enum Reason {
        OK, MISSING, DISABLED, RATE_LIMITED
    }

    public record Decision(Reason reason, TenantEntity tenant) {
        public boolean allowed() {
            return reason == Reason.OK;
        }
    }

    @Inject TenantService tenants;

    /** windowStartMs << 32 | count — AtomicLong CAS token bucket per second. */
    private final ConcurrentHashMap<String, AtomicLong> windows = new ConcurrentHashMap<>();

    public Decision admit(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return new Decision(Reason.OK, null);
        }
        String id = tenantId.trim();
        Optional<TenantEntity> opt = tenants.byId(id);
        if (opt.isEmpty()) {
            LOG.warn("TenantGuard reject missing tenantId={}", id);
            return new Decision(Reason.MISSING, null);
        }
        TenantEntity t = opt.get();
        if (!t.enabled) {
            LOG.warn("TenantGuard reject disabled tenantId={}", id);
            return new Decision(Reason.DISABLED, t);
        }
        int maxTps = t.maxTps <= 0 ? 50 : t.maxTps;
        if (!tryAcquire(id, maxTps)) {
            LOG.warn("TenantGuard rate-limit tenantId={} maxTps={}", id, maxTps);
            return new Decision(Reason.RATE_LIMITED, t);
        }
        return new Decision(Reason.OK, t);
    }

    /** True when key matches tenant.httpApiKey (constant-time-ish equals). */
    public boolean apiKeyMatches(String tenantId, String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank() || tenantId == null || tenantId.isBlank()) {
            return false;
        }
        return tenants.byId(tenantId)
                .map(t -> t.httpApiKey != null && t.httpApiKey.equals(presentedKey.trim()))
                .orElse(false);
    }

    /** Resolve tenant that owns this HTTP API key (for admin TENANT scope). */
    public Optional<TenantEntity> byHttpApiKey(String key) {
        return tenants.byHttpApiKey(key);
    }

    boolean tryAcquire(String tenantId, int maxTps) {
        long nowSec = System.currentTimeMillis() / 1000L;
        AtomicLong slot = windows.computeIfAbsent(tenantId, ignored -> new AtomicLong(pack(nowSec, 0)));
        for (int i = 0; i < 32; i++) {
            long cur = slot.get();
            long win = unpackWin(cur);
            long count = unpackCount(cur);
            long nextWin = nowSec;
            long nextCount;
            if (win != nextWin) {
                nextCount = 1;
            } else if (count >= maxTps) {
                return false;
            } else {
                nextCount = count + 1;
            }
            if (slot.compareAndSet(cur, pack(nextWin, nextCount))) {
                return true;
            }
        }
        return false;
    }

    private static long pack(long winSec, long count) {
        return (winSec << 32) | (count & 0xffff_ffffL);
    }

    private static long unpackWin(long packed) {
        return packed >>> 32;
    }

    private static long unpackCount(long packed) {
        return packed & 0xffff_ffffL;
    }
}
