package et.restlink.ussdgw.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AsHttpWireFormatTest {
    @Test
    void parseBlankDefaultsToXml() {
        assertThat(AsHttpWireFormat.parse(null)).isEqualTo(AsHttpWireFormat.XML);
        assertThat(AsHttpWireFormat.parse("")).isEqualTo(AsHttpWireFormat.XML);
        assertThat(AsHttpWireFormat.parse("  ")).isEqualTo(AsHttpWireFormat.XML);
    }

    @Test
    void parseCaseInsensitive() {
        assertThat(AsHttpWireFormat.parse("xml")).isEqualTo(AsHttpWireFormat.XML);
        assertThat(AsHttpWireFormat.parse("XML")).isEqualTo(AsHttpWireFormat.XML);
        assertThat(AsHttpWireFormat.parse("json")).isEqualTo(AsHttpWireFormat.JSON);
        assertThat(AsHttpWireFormat.parse("Json")).isEqualTo(AsHttpWireFormat.JSON);
        assertThat(AsHttpWireFormat.parse("other")).isEqualTo(AsHttpWireFormat.XML);
    }

    @Test
    void contentTypes() {
        assertThat(AsHttpWireFormat.XML.contentType()).isEqualTo("text/xml; charset=utf-8");
        assertThat(AsHttpWireFormat.JSON.contentType()).isEqualTo("application/json; charset=utf-8");
    }
}
