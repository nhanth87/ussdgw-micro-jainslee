package et.restlink.ussdgw.service;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5: activityId idempotency across nodes (local backing; the shared ISPN
 * backing uses the same atomic claim). Single-node behaviour otherwise.
 */
class NiFlowRegistryTest {

    @Test
    void claimIsIdempotentForSameActivity() {
        NiFlowRegistry reg = new NiFlowRegistry();
        NiFlowRegistry.Claim first = reg.claim("act-1", "251911230398");
        assertFalse(first.duplicate());

        NiFlowRegistry.Claim retry = reg.claim("act-1", "251911230398");
        assertTrue(retry.duplicate());
        assertEquals(first.flow().flowId(), retry.flow().flowId());
    }

    @Test
    void distinctActivitiesGetDistinctFlows() {
        NiFlowRegistry reg = new NiFlowRegistry();
        String a = reg.claim("act-a", "").flow().flowId();
        String b = reg.claim("act-b", "").flow().flowId();
        assertNotEquals(a, b);
    }

    @Test
    void findByActivityAndFlow() {
        NiFlowRegistry reg = new NiFlowRegistry();
        NiFlowRegistry.Claim c = reg.claim("act-x", "251900000001");
        Optional<NiFlowRegistry.FlowRecord> byAct = reg.findByActivity("act-x");
        assertTrue(byAct.isPresent());
        assertEquals(c.flow().flowId(), byAct.get().flowId());
        assertTrue(reg.findByFlow(c.flow().flowId()).isPresent());
        assertTrue(reg.findByFlow("nope").isEmpty());
        assertTrue(reg.findByActivity("nope").isEmpty());
    }

    @Test
    void blankActivityRefused() {
        NiFlowRegistry reg = new NiFlowRegistry();
        assertThrows(IllegalArgumentException.class, () -> reg.claim("  ", ""));
    }

    @Test
    void markMovesSnapshotForward() {
        NiFlowRegistry reg = new NiFlowRegistry();
        String flow = reg.claim("act-m", "").flow().flowId();
        reg.mark(flow, "COMPLETE", "hello");
        NiFlowRegistry.FlowRecord fr = reg.findByFlow(flow).orElseThrow();
        assertEquals("COMPLETE", fr.state());
        assertEquals("hello", fr.lastText());
        // Re-marking the same terminal state keeps the first text (no churn).
        reg.mark(flow, "COMPLETE", "changed");
        assertEquals("hello", reg.findByFlow(flow).orElseThrow().lastText());
        reg.mark(flow, "GATE_EXPIRED", null);
        assertEquals("GATE_EXPIRED", reg.findByFlow(flow).orElseThrow().state());
        assertEquals("hello", reg.findByFlow(flow).orElseThrow().lastText());
    }

    @Test
    void unknownFlowMarkIsNoOp() {
        NiFlowRegistry reg = new NiFlowRegistry();
        reg.mark("missing", "COMPLETE", null);
        reg.mark(null, "COMPLETE", null);
        reg.mark("missing", null, null);
    }

    @Test
    void activityHeaderLookupIsCaseInsensitive() {
        assertEquals("A1", NiFlowRegistry.activityHeader(Map.of("X-USSD-Activity-Id", "A1")));
        assertEquals("A2", NiFlowRegistry.activityHeader(Map.of("x-ussd-activity-id", "A2")));
        assertEquals(null, NiFlowRegistry.activityHeader(Map.of("Content-Type", "text/xml")));
        assertEquals(null, NiFlowRegistry.activityHeader(null));
        assertEquals(null, NiFlowRegistry.activityHeader(Map.of()));
    }
}
