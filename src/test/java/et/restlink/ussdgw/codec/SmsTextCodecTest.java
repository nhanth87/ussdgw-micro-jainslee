package et.restlink.ussdgw.codec;

import et.restlink.ussdgw.api.UssdAlphabet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmsTextCodecTest {

    @Test
    void shortAsciiUsesGsm7() {
        var enc = SmsTextCodec.encode("Hello RestLink", 10);
        assertThat(enc.dataCoding()).isEqualTo(SmsTextCodec.DCS_GSM7);
        assertThat(enc.parts()).hasSize(1);
        assertThat(enc.parts().get(0).udhi()).isFalse();
    }

    @Test
    void unicodeUsesUcs2() {
        var enc = SmsTextCodec.encode("ሰላም", 10);
        assertThat(enc.dataCoding()).isEqualTo(SmsTextCodec.DCS_UCS2);
        assertThat(SmsTextCodec.chooseCbsDataCoding("ሰላም", UssdAlphabet.AUTO))
                .isEqualTo(SmsTextCodec.CBS_UCS2);
    }

    @Test
    void asciiChoosesGsm7Cbs() {
        assertThat(SmsTextCodec.chooseCbsDataCoding("OK", UssdAlphabet.AUTO))
                .isEqualTo(SmsTextCodec.CBS_GSM7);
        assertThat(SmsTextCodec.chooseCbsDataCoding("OK", UssdAlphabet.ASCII))
                .isEqualTo(SmsTextCodec.CBS_GSM7);
        assertThat(SmsTextCodec.isAscii("OK")).isTrue();
        assertThat(SmsTextCodec.isAscii("ሰላም")).isFalse();
    }

    @Test
    void longTextSplitsUcs2() {
        String text = "A".repeat(161);
        var enc = SmsTextCodec.encode(text, 10);
        assertThat(enc.dataCoding()).isEqualTo(SmsTextCodec.DCS_UCS2);
        assertThat(enc.parts().size()).isGreaterThanOrEqualTo(2);
        assertThat(enc.parts().get(0).udhi()).isTrue();
    }

    @Test
    void rejectsTooManyParts() {
        String text = "X".repeat(67 * 3 + 1);
        assertThatThrownBy(() -> SmsTextCodec.encode(text, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodeUcs2RoundTrip() {
        byte[] raw = "Hi".getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
        assertThat(SmsTextCodec.decode(raw, SmsTextCodec.DCS_UCS2)).isEqualTo("Hi");
    }
}
