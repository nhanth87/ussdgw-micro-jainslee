package et.restlink.ussdgw.service;

/**
 * Immutable snapshot of one in-flight AS pull, owned by {@link AsPullStateRegistry}.
 *
 * @param correlationId bridge / VirtualSessionStore key — also the RA activity handle id
 * @param target        where the pull went, and what it would take to re-send it
 * @param startedAtMs   submit wall clock of the current attempt; the EWMA latency baseline
 * @param attempt       retries already spent (0 on first submit)
 * @param expiresAtMs   TTL horizon — an RA that never answers cannot pin this entry
 */
public record AsPullState(String correlationId, AsPullTarget target,
                          long startedAtMs, int attempt, long expiresAtMs) {

    public AsPullState {
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId required");
        }
        if (target == null) {
            throw new IllegalArgumentException("target required");
        }
    }

    /** Re-stamped for a retry: attempt+1, fresh latency baseline, fresh TTL. */
    AsPullState retriedAt(long nowMs, long ttlMs) {
        return new AsPullState(correlationId, target, nowMs, attempt + 1, nowMs + ttlMs);
    }

    /**
     * Latency of the current attempt, floored at 1 ms so a sub-millisecond completion still
     * seeds the adaptive gate — {@code AdaptiveTimeout.recordLatency} discards {@code <= 0}.
     */
    public long latencyMsAt(long nowMs) {
        return Math.max(1L, nowMs - startedAtMs);
    }
}
