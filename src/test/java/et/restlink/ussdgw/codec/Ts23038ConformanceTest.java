package et.restlink.ussdgw.codec;

import et.restlink.ussdgw.api.UssdAlphabet;

import org.junit.jupiter.api.Test;
import org.restcomm.protocols.ss7.map.datacoding.CBSDataCodingSchemeImpl;
import org.restcomm.protocols.ss7.map.primitives.USSDStringImpl;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GSM-7 / UCS-2 / 8-bit conformance against 3GPP TS 23.038 and SMPP 3.4, with jSS7's
 * {@code USSDStringImpl} (what actually goes on the MAP wire) as an independent oracle.
 */
class Ts23038ConformanceTest {

    /** TS 23.038 §6.2.1 default alphabet, by septet value (0x1B = escape, not a character). */
    private static final String[] TABLE = {
            "@", "£", "$", "¥", "è", "é", "ù", "ì", "ò", "Ç", "\n", "Ø", "ø", "\r", "Å", "å",
            "Δ", "_", "Φ", "Γ", "Λ", "Ω", "Π", "Ψ", "Σ", "Θ", "Ξ", null, "Æ", "æ", "ß", "É",
            " ", "!", "\"", "#", "¤", "%", "&", "'", "(", ")", "*", "+", ",", "-", ".", "/",
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ":", ";", "<", "=", ">", "?",
            "¡", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O",
            "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "Ä", "Ö", "Ñ", "Ü", "§",
            "¿", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o",
            "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "ä", "ö", "ñ", "ü", "à",
    };

    @Test
    void everyBasicCharacterHasItsTs23038Septet() {
        for (int septet = 0; septet < 128; septet++) {
            String ch = TABLE[septet];
            if (ch == null) {
                continue;
            }
            assertThat(Gsm7Alphabet.basicSeptet(ch.charAt(0)))
                    .as("septet of %s", ch).isEqualTo((byte) septet);
            assertThat(Gsm7Alphabet.fromSeptets(new byte[] {(byte) septet})).isEqualTo(ch);
        }
    }

    @Test
    void escapeIsNotACharacter() {
        assertThat(Gsm7Alphabet.inBasic('\u001B')).isFalse();
        assertThat(Gsm7Alphabet.inBasic('￿')).isFalse();
        assertThat(Gsm7Alphabet.canEncode("\u001B")).isFalse();
    }

    @Test
    void extensionTableMatchesTs23038() {
        String chars = "\f^{}\\[~]|€";
        byte[] want = {0x0A, 0x14, 0x28, 0x29, 0x2F, 0x3C, 0x3D, 0x3E, 0x40, 0x65};
        for (int i = 0; i < chars.length(); i++) {
            assertThat(Gsm7Alphabet.extensionSeptet(chars.charAt(i))).isEqualTo(want[i]);
            assertThat(Gsm7Alphabet.fromSeptets(new byte[] {0x1B, want[i]}))
                    .isEqualTo(String.valueOf(chars.charAt(i)));
        }
    }

    @Test
    void undefinedExtensionFallsBackToMainTable() {
        // ESC + 0x41 is not defined → display 'A' from the main table (TS 23.038 §6.2.1.1).
        assertThat(Gsm7Alphabet.fromSeptets(new byte[] {0x1B, 0x41})).isEqualTo("A");
    }

    @Test
    void ourGsm7AgreesWithJss7OnTheMapWire() throws Exception {
        StringBuilder all = new StringBuilder();
        for (String ch : TABLE) {
            if (ch != null && !"\r".equals(ch)) {  // CR is the USSD pad septet; tested apart
                all.append(ch);
            }
        }
        all.append("{}[]\\|^~€");
        String text = all.toString();
        var dcs = new CBSDataCodingSchemeImpl(SmsTextCodec.CBS_GSM7);
        var wire = new USSDStringImpl(Gsm7Alphabet.forJss7(text), dcs, null);
        // jSS7 reads septet 0x09 back as 'ç'; every other septet must match our table exactly.
        assertThat(new USSDStringImpl(wire.getEncodedString(), dcs).getString(null))
                .isEqualTo(text.replace('Ç', 'ç'));
        // Raw jSS7 would have turned 'Ç' into a space — the reason forJss7 exists.
        var raw = new USSDStringImpl("Ç", dcs, null);
        assertThat(new USSDStringImpl(raw.getEncodedString(), dcs).getString(null)).isNotEqualTo("ç");
        assertThat(Gsm7Alphabet.basicSeptet('ç')).isEqualTo((byte) 0x09);
        // jSS7 unpacks the septets; our table must read them identically.
        assertThat(Gsm7Alphabet.septetLength(text)).isEqualTo(127 - 1 + 18);
    }

