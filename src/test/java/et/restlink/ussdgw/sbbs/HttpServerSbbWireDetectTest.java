package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.api.AsHttpWireFormat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpServerSbbWireDetectTest {
    @Test
    void contentTypeXmlWins() {
        assertThat(HttpServerSbb.detectWireFormat("text/xml; charset=utf-8", "{\"a\":1}"))
                .isEqualTo(AsHttpWireFormat.XML);
        assertThat(HttpServerSbb.detectWireFormat("application/xml", "not-xml"))
                .isEqualTo(AsHttpWireFormat.XML);
    }

    @Test
    void bodyLeadingAngleBracketIsXml() {
        assertThat(HttpServerSbb.detectWireFormat("application/json", "  <dialog/>"))
                .isEqualTo(AsHttpWireFormat.XML);
        assertThat(HttpServerSbb.detectWireFormat(null, "<dialog mapMessagesSize=\"0\"/>"))
                .isEqualTo(AsHttpWireFormat.XML);
    }

    @Test
    void otherwiseJson() {
        assertThat(HttpServerSbb.detectWireFormat("application/json", "{\"accepted\":true}"))
                .isEqualTo(AsHttpWireFormat.JSON);
        assertThat(HttpServerSbb.detectWireFormat(null, "{ \"text\": \"hi\" }"))
                .isEqualTo(AsHttpWireFormat.JSON);
        assertThat(HttpServerSbb.detectWireFormat(null, null))
                .isEqualTo(AsHttpWireFormat.JSON);
        assertThat(HttpServerSbb.detectWireFormat("", "   "))
                .isEqualTo(AsHttpWireFormat.JSON);
    }
}
