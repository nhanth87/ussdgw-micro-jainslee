package et.restlink.ussdgw.hlr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B3 — the HLR face must key strictly on the outbound correlation. The old {@code takeAny()} handed
 * an upper answer to whichever inbound dialog happened to be first in the map.
 */
class PendingHlrProxyRegistryTest {

    private PendingHlrProxyRegistry registry;

    private static PendingHlrProxyRegistry.Pending pending(String dialogId, String msisdn) {
        return new PendingHlrProxyRegistry.Pending(
                dialogId, 1L, msisdn, 0, "251900000100", HlrResolveMode.PROXY_MAP);
    }

    @BeforeEach
    void setUp() {
        registry = new PendingHlrProxyRegistry();
    }

    @Test
    void takeAnyIsGone() {
        assertThat(Arrays.stream(PendingHlrProxyRegistry.class.getMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("takeAny");
    }

    @Test
    void unknownCorrelationNeverReturnsAnotherInboundDialog() {
        registry.put("out-a", pending("inbound-a", "251911000001"));
        registry.put("out-b", pending("inbound-b", "251911000002"));

        assertThat(registry.take("out-zzz")).isEmpty();
        assertThat(registry.take(null)).isEmpty();
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void exactCorrelationReturnsItsOwnInboundDialog() {
        registry.put("out-a", pending("inbound-a", "251911000001"));
        registry.put("out-b", pending("inbound-b", "251911000002"));

        assertThat(registry.take("out-a"))
                .get()
                .extracting(PendingHlrProxyRegistry.Pending::inboundDialogId)
                .isEqualTo("inbound-a");
        assertThat(registry.contains("out-a")).isFalse();
        assertThat(registry.contains("out-b")).isTrue();
    }

    @Test
    void ttlSweepReclaimsOnlyExpiredEntries() {
        registry.put("out-old", pending("inbound-old", "251911000001"), 0L);
        registry.put("out-new", pending("inbound-new", "251911000002"), registry.ttlMs());

        assertThat(registry.sweepExpired(registry.ttlMs() - 1)).isEmpty();

        List<PendingHlrProxyRegistry.Pending> expired = registry.sweepExpired(registry.ttlMs());
        assertThat(expired).hasSize(1);
        assertThat(expired.get(0).inboundDialogId()).isEqualTo("inbound-old");
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void enrichOnlyDefaultsToFalseOnTheRelayConstructor() {
        assertThat(pending("inbound-a", "251911000001").enrichOnly()).isFalse();
    }
}
