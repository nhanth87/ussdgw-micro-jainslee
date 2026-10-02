package et.restlink.ussdgw.bridge;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory retry queue for late NI pushes (P1-5). Entries are hints only: the
 * ussdTx row stays the source of truth ({@code PUSH_PENDING}), so a restart
 * simply drops pending retries and TTL reclaim cleans the rows. Swept by
 * {@code BridgeGateScheduler.tickNiRetries} — never {@code Thread.sleep}.
 */
@ApplicationScoped
public class NiPushRetryRegistry {
    public record RetryEntry(String correlationId, int attempt, long notBeforeMs,
                             boolean notifyOnly) {}

    private final ConcurrentHashMap<String, RetryEntry> retries = new ConcurrentHashMap<>();

    /**
     * Arm (or advance) the retry for {@code corr}.
     *
     * @return the new 1-based attempt number; the caller runs the fallback and
     *         cancels when it exceeds {@link NiPushRetryPolicy#MAX_ATTEMPTS}
     */
    public int advance(String correlationId, boolean notifyOnly) {
        if (correlationId == null || correlationId.isBlank()) return 0;
        String corr = correlationId.trim();
        long now = System.currentTimeMillis();
        RetryEntry e = retries.compute(corr, (k, prev) -> {
            int attempt = prev == null ? 1 : prev.attempt() + 1;
            return new RetryEntry(corr, attempt, now + NiPushRetryPolicy.delayMs(attempt),
                    notifyOnly);
        });
        return e.attempt();
    }

    /** Take and drop entries whose backoff has elapsed (scheduler drives the push). */
    public List<RetryEntry> takeDue(long nowMs) {
        List<RetryEntry> due = new ArrayList<>();
        for (RetryEntry e : retries.values().toArray(new RetryEntry[0])) {
            if (e.notBeforeMs() <= nowMs && retries.remove(e.correlationId(), e)) {
                due.add(e);
            }
        }
        return due;
    }

    public void cancel(String correlationId) {
        if (correlationId != null) {
            retries.remove(correlationId.trim());
        }
    }

    public int size() {
        return retries.size();
    }
}
