package et.restlink.ussdgw.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture tests for the dual-wire AS HTTP examples in
 * {@code docs/as-contract/map2map-as-xml.md} (linked from README).
 *
 * <p>GW→AS: encode pull from {@link AsRequest}. AS→GW: decode response fixtures.
 * Same URL; only {@link AsHttpWireFormat} / Content-Type and body shape change.
 */
class Map2MapAsWireContractExamplesTest {

    private final AsWireFacade facade = new AsWireFacade();

    private static final String MO_CORR = "corr-mo-1";
    private static final String MO_VS = "vs-mo-1";
    private static final String M2M_CORR = "e37caa26-9d16-4239-a2ff-deff0687da8d";
    private static final String M2M_VS = "4203367b-c862-4307-81a7-3fbaa50b2afd";
    private static final String HOP_AMHARIC =
            "ውድ ደንበኛ ፣ ውጤቱ በአጭር መልእክት ተልኳል። ኢትዮ ቴሌኮም";
    private static final String FINAL_AMHARIC =
            "ውድ ደንበኛ ፤ ውጤቱ በአጭር መለእክት ተልኳል፡፡ ኢትዮ ቴሌኮም";

    @Nested
    @DisplayName("§0 Content-Type (readme dual-wire table)")
    class ContentTypes {
        @Test
        void xmlAndJsonContentTypesMatchContract() {
            assertThat(AsHttpWireFormat.XML.contentType()).isEqualTo("text/xml; charset=utf-8");
            assertThat(AsHttpWireFormat.JSON.contentType())
                    .isEqualTo("application/json; charset=utf-8")
                    .isEqualTo(AsWireCodec.CONTENT_TYPE);
        }
    }

    @Nested
    @DisplayName("§1 Normal MO pull GW→AS")
    class MoPull {
        private AsRequest moPull() {
            return new AsRequest(MO_VS, MO_CORR, MO_CORR, 0, "251911000001", "*100#", "*100#", 0,
                    MO_CORR, 7000L, "BRIDGE")
                    .withOriginated("*100#", "SHORT");
        }

        @Test
        void xmlPullMatchesReadmeShape() {
            String xml = facade.encodePullRequest(moPull(), AsHttpWireFormat.XML);
            assertThat(xml)
                    .contains("appCntx=\"networkUnstructuredSsContext\"")
                    .contains("localId=\"" + MO_CORR + "\"")
                    .contains("sessionId=\"" + MO_VS + "\"")
                    .contains("virtualBridgeId=\"" + MO_CORR + "\"")
                    .contains("adaptiveTimeoutMs=\"7000\"")
                    .contains("asMode=\"BRIDGE\"")
                    .contains("shortCode=\"*100#\"")
                    .contains("originatedUssd=\"*100#\"")
                    .contains("codeKind=\"SHORT\"")
                    .contains("networkId=\"0\"")
                    .contains("processUnstructuredSSRequest_Request")
                    .contains("string=\"*100#\"")
                    .contains("number=\"251911000001\"")
                    .doesNotContain("unstructuredSSRequest_Request")
                    .doesNotContain("hlrResult=");
        }

        @Test
        void jsonPullRoundTripsReadmeFields() {
            String json = facade.encodePullRequest(moPull(), AsHttpWireFormat.JSON);
            AsRequest back = AsWireCodec.decodeRequest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(back.sessionId()).isEqualTo(MO_VS);
            assertThat(back.correlationId()).isEqualTo(MO_CORR);
            assertThat(back.requestId()).isEqualTo(MO_CORR);
            assertThat(back.generation()).isZero();
            assertThat(back.msisdn()).isEqualTo("251911000001");
            assertThat(back.shortCode()).isEqualTo("*100#");
            assertThat(back.ussdString()).isEqualTo("*100#");
            assertThat(back.networkId()).isZero();
            assertThat(back.virtualBridgeId()).isEqualTo(MO_CORR);
            assertThat(back.adaptiveTimeoutMs()).isEqualTo(7000L);
            assertThat(back.asMode()).isEqualTo("BRIDGE");
            assertThat(back.originatedUssd()).isEqualTo("*100#");
            assertThat(back.codeKind()).isEqualTo("SHORT");
        }

