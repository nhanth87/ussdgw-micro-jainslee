package et.restlink.ussdgw.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Lightweight AS pull resilience: per-URL circuit + bounded retry policy (no MicroProfile FT).
 * Retry only network / 5xx — never 4xx.
 */
@ApplicationScoped
public class AsPullClient {
    private static final Logger LOG = LogManager.getLogger(AsPullClient.class);

    public enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    public record Admit(boolean allow, CircuitState state, String reason) {
        public static Admit ok(CircuitState s) {
            return new Admit(true, s, null);
        }
        public static Admit deny(CircuitState s, String reason) {
            return new Admit(false, s, reason);
        }
    }

    @ConfigProperty(name = "ussd.as.pull.fail-threshold", defaultValue = "5")
    int failThreshold;
    @ConfigProperty(name = "ussd.as.pull.open-ms", defaultValue = "30000")
    long openMs;
    @ConfigProperty(name = "ussd.as.pull.max-retries", defaultValue = "1")
    int maxRetries;

    private final ConcurrentHashMap<String, Breaker> breakers = new ConcurrentHashMap<>();
    private final AtomicLong openRejects = new AtomicLong();

    public int maxRetries() {
        return Math.max(0, maxRetries);
    }

    public Admit tryAdmit(String asUrl) {
        String key = normalize(asUrl);
        Breaker b = breakers.computeIfAbsent(key, k -> new Breaker());
        long now = System.currentTimeMillis();
        CircuitState st = b.state(now, openMs);
        if (st == CircuitState.OPEN) {
            openRejects.incrementAndGet();
            LOG.warn("AS circuit OPEN url={} rejects={}", key, openRejects.get());
            return Admit.deny(st, "CIRCUIT_OPEN");
        }
        return Admit.ok(st);
    }

    public void recordSuccess(String asUrl) {
        breaker(asUrl).onSuccess();
    }

    public void recordFailure(String asUrl) {
        breaker(asUrl).onFailure(failThreshold, System.currentTimeMillis());
    }

    /** True when status/error warrants a retry (network or HTTP 5xx). */
    public static boolean isRetryable(int statusCode, String errorMessage) {
        if (statusCode >= 500) return true;
        if (statusCode > 0 && statusCode < 500) return false; // 4xx / 2xx — no retry
        // status <= 0 → transport failure
        return errorMessage != null && !errorMessage.isBlank();
    }

    public boolean shouldRetry(String asUrl, int attempt, int statusCode, String errorMessage) {
        if (attempt >= maxRetries()) return false;
        return isRetryable(statusCode, errorMessage);
    }

    public long openRejects() {
        return openRejects.get();
    }

    public CircuitState stateOf(String asUrl) {
        Breaker b = breakers.get(normalize(asUrl));
        if (b == null) return CircuitState.CLOSED;
        return b.state(System.currentTimeMillis(), openMs);
    }

    private Breaker breaker(String asUrl) {
        return breakers.computeIfAbsent(normalize(asUrl), k -> new Breaker());
    }

    private static String normalize(String url) {
        return url == null ? "" : url.trim();
    }

    static final class Breaker {
        private final AtomicInteger consecutiveFails = new AtomicInteger();
        private final AtomicLong openedAtMs = new AtomicLong(0);
        private volatile boolean halfOpenProbe;

        CircuitState state(long now, long openMs) {
            long opened = openedAtMs.get();
            if (opened <= 0) return CircuitState.CLOSED;
            if (now - opened < openMs) return CircuitState.OPEN;
            halfOpenProbe = true;
            return CircuitState.HALF_OPEN;
        }

        void onSuccess() {
            consecutiveFails.set(0);
            openedAtMs.set(0);
            halfOpenProbe = false;
        }

        void onFailure(int threshold, long now) {
            int n = consecutiveFails.incrementAndGet();
            if (halfOpenProbe || n >= Math.max(1, threshold)) {
                openedAtMs.set(now);
                halfOpenProbe = false;
            }
        }
    }
}
