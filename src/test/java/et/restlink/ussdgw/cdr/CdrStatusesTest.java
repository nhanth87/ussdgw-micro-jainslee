package et.restlink.ussdgw.cdr;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CdrStatusesTest {

    @Test
    void catalogCoversGateAndGatedAsFamilies() {
        assertThat(CdrStatuses.GATE_ARMED).isEqualTo("GATE_ARMED");
        assertThat(CdrStatuses.GATED).isEqualTo("GATED");
        assertThat(CdrStatuses.BRIDGED).isEqualTo("BRIDGED");
        assertThat(CdrStatuses.GATE_EXPIRED).isEqualTo("GATE_EXPIRED");
        assertThat(CdrStatuses.GATED_AS_NOTIFY).isEqualTo("GATED_AS_NOTIFY");
        assertThat(CdrStatuses.GATED_AS_ACK).isEqualTo("GATED_AS_ACK");
        assertThat(CdrStatuses.GATED_AS_FAIL).isEqualTo("GATED_AS_FAIL");
        assertThat(CdrStatuses.GATED_AS_SKIP).isEqualTo("GATED_AS_SKIP");
    }

    @Test
    void familyHelpers() {
        assertThat(CdrStatuses.isGateFamily("GATE_ARMED")).isTrue();
        assertThat(CdrStatuses.isGateFamily("GATED")).isTrue();
        assertThat(CdrStatuses.isGateFamily("GATED_AS_NOTIFY")).isTrue();
        assertThat(CdrStatuses.isGateFamily("MAP2MAP_GATED_HOP")).isTrue();
        assertThat(CdrStatuses.isGateFamily("MAP2MAP_OK")).isFalse();
        assertThat(CdrStatuses.isMap2MapFamily("MAP2MAP_ARMED")).isTrue();
        assertThat(CdrStatuses.isMap2MapFamily("GATED")).isFalse();
    }

    @Test
    void adminPresetsIncludeMap2MapAndGated() {
        assertThat(CdrStatuses.ADMIN_PRESETS.stream().map(CdrStatuses.StatusPreset::value))
                .contains("", "GATED*", "MAP2MAP_*", "GATED_AS*", "GATE_*", "BRIDGED*", "AS_*");
    }
}