        @Test
        void jsonPullFixtureFromReadmeDecodes() {
            String fixture = """
                    {
                      "sessionId": "vs-mo-1",
                      "correlationId": "corr-mo-1",
                      "requestId": "corr-mo-1",
                      "generation": 0,
                      "msisdn": "251911000001",
                      "shortCode": "*100#",
                      "ussdString": "*100#",
                      "networkId": 0,
                      "virtualBridgeId": "corr-mo-1",
                      "adaptiveTimeoutMs": 7000,
                      "asMode": "BRIDGE",
                      "originatedUssd": "*100#",
                      "codeKind": "SHORT"
                    }
                    """;
            AsRequest back = AsWireCodec.decodeRequest(
                    fixture.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(back.correlationId()).isEqualTo(MO_CORR);
            assertThat(back.ussdString()).isEqualTo("*100#");
            assertThat(back.generation()).isZero();
        }
    }

    @Nested
    @DisplayName("§2 AS→GW CONTINUE menu")
    class ContinueMenu {
        @Test
        void xmlContinueFixture() {
            String xml = """
                    <dialog mapMessagesSize="1" localId="corr-mo-1">
                      <unstructuredSSRequest_Request dataCodingScheme="15"
                          string="1. Balance&#10;2. Topup&#10;0. Exit"/>
                    </dialog>
                    """;
            AsResponse r = facade.decodePullResponse(xml, AsHttpWireFormat.XML, MO_CORR);
            assertThat(r.action()).isEqualTo(AsAction.CONTINUE);
            assertThat(r.correlationId()).isEqualTo(MO_CORR);
            assertThat(r.text()).isEqualTo("1. Balance\n2. Topup\n0. Exit");
            assertThat(r.async()).isFalse();
        }

        @Test
        void jsonContinueFixture() {
            String json = """
                    {
                      "correlationId": "corr-mo-1",
                      "requestId": "corr-mo-1",
                      "generation": 1,
                      "text": "1. Balance\\n2. Topup\\n0. Exit",
                      "action": "CONTINUE",
                      "async": false,
                      "alphabet": "AUTO",
                      "sessionId": "vs-mo-1",
                      "virtualBridgeId": "corr-mo-1"
                    }
                    """;
            AsResponse r = facade.decodePullResponse(json, AsHttpWireFormat.JSON, MO_CORR);
            assertThat(r.action()).isEqualTo(AsAction.CONTINUE);
            assertThat(r.text()).isEqualTo("1. Balance\n2. Topup\n0. Exit");
            assertThat(r.alphabet()).isEqualTo(UssdAlphabet.AUTO);
            assertThat(r.sessionId()).isEqualTo(MO_VS);
            assertThat(r.virtualBridgeId()).isEqualTo(MO_CORR);
            assertThat(r.resolvePushBackId()).isEqualTo(MO_CORR);
        }

        @Test
        void xmlNeverTreatsNotifyAsInteractiveMenu() {
            // Contract reminder: Notify is one-shot; menu path must be Request / CONTINUE.
            String notify = """
                    <dialog mapMessagesSize="1" localId="corr-mo-1">
                      <unstructuredSSNotify_Request dataCodingScheme="15" string="one-shot"/>
                    </dialog>
                    """;
            AsResponse r = facade.decodePullResponse(notify, AsHttpWireFormat.XML, MO_CORR);
            assertThat(r.action()).isNotEqualTo(AsAction.CONTINUE);
        }
    }

    @Nested
    @DisplayName("§3 AS→GW final END")
    class FinalEnd {
        @Test
        void xmlAmharicProcessResponseIsEndUnicode() {
            String xml = """
                    <dialog mapMessagesSize="1" localId="corr-mo-1"
                            sessionId="vs-mo-1" virtualBridgeId="corr-mo-1"
                            prearrangedEnd="false" returnMessageOnError="true">
                      <processUnstructuredSSRequest_Response
                          invokeId="1"
                          dataCodingScheme="72"
                          string="%s"/>
                    </dialog>
                    """.formatted(FINAL_AMHARIC);
            AsResponse r = facade.decodePullResponse(xml, AsHttpWireFormat.XML, MO_CORR);
            assertThat(r.action()).isEqualTo(AsAction.END);
            assertThat(r.text()).isEqualTo(FINAL_AMHARIC);
            assertThat(r.alphabet()).isEqualTo(UssdAlphabet.UNICODE);
            assertThat(r.correlationId()).isEqualTo(MO_CORR);
            assertThat(r.sessionId()).isEqualTo(MO_VS);
            assertThat(r.virtualBridgeId()).isEqualTo(MO_CORR);
        }

