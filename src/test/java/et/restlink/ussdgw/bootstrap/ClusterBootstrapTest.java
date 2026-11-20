package et.restlink.ussdgw.bootstrap;

import et.restlink.ussdgw.config.UssdConfigService;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP2 validation: cluster stays OFF by default; enabled mode refuses start on
 * a missing/invalid OTID range or blank initial-hosts (ADR 0007 D11/D12).
 */
class ClusterBootstrapTest {

    @Test
    void clusterOffByDefaultLeavesManagerNull() {
        assertFalse(new UssdConfigService().clusterEnabled());
        ClusterBootstrap boot = new ClusterBootstrap();
        boot.startIfEnabled();
        assertNull(boot.clusterManager());
        assertFalse(boot.enabled());
    }

    @Test
    void validConfigPassesValidation() {
        ClusterBootstrap.validateClusterConfig("ussdgw-1", "tcp",
                "ussdgw-1[7800],ussdgw-2[7800],witness-1[7800]", 1L, 1_000_000_000L);
    }

    @Test
    void missingOtidRangeRefusesStart() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                ClusterBootstrap.validateClusterConfig("ussdgw-1", "tcp",
                        "ussdgw-1[7800],ussdgw-2[7800],witness-1[7800]", 0L, 0L));
        assertTrue(ex.getMessage().contains("otid-range"));
    }

    @Test
    void invertedOtidRangeRefusesStart() {
        assertThrows(IllegalStateException.class, () ->
                ClusterBootstrap.validateClusterConfig("ussdgw-1", "tcp",
                        "ussdgw-1[7800]", 5L, 5L));
        assertThrows(IllegalStateException.class, () ->
                ClusterBootstrap.validateClusterConfig("ussdgw-1", "tcp",
                        "ussdgw-1[7800]", 9L, 2L));
    }

    @Test
    void blankInitialHostsRefusesStart() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                ClusterBootstrap.validateClusterConfig("ussdgw-1", "tcp", "  ", 1L, 10L));
        assertTrue(ex.getMessage().contains("initial-hosts"));
    }

    @Test
    void twoMembersWithoutWitnessWarnsButPasses() {
        // D12: no takeover without a witness — warn, don't refuse (single-node
        // dev must still boot).
        ClusterBootstrap.validateClusterConfig("ussdgw-1", "tcp",
                "ussdgw-1[7800],ussdgw-2[7800]", 1L, 10L);
    }

    @Test
    void configDefaultsAreSingleNodeSafe() {
        UssdConfigService cfg = new UssdConfigService();
        assertEquals("ussdgw-1", cfg.clusterNodeId());
        assertEquals("tcp", cfg.clusterStack());
        assertEquals(0L, cfg.otidRangeStart());
        assertEquals(0L, cfg.otidRangeEnd());
        assertTrue(cfg.sctpLocalEndpoints().isEmpty());
        assertEquals(0, cfg.sctpEndpointIndex());
        assertTrue(cfg.clusterInitialHosts().contains("witness-1"));
    }

    @Test
    void statusReportsDisabledByDefault() {
        ClusterBootstrap boot = new ClusterBootstrap();
        boot.startIfEnabled();
        assertEquals(false, boot.status().get("cluster.enabled"));
        assertEquals("LOCAL", boot.status().get("cluster.mode"));
        assertEquals("[0, 0]", boot.status().get("cluster.otidRange"));
    }
}
