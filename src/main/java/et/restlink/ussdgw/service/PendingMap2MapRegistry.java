package et.restlink.ussdgw.service;

import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.events.Map2MapRequestEvent;

import com.microjainslee.api.RaCommandPort;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * MAP2MAP outbound legs keyed strictly by {@code outboundCorr} (ra-jss7 dialog key).
 * Phases: {@link Phase#AWAITING_SRI} after SRI-SM sent; {@link Phase#AWAITING_USSD} after
 * UnstructuredSS-Request toward MSC. Miss = empty — never another subscriber.
 *
 * <p>TTL is {@code max(ussd.map2map.pending-ttl-ms, dialogTimeoutMs)} so a short hop TTL
 * cannot hard-fail before the MAP dialog budget / AdaptiveTimeout window.
 */
@ApplicationScoped
public class PendingMap2MapRegistry {
    static final long DEFAULT_TTL_MS = 30_000L;
    /** Outbound ra-jss7 dialog key prefix for MAP2MAP hops. */
    public static final String OUTBOUND_PREFIX = "m2m-";

    public enum Phase { AWAITING_SRI, AWAITING_USSD }

    public record Pending(
            Map2MapRequestEvent req,
            Phase phase,
            String mscGt,
            String imsi
    ) {
        public Pending withUssd(String mscGt, String imsi) {
            return new Pending(req, Phase.AWAITING_USSD, mscGt, imsi);
        }
    }

    private record Entry(Pending pending, long expiresAtMs) {}

    @ConfigProperty(name = "ussd.map2map.pending-ttl-ms", defaultValue = "30000")
    long ttlMsProp;

    @Inject UssdConfigService config;

    private final ConcurrentHashMap<String, Entry> pending = new ConcurrentHashMap<>();
    private volatile Supplier<? extends RaCommandPort> ss7Supplier = () -> null;

    /** Stable outbound dialog key for an inbound MO correlation. */
    public static String outboundCorr(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return OUTBOUND_PREFIX;
        }
        String c = correlationId.trim();
        return c.startsWith(OUTBOUND_PREFIX) ? c : OUTBOUND_PREFIX + c;
    }

    public void bindSs7(Supplier<? extends RaCommandPort> supplier) {
        this.ss7Supplier = supplier == null ? () -> null : supplier;
    }

    public RaCommandPort ss7() {
        try {
            return ss7Supplier.get();
        } catch (Throwable t) {
            return null;
        }
    }

    public void putSri(String outboundCorr, Map2MapRequestEvent req) {
        putSri(outboundCorr, req, System.currentTimeMillis());
    }

    public void putSri(String outboundCorr, Map2MapRequestEvent req, long nowMs) {
        if (outboundCorr == null || outboundCorr.isBlank() || req == null) {
            return;
        }
        pending.put(outboundCorr.trim(),
                new Entry(new Pending(req, Phase.AWAITING_SRI, null, null), nowMs + ttlMs()));
    }

    /** After SRI: keep same key, advance to awaiting UnstructuredSS-Response. */
    public void putUssd(String outboundCorr, Map2MapRequestEvent req, String mscGt, String imsi) {
        putUssd(outboundCorr, req, mscGt, imsi, System.currentTimeMillis());
    }

    public void putUssd(String outboundCorr, Map2MapRequestEvent req, String mscGt, String imsi,
                        long nowMs) {
        if (outboundCorr == null || outboundCorr.isBlank() || req == null) {
            return;
        }
        pending.put(outboundCorr.trim(),
                new Entry(new Pending(req, Phase.AWAITING_USSD, mscGt, imsi), nowMs + ttlMs()));
    }

    public Optional<Pending> take(String outboundCorr) {
        if (outboundCorr == null || outboundCorr.isBlank()) {
            return Optional.empty();
        }
        Entry e = pending.remove(outboundCorr.trim());
        return e == null ? Optional.empty() : Optional.of(e.pending());
    }

    public Optional<Pending> takeIfPhase(String outboundCorr, Phase phase) {
        if (outboundCorr == null || outboundCorr.isBlank() || phase == null) {
            return Optional.empty();
        }
        String key = outboundCorr.trim();
        Entry e = pending.get(key);
        if (e == null || e.pending().phase() != phase) {
            return Optional.empty();
        }
        pending.remove(key, e);
        return Optional.of(e.pending());
    }

    public Optional<Pending> peek(String outboundCorr) {
        if (outboundCorr == null || outboundCorr.isBlank()) {
            return Optional.empty();
        }
        Entry e = pending.get(outboundCorr.trim());
        return e == null ? Optional.empty() : Optional.of(e.pending());
    }

    public List<Map2MapRequestEvent> sweepExpired(long nowMs) {
        List<Map2MapRequestEvent> expired = new ArrayList<>();
        pending.entrySet().removeIf(e -> {
            if (e.getValue().expiresAtMs() > nowMs) {
                return false;
            }
            expired.add(e.getValue().pending().req());
            return true;
        });
        return expired;
    }

    /**
     * Effective hop TTL: at least the configured map2map TTL and not shorter than the
     * MAP dialog budget so AdaptiveTimeout can fire during a long hop.
     */
    public long ttlMs() {
        long configured = ttlMsProp > 0 ? ttlMsProp : DEFAULT_TTL_MS;
        long dialog = 0L;
        try {
            if (config != null) {
                dialog = config.dialogTimeoutMs();
            }
        } catch (Throwable ignored) { }
        return Math.max(configured, dialog > 0 ? dialog : configured);
    }

    public int size() {
        return pending.size();
    }
}
