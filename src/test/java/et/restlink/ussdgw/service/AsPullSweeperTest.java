package et.restlink.ussdgw.service;

import et.restlink.ussdgw.api.AsHttpWireFormat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A completion the RA never delivers (restart, dropped socket) must not pin a request body.
 * The scheduled sweep is the backstop for that; it deliberately leaves the saga and the
 * breakers alone.
 */
class AsPullSweeperTest {

    @Test
    void scheduledSweepReclaimsPullsTheRaNeverCompleted() {
        AsPullStateRegistry registry = new AsPullStateRegistry();
        registry.ttlMsProp = 1L;
        AsPullSweeper sweeper = new AsPullSweeper();
        sweeper.registry = registry;

        registry.open("abandoned", new AsPullTarget.Http(
                "http://as-alpha/pull", "<dialog/>", AsHttpWireFormat.XML), 0L);
        assertThat(registry.size()).isEqualTo(1);

        sweeper.sweepAbandonedPulls();

        assertThat(registry.size()).isZero();
        assertThat(registry.evictedCount()).isEqualTo(1);
    }

    @Test
    void sweepIsAnoOpWhileThePullIsStillInFlight() {
        AsPullStateRegistry registry = new AsPullStateRegistry();
        registry.ttlMsProp = 600_000L;
        AsPullSweeper sweeper = new AsPullSweeper();
        sweeper.registry = registry;

        registry.open("live", new AsPullTarget.Http(
                "http://as-alpha/pull", "<dialog/>", AsHttpWireFormat.XML),
                System.currentTimeMillis());

        sweeper.sweepAbandonedPulls();

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.evictedCount()).isZero();
    }
}
