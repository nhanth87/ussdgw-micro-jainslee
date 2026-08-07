package et.restlink.ussdgw.api;

import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.VirtualSession;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AsPullMetadataTest {
    @Test
    void enrichSetsGateBridgeAndModeWhenArmed() {
        VirtualSession s = new VirtualSession("vs-1", "corr-1", "r1", "2519", 3, "dlg", "*123#");
        s.setGateMs(4200);
        s.setAdaptiveBridgeArm(true);
        AsRequest base = new AsRequest("vs-1", "corr-1", "r1", 1, "2519", "*123#", "1", 3);
        AsRequest out = AsPullMetadata.enrich(base, s, new AdaptiveTimeout(), null, true);
        assertThat(out.adaptiveTimeoutMs()).isEqualTo(4200L);
        assertThat(out.virtualBridgeId()).isEqualTo("corr-1");
        assertThat(out.asMode()).isEqualTo("BRIDGE");
        assertThat(out.sessionId()).isEqualTo("vs-1");
        assertThat(out.correlationId()).isEqualTo("corr-1");
    }

    @Test
    void enrichSyncWhenBridgeDisarmed() {
        VirtualSession s = new VirtualSession("vs", "c", "r", "1", 0, "d", "*1#");
        s.setGateMs(1000);
        s.setAdaptiveBridgeArm(false);
        AsRequest out = AsPullMetadata.enrich(
                new AsRequest("vs", "c", "r", 0, "1", "*1#", "*1#", 0),
                s, new AdaptiveTimeout(), null, false);
        assertThat(out.virtualBridgeId()).isNull();
        assertThat(out.asMode()).isEqualTo("SYNC");
        assertThat(out.adaptiveTimeoutMs()).isEqualTo(1000L);
    }
}