    @Test
    void ucs2AmharicRoundTripsOnTheMapWire() throws Exception {
        String amharic = "እንኳን ደህና መጡ! ቀሪ ሂሳብዎ 25.50 ብር ነው።";
        var decision = UssdEncodingPolicy.resolve(amharic, UssdAlphabet.AUTO);
        assertThat(decision.cbsDcs()).isEqualTo(SmsTextCodec.CBS_UCS2);
        var dcs = new CBSDataCodingSchemeImpl(decision.cbsDcs());
        var wire = new USSDStringImpl(amharic, dcs, null);
        assertThat(wire.getEncodedString()).hasSize(amharic.length() * 2);
        assertThat(new USSDStringImpl(wire.getEncodedString(), dcs).getString(null)).isEqualTo(amharic);
    }

    @Test
    void gsm8RoundTripsWhenACharsetIsGiven() throws Exception {
        String latin = "Café déjà vu ÿ";
        var dcs = new CBSDataCodingSchemeImpl(SmsTextCodec.CBS_GSM8);
        var wire = new USSDStringImpl(latin, dcs, StandardCharsets.ISO_8859_1);
        assertThat(new USSDStringImpl(wire.getEncodedString(), dcs)
                .getString(StandardCharsets.ISO_8859_1)).isEqualTo(latin);
    }

    @Test
    void cbsDcsGroupsDecodePerTs23038Section5() {
        assertThat(DataCodingSchemes.alphabetOfCbsDcs(0x0F)).isEqualTo(UssdAlphabet.UCS7);
        assertThat(DataCodingSchemes.alphabetOfCbsDcs(0x00)).isEqualTo(UssdAlphabet.UCS7);
        assertThat(DataCodingSchemes.alphabetOfCbsDcs(0x10)).isEqualTo(UssdAlphabet.UCS7);
        assertThat(DataCodingSchemes.alphabetOfCbsDcs(0x11)).isEqualTo(UssdAlphabet.UNICODE);
        assertThat(DataCodingSchemes.alphabetOfCbsDcs(0x20)).isEqualTo(UssdAlphabet.UCS7);
        assertThat(DataCodingSchemes.alphabetOfCbsDcs(0x40)).isEqualTo(UssdAlphabet.UCS7);
        assertThat(DataCodingSchemes.alphabetOfCbsDcs(0x44)).isEqualTo(UssdAlphabet.UCS8);
        assertThat(DataCodingSchemes.alphabetOfCbsDcs(0x48)).isEqualTo(UssdAlphabet.UNICODE);
        assertThat(DataCodingSchemes.alphabetOfCbsDcs(0x59)).isEqualTo(UssdAlphabet.UNICODE);
        assertThat(DataCodingSchemes.alphabetOfCbsDcs(0x98)).isEqualTo(UssdAlphabet.UNICODE);
        assertThat(DataCodingSchemes.alphabetOfCbsDcs(0xF0)).isEqualTo(UssdAlphabet.UCS7);
        assertThat(DataCodingSchemes.alphabetOfCbsDcs(0xF4)).isEqualTo(UssdAlphabet.UCS8);
        assertThat(DataCodingSchemes.alphabetOfCbsDcs(0xA0)).isEqualTo(UssdAlphabet.UCS7);
        // Classic-GW compat: bare 8 was read as UCS-2 (SMS DCS §4), not CBS group 0000.
        assertThat(DataCodingSchemes.alphabetOfCbsDcs(0x08)).isEqualTo(UssdAlphabet.UNICODE);
    }

