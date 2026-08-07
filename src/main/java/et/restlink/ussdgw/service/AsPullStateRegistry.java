package et.restlink.ussdgw.service;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single owner of per-correlation AS pull state, keyed by correlation id.
 *
 * <p>Classic {@code ussdgateway} never kept this on an SBB instance: the child SBB read its
 * dialogue through CMP fields, and {@code GrpcClientSbb} held the adaptive-gate submit clock in a
 * {@code static} map ({@code GRPC_SUBMIT_AT_MS}) removed both on completion and on the protocol
 * timer. micro-jainslee has no CMP on these SBBs and hands out a different pooled instance per
 * activity context, so instance fields correlate nothing — this registry is the one owner instead.
 *
 * <p>Every entry carries a TTL horizon so an RA that never answers (restart, dropped socket)
 * cannot pin a request body forever, and the map is hard-bounded so a stampede degrades by
 * refusing new pulls rather than by exhausting the heap.
 */
@ApplicationScoped
public class AsPullStateRegistry {
    static final long DEFAULT_TTL_MS = 60_000L;
    static final int MAX_ENTRIES = 100_000;

    @ConfigProperty(name = "ussd.as.pull.state-ttl-ms", defaultValue = "60000")
    long ttlMsProp;

    private final ConcurrentHashMap<String, AsPullState> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong evictedCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();

    /**
     * Record a pull that is about to be submitted. Call this only once the transport is known to
     * be available, so no exit path can leave an orphan behind.
     *
     * @return the stored state, or empty when the registry is saturated (caller must fail the pull)
     */
    public Optional<AsPullState> open(String correlationId, AsPullTarget target, long nowMs) {
        String key = key(correlationId);
        if (key == null || target == null) {
            return Optional.empty();
        }
        if (inFlight.size() >= MAX_ENTRIES && !inFlight.containsKey(key)) {
            sweepExpired(nowMs);
            if (inFlight.size() >= MAX_ENTRIES) {
                rejectedCount.incrementAndGet();
                return Optional.empty();
            }
        }
        AsPullState state = new AsPullState(key, target, nowMs, 0, nowMs + ttlMs());
        inFlight.put(key, state);
        return Optional.of(state);
    }

    /** Read without consuming — a retry keeps the entry alive. */
    public Optional<AsPullState> peek(String correlationId) {
        String key = key(correlationId);
        return key == null ? Optional.empty() : Optional.ofNullable(inFlight.get(key));
    }

    /**
     * Fail-closed retry accounting: the attempt counter only advances when the entry still
     * exists. An absent correlation yields empty, and the caller must not re-send — the old
     * {@code getOrDefault(corr, new AtomicInteger(0))} incremented a throwaway object, so the
     * attempt stayed 0 and the retry budget never ran out.
     *
     * @return the re-stamped state (attempt+1, fresh latency baseline), or empty when absent
     */
    public Optional<AsPullState> beginRetry(String correlationId, long nowMs) {
        String key = key(correlationId);
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                inFlight.computeIfPresent(key, (k, s) -> s.retriedAt(nowMs, ttlMs())));
    }

    /** Terminal cleanup. Idempotent, so it is safe on every exit path. */
    public Optional<AsPullState> close(String correlationId) {
        String key = key(correlationId);
        return key == null ? Optional.empty() : Optional.ofNullable(inFlight.remove(key));
    }

    /** Remove and return every entry past its TTL horizon. */
    public List<AsPullState> sweepExpired(long nowMs) {
        List<AsPullState> expired = new ArrayList<>();
        inFlight.entrySet().removeIf(e -> {
            if (e.getValue().expiresAtMs() > nowMs) {
                return false;
            }
            expired.add(e.getValue());
            return true;
        });
        evictedCount.addAndGet(expired.size());
        return expired;
    }

    public long ttlMs() {
        return ttlMsProp > 0 ? ttlMsProp : DEFAULT_TTL_MS;
    }

    public int size() {
        return inFlight.size();
    }

    public long evictedCount() {
        return evictedCount.get();
    }

    public long rejectedCount() {
        return rejectedCount.get();
    }

    private static String key(String correlationId) {
        if (correlationId == null) {
            return null;
        }
        String trimmed = correlationId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
