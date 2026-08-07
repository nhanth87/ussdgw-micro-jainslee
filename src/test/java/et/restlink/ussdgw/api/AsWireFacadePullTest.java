package et.restlink.ussdgw.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pull encode/decode paths used by HttpClientSbb via {@link AsWireFacade}. */
class AsWireFacadePullTest {
    private final AsWireFacade facade = new AsWireFacade();

    @Test
    void encodePullJsonRoundTripsViaDecode() {
        AsRequest req = new AsRequest("s1", "c1", "r1", 1, "251911", "*123#", "1", 7);
        String body = facade.encodePullRequest(req, AsHttpWireFormat.JSON);
        assertThat(body).contains("\"correlationId\":\"c1\"");
        AsResponse resp = facade.decodePullResponse(
                "{\"correlationId\":\"c1\",\"generation\":1,\"text\":\"ok\",\"action\":\"END\"}",
                AsHttpWireFormat.JSON, "c1");
        assertThat(resp.correlationId()).isEqualTo("c1");
        assertThat(resp.text()).isEqualTo("ok");
    }

    @Test
    void encodePullXmlContainsDialog() {
        AsRequest req = new AsRequest("s1", "c1", "r1", 1, "251911", "*123#", "hi", 7);
        String body = facade.encodePullRequest(req, AsHttpWireFormat.XML);
        assertThat(body).contains("<dialog").contains("c1");
    }

    @Test
    void encodePullJsonIncludesMetadata() {
        AsRequest req = new AsRequest("s1", "c1", "r1", 1, "251911", "*123#", "1", 7,
                "c1", 2500L, "SYNC");
        String body = facade.encodePullRequest(req, AsHttpWireFormat.JSON);
        assertThat(body).contains("\"virtualBridgeId\":\"c1\"")
                .contains("\"adaptiveTimeoutMs\":2500")
                .contains("\"asMode\":\"SYNC\"");
    }

    @Test
    void nullFormatDefaultsToXml() {
        AsRequest req = new AsRequest("s1", "c1", "r1", 1, "251911", "*123#", "hi", 7);
        String body = facade.encodePullRequest(req, null);
        assertThat(body).contains("<dialog");
    }
}
