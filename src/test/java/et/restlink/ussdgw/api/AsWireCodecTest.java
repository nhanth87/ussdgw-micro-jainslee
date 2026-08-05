package et.restlink.ussdgw.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AsWireCodecTest {
    @Test
    void roundTripRequestResponse() {
        AsRequest req = new AsRequest("s", "c", "r", 1, "2519", "*123#", "*123#", 0);
        AsRequest back = AsWireCodec.decodeRequest(AsWireCodec.encodeRequest(req));
        assertThat(back.correlationId()).isEqualTo("c");
        assertThat(back.ussdString()).isEqualTo("*123#");

        AsResponse resp = new AsResponse("c", "r", 1, "Hello", AsAction.CONTINUE, false);
        AsResponse decoded = AsWireCodec.decodeResponse(AsWireCodec.encodeResponse(resp), "fallback");
        assertThat(decoded.action()).isEqualTo(AsAction.CONTINUE);
        assertThat(decoded.text()).isEqualTo("Hello");
    }

    @Test
    void httpAlphabetFromAsJsonNotHardcoded() {
        String json = """
                {"correlationId":"c1","requestId":"r1","generation":1,
                 "text":"ሰላም","action":"END","async":false,"alphabet":"unicode"}
                """;
        AsResponse r = AsWireCodec.decodeResponse(json, "fallback");
        assertThat(r.alphabet()).isEqualTo(UssdAlphabet.UNICODE);
        assertThat(r.alphabet().toWire()).isEqualTo("unicode");

        AsResponse gsm = AsWireCodec.decodeResponse(
                "{\"correlationId\":\"c\",\"text\":\"OK\",\"alphabet\":\"ucs7\"}", "fb");
        assertThat(gsm.alphabet()).isEqualTo(UssdAlphabet.UCS7);

        AsResponse eight = AsWireCodec.decodeResponse(
                "{\"correlationId\":\"c\",\"text\":\"OK\",\"alphabet\":\"ucs8\"}", "fb");
        assertThat(eight.alphabet()).isEqualTo(UssdAlphabet.UCS8);
    }

    @Test
    void rawTextBecomesEnd() {
        AsResponse r = AsWireCodec.decodeResponse("Menu text", "corr-1");
        assertThat(r.correlationId()).isEqualTo("corr-1");
        assertThat(r.action()).isEqualTo(AsAction.END);
        assertThat(r.text()).isEqualTo("Menu text");
        assertThat(r.alphabet()).isEqualTo(UssdAlphabet.AUTO);
    }
}
