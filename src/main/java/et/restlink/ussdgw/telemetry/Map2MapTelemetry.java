package et.restlink.ussdgw.telemetry;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime counters for MAP2MAP / re-route (2026-08-08 lessons).
 *
 * <p>Honest process-local tallies — never invent link UP. Surfaced on
 * {@code /admin/status.json} as {@code map2map.*} and on the admin status strip.
 */
@ApplicationScoped
public class Map2MapTelemetry {

    private final AtomicLong armed = new AtomicLong();
    private final AtomicLong hopStarted = new AtomicLong();
    private final AtomicLong hopFixedGt = new AtomicLong();
    private final AtomicLong hopUpperGt = new AtomicLong();
    private final AtomicLong hopFake = new AtomicLong();
    private final AtomicLong hopSri = new AtomicLong();
    private final AtomicLong hopOk = new AtomicLong();
    private final AtomicLong hopTimeout = new AtomicLong();
    private final AtomicLong timeoutAfterBridge = new AtomicLong();
    private final AtomicLong gatedDuringHop = new AtomicLong();
    private final AtomicLong asRouted = new AtomicLong();
    private final AtomicLong completionAfterGate = new AtomicLong();
    private final AtomicLong labSkipped = new AtomicLong();
    private final AtomicLong failClosed = new AtomicLong();

    public void armed() { armed.incrementAndGet(); }
    public void hopStarted() { hopStarted.incrementAndGet(); }
    public void hopFixedGt() { hopFixedGt.incrementAndGet(); }
    /** Case 2 blank hop_dest → HLR Face upper-gt (no SRI). */
    public void hopUpperGt() { hopUpperGt.incrementAndGet(); }
    /** @deprecated Case 2 no longer uses FAKE→MSC; counter retained for status.json compat. */
    public void hopFake() { hopFake.incrementAndGet(); }
    /** @deprecated Case 2 no longer uses SRI; Case 1 NI / SriSbb is separate. */
    public void hopSri() { hopSri.incrementAndGet(); }
    public void hopOk() { hopOk.incrementAndGet(); }
    public void hopTimeout() { hopTimeout.incrementAndGet(); }
    public void timeoutAfterBridge() { timeoutAfterBridge.incrementAndGet(); }
    public void gatedDuringHop() { gatedDuringHop.incrementAndGet(); }
    public void asRouted() { asRouted.incrementAndGet(); }
    public void completionAfterGate() { completionAfterGate.incrementAndGet(); }
    public void labSkipped() { labSkipped.incrementAndGet(); }
    public void failClosed() { failClosed.incrementAndGet(); }

    public long armedCount() { return armed.get(); }
    public long hopStartedCount() { return hopStarted.get(); }
    public long hopFixedGtCount() { return hopFixedGt.get(); }
    public long hopUpperGtCount() { return hopUpperGt.get(); }
    public long hopFakeCount() { return hopFake.get(); }
    public long hopSriCount() { return hopSri.get(); }
    public long hopOkCount() { return hopOk.get(); }
    public long hopTimeoutCount() { return hopTimeout.get(); }
    public long timeoutAfterBridgeCount() { return timeoutAfterBridge.get(); }
    public long gatedDuringHopCount() { return gatedDuringHop.get(); }
    public long asRoutedCount() { return asRouted.get(); }
    public long completionAfterGateCount() { return completionAfterGate.get(); }
    public long labSkippedCount() { return labSkipped.get(); }
    public long failClosedCount() { return failClosed.get(); }

    /**
     * Flat {@code map2map.*} keys for status.json / monitor feed. {@code pending} is a live
     * gauge (caller supplies size) so sweeps stay visible without inventing traffic.
     */
    public Map<String, Object> snapshot(int pendingSize) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("map2map.armed", armed.get());
        m.put("map2map.hopStarted", hopStarted.get());
        m.put("map2map.hopFixedGt", hopFixedGt.get());
        m.put("map2map.hopUpperGt", hopUpperGt.get());
        m.put("map2map.hopFake", hopFake.get());
        m.put("map2map.hopSri", hopSri.get());
        m.put("map2map.hopOk", hopOk.get());
        m.put("map2map.hopTimeout", hopTimeout.get());
        m.put("map2map.timeoutAfterBridge", timeoutAfterBridge.get());
        m.put("map2map.gatedDuringHop", gatedDuringHop.get());
        m.put("map2map.asRouted", asRouted.get());
        m.put("map2map.completionAfterGate", completionAfterGate.get());
        m.put("map2map.labSkipped", labSkipped.get());
        m.put("map2map.failClosed", failClosed.get());
        m.put("map2map.pending", Math.max(0, pendingSize));
        return m;
    }

    /** Test / diagnostics reset — not used in production paths. */
    public void resetForTests() {
        armed.set(0);
        hopStarted.set(0);
        hopFixedGt.set(0);
        hopUpperGt.set(0);
        hopFake.set(0);
        hopSri.set(0);
        hopOk.set(0);
        hopTimeout.set(0);
        timeoutAfterBridge.set(0);
        gatedDuringHop.set(0);
        asRouted.set(0);
        completionAfterGate.set(0);
        labSkipped.set(0);
        failClosed.set(0);
    }
}
