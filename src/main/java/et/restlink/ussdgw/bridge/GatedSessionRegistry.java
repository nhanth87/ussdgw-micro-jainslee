package et.restlink.ussdgw.bridge;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * In-memory hints for AS / {@link et.restlink.ussdgw.service.AsPullRouter} after a gate.
 * Keyed by correlation id; secondary index by MSISDN+shortCode and JSESSIONID for re-push.
 * Not durable across restart — same class of state as NI park.
 */
@ApplicationScoped
public class GatedSessionRegistry {
    private static final Logger LOG = LogManager.getLogger(GatedSessionRegistry.class);

    /** Default retention for a gated hint (classic bridgeStateTtlSec-ish). */
    public static final long DEFAULT_TTL_MS = 180_000L;

    private final ConcurrentHashMap<String, GatedSessionMeta> byCorr = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> msisdnScToCorr = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> jsessionToCorr = new ConcurrentHashMap<>();
    private volatile long ttlMs = DEFAULT_TTL_MS;

    public void setTtlMs(long ttlMs) {
        this.ttlMs = Math.max(1_000L, ttlMs);
    }

    public void stamp(GatedSessionMeta meta) {
        if (meta == null || meta.correlationId() == null || meta.correlationId().isBlank()) {
            return;
        }
        String corr = meta.correlationId().trim();
        GatedSessionMeta prior = byCorr.put(corr, meta);
        if (prior != null) {
            dropSecondary(prior);
        }
        indexSecondary(meta);
        LOG.info("Gated session stamped corr={} reason={} jsession={} gateMs={} ewmaMs={}",
                corr, meta.gateReason(), meta.jsessionId(), meta.gateMs(), meta.observedEwmaMs());
    }

    public Optional<GatedSessionMeta> peek(String correlationId) {
        return getLive(correlationId);
    }

    /** Consume (remove) by correlation — one-shot for a later pull/push. */
    public Optional<GatedSessionMeta> take(String correlationId) {
        Optional<GatedSessionMeta> live = getLive(correlationId);
        if (live.isEmpty()) {
            return Optional.empty();
        }
        GatedSessionMeta m = live.get();
        byCorr.remove(m.correlationId(), m);
        dropSecondary(m);
        return Optional.of(m);
    }

    public Optional<GatedSessionMeta> peekByJsession(String jsessionId) {
        if (jsessionId == null || jsessionId.isBlank()) {
            return Optional.empty();
        }
        String corr = jsessionToCorr.get(jsessionId.trim());
        return corr == null ? Optional.empty() : getLive(corr);
    }

    public Optional<GatedSessionMeta> peekByMsisdnShortCode(String msisdn, String shortCode) {
        String key = msisdnScKey(msisdn, shortCode);
        if (key == null) {
            return Optional.empty();
        }
        String corr = msisdnScToCorr.get(key);
        return corr == null ? Optional.empty() : getLive(corr);
    }

    /**
     * Prefer correlation hint; else MSISDN+shortCode. Does not consume — pull may
     * still need the hint if AS retries.
     */
    public Optional<GatedSessionMeta> resolveForPull(String correlationId, String msisdn,
                                                     String shortCode) {
        Optional<GatedSessionMeta> byId = getLive(correlationId);
        if (byId.isPresent()) {
            return byId;
        }
        return peekByMsisdnShortCode(msisdn, shortCode);
    }

    public int size() {
        sweepExpired();
        return byCorr.size();
    }

    public void clear() {
        byCorr.clear();
        msisdnScToCorr.clear();
        jsessionToCorr.clear();
    }

    private Optional<GatedSessionMeta> getLive(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return Optional.empty();
        }
        String corr = correlationId.trim();
        GatedSessionMeta m = byCorr.get(corr);
        if (m == null) {
            return Optional.empty();
        }
        if (expired(m)) {
            byCorr.remove(corr, m);
            dropSecondary(m);
            return Optional.empty();
        }
        return Optional.of(m);
    }

    private boolean expired(GatedSessionMeta m) {
        return m.stampedAtMs() > 0
                && (System.currentTimeMillis() - m.stampedAtMs()) > ttlMs;
    }

    private void indexSecondary(GatedSessionMeta meta) {
        String msisdnKey = msisdnScKey(meta.msisdn(), meta.shortCode());
        if (msisdnKey != null) {
            msisdnScToCorr.put(msisdnKey, meta.correlationId());
        }
        if (meta.jsessionId() != null && !meta.jsessionId().isBlank()) {
            jsessionToCorr.put(meta.jsessionId().trim(), meta.correlationId());
        }
    }

    private void dropSecondary(GatedSessionMeta meta) {
        if (meta == null) {
            return;
        }
        String msisdnKey = msisdnScKey(meta.msisdn(), meta.shortCode());
        if (msisdnKey != null) {
            msisdnScToCorr.remove(msisdnKey, meta.correlationId());
        }
        if (meta.jsessionId() != null && !meta.jsessionId().isBlank()) {
            jsessionToCorr.remove(meta.jsessionId().trim(), meta.correlationId());
        }
    }

    private void sweepExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, GatedSessionMeta>> it = byCorr.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, GatedSessionMeta> e = it.next();
            GatedSessionMeta m = e.getValue();
            if (m.stampedAtMs() > 0 && (now - m.stampedAtMs()) > ttlMs) {
                it.remove();
                dropSecondary(m);
            }
        }
    }

    static String msisdnScKey(String msisdn, String shortCode) {
        if (msisdn == null || msisdn.isBlank() || shortCode == null || shortCode.isBlank()) {
            return null;
        }
        return msisdn.trim() + '|' + shortCode.trim();
    }
}
