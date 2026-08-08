package et.restlink.ussdgw.telemetry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Map2MapTelemetryTest {

    private Map2MapTelemetry telemetry;

    @BeforeEach
    void setUp() {
        telemetry = new Map2MapTelemetry();
    }

    @Test
    void incrementsAndSnapshotKeys() {
        telemetry.armed();
        telemetry.hopStarted();
        telemetry.hopFixedGt();
        telemetry.hopUpperGt();
        telemetry.hopFake();
        telemetry.hopSri();
        telemetry.hopOk();
        telemetry.asRouted();
        telemetry.gatedDuringHop();
        telemetry.completionAfterGate();
        telemetry.hopTimeout();
        telemetry.timeoutAfterBridge();
        telemetry.labSkipped();
        telemetry.failClosed();

        Map<String, Object> snap = telemetry.snapshot(3);
        assertThat(snap)
                .containsEntry("map2map.armed", 1L)
                .containsEntry("map2map.hopStarted", 1L)
                .containsEntry("map2map.hopFixedGt", 1L)
                .containsEntry("map2map.hopUpperGt", 1L)
                .containsEntry("map2map.hopFake", 1L)
                .containsEntry("map2map.hopSri", 1L)
                .containsEntry("map2map.hopOk", 1L)
                .containsEntry("map2map.asRouted", 1L)
                .containsEntry("map2map.gatedDuringHop", 1L)
                .containsEntry("map2map.completionAfterGate", 1L)
                .containsEntry("map2map.hopTimeout", 1L)
                .containsEntry("map2map.timeoutAfterBridge", 1L)
                .containsEntry("map2map.labSkipped", 1L)
                .containsEntry("map2map.failClosed", 1L)
                .containsEntry("map2map.pending", 3);
    }

    @Test
    void pendingGaugeClampsNegative() {
        assertThat(telemetry.snapshot(-5)).containsEntry("map2map.pending", 0);
    }
}
