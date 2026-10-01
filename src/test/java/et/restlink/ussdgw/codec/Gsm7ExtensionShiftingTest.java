package et.restlink.ussdgw.codec;

import et.restlink.ussdgw.api.UssdAlphabet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GSM-7 extension table shifting (ESC + secondary septet) per 3GPP TS 23.038 §6.2.1.1.
 * Extension chars: { } [ ] \ | ^ ~ € (form feed) — each encoded as ESC (0x1B) + extension septet.
 */
class Gsm7ExtensionShiftingTest {

    @Test
    void extensionCharsAreEncodable() {
        // All extension table chars must be encodable
        String extensionChars = "{}[]\\|^~€";
        assertThat(Gsm7Alphabet.canEncode(extensionChars)).isTrue();
    }

    @Test
    void extensionCharsCountAsTwoSeptets() {
        // Each extension char = ESC + secondary septet = 2 septets
        assertThat(Gsm7Alphabet.septetLength("{")).isEqualTo(2);
        assertThat(Gsm7Alphabet.septetLength("}")).isEqualTo(2);
        assertThat(Gsm7Alphabet.septetLength("[")).isEqualTo(2);
        assertThat(Gsm7Alphabet.septetLength("]")).isEqualTo(2);
        assertThat(Gsm7Alphabet.septetLength("\\"))).isEqualTo(2);
        assertThat(Gsm7Alphabet.septetLength("|")).isEqualTo(2);
        assertThat(Gsm7Alphabet.septetLength("^")).isEqualTo(2);
        assertThat(Gsm7Alphabet.septetLength("~")).isEqualTo(2);
        assertThat(Gsm7Alphabet.septetLength("€")).isEqualTo(2);
    }

    @Test
    void mixedBasicAndExtension() {
        // "price €1" = 7 basic + 2 extension (€) = 9 septets
        String text = "price €1";
        assertThat(Gsm7Alphabet.canEncode(text)).isTrue();
        assertThat(Gsm7Alphabet.septetLength(text)).isEqualTo(9);
    }

    @Test
    void extensionCharsEncodeToEscPair() {
        // "{" → ESC (0x1B) + 0x28
        byte[] septets = Gsm7Alphabet.toSeptets("{");
        assertThat(septets).hasSize(2);
        assertThat(septets[0]).isEqualTo(Gsm7Alphabet.ESCAPE);
        assertThat(septets[1]).isEqualTo((byte) 0x28);
    }

    @Test
    void extensionCharsDecodeFromEscPair() {
        // ESC + 0x28 → "{"
        byte[] septets = {Gsm7Alphabet.ESCAPE, 0x28};
        String decoded = Gsm7Alphabet.fromSeptets(septets);
        assertThat(decoded).isEqualTo("{");
    }

    @Test
    void euroSignRoundTrip() {
        // "€" → ESC + 0x65 → "€"
        byte[] septets = Gsm7Alphabet.toSeptets("€");
        String decoded = Gsm7Alphabet.fromSeptets(septets);
        assertThat(decoded).isEqualTo("€");
    }

    @Test
    void mixedTextWithExtensionRoundTrip() {
        // "Hello {world}" = basic + extension chars
        String text = "Hello {world}";
        assertThat(Gsm7Alphabet.canEncode(text)).isTrue();
        byte[] septets = Gsm7Alphabet.toSeptets(text);
        String decoded = Gsm7Alphabet.fromSeptets(septets);
        assertThat(decoded).isEqualTo(text);
    }

    @Test
    void extensionCharsResolveToGsm7() {
        // Text with extension chars should resolve to GSM-7 (not UCS-2)
        String text = "price €100";
        assertThat(UssdEncodingPolicy.resolve(text, UssdAlphabet.AUTO).alphabet())
                .isEqualTo(UssdAlphabet.UCS7);
        assertThat(UssdEncodingPolicy.resolve(text, UssdAlphabet.AUTO).cbsDcs())
                .isEqualTo(SmsTextCodec.CBS_GSM7);
    }
}
