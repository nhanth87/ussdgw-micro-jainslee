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

    @Test
    void latePushMetadataRoundTripOnRequestAndResponse() {
        AsRequest req = new AsRequest("vs-9", "corr-9", "req-9", 2, "2519", "*100#", "1", 1,
                "corr-9", 3500L, "BRIDGE");
        AsRequest back = AsWireCodec.decodeRequest(AsWireCodec.encodeRequest(req));
        assertThat(back.sessionId()).isEqualTo("vs-9");
        assertThat(back.correlationId()).isEqualTo("corr-9");
        assertThat(back.virtualBridgeId()).isEqualTo("corr-9");
        assertThat(back.adaptiveTimeoutMs()).isEqualTo(3500L);
        assertThat(back.asMode()).isEqualTo("BRIDGE");

        AsResponse resp = new AsResponse("corr-9", "req-9", 2, "Menu", AsAction.CONTINUE, true,
                UssdAlphabet.AUTO, "vs-9", "corr-9", 3500L);
        AsResponse decoded = AsWireCodec.decodeResponse(AsWireCodec.encodeResponse(resp), "fb");
        assertThat(decoded.async()).isTrue();
        assertThat(decoded.sessionId()).isEqualTo("vs-9");
        assertThat(decoded.virtualBridgeId()).isEqualTo("corr-9");
        assertThat(decoded.adaptiveTimeoutMs()).isEqualTo(3500L);
        assertThat(decoded.resolvePushBackId()).isEqualTo("corr-9");
    }

    @Test
    void grpcJsonBytesShareSameCodecAsHttp() {
        // GrpcClientSbb / GrpcServerSbb use AsWireCodec byte path — identical schema.
        AsRequest req = new AsRequest("vs", "c-grpc", "r", 1, "2519", "*1#", "*1#", 0,
                "c-grpc", 2000L, "SYNC");
        byte[] payload = AsWireCodec.encodeRequest(req);
        AsRequest decoded = AsWireCodec.decodeRequest(payload);
        assertThat(decoded.virtualBridgeId()).isEqualTo("c-grpc");
        assertThat(decoded.adaptiveTimeoutMs()).isEqualTo(2000L);

        String callbackJson = """
                {"virtualBridgeId":"c-grpc","generation":1,"text":"late","action":"END","async":false}
                """;
        AsResponse late = AsWireCodec.decodeResponse(callbackJson.getBytes(), "fallback");
        assertThat(late.resolvePushBackId()).isEqualTo("c-grpc");
        assertThat(late.correlationId()).isEqualTo("c-grpc");
        assertThat(late.text()).isEqualTo("late");
    }

    @Test
    void resolvePushBackPrefersCorrelationThenBridgeThenSession() {
        assertThat(new AsResponse(null, "r", 1, "t", AsAction.END, false, null, "vs", "br", null)
                .resolvePushBackId()).isEqualTo("br");
        assertThat(new AsResponse(null, "r", 1, "t", AsAction.END, false, null, "vs", null, null)
                .resolvePushBackId()).isEqualTo("vs");
    }
}
