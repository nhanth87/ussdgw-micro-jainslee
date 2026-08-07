package et.restlink.ussdgw.tenant;

import et.restlink.ussdgw.persist.TenantEntity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Hot-path multi-tenant gate: enabled check + per-tenant maxTps (1s CAS window).
 * Bound tenant must exist and be enabled. Untenanted traffic (legacy rules —
 * {@code ussd_short_code.tenant_id} is nullable) falls into a shared global bucket rather
 * than bypassing rate limiting entirely.
 */
@ApplicationScoped
public class TenantGuard {
    private static final Logger LOG = LogManager.getLogger(TenantGuard.class);

    /** Bucket key for traffic that carries no tenantId. */
    static final String GLOBAL_BUCKET = "\u0000global";

    public enum Reason {
        OK, MISSING, DISABLED, RATE_LIMITED
    }

    public record Decision(Reason reason, TenantEntity tenant) {
        public boolean allowed() {
            return reason == Reason.OK;
        }
    }

    @Inject TenantService tenants;

    /**
     * Ceiling for untenanted traffic. Sized above the 10k TPS lab target so the load test is
     * not the thing it trips; {@code <= 0} restores the old unlimited behaviour (lab escape
     * hatch). Field initialiser mirrors {@code defaultValue} for non-CDI construction.
     */
    @ConfigProperty(name = "ussd.tenant.global-max-tps", defaultValue = "20000")
    int globalMaxTps = 20_000;

    /** windowStartMs << 32 | count — AtomicLong CAS token bucket per second. */
    private final ConcurrentHashMap<String, AtomicLong> windows = new ConcurrentHashMap<>();

    public Decision admit(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            if (globalMaxTps > 0 && !tryAcquire(GLOBAL_BUCKET, globalMaxTps)) {
                LOG.warn("TenantGuard rate-limit untenanted traffic globalMaxTps={}", globalMaxTps);
                return new Decision(Reason.RATE_LIMITED, null);
            }
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

    /** True when key matches tenant.httpApiKey, compared in constant time. */
    public boolean apiKeyMatches(String tenantId, String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank() || tenantId == null || tenantId.isBlank()) {
            return false;
        }
        return tenants.byId(tenantId)
                .map(t -> constantTimeEquals(t.httpApiKey, presentedKey.trim()))
                .orElse(false);
    }

    /** Length-leaking but content-safe comparison — {@link MessageDigest#isEqual} is the JDK's. */
    static boolean constantTimeEquals(String expected, String presented) {
        if (expected == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
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