        @Test
        void jsonAmharicEndUnicode() {
            String json = """
                    {
                      "correlationId": "corr-mo-1",
                      "requestId": "corr-mo-1",
                      "generation": 1,
                      "text": "%s",
                      "action": "END",
                      "async": false,
                      "alphabet": "UNICODE",
                      "sessionId": "vs-mo-1",
                      "virtualBridgeId": "corr-mo-1"
                    }
                    """.formatted(FINAL_AMHARIC);
            AsResponse r = facade.decodePullResponse(json, AsHttpWireFormat.JSON, MO_CORR);
            assertThat(r.action()).isEqualTo(AsAction.END);
            assertThat(r.text()).isEqualTo(FINAL_AMHARIC);
            assertThat(r.alphabet()).isEqualTo(UssdAlphabet.UNICODE);
        }

        @Test
        void emptyDialogAndEmptyJsonAreEnd() {
            AsResponse xmlEmpty = facade.decodePullResponse(
                    "<dialog mapMessagesSize=\"0\"/>", AsHttpWireFormat.XML, MO_CORR);
            assertThat(xmlEmpty.action()).isEqualTo(AsAction.END);
            assertThat(xmlEmpty.text()).isEmpty();

            AsResponse jsonEmpty = facade.decodePullResponse(
                    """
                    { "correlationId": "corr-mo-1", "action": "END", "text": "", "async": false }
                    """,
                    AsHttpWireFormat.JSON, MO_CORR);
            assertThat(jsonEmpty.action()).isEqualTo(AsAction.END);
            assertThat(jsonEmpty.text()).isEmpty();
        }

        @Test
        void abortFixtures() {
            AsResponse xmlAbort = facade.decodePullResponse(
                    """
                    <dialog mapMessagesSize="0" mapUserAbortChoice="isUserSpecificReason"/>
                    """,
                    AsHttpWireFormat.XML, MO_CORR);
            assertThat(xmlAbort.action()).isEqualTo(AsAction.ABORT);

            AsResponse jsonAbort = facade.decodePullResponse(
                    """
                    { "correlationId": "corr-mo-1", "action": "ABORT", "text": "", "async": false }
                    """,
                    AsHttpWireFormat.JSON, MO_CORR);
            assertThat(jsonAbort.action()).isEqualTo(AsAction.ABORT);
            assertThat(jsonAbort.correlationId()).isEqualTo(MO_CORR);
        }
    }

    @Nested
    @DisplayName("§4 MAP2MAP hop pull GW→AS")
    class Map2MapHopPull {
        private AsRequest hopResponded() {
            return new AsRequest(M2M_VS, M2M_CORR, M2M_CORR, 0, "251911230398", "*804#",
                    HOP_AMHARIC, 0, M2M_CORR, 25000L, "BRIDGE")
                    .withOriginated("*804#", "SHORT")
                    .withMap2MapCodes("*875#", "*8775#");
        }

        private AsRequest hopNone() {
            return new AsRequest(M2M_VS, M2M_CORR, M2M_CORR, 0, "251911230398", "*804#",
                    "", 0, M2M_CORR, 25000L, "BRIDGE")
                    .withOriginated("*804#", "SHORT")
                    .withMap2MapCodes("*875#", "*8775#");
        }

        private AsRequest hopReject() {
            return new AsRequest("vs-…", "corr-…", "corr-…", 0, "251911000001", "*804#",
                    "hlr reject", 0, "corr-…", 25000L, "BRIDGE")
                    .withOriginated("*804#", "SHORT")
                    .withMap2MapCodes("*875#", "*875#");
        }

        @Test
        void xmlHopRespondedUsesStringAndHlrResultResponded() {
            String xml = facade.encodePullRequest(hopResponded(), AsHttpWireFormat.XML);
            assertThat(xml)
                    .contains("localId=\"" + M2M_CORR + "\"")
                    .contains("sessionId=\"" + M2M_VS + "\"")
                    .contains("shortCode=\"*804#\"")
                    .contains("originatedUssd=\"*804#\"")
                    .contains("redirectUssd=\"*875#\"")
                    .contains("hopUssd=\"*8775#\"")
                    .contains("hlrResult=\"responded\"")
                    .contains("adaptiveTimeoutMs=\"25000\"")
                    .contains("string=\"" + HOP_AMHARIC + "\"")
                    .contains("number=\"251911230398\"")
                    .doesNotContain("hlr none");
        }

