package et.restlink.ussdgw.bridge;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveTimeoutTest {

    @Test
    void formatSnapshotForDisplay_neverRawMapToString() {
        assertThat(AdaptiveTimeout.formatSnapshotForDisplay(Map.of())).isEqualTo("—");
        assertThat(AdaptiveTimeout.formatSnapshotForDisplay(null)).isEqualTo("—");

        Map<Integer, Double> one = Map.of(1, 1000.0);
        String single = AdaptiveTimeout.formatSnapshotForDisplay(one);
        assertThat(single).isEqualTo("1000 ms");
        assertThat(single).doesNotContain("{").doesNotContain("=");

        Map<Integer, Double> multi = new LinkedHashMap<>();
        multi.put(0, 900.0);
        multi.put(1, 12_500.0);
        String many = AdaptiveTimeout.formatSnapshotForDisplay(multi);
        assertThat(many).contains("n0:900ms").contains("n1:12.5k");
        assertThat(many).doesNotContain("{1=");
    }


    @Test
    void unseededReturnsConfiguredGate() {
        AdaptiveTimeout at = new AdaptiveTimeout();
        assertThat(at.suggestGateMs(987654, 7000)).isEqualTo(7000);
    }

    @Test
    void ewmaSuggestsBetweenFloorAndCeiling() {
        AdaptiveTimeout at = new AdaptiveTimeout();
        at.recordLatency(7, 2000);
        at.recordLatency(7, 2000);
        long gate = at.suggestGateMs(7, 7000);
        assertThat(gate).isBetween(AdaptiveTimeout.FLOOR_MS, 7000L);
        assertThat(gate).isEqualTo(3000L); // 2000 * 1.5
    }

    @Test
    void suggestionStaysWithinCeiling() {
        AdaptiveTimeout at = new AdaptiveTimeout();
        int net = 555;
        for (int i = 0; i < 20; i++) {
            at.recordLatency(net, 50_000);
        }
        long gate = at.suggestGateMs(net, 7000);
        assertThat(gate).isLessThanOrEqualTo(7000);
        assertThat(gate).isGreaterThanOrEqualTo(AdaptiveTimeout.FLOOR_MS);
    }

    @Test
    void fastAsYieldsShorterGate() {
        AdaptiveTimeout at = new AdaptiveTimeout();
        int net = 556;
        for (int i = 0; i < 20; i++) {
            at.recordLatency(net, 1200);
        }
        long gate = at.suggestGateMs(net, 7000);
        assertThat(gate).isLessThan(7000);
        assertThat(gate).isGreaterThanOrEqualTo(AdaptiveTimeout.FLOOR_MS);
    }

    @Test
    void effectiveGateFallsBackToDialogWhenAsyncInvalid() {
        AdaptiveTimeout at = new AdaptiveTimeout();
        assertThat(at.effectiveGateMs(1, 0, 60_000)).isEqualTo(60_000);
        assertThat(at.effectiveGateMs(1, 60_000, 60_000)).isEqualTo(60_000);
        assertThat(at.effectiveGateMs(1, 90_000, 60_000)).isEqualTo(60_000);
    }

    @Test
    void effectiveGateInvalidAsyncIgnoresEwmaUnlikeCeilingFallback() {
        // Invalid asyncGate → dialog; EWMA never shrinks the live budget.
        AdaptiveTimeout at = new AdaptiveTimeout();
        at.recordLatency(3, 1000);
        assertThat(at.effectiveGateMs(3, 0, 60_000)).isEqualTo(60_000);
        assertThat(at.effectiveGateMs(3, 60_000, 60_000)).isEqualTo(60_000);
    }

    @Test
    void effectiveGateAlwaysUsesConfiguredCeilingNotEwma() {
        AdaptiveTimeout at = new AdaptiveTimeout();
        at.recordLatency(9, 2000);
        // EWMA suggestion still models latency for telemetry.
        assertThat(at.suggestGateMs(9, 25_000)).isEqualTo(3000L);
        // Live GATE_ARMED budget = config ceiling (MAP2MAP hop + MO AS + NI park).
        assertThat(at.effectiveGateMs(9, 25_000, 60_000)).isEqualTo(25_000L);
        assertThat(at.effectiveGateMs(9, 7000, 60_000)).isEqualTo(7000L);
    }

    @Test
    void effectiveGatePullIgnoresMsisdnEwmaForLiveBudget() {
        AdaptiveTimeout at = new AdaptiveTimeout();
        at.recordLatency(1, "251900000001", 2000, 30_000);
        assertThat(at.suggestGateMs(1, "251900000001", 25_000)).isEqualTo(3000L);
        assertThat(at.effectiveGateMs(1, "251900000001", 25_000, 30_000)).isEqualTo(25_000L);
        assertThat(at.effectiveGateMs(1, "251900000099", 25_000, 30_000)).isEqualTo(25_000L);
        assertThat(at.effectiveGateMs(99, "251900000099", 25_000, 30_000)).isEqualTo(25_000L);
    }

    @Test
    void rejectsNonPositiveLatency() {
        AdaptiveTimeout at = new AdaptiveTimeout();
        at.recordLatency(1, 0);
        at.recordLatency(1, -5);
        assertThat(at.observedLatencyMs(1)).isEqualTo(0d);
        assertThat(at.suggestGateMs(1, 7000)).isEqualTo(7000);
    }

    @Test
    void oneHungAsSampleIsClampedAndTheGateRecovers() {
        AdaptiveTimeout at = new AdaptiveTimeout();
        int net = 4242;
        long dialogTimeout = 7000L;
        for (int i = 0; i < 20; i++) {
            at.recordLatency(net, 1200, dialogTimeout);
        }
        long steady = at.suggestGateMs(net, 7000);
        assertThat(steady).isEqualTo(1800L); // 1200 * 1.5

        // A single hung AS round trip. Unclamped this alone pegs the EWMA far above the
        // ceiling and the suggestion stays at the ceiling for ~20 further samples.
        at.recordLatency(net, 600_000, dialogTimeout);
        long afterOutlier = at.suggestGateMs(net, 7000);
        assertThat(afterOutlier)
                .as("one outlier must not peg the EWMA suggestion at the ceiling")
                .isLessThan(7000L)
                .isGreaterThan(steady);

        for (int i = 0; i < 10; i++) {
            at.recordLatency(net, 1200, dialogTimeout);
        }
        assertThat(at.suggestGateMs(net, 7000))
                .as("suggestion returns close to the steady state once the AS speeds up")
                .isBetween(steady, steady + 300L);
    }

    @Test
    void sampleIsCappedByTheSuppliedDialogTimeout() {
        AdaptiveTimeout at = new AdaptiveTimeout();
        at.recordLatency(31, 900_000, 5000);
        assertThat(at.observedLatencyMs(31)).isEqualTo(5000d);
    }

    @Test
    void resetDropsTheModelSoTheConfiguredGateApplies() {
        AdaptiveTimeout at = new AdaptiveTimeout();
        at.recordLatency(77, 2000);
        assertThat(at.suggestGateMs(77, 7000)).isEqualTo(3000L);
        assertThat(at.snapshot()).containsKey(77);

        assertThat(at.reset(77)).isTrue();
        assertThat(at.reset(77)).isFalse();
        assertThat(at.observedLatencyMs(77)).isEqualTo(0d);
        assertThat(at.suggestGateMs(77, 7000)).isEqualTo(7000L);

        at.recordLatency(78, 2000);
        at.recordLatency(79, 2000);
        assertThat(at.resetAll()).isEqualTo(2);
        assertThat(at.snapshot()).isEmpty();
    }

    @Test
    void pullMsisdnProfileKeepsEwmaAcrossSessions() {
        AdaptiveTimeout at = new AdaptiveTimeout();
        // Slow network-only seed, then a fast pull sample for one MSISDN (also nudges network).
        at.recordLatency(0, 10_000, 30_000);
        at.recordLatency(0, "251911230398", 2000, 30_000);
        assertThat(at.suggestGateMs(0, "251911230398", 25_000)).isEqualTo(3000L);
        // 0.2*2000 + 0.8*10000 = 8400 → *1.5 = 12600
        assertThat(at.suggestGateMs(0, 25_000)).isEqualTo(12_600L);
        assertThat(at.msisdnProfileSize()).isEqualTo(1);
        assertThat(at.resetMsisdn("+251-911-230-398")).isTrue();
        // User profile gone → fall back to network EWMA.
        assertThat(at.suggestGateMs(0, "251911230398", 25_000)).isEqualTo(12_600L);
    }
}
