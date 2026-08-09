package et.restlink.ussdgw.service;

import com.microjainslee.cluster.Ss7PeerRouteAffinity.PeerRoute;

import org.junit.jupiter.api.Test;
import org.restcomm.protocols.ss7.config.Ss7Config;
import org.restcomm.protocols.ss7.config.Ss7ConfigLoader;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ss7PeerRouteServiceTest {

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
                    { "name": "L1-BP-1404", "type": "server", "channel": "sctp",
                      "local": "127.0.0.1:8013", "peer": "127.0.0.1:8014" },
                    { "name": "L2-BP-1403", "type": "server", "channel": "sctp",
                      "local": "127.0.0.1:8023", "peer": "127.0.0.1:8024" }
                  ]},
                  "m3ua": {
                    "as": [{
                      "name": "AS-BP", "mode": "loadshare", "functionality": "ipsp",
                      "ipsp": "server", "exchangeType": "DE", "routingContext": 1,
                      "links": ["L1-BP-1404", "L2-BP-1403"]
                    }],
                    "routes": [
                      { "to": { "dpc": 1404, "opc": 1 }, "via": "AS-BP" },
                      { "to": { "dpc": 1403, "opc": 1 }, "via": "AS-BP" }
                    ]
                  },
                  "sccp": {
                    "localPoints": [{
                      "pc": 1, "networkIndicator": "national", "networkId": 0,
                      "reachablePointCodes": [1403, 1404]
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
        assertTrue(n0.contains(new PeerRoute("L1-BP-1404-ASP", 1404)));
        assertTrue(n0.contains(new PeerRoute("L2-BP-1403-ASP", 1403)));
    }

    @Test
    void matchAspForDpcPrefersExactSuffix() {
        assertEquals("L1-BP-1404-ASP",
                Ss7PeerRouteService.matchAspForDpc(List.of("L1-BP-1404", "L2-BP-1403"), 1404));
        assertEquals("L2-BP-1403-ASP",
                Ss7PeerRouteService.matchAspForDpc(List.of("L1-BP-1404", "L2-BP-1403"), 1403));
    }

    private static Ss7Config loadClasspath(String name) throws Exception {
        try (InputStream in = Ss7PeerRouteServiceTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name);
            return Ss7ConfigLoader.load(in);
        }
    }
}