        @Test
        void jsonHopRespondedRoundTripsAndFixtureDecodes() {
            String encoded = facade.encodePullRequest(hopResponded(), AsHttpWireFormat.JSON);
            AsRequest back = AsWireCodec.decodeRequest(
                    encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(back.ussdString()).isEqualTo(HOP_AMHARIC);
            assertThat(back.redirectUssd()).isEqualTo("*875#");
            assertThat(back.hopUssd()).isEqualTo("*8775#");
            assertThat(back.originatedUssd()).isEqualTo("*804#");

            String fixture = """
                    {
                      "sessionId": "4203367b-c862-4307-81a7-3fbaa50b2afd",
                      "correlationId": "e37caa26-9d16-4239-a2ff-deff0687da8d",
                      "requestId": "e37caa26-9d16-4239-a2ff-deff0687da8d",
                      "generation": 0,
                      "msisdn": "251911230398",
                      "shortCode": "*804#",
                      "ussdString": "%s",
                      "networkId": 0,
                      "virtualBridgeId": "e37caa26-9d16-4239-a2ff-deff0687da8d",
                      "adaptiveTimeoutMs": 25000,
                      "asMode": "BRIDGE",
                      "originatedUssd": "*804#",
                      "codeKind": "SHORT",
                      "redirectUssd": "*875#",
                      "hopUssd": "*8775#"
                    }
                    """.formatted(HOP_AMHARIC);
            AsRequest fromDoc = AsWireCodec.decodeRequest(
                    fixture.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(fromDoc.ussdString()).isEqualTo(HOP_AMHARIC);
            assertThat(fromDoc.hopUssd()).isEqualTo("*8775#");
        }

        @Test
        void xmlHopNoneKeepsEmptyStringNotHlrNoneLiteral() {
            String xml = facade.encodePullRequest(hopNone(), AsHttpWireFormat.XML);
            assertThat(xml)
                    .contains("hlrResult=\"none\"")
                    .contains("redirectUssd=\"*875#\"")
                    .contains("hopUssd=\"*8775#\"")
                    .contains("string=\"\"")
                    .doesNotContain("string=\"hlr none\"");
        }

        @Test
        void jsonHopNoneEmptyUssdString() {
            String json = facade.encodePullRequest(hopNone(), AsHttpWireFormat.JSON);
            AsRequest back = AsWireCodec.decodeRequest(
                    json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(back.ussdString()).isEmpty();
            assertThat(back.redirectUssd()).isEqualTo("*875#");
            assertThat(back.hopUssd()).isEqualTo("*8775#");
        }

        @Test
        void hopRejectUsesHlrRejectString() {
            String xml = facade.encodePullRequest(hopReject(), AsHttpWireFormat.XML);
            assertThat(xml)
                    .contains("hlrResult=\"reject\"")
                    .contains("string=\"hlr reject\"")
                    .contains("redirectUssd=\"*875#\"");

            String json = facade.encodePullRequest(hopReject(), AsHttpWireFormat.JSON);
            AsRequest back = AsWireCodec.decodeRequest(
                    json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(back.ussdString()).isEqualTo("hlr reject");
            assertThat(back.hopUssd()).isEqualTo("*875#");
        }

        @Test
        void continueAfterEmptyHopMeowMenu() {
            String xml = """
                    <dialog mapMessagesSize="1" localId="e37caa26-9d16-4239-a2ff-deff0687da8d">
                      <unstructuredSSRequest_Request dataCodingScheme="15"
                          string="meow meow meow meow"/>
                    </dialog>
                    """;
            AsResponse xmlMenu = facade.decodePullResponse(xml, AsHttpWireFormat.XML, M2M_CORR);
            assertThat(xmlMenu.action()).isEqualTo(AsAction.CONTINUE);
            assertThat(xmlMenu.text()).isEqualTo("meow meow meow meow");

            String json = """
                    {
                      "correlationId": "e37caa26-9d16-4239-a2ff-deff0687da8d",
                      "requestId": "e37caa26-9d16-4239-a2ff-deff0687da8d",
                      "generation": 1,
                      "text": "meow meow meow meow",
                      "action": "CONTINUE",
                      "async": false,
                      "alphabet": "AUTO"
                    }
                    """;
            AsResponse jsonMenu = facade.decodePullResponse(json, AsHttpWireFormat.JSON, M2M_CORR);
            assertThat(jsonMenu.action()).isEqualTo(AsAction.CONTINUE);
            assertThat(jsonMenu.text()).isEqualTo("meow meow meow meow");
        }
    }

    @Nested
    @DisplayName("§4d Multi-menu successive round-trips")
    class MultiMenu {
        @Test
        void turn1XmlAndJsonFirstMenu() {
            String xml = """
                    <dialog mapMessagesSize="1"
                            localId="e37caa26-9d16-4239-a2ff-deff0687da8d"
                            sessionId="4203367b-c862-4307-81a7-3fbaa50b2afd"
                            virtualBridgeId="e37caa26-9d16-4239-a2ff-deff0687da8d">
                      <unstructuredSSRequest_Request dataCodingScheme="15"
                          string="1. Balance&#10;2. Data&#10;3. Help&#10;0. Exit"/>
                    </dialog>
                    """;
            AsResponse x = facade.decodePullResponse(xml, AsHttpWireFormat.XML, M2M_CORR);
            assertThat(x.action()).isEqualTo(AsAction.CONTINUE);
            assertThat(x.text()).isEqualTo("1. Balance\n2. Data\n3. Help\n0. Exit");
            assertThat(x.sessionId()).isEqualTo(M2M_VS);

            String json = """
                    {
                      "correlationId": "e37caa26-9d16-4239-a2ff-deff0687da8d",
                      "requestId": "e37caa26-9d16-4239-a2ff-deff0687da8d",
                      "generation": 1,
                      "text": "1. Balance\\n2. Data\\n3. Help\\n0. Exit",
                      "action": "CONTINUE",
                      "async": false,
                      "alphabet": "AUTO",
                      "sessionId": "4203367b-c862-4307-81a7-3fbaa50b2afd",
                      "virtualBridgeId": "e37caa26-9d16-4239-a2ff-deff0687da8d"
                    }
                    """;
            AsResponse j = facade.decodePullResponse(json, AsHttpWireFormat.JSON, M2M_CORR);
            assertThat(j.action()).isEqualTo(AsAction.CONTINUE);
            assertThat(j.text()).isEqualTo(x.text());
            assertThat(j.resolvePushBackId()).isEqualTo(M2M_CORR);
        }

        @Test
        void turn2BalanceSubmenuSameCorrelation() {
            String xml = """
                    <dialog mapMessagesSize="1" localId="e37caa26-9d16-4239-a2ff-deff0687da8d">
                      <unstructuredSSRequest_Request dataCodingScheme="15"
                          string="Balance menu&#10;1. Main&#10;2. Bonus&#10;0. Back"/>
                    </dialog>
                    """;
            AsResponse x = facade.decodePullResponse(xml, AsHttpWireFormat.XML, M2M_CORR);
            assertThat(x.correlationId()).isEqualTo(M2M_CORR);
            assertThat(x.text()).isEqualTo("Balance menu\n1. Main\n2. Bonus\n0. Back");

            AsResponse j = facade.decodePullResponse("""
                    {
                      "correlationId": "e37caa26-9d16-4239-a2ff-deff0687da8d",
                      "text": "Balance menu\\n1. Main\\n2. Bonus\\n0. Back",
                      "action": "CONTINUE",
                      "async": false,
                      "alphabet": "AUTO"
                    }
                    """, AsHttpWireFormat.JSON, M2M_CORR);
            assertThat(j.action()).isEqualTo(AsAction.CONTINUE);
            assertThat(j.text()).isEqualTo(x.text());
        }

        @Test
        void turn3FinalThankYouIsEnd() {
            AsResponse x = facade.decodePullResponse("""
                    <dialog mapMessagesSize="1" localId="e37caa26-9d16-4239-a2ff-deff0687da8d">
                      <processUnstructuredSSRequest_Response dataCodingScheme="15"
                          string="Thank you."/>
                    </dialog>
                    """, AsHttpWireFormat.XML, M2M_CORR);
            assertThat(x.action()).isEqualTo(AsAction.END);
            assertThat(x.text()).isEqualTo("Thank you.");

            AsResponse j = facade.decodePullResponse("""
                    {
                      "correlationId": "e37caa26-9d16-4239-a2ff-deff0687da8d",
                      "text": "Thank you.",
                      "action": "END",
                      "async": false,
                      "alphabet": "AUTO"
                    }
                    """, AsHttpWireFormat.JSON, M2M_CORR);
            assertThat(j.action()).isEqualTo(AsAction.END);
            assertThat(j.text()).isEqualTo("Thank you.");
        }

        @Test
        void mapMessagesSizeGt1AppliesFirstRequestOnly() {
            String xml = """
                    <dialog mapMessagesSize="2" localId="e37caa26-9d16-4239-a2ff-deff0687da8d">
                      <unstructuredSSRequest_Request dataCodingScheme="15" string="Menu 1&#10;1 Next"/>
                      <unstructuredSSRequest_Request dataCodingScheme="15" string="ignored-second"/>
                    </dialog>
                    """;
            AsResponse r = facade.decodePullResponse(xml, AsHttpWireFormat.XML, M2M_CORR);
            assertThat(r.action()).isEqualTo(AsAction.CONTINUE);
            assertThat(r.text()).isEqualTo("Menu 1\n1 Next");
        }
    }

    @Nested
    @DisplayName("Dual-wire parity via AsWireFacade")
    class DualWireParity {
        @Test
        void sameContinueSemanticsOnXmlAndJson() {
            String text = "1. Balance\n2. Topup\n0. Exit";
            AsResponse xml = facade.decodePullResponse("""
                    <dialog localId="corr-mo-1">
                      <unstructuredSSRequest_Request dataCodingScheme="15"
                          string="1. Balance&#10;2. Topup&#10;0. Exit"/>
                    </dialog>
                    """, AsHttpWireFormat.XML, MO_CORR);
            AsResponse json = facade.decodePullResponse("""
                    {"correlationId":"corr-mo-1","text":"1. Balance\\n2. Topup\\n0. Exit",
                     "action":"CONTINUE","async":false}
                    """, AsHttpWireFormat.JSON, MO_CORR);
            assertThat(xml.action()).isEqualTo(json.action()).isEqualTo(AsAction.CONTINUE);
            assertThat(xml.text()).isEqualTo(json.text()).isEqualTo(text);
            assertThat(xml.correlationId()).isEqualTo(json.correlationId()).isEqualTo(MO_CORR);
        }

        @Test
        void sameEndSemanticsOnXmlAndJson() {
            AsResponse xml = facade.decodePullResponse("""
                    <dialog localId="corr-mo-1">
                      <processUnstructuredSSRequest_Response dataCodingScheme="15"
                          string="Thank you."/>
                    </dialog>
                    """, AsHttpWireFormat.XML, MO_CORR);
            AsResponse json = facade.decodePullResponse("""
                    {"correlationId":"corr-mo-1","text":"Thank you.","action":"END","async":false}
                    """, AsHttpWireFormat.JSON, MO_CORR);
            assertThat(xml.action()).isEqualTo(json.action()).isEqualTo(AsAction.END);
            assertThat(xml.text()).isEqualTo(json.text()).isEqualTo("Thank you.");
        }

        @Test
        void continueMustNotDecodeAsEnd_andEndMustNotDecodeAsContinue() {
            AsResponse menuXml = facade.decodePullResponse("""
                    <dialog localId="corr-mo-1">
                      <unstructuredSSRequest_Request dataCodingScheme="15" string="Menu"/>
                    </dialog>
                    """, AsHttpWireFormat.XML, MO_CORR);
            AsResponse endXml = facade.decodePullResponse("""
                    <dialog localId="corr-mo-1">
                      <processUnstructuredSSRequest_Response dataCodingScheme="15" string="Done"/>
                    </dialog>
                    """, AsHttpWireFormat.XML, MO_CORR);
            assertThat(menuXml.action()).isEqualTo(AsAction.CONTINUE);
            assertThat(endXml.action()).isEqualTo(AsAction.END);
            assertThat(menuXml.action()).isNotEqualTo(endXml.action());

            AsResponse menuJson = facade.decodePullResponse(
                    "{\"correlationId\":\"corr-mo-1\",\"text\":\"Menu\",\"action\":\"CONTINUE\"}",
                    AsHttpWireFormat.JSON, MO_CORR);
            AsResponse endJson = facade.decodePullResponse(
                    "{\"correlationId\":\"corr-mo-1\",\"text\":\"Done\",\"action\":\"END\"}",
                    AsHttpWireFormat.JSON, MO_CORR);
            assertThat(menuJson.action()).isEqualTo(AsAction.CONTINUE);
            assertThat(endJson.action()).isEqualTo(AsAction.END);
        }
    }

    @Nested
    @DisplayName("Begin → Continue multimenu → End wire flow")
    class BeginContinueEndFlow {
        @Test
        void beginPullThenContinueThenDigitPullThenEnd_xmlAndJsonParity() {
            // BEGIN (gen 0): processUnstructuredSSRequest_Request
            AsRequest begin = new AsRequest(MO_VS, MO_CORR, MO_CORR, 0, "251911000001", "*100#",
                    "*100#", 0, MO_CORR, 7000L, "BRIDGE").withOriginated("*100#", "SHORT");
            String beginXml = facade.encodePullRequest(begin, AsHttpWireFormat.XML);
            String beginJson = facade.encodePullRequest(begin, AsHttpWireFormat.JSON);
            assertThat(beginXml)
                    .contains("processUnstructuredSSRequest_Request")
                    .doesNotContain("unstructuredSSRequest_Request");
            AsRequest beginBack = AsWireCodec.decodeRequest(
                    beginJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(beginBack.generation()).isZero();
            assertThat(beginBack.ussdString()).isEqualTo("*100#");
            assertThat(beginBack.adaptiveTimeoutMs()).isEqualTo(7000L);

            // AS CONTINUE menu
            AsResponse contXml = facade.decodePullResponse("""
                    <dialog mapMessagesSize="1" localId="corr-mo-1">
                      <unstructuredSSRequest_Request dataCodingScheme="15"
                          string="1. Balance&#10;2. Topup&#10;0. Exit"/>
                    </dialog>
                    """, AsHttpWireFormat.XML, MO_CORR);
            AsResponse contJson = facade.decodePullResponse("""
                    {"correlationId":"corr-mo-1","generation":1,"text":"1. Balance\\n2. Topup\\n0. Exit",
                     "action":"CONTINUE","async":false,"alphabet":"AUTO"}
                    """, AsHttpWireFormat.JSON, MO_CORR);
            assertThat(contXml.action()).isEqualTo(contJson.action()).isEqualTo(AsAction.CONTINUE);
            assertThat(contXml.text()).isEqualTo(contJson.text());

            // Digit continue pull (generation > 0 → unstructuredSSRequest_Request)
            AsRequest digit = new AsRequest(MO_VS, MO_CORR, MO_CORR, 1, "251911000001", "*100#",
                    "1", 0, MO_CORR, 7000L, "BRIDGE").withOriginated("*100#", "SHORT");
            String digitXml = facade.encodePullRequest(digit, AsHttpWireFormat.XML);
            String digitJson = facade.encodePullRequest(digit, AsHttpWireFormat.JSON);
            assertThat(digitXml)
                    .contains("unstructuredSSRequest_Request")
                    .contains("string=\"1\"")
                    .doesNotContain("processUnstructuredSSRequest_Request");
            AsRequest digitBack = AsWireCodec.decodeRequest(
                    digitJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(digitBack.generation()).isEqualTo(1);
            assertThat(digitBack.ussdString()).isEqualTo("1");

            // Final END
            AsResponse endXml = facade.decodePullResponse("""
                    <dialog mapMessagesSize="1" localId="corr-mo-1">
                      <processUnstructuredSSRequest_Response dataCodingScheme="15"
                          string="Thank you."/>
                    </dialog>
                    """, AsHttpWireFormat.XML, MO_CORR);
            AsResponse endJson = facade.decodePullResponse("""
                    {"correlationId":"corr-mo-1","text":"Thank you.","action":"END","async":false}
                    """, AsHttpWireFormat.JSON, MO_CORR);
            assertThat(endXml.action()).isEqualTo(endJson.action()).isEqualTo(AsAction.END);
            assertThat(endXml.text()).isEqualTo(endJson.text()).isEqualTo("Thank you.");
        }

        @Test
        void wrongFinalElementStayContinue_mustNotSilentlyBecomeEnd() {
            // Integrator mistake: final text in Request / CONTINUE — stays CONTINUE (bug surface).
            AsResponse wrong = facade.decodePullResponse("""
                    <dialog localId="corr-mo-1">
                      <unstructuredSSRequest_Request dataCodingScheme="15"
                          string="Thank you."/>
                    </dialog>
                    """, AsHttpWireFormat.XML, MO_CORR);
            assertThat(wrong.action()).isEqualTo(AsAction.CONTINUE);
            assertThat(wrong.action()).isNotEqualTo(AsAction.END);
        }
    }

    @Nested
    @DisplayName("Gated notify AdaptiveTimeout wire")
    class GatedNotifyWire {
        @Test
        void xmlAndJsonGatedBodiesCarryGateBudgetJsessionAndReason() {
            var meta = et.restlink.ussdgw.bridge.GatedSessionMeta.niPark(
                    MO_CORR, "js-gate-1", 4200L, 2800L, 1, "251911000001", "*100#", MO_VS);

            String xml = facade.encodeNiGatedAbort(meta, AsHttpWireFormat.XML);
            assertThat(xml)
                    .contains("localId=\"" + MO_CORR + "\"")
                    .contains("virtualBridgeId=\"" + MO_CORR + "\"")
                    .contains("adaptiveTimeoutMs=\"4200\"")
                    .contains("observedEwmaMs=\"2800\"")
                    .contains("jsessionId=\"js-gate-1\"")
                    .contains("gateReason=\"GATE_EXPIRED\"");

            String json = facade.encodeNiGatedAbort(meta, AsHttpWireFormat.JSON);
            assertThat(json)
                    .contains("\"correlationId\":\"" + MO_CORR + "\"")
                    .contains("\"jsessionId\":\"js-gate-1\"")
                    .contains("\"adaptiveTimeoutMs\":4200")
                    .contains("\"observedEwmaMs\":2800")
                    .contains("\"gateReason\":\"GATE_EXPIRED\"")
                    .contains("\"action\":\"ABORT\"");

            // Late callback must resolve via localId / correlationId after gate
            AsResponse lateXml = facade.decodeCallback("""
                    <dialog localId="corr-mo-1" virtualBridgeId="corr-mo-1">
                      <processUnstructuredSSRequest_Response dataCodingScheme="15"
                          string="Late OK"/>
                    </dialog>
                    """, AsHttpWireFormat.XML);
            AsResponse lateJson = facade.decodeCallback("""
                    {"correlationId":"corr-mo-1","virtualBridgeId":"corr-mo-1",
                     "text":"Late OK","action":"END","async":false}
                    """, AsHttpWireFormat.JSON);
            assertThat(lateXml.resolvePushBackId()).isEqualTo(MO_CORR);
            assertThat(lateJson.resolvePushBackId()).isEqualTo(MO_CORR);
            assertThat(lateXml.text()).isEqualTo(lateJson.text()).isEqualTo("Late OK");
        }

        @Test
        void hopNonePullMustNotEmbedHlrNoneLiteral_xmlJsonParity() {
            AsRequest none = new AsRequest(M2M_VS, M2M_CORR, M2M_CORR, 0, "251911230398", "*804#",
                    "", 0, M2M_CORR, 25000L, "BRIDGE")
                    .withOriginated("*804#", "SHORT")
                    .withMap2MapCodes("*875#", "*8775#");
            String xml = facade.encodePullRequest(none, AsHttpWireFormat.XML);
            String json = facade.encodePullRequest(none, AsHttpWireFormat.JSON);
            assertThat(xml).contains("hlrResult=\"none\"").doesNotContain("hlr none");
            AsRequest back = AsWireCodec.decodeRequest(
                    json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(back.ussdString()).isEmpty();
            assertThat(back.ussdString()).isNotEqualTo("hlr none");
            assertThat(back.adaptiveTimeoutMs()).isEqualTo(25000L);
            assertThat(back.redirectUssd()).isEqualTo("*875#");
        }
    }
}
