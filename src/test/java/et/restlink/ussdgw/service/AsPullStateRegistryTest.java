package et.restlink.ussdgw.service;

import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Ownership, fail-closed retry accounting and TTL reclaim for in-flight AS pull state. */
class AsPullStateRegistryTest {
    private AsPullStateRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AsPullStateRegistry();
        registry.ttlMsProp = 1_000L;
    }

    private static AsPullTarget.Http http(String url) {
        return new AsPullTarget.Http(url, "<dialog/>", AsHttpWireFormat.XML);
    }

    @Test
    void openThenCloseLeavesNothingBehind() {
        registry.open("c1", http("http://as-a/pull"), 1_000L);
        assertThat(registry.size()).isEqualTo(1);

        assertThat(registry.close("c1")).isPresent();
        assertThat(registry.size()).isZero();
        assertThat(registry.close("c1")).isEmpty();
    }

    @Test
    void stateSurvivesForAnyReaderNotJustTheOpener() {
        registry.open("c1", http("http://as-a/pull"), 1_000L);
        // The completion is handled by a different SBB instance; the registry is the one owner.
        AsPullState seen = registry.peek("c1").orElseThrow();
        assertThat(seen.target().circuitKey()).isEqualTo("http://as-a/pull");
        assertThat(seen.startedAtMs()).isEqualTo(1_000L);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void latencyIsAlwaysPositiveEvenForSubMillisecondCompletions() {
        AsPullState state = registry.open("c1", http("http://as-a/pull"), 5_000L).orElseThrow();
        assertThat(state.latencyMsAt(5_000L)).isEqualTo(1L);
        assertThat(state.latencyMsAt(5_120L)).isEqualTo(120L);
    }

    @Test
    void beginRetryAdvancesAttemptAndRebasesLatency() {
        registry.open("c1", http("http://as-a/pull"), 1_000L);

        AsPullState first = registry.beginRetry("c1", 1_400L).orElseThrow();
        assertThat(first.attempt()).isEqualTo(1);
        assertThat(first.startedAtMs()).isEqualTo(1_400L);
        assertThat(first.expiresAtMs()).isEqualTo(1_400L + registry.ttlMs());

        assertThat(registry.beginRetry("c1", 1_800L).orElseThrow().attempt()).isEqualTo(2);
        assertThat(registry.peek("c1").orElseThrow().attempt()).isEqualTo(2);
    }

    @Test
    void beginRetryFailsClosedWhenStateIsAbsent() {
        assertThat(registry.beginRetry("never-opened", 1_000L)).isEmpty();
        assertThat(registry.size()).isZero();
    }

    @Test
    void ttlSweepEvictsAbandonedEntries() {
        registry.open("dropped", http("http://as-a/pull"), 1_000L);
        registry.open("live", http("http://as-b/pull"), 1_900L);

        assertThat(registry.sweepExpired(1_500L)).isEmpty();

        List<AsPullState> evicted = registry.sweepExpired(2_500L);
        assertThat(evicted).extracting(AsPullState::correlationId).containsExactly("dropped");
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.evictedCount()).isEqualTo(1);

        assertThat(registry.sweepExpired(9_999L)).hasSize(1);
        assertThat(registry.size()).isZero();
    }

    @Test
    void grpcTargetKeysOnEndpointAndMethod() {
        AsRequest req = new AsRequest("vs", "c1", "r1", 0, "2519", "*123#", "*123#", 0);
        AsPullTarget target = new AsPullTarget.Grpc("localhost:50051", "et.as/Pull", req);
        assertThat(target.circuitKey()).isEqualTo("localhost:50051|et.as/Pull");
    }

    @Test
    void blankCorrelationIsRefused() {
        assertThat(registry.open("  ", http("http://as-a/pull"), 1_000L)).isEmpty();
        assertThat(registry.peek(null)).isEmpty();
        assertThat(registry.size()).isZero();
    }
}
