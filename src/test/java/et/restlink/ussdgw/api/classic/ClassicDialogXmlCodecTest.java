package et.restlink.ussdgw.api.classic;

import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.AsResponse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClassicDialogXmlCodecTest {
    @Test
    void encodePullGenerationZeroUsesProcessUnstructured() {
        AsRequest req = new AsRequest("s1", "corr-42", "r1", 0, "251911122233", "*123#", "*123#", 7);
        String xml = ClassicDialogXmlCodec.encodePull(req);
        assertThat(xml).contains("<dialog ")
                .contains("localId=\"corr-42\"")
                .contains("networkId=\"7\"")
                .contains("processUnstructuredSSRequest_Request")
                .contains("string=\"*123#\"")
                .contains("number=\"251911122233\"")
                .doesNotContain("unstructuredSSRequest_Request");
    }

    @Test
    void encodePullContinueUsesUnstructuredRequest() {
        AsRequest req = new AsRequest("s1", "c", "r", 1, "2519", "1", "1", 0);
        String xml = ClassicDialogXmlCodec.encodePull(req);
        assertThat(xml).contains("unstructuredSSRequest_Request")
                .contains("string=\"1\"")
                .doesNotContain("processUnstructuredSSRequest_Request");
    }

    @Test
    void decodeResponseFixtureContinueThenEndThenAbort() {
        String continueXml = """
                <?xml version="1.0"?>
                <dialog localId="c1">
                  <unstructuredSSRequest_Request dataCodingScheme="15" string="1. Balance"/>
                </dialog>
                """;
        AsResponse cont = ClassicDialogXmlCodec.decodeResponse(continueXml, "fb");
        assertThat(cont.correlationId()).isEqualTo("c1");
        assertThat(cont.action()).isEqualTo(AsAction.CONTINUE);
        assertThat(cont.text()).isEqualTo("1. Balance");
        assertThat(cont.async()).isFalse();

        String endXml = """
                <dialog localId="c1">
                  <processUnstructuredSSRequest_Response dataCodingScheme="15" string=""/>
                </dialog>
                """;
        AsResponse end = ClassicDialogXmlCodec.decodeResponse(endXml, "fb");
        assertThat(end.action()).isEqualTo(AsAction.END);
        assertThat(end.text()).isEmpty();

        String abortXml = """
                <dialog localId="c9" mapMessagesSize="0" mapUserAbortChoice="isUserSpecificReason"/>
                """;
        AsResponse abort = ClassicDialogXmlCodec.decodeResponse(abortXml, "fb");
        assertThat(abort.action()).isEqualTo(AsAction.ABORT);
        assertThat(abort.correlationId()).isEqualTo("c9");
    }

    @Test
    void roundTripEncodePullDecodeResponse() {
        AsRequest req = new AsRequest("s", "corr-rt", "r", 0, "1234567890", "*100#", "*100#", 1);
        String pull = ClassicDialogXmlCodec.encodePull(req);
        assertThat(pull).contains("processUnstructuredSSRequest_Request").contains("*100#");

        String asReply = """
                <dialog localId="corr-rt">
                  <processUnstructuredSSRequest_Response dataCodingScheme="15" string="Thank you"/>
                </dialog>
                """;
        AsResponse resp = ClassicDialogXmlCodec.decodeResponse(asReply, "fb");
        assertThat(resp.correlationId()).isEqualTo("corr-rt");
        assertThat(resp.text()).isEqualTo("Thank you");
        assertThat(resp.action()).isEqualTo(AsAction.END);
    }

    @Test
    void encodeNiNotifyResponse() {
        String xml = ClassicDialogXmlCodec.encodeNiNotifyResponse("corr-ntfy");
        assertThat(xml)
                .contains("localId=\"corr-ntfy\"")
                .contains("mapMessagesSize=\"1\"")
                .contains("<unstructuredSSNotify_Response/>")
                .doesNotContain("unstructuredSSRequest_Request");
    }

    @Test
    void encodeNiSnapshotAndDecodeNiRequest() {
        String ni = ClassicDialogXmlCodec.encodeNiSnapshot("ni-1", "Push text", AsAction.CONTINUE, true);
        assertThat(ni).contains("emptyDialogHandshake=\"true\"")
                .contains("unstructuredSSRequest_Request")
                .contains("string=\"Push text\"");

        String ingress = """
                <dialog localId="ni-in" emptyDialogHandshake="true">
                  <unstructuredSSNotify_Request dataCodingScheme="15" string="Hi">
                    <msisdn nai="international_number" npi="ISDN" number="11111111111111"/>
                  </unstructuredSSNotify_Request>
                </dialog>
                """;
        ClassicNiIngress parsed = ClassicDialogXmlCodec.decodeNiRequest(ingress);
        assertThat(parsed.msisdn()).isEqualTo("11111111111111");
        assertThat(parsed.text()).isEqualTo("Hi");
        assertThat(parsed.correlationId()).isEqualTo("ni-in");
        assertThat(parsed.emptyDialogHandshake()).isTrue();
    }

    @Test
    void encodePullIncludesLatePushMetadataAttrs() {
        AsRequest req = new AsRequest("vs-1", "corr-meta", "r1", 0, "251911", "*123#", "*123#", 2,
                "corr-meta", 5100L, "BRIDGE");
        String xml = ClassicDialogXmlCodec.encodePull(req);
        assertThat(xml)
                .contains("localId=\"corr-meta\"")
                .contains("sessionId=\"vs-1\"")
                .contains("virtualBridgeId=\"corr-meta\"")
                .contains("adaptiveTimeoutMs=\"5100\"")
                .contains("asMode=\"BRIDGE\"");
    }

    @Test
    void encodePullIncludesOriginatedShortCodeAndCodeKind() {
        AsRequest req = new AsRequest("vs-1", "corr-m2m", "r1", 0, "251911000001", "*804#",
                "UserInfo hop", 0, "corr-m2m", 4200L, "BRIDGE")
                .withOriginated("*804#", "SHORT");
        String xml = ClassicDialogXmlCodec.encodePull(req);
        assertThat(xml)
                .contains("shortCode=\"*804#\"")
                .contains("originatedUssd=\"*804#\"")
                .contains("codeKind=\"SHORT\"")
                .contains("string=\"UserInfo hop\"")
                .contains("number=\"251911000001\"");
    }

    @Test
    void encodePullReRouteHlrRejectUsesStringAndHlrResult() {
        AsRequest req = new AsRequest("vs-1", "corr-rej", "r1", 0, "251911000001", "*804#",
                "hlr reject", 0)
                .withOriginated("*804#", "SHORT");
        String xml = ClassicDialogXmlCodec.encodePull(req);
        assertThat(xml)
                .contains("hlrResult=\"reject\"")
                .contains("string=\"hlr reject\"")
                .contains("originatedUssd=\"*804#\"");
    }

    @Test
    void encodePullReRouteHlrPendingUsesStringAndHlrResult() {
        AsRequest req = new AsRequest("vs-1", "corr-pend", "r1", 0, "251911000001", "*804#",
                "hlr pending", 0)
                .withOriginated("*804#", "SHORT");
        String xml = ClassicDialogXmlCodec.encodePull(req);
        assertThat(xml)
                .contains("hlrResult=\"pending\"")
                .contains("string=\"hlr pending\"");
    }

    @Test
    void encodePullMap2MapIncludesRedirectAndHopUssd() {
        AsRequest req = new AsRequest("vs-1", "corr-m2m", "r1", 0, "251911230398", "*804#",
                "", 0, "corr-m2m", 25000L, "BRIDGE")
                .withOriginated("*804#", "SHORT")
                .withMap2MapCodes("*875#", "*8775#");
        String xml = ClassicDialogXmlCodec.encodePull(req);
        assertThat(xml)
                .contains("shortCode=\"*804#\"")
                .contains("originatedUssd=\"*804#\"")
                .contains("redirectUssd=\"*875#\"")
                .contains("hopUssd=\"*8775#\"")
                .contains("hlrResult=\"none\"")
                .contains("string=\"\"")
                .doesNotContain("string=\"hlr none\"")
                .contains("number=\"251911230398\"");
    }

    @Test
    void encodePullMap2MapHopTextKeepsRedirectAttrs() {
        AsRequest req = new AsRequest("vs-1", "corr-ok", "r1", 0, "251911000001", "*804#",
                "Balance: 12.50 ETB", 0)
                .withOriginated("*804#", "SHORT")
                .withMap2MapCodes("*875#", "*875#");
        String xml = ClassicDialogXmlCodec.encodePull(req);
        assertThat(xml)
                .contains("string=\"Balance: 12.50 ETB\"")
                .contains("redirectUssd=\"*875#\"")
                .contains("hopUssd=\"*875#\"")
                .contains("hlrResult=\"responded\"");
    }

    @Test
    void encodePullMap2MapAmharicHopTextInStringAttr() {
        String amharic = "ውድ ደንበኛ ፣ ውጤቱ በአጭር መልእክት ተልኳል። ኢትዮ ቴሌኮም";
        AsRequest req = new AsRequest("vs-1", "corr-am", "r1", 0, "251911230398", "*804#",
                amharic, 0)
                .withOriginated("*804#", "SHORT")
                .withMap2MapCodes("*875#", "*8775#");
        String xml = ClassicDialogXmlCodec.encodePull(req);
        assertThat(xml)
                .contains("hlrResult=\"responded\"")
                .contains("string=\"" + amharic + "\"")
                .contains("hopUssd=\"*8775#\"")
                .doesNotContain("hlr none");
    }

    @Test
    void decodeResponseMultiMenuFirstRequestWins() {
        // mapMessagesSize>1: GW applies the first Request/Response string (menu body).
        // Successive menus use later HTTP round-trips — see map2map-as-xml.md §4d.
        String xml = """
                <dialog mapMessagesSize="2" localId="corr-mm">
                  <unstructuredSSRequest_Request dataCodingScheme="15" string="Menu 1&#10;1 Next"/>
                  <unstructuredSSRequest_Request dataCodingScheme="15" string="ignored-second"/>
                </dialog>
                """;
        AsResponse r = ClassicDialogXmlCodec.decodeResponse(xml, "fb");
        assertThat(r.action()).isEqualTo(AsAction.CONTINUE);
        assertThat(r.text()).isEqualTo("Menu 1\n1 Next");
        assertThat(r.correlationId()).isEqualTo("corr-mm");
    }

    @Test
    void decodeFinalProcessResponseIsEndWithUcs2Amharic() {
        String amharic = "ውድ ደንበኛ ፤ ውጤቱ በአጭር መለእክት ተልኳል፡፡ ኢትዮ ቴሌኮም";
        String xml = """
                <dialog mapMessagesSize="1" prearrangedEnd="false" returnMessageOnError="true">
                  <processUnstructuredSSRequest_Response
                      invokeId="1"
                      dataCodingScheme="72"
                      string="%s"/>
                </dialog>
                """.formatted(amharic);
        AsResponse r = ClassicDialogXmlCodec.decodeResponse(xml, "corr-fallback");
        assertThat(r.action()).isEqualTo(AsAction.END);
        assertThat(r.text()).isEqualTo(amharic);
        assertThat(r.alphabet()).isEqualTo(et.restlink.ussdgw.api.UssdAlphabet.UNICODE);
        // Missing localId → sync pull uses outstanding correlation
        assertThat(r.correlationId()).isEqualTo("corr-fallback");
        assertThat(r.text().length()).isNotEqualTo(8);
    }

    @Test
    void decodeMenuRequestRemainsContinue() {
        String xml = """
                <dialog mapMessagesSize="1" localId="corr-menu">
                  <unstructuredSSRequest_Request dataCodingScheme="15"
                      string="meow meow meow meow"/>
                </dialog>
                """;
        AsResponse r = ClassicDialogXmlCodec.decodeResponse(xml, "fb");
        assertThat(r.action()).isEqualTo(AsAction.CONTINUE);
        assertThat(r.text()).isEqualTo("meow meow meow meow");
        assertThat(r.alphabet()).isEqualTo(et.restlink.ussdgw.api.UssdAlphabet.UCS7);
    }

    @Test
    void decodeResponseReadsAsyncAndBridgeAttrs() {
        String xml = """
                <dialog localId="c-async" sessionId="vs-x" virtualBridgeId="c-async"
                        adaptiveTimeoutMs="3000" async="true">
                  <unstructuredSSRequest_Request dataCodingScheme="15" string=""/>
                </dialog>
                """;
        AsResponse r = ClassicDialogXmlCodec.decodeResponse(xml, "fb");
        assertThat(r.async()).isTrue();
        assertThat(r.sessionId()).isEqualTo("vs-x");
        assertThat(r.virtualBridgeId()).isEqualTo("c-async");
        assertThat(r.adaptiveTimeoutMs()).isEqualTo(3000L);
        assertThat(r.resolvePushBackId()).isEqualTo("c-async");
    }

    @Test
    void encodeGatedPushIncludesBridgeAdaptiveJsessionAndNotify() {
        var meta = et.restlink.ussdgw.bridge.GatedSessionMeta.niPark(
                "corr-g", "js-g", 4200L, 2800L, 0, "251911000001", "*123#", "vs-g");
        String xml = ClassicDialogXmlCodec.encodeGatedPush(meta);
        assertThat(xml)
                .contains("localId=\"corr-g\"")
                .contains("sessionId=\"vs-g\"")
                .contains("virtualBridgeId=\"corr-g\"")
                .contains("adaptiveTimeoutMs=\"4200\"")
                .contains("observedEwmaMs=\"2800\"")
                .contains("jsessionId=\"js-g\"")
                .contains("gateReason=\"GATE_EXPIRED\"")
                .contains("unstructuredSSNotify_Request")
                .contains("string=\"GATE_EXPIRED\"")
                .contains("number=\"251911000001\"");
    }

    @Test
    void decodeCallbackResolvesViaVirtualBridgeIdWhenLocalIdMissing() {
        String xml = """
                <dialog virtualBridgeId="bridge-only">
                  <processUnstructuredSSRequest_Response dataCodingScheme="15" string="Late OK"/>
                </dialog>
                """;
        AsResponse r = ClassicDialogXmlCodec.decodeCallback(xml);
        assertThat(r.correlationId()).isEqualTo("bridge-only");
        assertThat(r.text()).isEqualTo("Late OK");
    }
}
