package et.restlink.ussdgw.service;

import et.restlink.ussdgw.events.NiPushRequestEvent;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NI push awaiting its own SRI-SM answer, keyed strictly by the outbound SRI correlation id.
 *
 * <p>Classic {@code ussdgateway} correlates structurally: {@code HttpServerSbb} creates a private
 * {@code SriSbb} child per push and the SRI answer is delivered on that child's own MAP dialog
 * activity. An answer that matches no pending query therefore resolves to nothing — it can never
 * pick up a different subscriber's push. This registry keeps the same guarantee with an explicit
 * key, plus a TTL so a silent HLR cannot leak entries.
 */
@ApplicationScoped
public class PendingSriRegistry {
    static final long DEFAULT_TTL_MS = 30_000L;

    private record Entry(NiPushRequestEvent ni, long expiresAtMs) {}

    @ConfigProperty(name = "ussd.sri.pending-ttl-ms", defaultValue = "30000")
    long ttlMsProp;

    private final ConcurrentHashMap<String, Entry> pending = new ConcurrentHashMap<>();

    public void put(String correlationId, NiPushRequestEvent ni) {
        put(correlationId, ni, System.currentTimeMillis());
    }

    public void put(String correlationId, NiPushRequestEvent ni, long nowMs) {
        if (correlationId == null || correlationId.isBlank() || ni == null) {
            return;
        }
        pending.put(correlationId.trim(), new Entry(ni, nowMs + ttlMs()));
    }

    /**
     * Exact correlation match only. An unknown correlation yields empty — never another
     * subscriber's pending push.
     */
    public Optional<NiPushRequestEvent> take(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return Optional.empty();
        }
        Entry e = pending.remove(correlationId.trim());
        return e == null ? Optional.empty() : Optional.of(e.ni());
    }

    /** Remove and return every entry whose TTL elapsed; caller fails the saga for each. */
    public List<NiPushRequestEvent> sweepExpired(long nowMs) {
        List<NiPushRequestEvent> expired = new ArrayList<>();
        pending.entrySet().removeIf(e -> {
            if (e.getValue().expiresAtMs() > nowMs) {
                return false;
            }
            expired.add(e.getValue().ni());
            return true;
        });
        return expired;
    }

    public long ttlMs() {
        return ttlMsProp > 0 ? ttlMsProp : DEFAULT_TTL_MS;
    }

    public int size() {
        return pending.size();
    }
}
