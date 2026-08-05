package et.restlink.ussdgw.codec;

import et.restlink.ussdgw.api.UssdAlphabet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UssdEncodingPolicyTest {

    @Test
    void asExplicitAlphabetWinsOverContent() {
        // AS asked ucs7 even for ASCII — honor AS
        assertThat(UssdEncodingPolicy.resolve("OK", UssdAlphabet.UCS7).cbsDcs())
                .isEqualTo(SmsTextCodec.CBS_GSM7);
        assertThat(UssdEncodingPolicy.resolve("OK", UssdAlphabet.UCS8).cbsDcs())
                .isEqualTo(SmsTextCodec.CBS_GSM8);
        assertThat(UssdEncodingPolicy.resolve("OK", UssdAlphabet.UNICODE).cbsDcs())
                .isEqualTo(SmsTextCodec.CBS_UCS2);
    }

    @Test
    void smppDcsMapsToAlphabet() {
        assertThat(SmsTextCodec.alphabetFromSmppDcs((byte) 0x00)).isEqualTo(UssdAlphabet.UCS7);
        assertThat(SmsTextCodec.alphabetFromSmppDcs((byte) 0x03)).isEqualTo(UssdAlphabet.UCS8);
        assertThat(SmsTextCodec.alphabetFromSmppDcs((byte) 0x08)).isEqualTo(UssdAlphabet.UNICODE);
    }

    @Test
    void extensionCharsStillGsm7() {
        assertThat(Gsm7Alphabet.canEncode("price €1 [ok]")).isTrue();
        assertThat(UssdEncodingPolicy.resolve("price €1", UssdAlphabet.UCS7).cbsDcs())
                .isEqualTo(SmsTextCodec.CBS_GSM7);
    }

    @Test
    void autoAmharicUsesUnicode() {
        assertThat(UssdEncodingPolicy.resolve("ሰላም", UssdAlphabet.AUTO).alphabet())
                .isEqualTo(UssdAlphabet.UNICODE);
    }
}
