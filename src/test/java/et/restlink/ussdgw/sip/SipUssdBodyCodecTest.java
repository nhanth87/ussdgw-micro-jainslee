package et.restlink.ussdgw.sip;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SipUssdBodyCodecTest {

    @Test
    void sdpExtractsUssdStringAsNiPush() {
        String sdp = """
                v=0
                o=- 0 0 IN IP4 127.0.0.1
                s=-
                a=msisdn:251911000111
                a=ussd-string:Hello from AS
                """;
        var d = SipUssdBodyCodec.decode("application/sdp", sdp, false, "BODY");
        assertThat(d.kind()).isEqualTo(SipUssdBodyCodec.InboundKind.NI_PUSH);
        assertThat(d.explicitNi()).isTrue();
        assertThat(d.ussdText()).isEqualTo("Hello from AS");
        assertThat(d.msisdnHint()).isEqualTo("251911000111");
    }

    @Test
    void dialBodyIsMoPull() {
        var d = SipUssdBodyCodec.decode("text/plain", "*123#", false, "BODY");
        assertThat(d.kind()).isEqualTo(SipUssdBodyCodec.InboundKind.MO_PULL);
        assertThat(d.ussdText()).isEqualTo("*123#");
        assertThat(d.explicitNi()).isFalse();
    }

    @Test
    void freeTextIsSoftNiPush() {
        var d = SipUssdBodyCodec.decode("text/plain", "Please wait", false, "BODY");
        assertThat(d.kind()).isEqualTo(SipUssdBodyCodec.InboundKind.NI_PUSH);
        assertThat(d.explicitNi()).isFalse();
    }

    @Test
    void encodePullPlainHasFields() {
        String p = SipUssdBodyCodec.encodePullPlain("c1", "2519", "*123#", "*123#");
        assertThat(p).contains("correlationId=c1").contains("msisdn=2519").contains("shortCode=*123#");
    }

    @Test
    void normalizeMsisdnAcceptsDigitsInRange() {
        assertThat(SipUssdBodyCodec.normalizeMsisdn("251911000111")).contains("251911000111");
        assertThat(SipUssdBodyCodec.normalizeMsisdn("+251-911-000-111")).contains("251911000111");
    }

    @Test
    void normalizeMsisdnRejectsShortLongEmpty() {
        assertThat(SipUssdBodyCodec.normalizeMsisdn("1234567")).isEmpty();
        assertThat(SipUssdBodyCodec.normalizeMsisdn("1234567890123456")).isEmpty();
        assertThat(SipUssdBodyCodec.normalizeMsisdn("")).isEmpty();
        assertThat(SipUssdBodyCodec.normalizeMsisdn("abc")).isEmpty();
        assertThat(SipUssdBodyCodec.normalizeMsisdn(null)).isEmpty();
    }
}