    @Test
    void smppDataCodingPerSmpp34() {
        assertThat(DataCodingSchemes.smppCharset(0x00)).isEqualTo(DataCodingSchemes.SmppCharset.GSM7_UNPACKED);
        assertThat(DataCodingSchemes.smppCharset(0x01)).isEqualTo(DataCodingSchemes.SmppCharset.IA5);
        assertThat(DataCodingSchemes.smppCharset(0x03)).isEqualTo(DataCodingSchemes.SmppCharset.LATIN1);
        assertThat(DataCodingSchemes.smppCharset(0x08)).isEqualTo(DataCodingSchemes.SmppCharset.UCS2);
        assertThat(DataCodingSchemes.smppCharset(0x18)).isEqualTo(DataCodingSchemes.SmppCharset.UCS2);
        assertThat(DataCodingSchemes.smppCharset(0xE0)).isEqualTo(DataCodingSchemes.SmppCharset.UCS2);
        // 0xF8 = class 0, GSM 7-bit — the old (dcs & 0x0C) == 0x08 rule called it UCS-2.
        assertThat(DataCodingSchemes.smppCharset(0xF8)).isEqualTo(DataCodingSchemes.SmppCharset.GSM7_UNPACKED);
        assertThat(DataCodingSchemes.smppCharset(0xF4)).isEqualTo(DataCodingSchemes.SmppCharset.BINARY_8BIT);
    }

    @Test
    void smppDecodeTakesUnpackedSeptetsAndNeverGuessesPacking() {
        // "*123#" as unpacked GSM-7 septets (== ASCII for these characters).
        assertThat(SmsTextCodec.decode("*123#".getBytes(StandardCharsets.US_ASCII), (byte) 0x00))
                .isEqualTo("*123#");
        // '@' is septet 0x00 and '$' 0x02 in GSM-7.
        assertThat(SmsTextCodec.decode(new byte[] {0x00, 0x02}, (byte) 0x00)).isEqualTo("@$");
        assertThat(SmsTextCodec.decode("a@b".getBytes(StandardCharsets.US_ASCII), (byte) 0x01))
                .isEqualTo("a@b");
        assertThat(SmsTextCodec.decode("ሰላም".getBytes(StandardCharsets.UTF_16BE), (byte) 0x08))
                .isEqualTo("ሰላም");
    }

    @Test
    void smppUssdEncodeIsUnpackedSingleShortMessageWithinUssdLimits() {
        var gsm = SmsTextCodec.encodeSmppUssd("Balance: 10 ETB {ok}", UssdAlphabet.AUTO);
        assertThat(gsm.dataCoding()).isEqualTo((byte) 0x00);
        assertThat(gsm.shortMessage()).isEqualTo(Gsm7Alphabet.toSeptets("Balance: 10 ETB {ok}"));
        assertThat(SmsTextCodec.decode(gsm.shortMessage(), gsm.dataCoding())).isEqualTo("Balance: 10 ETB {ok}");

        String longAmharic = "ሀ".repeat(120);
        var ucs2 = SmsTextCodec.encodeSmppUssd(longAmharic, UssdAlphabet.UNICODE);
        assertThat(ucs2.dataCoding()).isEqualTo((byte) 0x08);
        assertThat(ucs2.shortMessage()).hasSize(160);  // 80 UCS-2 units, no UDH, no split
        assertThat(ucs2.sentText()).hasSize(80);
    }

    @Test
    void longGsmTextStaysGsm7AndIsCutAt182Septets() {
        String text = "A".repeat(200);
        var d = UssdEncodingPolicy.resolve(text, UssdAlphabet.AUTO);
        assertThat(d.alphabet()).isEqualTo(UssdAlphabet.UCS7);
        assertThat(UssdEncodingPolicy.truncateToFit(text, d.alphabet())).hasSize(182);
        // An extension character is two septets and must never be split from its ESC.
        String ext = "€".repeat(100);
        assertThat(UssdEncodingPolicy.truncateToFit(ext, UssdAlphabet.UCS7)).hasSize(91);
    }

    @Test
    void ucs8OutsideLatin1FallsBackToUcs2() {
        var d = UssdEncodingPolicy.resolve("price €5", UssdAlphabet.UCS8);
        assertThat(d.alphabet()).isEqualTo(UssdAlphabet.UNICODE);
        assertThat(UssdEncodingPolicy.resolve("Café", UssdAlphabet.UCS8).cbsDcs())
                .isEqualTo(SmsTextCodec.CBS_GSM8);
    }

    @Test
    void ucs2TruncationNeverSplitsASurrogatePair() {
        String text = "a".repeat(79) + "😀" + "b";
        String cut = UssdEncodingPolicy.truncateToFit(text, UssdAlphabet.UNICODE);
        assertThat(cut).hasSize(79);
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 1))).isFalse();
    }
}
