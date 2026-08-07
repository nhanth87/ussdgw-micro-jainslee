package et.restlink.ussdgw.service;

import et.restlink.ussdgw.events.NiPushRequestEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 — an SRI-SM answer must resolve to its own pending push or to nothing at all.
 *
 * <p>Classic {@code ussdgateway} gets this structurally: {@code HttpServerSbb} creates a private
 * {@code SriSbb} child per push, so the answer arrives on that child's own MAP dialog activity and
 * an unmatched answer resolves to nothing. This registry reproduces the guarantee with an explicit
 * correlation key.
 */
class PendingSriRegistryTest {

    private PendingSriRegistry registry;

    private static NiPushRequestEvent push(String corr, String msisdn) {
        return new NiPushRequestEvent(corr, msisdn, "hello", 0);
    }

    @BeforeEach
    void setUp() {
        registry = new PendingSriRegistry();
    }

    @Test
    void unknownCorrelationNeverReturnsAnotherSubscribersPush() {
        registry.put("corr-a", push("corr-a", "251911000001"));
        registry.put("corr-b", push("corr-b", "251911000002"));

        assertThat(registry.take("corr-zzz")).isEmpty();
        assertThat(registry.take(null)).isEmpty();
        assertThat(registry.take("  ")).isEmpty();
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void exactCorrelationReturnsItsOwnPush() {
        registry.put("corr-a", push("corr-a", "251911000001"));
        registry.put("corr-b", push("corr-b", "251911000002"));

        assertThat(registry.take("corr-b"))
                .get()
                .extracting(NiPushRequestEvent::msisdn)
                .isEqualTo("251911000002");
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.take("corr-b")).isEmpty();
    }

    @Test
    void emptyRegistryYieldsEmptyRatherThanAnything() {
        assertThat(registry.take("corr-a")).isEmpty();
    }

    @Test
    void ttlSweepReclaimsSilentHlrQueriesOnly() {
        registry.put("corr-old", push("corr-old", "251911000001"), 0L);
        registry.put("corr-new", push("corr-new", "251911000002"), registry.ttlMs());

        assertThat(registry.sweepExpired(registry.ttlMs() - 1)).isEmpty();

        List<NiPushRequestEvent> expired = registry.sweepExpired(registry.ttlMs());
        assertThat(expired).hasSize(1);
        assertThat(expired.get(0).correlationId()).isEqualTo("corr-old");
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.take("corr-new")).isPresent();
    }
}
