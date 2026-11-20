package et.restlink.ussdgw.bootstrap;

import et.restlink.ussdgw.config.UssdConfigService;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gap (1): single-node default. Cluster disabled (default) creates no
 * {@link com.microjainslee.cluster.ClusterManager} / JGroups fabric and leaves
 * SS7/HTTP behaviour on the unchanged single-node path.
 */
class SingleNodeDefaultClusterTest {

    @Test
    void defaultConfigIsClusterOffWithNoManagerOrJGroups() {
        UssdConfigService cfg = new UssdConfigService();
        assertFalse(cfg.clusterEnabled(), "cluster must be OFF by default");
        assertEquals(0L, cfg.otidRangeStart());
        assertEquals(0L, cfg.otidRangeEnd());

        ClusterBootstrap boot = new ClusterBootstrap();
        boot.startIfEnabled();

        assertNull(boot.clusterManager(), "disabled cluster must not create a ClusterManager");
        assertFalse(boot.enabled());
        assertEquals(Boolean.FALSE, boot.status().get("cluster.enabled"));
        assertEquals("LOCAL", boot.status().get("cluster.mode"));
        // No JGroups transport is ever started when the manager is absent.
    }

    @Test
    void disabledClusterStatusReportsSingleNodeDefaults() {
        ClusterBootstrap boot = new ClusterBootstrap();
        boot.startIfEnabled();
        assertEquals("ussdgw-1", boot.status().get("cluster.nodeId"));
        assertEquals("[0, 0]", String.valueOf(boot.status().get("cluster.otidRange")));
        assertNotNull(boot.status().get("cluster.initialHosts"));
    }
}
