package et.restlink.ussdgw.bridge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveTimeoutTest {

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
        // Classic SessionBridgeSupport: invalid asyncGate → dialog, no EWMA shrink.
        AdaptiveTimeout at = new AdaptiveTimeout();
        at.recordLatency(3, 1000);
        assertThat(at.effectiveGateMs(3, 0, 60_000)).isEqualTo(60_000);
        assertThat(at.effectiveGateMs(3, 60_000, 60_000)).isEqualTo(60_000);
    }

    @Test
    void effectiveGateUsesAsyncCeilingWhenValid() {
        AdaptiveTimeout at = new AdaptiveTimeout();
        at.recordLatency(9, 2000);
        long gate = at.effectiveGateMs(9, 7000, 60_000);
        assertThat(gate).isEqualTo(3000L);
    }

    @Test
    void rejectsNonPositiveLatency() {
        AdaptiveTimeout at = new AdaptiveTimeout();
        at.recordLatency(1, 0);
        at.recordLatency(1, -5);
        assertThat(at.observedLatencyMs(1)).isEqualTo(0d);
        assertThat(at.suggestGateMs(1, 7000)).isEqualTo(7000);
    }
}
