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
 * Adaptive AS-latency model plus the live session gate budget helper.
 *
 * <p><b>Live gate budget</b> ({@link #effectiveGateMs}) — operator-configured ceiling
 * ({@code ussd.bridge.async-gate-timeout-ms}, default 25s), or dialog timeout when async is
 * unset / not strictly below dialog. Used by {@code VirtualSessionBridge.startAwaitingAs}
 * (MAP2MAP hop arm + MO AS wait) and {@code ClassicNiHttpPark}. Does <strong>not</strong>
 * shrink via EWMA — gated response is only allowed after the full configured wait when hop/AS
 * is silent.
 *
 * <p><b>Observed EWMA</b> ({@link #suggestGateMs} / {@link #observedLatencyMs}) — still learned
 * from AS round-trips for telemetry, CDR {@code observed_ewma_ms}, and admin status. Classic
 * {@code clamp(EWMA × 1.5, FLOOR, ceiling)} stays here only; it never arms the session
 * deadline. Guards the classic model lacked:
 * <ul>
 *   <li><b>outlier clamp</b> — a sample is confined to {@code [FLOOR_MS, maxSampleMs]}, so one
 *       hung-AS round trip cannot drag the average to the ceiling for the next ~20 samples;</li>
 *   <li><b>decay + stale reset</b> — with no fresh sample the average drifts back toward the
 *       operator-configured gate and is eventually dropped, so an AS redeploy is not modelled
 *       forever by the old average;</li>
 *   <li><b>explicit reset</b> — {@link #reset(int)} / {@link #resetAll()} for admin use.</li>
 * </ul>
 *
 * <p>Keyed by {@code networkId} (shared pull/push), and <strong>temporarily</strong> also by
 * subscriber MSISDN for the <em>pull</em> EWMA profile. Per-MSISDN map is hard-bounded
 * ({@link #MAX_MSISDN_ENTRIES}) + stale TTL — never unbounded. All ageing uses
 * {@link System#nanoTime()} so NTP steps cannot corrupt it.
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
    /**
     * Hard bound on per-MSISDN pull profiles (temporary). Oldest / first stale entries drop
     * when exceeded — never grow with the full subscriber base.
     */
    public static final int MAX_MSISDN_ENTRIES = 8192;

    private static final class Ewma {
        volatile double valueMs;
        volatile boolean seeded;
        volatile long lastSampleNanos;
    }

    private final ConcurrentHashMap<Integer, Ewma> perNetwork = new ConcurrentHashMap<>();
    /** Pull-only user adaptive profile (digits MSISDN → EWMA). */
    private final ConcurrentHashMap<String, Ewma> perMsisdn = new ConcurrentHashMap<>();

    @PostConstruct
    void armOnBoot() {
        // Passive EWMA model — always available; no enable flag and no admin Start.
        LOG.info("AdaptiveTimeout armed: floor={}ms headroom={} alpha={} maxMsisdn={}",
                FLOOR_MS, HEADROOM, ALPHA, MAX_MSISDN_ENTRIES);
    }

    public void recordLatency(int networkId, long latencyMs) {
        recordLatency(networkId, latencyMs, DEFAULT_MAX_SAMPLE_MS);
    }

    /**
     * @param maxSampleMs upper clamp for the sample — pass the dialog timeout, since no useful
     *                    round trip can exceed the lifetime of the dialog it belongs to
     */
    public void recordLatency(int networkId, long latencyMs, long maxSampleMs) {
        applySample(perNetwork.computeIfAbsent(networkId, k -> new Ewma()), latencyMs, maxSampleMs);
    }

    /**
     * Pull-path sample: update network EWMA and, when {@code msisdn} is present, the temporary
     * per-user profile so the next pull for that handset reuses its gate.
     */
    public void recordLatency(int networkId, String msisdn, long latencyMs, long maxSampleMs) {
        recordLatency(networkId, latencyMs, maxSampleMs);
        String key = normalizeMsisdn(msisdn);
        if (key == null) {
            return;
        }
        trimMsisdnIfNeeded();
        applySample(perMsisdn.computeIfAbsent(key, k -> new Ewma()), latencyMs, maxSampleMs);
    }

    /**
     * Observed / suggested gate from EWMA (telemetry only) —
     * {@code clamp(EWMA × HEADROOM, FLOOR_MS, configuredGate)}.
     * Does not arm live session deadlines; see {@link #effectiveGateMs}.
     *
     * @param networkId      operator/subnetwork id
     * @param configuredGate operator-configured gate ceiling (ms)
     * @return suggested gate in {@code [FLOOR_MS, configuredGate]}, or {@code configuredGate}
     *         when unseeded
     */
    public long suggestGateMs(int networkId, long configuredGate) {
        double modelled = decayedLatencyMs(perNetwork.get(networkId), configuredGate);
        return clampGate(modelled, configuredGate);
    }

    /**
     * Pull-path EWMA suggestion: prefer per-MSISDN when seeded; else networkId model.
     * Telemetry only — not the live {@link #effectiveGateMs} budget.
     */
    public long suggestGateMs(int networkId, String msisdn, long configuredGate) {
        String key = normalizeMsisdn(msisdn);
        if (key != null) {
            double user = decayedLatencyMs(perMsisdn.get(key), configuredGate);
            if (user > 0d) {
                return clampGate(user, configuredGate);
            }
        }
        return suggestGateMs(networkId, configuredGate);
    }

    /**
     * Live {@code GATE_ARMED} budget (MAP2MAP hop arm, MO AS wait, NI HTTP park):
     * <ul>
     *   <li>if asyncGate is non-positive or not strictly below dialogTimeout →
     *       return dialogTimeout;</li>
     *   <li>otherwise return the configured asyncGate ceiling (no EWMA shrink).</li>
     * </ul>
     * {@code networkId} is retained for call-site compatibility; it does not affect the budget.
     */
    public long effectiveGateMs(int networkId, long asyncGateTimeoutMs, long dialogTimeoutMs) {
        return effectiveGateMs(networkId, null, asyncGateTimeoutMs, dialogTimeoutMs);
    }

    /**
     * Same as {@link #effectiveGateMs(int, long, long)} — {@code msisdn} does not shrink the
     * live budget (EWMA profiles remain for {@link #suggestGateMs} / observed samples only).
     */
    public long effectiveGateMs(int networkId, String msisdn,
                                long asyncGateTimeoutMs, long dialogTimeoutMs) {
        long dialog = dialogTimeoutMs > 0 ? dialogTimeoutMs : 30_000L;
        if (asyncGateTimeoutMs <= 0 || asyncGateTimeoutMs >= dialog) {
            return dialog;
        }
        return asyncGateTimeoutMs;
    }

    /** Raw observed average for telemetry / CDR; {@code 0} when unseeded or stale. */
    public double observedLatencyMs(int networkId) {
        return observedOf(perNetwork.get(networkId));
    }

    /** Per-MSISDN pull profile observation; {@code 0} when unseeded / blank / stale. */
    public double observedLatencyMs(int networkId, String msisdn) {
        String key = normalizeMsisdn(msisdn);
        if (key != null) {
            double user = observedOf(perMsisdn.get(key));
            if (user > 0d) {
                return user;
            }
        }
        return observedLatencyMs(networkId);
    }

    /** Drop the model for one network (admin — e.g. after an AS redeploy). */
    public boolean reset(int networkId) {
        return perNetwork.remove(networkId) != null;
    }

    /** Drop one pull-user profile (digits or raw MSISDN). */
    public boolean resetMsisdn(String msisdn) {
        String key = normalizeMsisdn(msisdn);
        return key != null && perMsisdn.remove(key) != null;
    }

    /** Drop every network + MSISDN model; returns how many network models were seeded. */
    public int resetAll() {
        int n = perNetwork.size();
        perNetwork.clear();
        perMsisdn.clear();
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

    /** Bound size of the temporary per-MSISDN pull profile map (telemetry). */
    public int msisdnProfileSize() {
        return perMsisdn.size();
    }

    private static void applySample(Ewma e, long latencyMs, long maxSampleMs) {
        if (e == null || latencyMs <= 0) {
            return;
        }
        long ceiling = maxSampleMs > 0 ? maxSampleMs : DEFAULT_MAX_SAMPLE_MS;
        long sample = Math.clamp(latencyMs, Math.min(FLOOR_MS, ceiling), ceiling);
        long now = System.nanoTime();
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

    private static long clampGate(double modelled, long configuredGate) {
        if (modelled <= 0d) {
            return configuredGate;
        }
        long proposed = (long) (modelled * HEADROOM);
        return Math.clamp(proposed, FLOOR_MS, Math.max(FLOOR_MS, configuredGate));
    }

    private static double observedOf(Ewma e) {
        if (e == null || !e.seeded || isStale(e, System.nanoTime())) {
            return 0d;
        }
        return e.valueMs;
    }

    /**
     * Average blended back toward {@code configuredGate} by age, so a model that stops being
     * refreshed gradually stops overriding operator configuration instead of freezing.
     */
    private static double decayedLatencyMs(Ewma e, long configuredGate) {
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

    private void trimMsisdnIfNeeded() {
        if (perMsisdn.size() < MAX_MSISDN_ENTRIES) {
            return;
        }
        long now = System.nanoTime();
        for (Map.Entry<String, Ewma> e : perMsisdn.entrySet()) {
            Ewma v = e.getValue();
            if (v == null || !v.seeded || isStale(v, now)) {
                perMsisdn.remove(e.getKey(), v);
            }
            if (perMsisdn.size() < MAX_MSISDN_ENTRIES) {
                return;
            }
        }
        // Still over bound — drop an arbitrary entry (temporary profile; not durable SoT).
        var it = perMsisdn.keySet().iterator();
        if (it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    /** Digits-only MSISDN key; null when blank / no digits. */
    public static String normalizeMsisdn(String msisdn) {
        if (msisdn == null || msisdn.isBlank()) {
            return null;
        }
        StringBuilder b = new StringBuilder(msisdn.length());
        for (int i = 0; i < msisdn.length(); i++) {
            char c = msisdn.charAt(i);
            if (c >= '0' && c <= '9') {
                b.append(c);
            }
        }
        return b.isEmpty() ? null : b.toString();
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
