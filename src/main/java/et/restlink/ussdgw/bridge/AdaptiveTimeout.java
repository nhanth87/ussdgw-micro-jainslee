package et.restlink.ussdgw.bridge;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-network adaptive gate timeout — classic ussdgateway AdaptiveTimeout semantics.
 * EWMA of AS latency → gate in {@code [FLOOR_MS, configuredCeiling]}.
 */
@ApplicationScoped
public class AdaptiveTimeout {
    /** Smoothing factor for the EWMA (0..1); higher reacts faster. */
    public static final double ALPHA = 0.2;
    /** Headroom multiplier applied to the average latency to absorb jitter. */
    public static final double HEADROOM = 1.5;
    public static final long FLOOR_MS = 1000;

    private static final class Ewma {
        volatile double valueMs;
        volatile boolean seeded;
    }

    private final ConcurrentHashMap<Integer, Ewma> perNetwork = new ConcurrentHashMap<>();

    public void recordLatency(int networkId, long latencyMs) {
        if (latencyMs <= 0) {
            return;
        }
        Ewma e = perNetwork.computeIfAbsent(networkId, k -> new Ewma());
        synchronized (e) {
            if (!e.seeded) {
                e.valueMs = latencyMs;
                e.seeded = true;
            } else {
                e.valueMs = ALPHA * latencyMs + (1 - ALPHA) * e.valueMs;
            }
        }
    }

    /**
     * @param networkId      operator/subnetwork id
     * @param configuredGate operator-configured gate ceiling (ms)
     * @return gate in {@code [FLOOR_MS, configuredGate]} adapted to observed latency
     */
    public long suggestGateMs(int networkId, long configuredGate) {
        Ewma e = perNetwork.get(networkId);
        if (e == null || !e.seeded) {
            return configuredGate;
        }
        long proposed = (long) (e.valueMs * HEADROOM);
        if (proposed < FLOOR_MS) {
            proposed = FLOOR_MS;
        }
        if (proposed > configuredGate) {
            proposed = configuredGate;
        }
        return proposed;
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

    public double observedLatencyMs(int networkId) {
        Ewma e = perNetwork.get(networkId);
        return (e == null || !e.seeded) ? 0d : e.valueMs;
    }

    /** Seeded networkId → EWMA latency ms for admin / telemetry. */
    public Map<Integer, Double> snapshot() {
        Map<Integer, Double> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, Ewma> e : perNetwork.entrySet()) {
            Ewma v = e.getValue();
            if (v != null && v.seeded) {
                out.put(e.getKey(), v.valueMs);
            }
        }
        return out;
    }
}
