package et.restlink.ussdgw.bridge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatedSessionRegistryTest {
    private GatedSessionRegistry reg;

    @BeforeEach
    void setUp() {
        reg = new GatedSessionRegistry();
    }

    @Test
    void stampAndResolveByCorrJsessionAndMsisdn() {
        GatedSessionMeta meta = GatedSessionMeta.niPark(
                "corr-1", "js-1", 4200L, 3100L, 0, "251911", "*123#", "vs-1");
        reg.stamp(meta);

        assertThat(reg.peek("corr-1")).contains(meta);
        assertThat(reg.peekByJsession("js-1").orElseThrow().gateReason())
                .isEqualTo(GatedSessionMeta.REASON_GATE_EXPIRED);
        assertThat(reg.peekByMsisdnShortCode("251911", "*123#").orElseThrow().virtualBridgeId())
                .isEqualTo("corr-1");
        assertThat(reg.resolveForPull("corr-1", "251911", "*123#").orElseThrow().jsessionId())
                .isEqualTo("js-1");
        assertThat(reg.resolveForPull(null, "251911", "*123#").orElseThrow().observedEwmaMs())
                .isEqualTo(3100L);
    }

    @Test
    void takeRemovesHint() {
        reg.stamp(GatedSessionMeta.niPark("c", "js", 1000L, null, 0, "1", "*1#", null));
        assertThat(reg.take("c")).isPresent();
        assertThat(reg.peek("c")).isEmpty();
        assertThat(reg.peekByJsession("js")).isEmpty();
    }
}
