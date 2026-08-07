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
        assertThat(resp.action()).isEqualTo(AsAction.CONTINUE);
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
