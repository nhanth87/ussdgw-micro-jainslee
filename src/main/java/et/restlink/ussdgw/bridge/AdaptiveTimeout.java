package et.restlink.ussdgw.bridge;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Per-network adaptive gate timeout — classic ussdgateway {@code AdaptiveTimeout} semantics
 * (EWMA of AS latency → gate in {@code [FLOOR_MS, configuredCeiling]}) plus three guards the
 * classic model lacks:
 * <ul>
 *   <li><b>outlier clamp</b> — a sample is confined to {@code [FLOOR_MS, maxSampleMs]}, so one
 *       hung-AS round trip cannot drag the average to the ceiling for the next ~20 samples;</li>
 *   <li><b>decay + stale reset</b> — with no fresh sample the average drifts back toward the
 *       operator-configured gate and is eventually dropped, so an AS redeploy is not modelled
 *       forever by the old average;</li>
 *   <li><b>explicit reset</b> — {@link #reset(int)} / {@link #resetAll()} for admin use.</li>
 * </ul>
 * Keyed by {@code networkId}, never by MSISDN (unbounded cardinality), and shared by the pull
 * and push paths. All ageing uses {@link System#nanoTime()} so NTP steps cannot corrupt it.
 */
@ApplicationScoped
public class AdaptiveTimeout {
    private static final Logger LOG = LogManager.getLogger(AdaptiveTimeout.class);

    /** Smoothing factor for the EWMA (0..1); higher reacts faster. */
    public static final double ALPHA = 0.2;
    /** Headroom multiplier applied to the average latency to absorb jitter. */
    public static final double HEADROOM = 1.5;
    public static final long FLOOR_MS = 1000;
    /** Sample ceiling used when the caller supplies no dialog timeout. */
    public static final long DEFAULT_MAX_SAMPLE_MS = 60_000L;
    /** Samples younger than this never decay, keeping the hot path exact. */
    public static final long DECAY_GRACE_MS = 1_000L;
    /** Age at which the average has drifted halfway back to the configured gate. */
    public static final long DECAY_HALF_LIFE_MS = 300_000L;
    /** Age beyond which the model is treated as dead (AS redeploy) and ignored. */
    public static final long STALE_RESET_MS = 1_800_000L;

    private static final class Ewma {
        volatile double valueMs;
        volatile boolean seeded;
        volatile long lastSampleNanos;
    }

    private final ConcurrentHashMap<Integer, Ewma> perNetwork = new ConcurrentHashMap<>();

    @PostConstruct
    void armOnBoot() {
        // Passive EWMA model — always available; no enable flag and no admin Start.
        LOG.info("AdaptiveTimeout armed: floor={}ms headroom={} alpha={}",
                FLOOR_MS, HEADROOM, ALPHA);
    }

    public void recordLatency(int networkId, long latencyMs) {
        recordLatency(networkId, latencyMs, DEFAULT_MAX_SAMPLE_MS);
    }

    /**
     * @param maxSampleMs upper clamp for the sample — pass the dialog timeout, since no useful
     *                    round trip can exceed the lifetime of the dialog it belongs to
     */
    public void recordLatency(int networkId, long latencyMs, long maxSampleMs) {
        if (latencyMs <= 0) {
            return;
        }
        long ceiling = maxSampleMs > 0 ? maxSampleMs : DEFAULT_MAX_SAMPLE_MS;
        long sample = Math.clamp(latencyMs, Math.min(FLOOR_MS, ceiling), ceiling);
        long now = System.nanoTime();
        Ewma e = perNetwork.computeIfAbsent(networkId, k -> new Ewma());
        synchronized (e) {
            if (!e.seeded || isStale(e, now)) {
                e.valueMs = sample;
                e.seeded = true;
            } else {
                e.valueMs = ALPHA * sample + (1 - ALPHA) * e.valueMs;
            }
            e.lastSampleNanos = now;
        }
    }

    /**
     * @param networkId      operator/subnetwork id
     * @param configuredGate operator-configured gate ceiling (ms)
     * @return gate in {@code [FLOOR_MS, configuredGate]} adapted to observed latency
     */
    public long suggestGateMs(int networkId, long configuredGate) {
        double modelled = decayedLatencyMs(networkId, configuredGate);
        if (modelled <= 0d) {
            return configuredGate;
        }
        long proposed = (long) (modelled * HEADROOM);
        return Math.clamp(proposed, FLOOR_MS, Math.max(FLOOR_MS, configuredGate));
    }

    /**
     * Classic {@code SessionBridgeSupport.gateTimeoutMs(networkId)}:
     * <ul>
     *   <li>if asyncGate is non-positive or not strictly below dialogTimeout →
     *       return dialogTimeout as the gate (no EWMA);</li>
     *   <li>otherwise EWMA-suggest against asyncGate as ceiling.</li>
     * </ul>
     */
    public long effectiveGateMs(int networkId, long asyncGateTimeoutMs, long dialogTimeoutMs) {
        long dialog = dialogTimeoutMs > 0 ? dialogTimeoutMs : 7000L;
        if (asyncGateTimeoutMs <= 0 || asyncGateTimeoutMs >= dialog) {
            return dialog;
        }
        return suggestGateMs(networkId, asyncGateTimeoutMs);
    }

    /** Raw observed average for telemetry / CDR; {@code 0} when unseeded or stale. */
    public double observedLatencyMs(int networkId) {
        Ewma e = perNetwork.get(networkId);
        if (e == null || !e.seeded || isStale(e, System.nanoTime())) {
            return 0d;
        }
        return e.valueMs;
    }

    /** Drop the model for one network (admin — e.g. after an AS redeploy). */
    public boolean reset(int networkId) {
        return perNetwork.remove(networkId) != null;
    }

    /** Drop every network model; returns how many were seeded. */
    public int resetAll() {
        int n = perNetwork.size();
        perNetwork.clear();
        return n;
    }

    /** Seeded networkId → EWMA latency ms for admin / telemetry. */
    public Map<Integer, Double> snapshot() {
        Map<Integer, Double> out = new LinkedHashMap<>();
        long now = System.nanoTime();
        for (Map.Entry<Integer, Ewma> e : perNetwork.entrySet()) {
            Ewma v = e.getValue();
            if (v != null && v.seeded && !isStale(v, now)) {
                out.put(e.getKey(), v.valueMs);
            }
        }
        return out;
    }

    /**
     * Average blended back toward {@code configuredGate} by age, so a model that stops being
     * refreshed gradually stops overriding operator configuration instead of freezing.
     */
    private double decayedLatencyMs(int networkId, long configuredGate) {
        Ewma e = perNetwork.get(networkId);
        if (e == null || !e.seeded) {
            return 0d;
        }
        long now = System.nanoTime();
        if (isStale(e, now)) {
            return 0d;
        }
        long decayMs = ageMs(e, now) - DECAY_GRACE_MS;
        if (decayMs <= 0) {
            return e.valueMs;
        }
        double weight = Math.pow(0.5, (double) decayMs / DECAY_HALF_LIFE_MS);
        return weight * e.valueMs + (1 - weight) * configuredGate;
    }

    private static boolean isStale(Ewma e, long nowNanos) {
        return ageMs(e, nowNanos) >= STALE_RESET_MS;
    }

    private static long ageMs(Ewma e, long nowNanos) {
        long last = e.lastSampleNanos;
        if (last == 0L) {
            return 0L;
        }
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, nowNanos - last));
    }
}
