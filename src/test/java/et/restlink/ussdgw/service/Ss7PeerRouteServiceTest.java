package et.restlink.ussdgw.service;

import com.microjainslee.cluster.Ss7PeerRouteAffinity.PeerRoute;

import org.junit.jupiter.api.Test;
import org.restcomm.protocols.ss7.config.Ss7Config;
import org.restcomm.protocols.ss7.config.Ss7ConfigLoader;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ss7PeerRouteServiceTest {

    @Test
    void affinityUnboundByDefaultBoundAfterBindCluster() {
        Ss7PeerRouteService svc = new Ss7PeerRouteService();
        assertFalse(svc.affinityBound());
        com.microjainslee.cluster.ClusterManager cm = localClusterManager("aff-1");
        try {
            svc.bindCluster(cm);
            assertTrue(svc.affinityBound());
            svc.bindCluster(null);
            assertFalse(svc.affinityBound());
        } finally {
            cm.stop();
        }
    }

    @Test
    void boundAffinityRoundRobinsAcrossCandidates() throws Exception {
        Ss7PeerRouteService svc = new Ss7PeerRouteService();
        set(svc, "enabled", true);
        com.microjainslee.cluster.ClusterManager cm = localClusterManager("aff-2");
        try {
            svc.bindCluster(cm);
            svc.refreshFromConfig(multiAsLabConfig());
            var pool = new java.util.HashSet<String>();
            for (int i = 0; i < 8; i++) {
                var pick = svc.pickForNewSession(0, "pool-" + i);
                assertNotNull(pick);
                pool.add(pick.aspName());
            }
            // N=2 candidates: cluster-wide round-robin must visit both.
            assertEquals(2, pool.size());
        } finally {
            cm.stop();
        }
    }

    @Test
    void buildCandidatesFromMultiAsLabJson() throws Exception {
        Ss7Config cfg = loadClasspath("ss7-multi-link-lab.json");
        Map<Integer, List<PeerRoute>> built = Ss7PeerRouteService.buildCandidates(cfg);
        assertEquals(1, built.size());
        List<PeerRoute> n0 = built.get(0);
        assertNotNull(n0);
        assertEquals(2, n0.size());
        assertTrue(n0.contains(new PeerRoute("L1-PEER-A-ASP", 20)));
        assertTrue(n0.contains(new PeerRoute("L2-PEER-B-ASP", 21)));
    }

    @Test
    void buildCandidatesOneAsNnLinks() throws Exception {
        String json = """
                {
                  "stackName": "nn",
                  "protocols": { "map": true },
                  "sctp": { "links": [
                    { "name": "L1-STP-2", "type": "server", "channel": "sctp",
                      "local": "127.0.0.1:8013", "peer": "127.0.0.1:8014" },
                    { "name": "L2-STP-3", "type": "server", "channel": "sctp",
                      "local": "127.0.0.1:8023", "peer": "127.0.0.1:8024" }
                  ]},
                  "m3ua": {
                    "as": [{
                      "name": "AS-LAB", "mode": "loadshare", "functionality": "ipsp",
                      "ipsp": "server", "exchangeType": "DE", "routingContext": 1,
                      "links": ["L1-STP-2", "L2-STP-3"]
                    }],
                    "routes": [
                      { "to": { "dpc": 2, "opc": 1 }, "via": "AS-LAB" },
                      { "to": { "dpc": 3, "opc": 1 }, "via": "AS-LAB" }
                    ]
                  },
                  "sccp": {
                    "localPoints": [{
                      "pc": 1, "networkIndicator": "national", "networkId": 0,
                      "reachablePointCodes": [2, 3]
                    }]
                  },
                  "services": [{ "name": "primary", "ssn": 8, "protocol": "map" }]
                }
                """;
        Ss7Config cfg = Ss7ConfigLoader.load(
                new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        List<PeerRoute> n0 = Ss7PeerRouteService.buildCandidates(cfg).get(0);
        assertNotNull(n0);
        assertEquals(2, n0.size());
        assertTrue(n0.contains(new PeerRoute("L1-STP-2-ASP", 2)));
        assertTrue(n0.contains(new PeerRoute("L2-STP-3-ASP", 3)));
    }

    @Test
    void matchAspForDpcPrefersExactSuffix() {
        assertEquals("L1-STP-2-ASP",
                Ss7PeerRouteService.matchAspForDpc(List.of("L1-STP-2", "L2-STP-3"), 2));
        assertEquals("L2-STP-3-ASP",
                Ss7PeerRouteService.matchAspForDpc(List.of("L1-STP-2", "L2-STP-3"), 3));
    }

    private static Ss7Config loadClasspath(String name) throws Exception {
        try (InputStream in = Ss7PeerRouteServiceTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name);
            return Ss7ConfigLoader.load(in);
        }
    }

    /** Local (non-clustered) ClusterManager — proves the Infinispan API alignment. */
    private static com.microjainslee.cluster.ClusterManager localClusterManager(String nodeId) {
        com.microjainslee.core.MicroSleeConfiguration cfg =
                com.microjainslee.core.MicroSleeConfiguration.builder()
                        .clusterEnabled(false)
                        .nodeId(nodeId)
                        .build();
        com.microjainslee.cluster.ClusterManager cm =
                new com.microjainslee.cluster.ClusterManager(cfg, nodeId);
        cm.start();
        return cm;
    }

    private static void set(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Ss7Config multiAsLabConfig() throws Exception {
        String json = """
                {
                  "stackName": "nn",
                  "protocols": { "map": true },
                  "sctp": { "links": [
                    { "name": "L1-STP-2", "type": "server", "channel": "sctp",
                      "local": "127.0.0.1:8013", "peer": "127.0.0.1:8014" },
                    { "name": "L2-STP-3", "type": "server", "channel": "sctp",
                      "local": "127.0.0.1:8023", "peer": "127.0.0.1:8024" }
                  ]},
                  "m3ua": {
                    "as": [{
                      "name": "AS-LAB", "mode": "loadshare", "functionality": "ipsp",
                      "ipsp": "server", "exchangeType": "DE", "routingContext": 1,
                      "links": ["L1-STP-2", "L2-STP-3"]
                    }],
                    "routes": [
                      { "to": { "dpc": 2, "opc": 1 }, "via": "AS-LAB" },
                      { "to": { "dpc": 3, "opc": 1 }, "via": "AS-LAB" }
                    ]
                  },
                  "sccp": {
                    "localPoints": [{
                      "pc": 1, "networkIndicator": "national", "networkId": 0,
                      "reachablePointCodes": [2, 3]
                    }]
                  },
                  "services": [{ "name": "primary", "ssn": 8, "protocol": "map" }]
                }
                """;
        return Ss7ConfigLoader.load(
                new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
