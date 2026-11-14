package et.restlink.ussdgw.api.classic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.api.UssdAlphabet;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden compatibility with the classic WildFly ussdgateway XML wire. The fixtures under
 * {@code classic-legacy/} are copied verbatim from
 * {@code ussdgateway/core/xml/src/test/resources/legacy/map/} — XML the old gateway and its ASes
 * exchange today. They must keep decoding, and what this gateway sends must keep their shape.
 */
class ClassicLegacyFixtureCompatTest {

    private static final XmlMapper XML = new XmlMapper();

    private static String fixture(String name) throws IOException {
        try (InputStream in = ClassicLegacyFixtureCompatTest.class
                .getResourceAsStream("/classic-legacy/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void classicNiNotifyPushDecodes() throws Exception {
        ClassicNiIngress ni = ClassicDialogXmlCodec.decodeNiRequest(fixture("push-unstructuredSSNotify.xml"));
        assertThat(ni.msisdn()).isEqualTo("11111111111111");
        assertThat(ni.text()).isEqualTo(
                "Your new balance is 34.38 AFN and expires on 30.07.2012. Cost of last event was 0.50 AFN.");
        assertThat(ni.notifyOnly()).isTrue();
        assertThat(ni.rawFormat()).isEqualTo(et.restlink.ussdgw.api.AsHttpWireFormat.XML);
    }

    @Test
    void classicPullRequestShapeIsWhatWeStillSend() throws Exception {
        // What classic sent to the AS for an MO pull (attribute form).
        JsonNode legacy = XML.readTree(fixture("pull-processUnstructuredSSRequest-attribute.xml"));
        String ours = ClassicDialogXmlCodec.encodePull(
                new AsRequest("s1", "corr-1", "r1", 0, "1234567890", "*100#", "*100#", 0));
        JsonNode mine = XML.readTree(ours);

        JsonNode l = legacy.get("processUnstructuredSSRequest_Request");
        JsonNode m = mine.get("processUnstructuredSSRequest_Request");
        assertThat(m).as("same element name").isNotNull();
        assertThat(m.get("dataCodingScheme").asText()).isEqualTo(l.get("dataCodingScheme").asText());
        assertThat(m.get("string").asText()).isEqualTo(l.get("string").asText());
        assertThat(m.get("msisdn").get("number").asText()).isEqualTo(l.get("msisdn").get("number").asText());
        assertThat(m.get("msisdn").get("nai").asText()).isEqualTo(l.get("msisdn").get("nai").asText());
        assertThat(m.get("msisdn").get("npi").asText()).isEqualTo(l.get("msisdn").get("npi").asText());
    }

    @Test
    void classicNiRequestWithSccpAddressStillDecodes() throws Exception {
        ClassicNiIngress ni = ClassicDialogXmlCodec.decodeNiRequest(fixture("dialog-with-sccp-address.xml"));
        assertThat(ni.text()).isEqualTo("*234#");
        assertThat(ni.notifyOnly()).isFalse();
    }

    @Test
    void elementFormAlsoDecodes() throws Exception {
        ClassicNiIngress ni = ClassicDialogXmlCodec.decodeNiRequest(
                fixture("pull-processUnstructuredSSRequest-element.xml"));
        assertThat(ni.msisdn()).isEqualTo("1234567890");
    }

    @Test
    void classicAsResponsesKeepTheirAlphabet() {
        // 15 (GSM-7), 72 (UCS-2 Amharic), 68 (8-bit), bare 8 (classic UCS-2), 0 (GSM-7 German).
        assertThat(alphabetOf(15, "Balance 10 ETB")).isEqualTo(UssdAlphabet.UCS7);
        assertThat(alphabetOf(72, "ቀሪ ሂሳብዎ")).isEqualTo(UssdAlphabet.UNICODE);
        assertThat(alphabetOf(68, "Café")).isEqualTo(UssdAlphabet.UCS8);
        assertThat(alphabetOf(8, "ሰላም")).isEqualTo(UssdAlphabet.UNICODE);
        assertThat(alphabetOf(0, "Hallo")).isEqualTo(UssdAlphabet.UCS7);
    }

    private static UssdAlphabet alphabetOf(int dcs, String text) {
        String xml = "<dialog localId=\"c1\" mapMessagesSize=\"1\">"
                + "<processUnstructuredSSRequest_Response dataCodingScheme=\"" + dcs + "\" string=\""
                + text + "\"/></dialog>";
        AsResponse r = ClassicDialogXmlCodec.decodeResponse(xml, "c1");
        assertThat(r.text()).isEqualTo(text);
        assertThat(r.action()).isEqualTo(AsAction.END);
        return r.alphabet();
    }
}
