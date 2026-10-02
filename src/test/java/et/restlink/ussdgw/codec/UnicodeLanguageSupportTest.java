package et.restlink.ussdgw.codec;

import et.restlink.ussdgw.api.UssdAlphabet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unicode support for multi-language USSD: Arabic, Hindi (Devanagari), Thai, Chinese,
 * Japanese, Korean, and Ethiopic (Amharic). All non-GSM-7 scripts must resolve to UCS-2
 * (CBS DCS 0x48) in AUTO mode, and encode/decode correctly via UTF-16BE.
 */
class UnicodeLanguageSupportTest {

    @Test
    void arabicAutoResolvesToUcs2() {
        // Arabic script (U+0600-U+06FF) is not GSM-7 → UCS-2
        String arabic = "مرحبا"; // "marhaban" (hello)
        assertThat(UssdEncodingPolicy.resolve(arabic, UssdAlphabet.AUTO).alphabet())
                .isEqualTo(UssdAlphabet.UNICODE);
        assertThat(UssdEncodingPolicy.resolve(arabic, UssdAlphabet.AUTO).cbsDcs())
                .isEqualTo(SmsTextCodec.CBS_UCS2);
    }

    @Test
    void hindiAutoResolvesToUcs2() {
        // Devanagari script (U+0900-U+097F) is not GSM-7 → UCS-2
        String hindi = "नमस्ते"; // "namaste" (hello)
        assertThat(UssdEncodingPolicy.resolve(hindi, UssdAlphabet.AUTO).alphabet())
                .isEqualTo(UssdAlphabet.UNICODE);
        assertThat(UssdEncodingPolicy.resolve(hindi, UssdAlphabet.AUTO).cbsDcs())
                .isEqualTo(SmsTextCodec.CBS_UCS2);
    }

    @Test
    void thaiAutoResolvesToUcs2() {
        // Thai script (U+0E00-U+0E7F) is not GSM-7 → UCS-2
        String thai = "สวัสดี"; // "sawasdee" (hello)
        assertThat(UssdEncodingPolicy.resolve(thai, UssdAlphabet.AUTO).alphabet())
                .isEqualTo(UssdAlphabet.UNICODE);
        assertThat(UssdEncodingPolicy.resolve(thai, UssdAlphabet.AUTO).cbsDcs())
                .isEqualTo(SmsTextCodec.CBS_UCS2);
    }

    @Test
    void chineseAutoResolvesToUcs2() {
        // CJK Unified Ideographs (U+4E00-U+9FFF) are not GSM-7 → UCS-2
        String chinese = "你好"; // "nǐ hǎo" (hello)
        assertThat(UssdEncodingPolicy.resolve(chinese, UssdAlphabet.AUTO).alphabet())
                .isEqualTo(UssdAlphabet.UNICODE);
        assertThat(UssdEncodingPolicy.resolve(chinese, UssdAlphabet.AUTO).cbsDcs())
                .isEqualTo(SmsTextCodec.CBS_UCS2);
    }

    @Test
    void japaneseAutoResolvesToUcs2() {
        // Hiragana (U+3040-U+309F) and Katakana (U+30A0-U+30FF) are not GSM-7 → UCS-2
        String japanese = "こんにちは"; // "konnichiwa" (hello)
        assertThat(UssdEncodingPolicy.resolve(japanese, UssdAlphabet.AUTO).alphabet())
                .isEqualTo(UssdAlphabet.UNICODE);
        assertThat(UssdEncodingPolicy.resolve(japanese, UssdAlphabet.AUTO).cbsDcs())
                .isEqualTo(SmsTextCodec.CBS_UCS2);
    }

    @Test
    void koreanAutoResolvesToUcs2() {
        // Hangul Syllables (U+AC00-U+D7AF) are not GSM-7 → UCS-2
        String korean = "안녕하세요"; // "annyeonghaseyo" (hello)
        assertThat(UssdEncodingPolicy.resolve(korean, UssdAlphabet.AUTO).alphabet())
                .isEqualTo(UssdAlphabet.UNICODE);
        assertThat(UssdEncodingPolicy.resolve(korean, UssdAlphabet.AUTO).cbsDcs())
                .isEqualTo(SmsTextCodec.CBS_UCS2);
    }

    @Test
    void amharicAutoResolvesToUcs2() {
        // Ethiopic script (U+1200-U+137F) is not GSM-7 → UCS-2
        String amharic = "ሰላም"; // "selam" (hello)
        assertThat(UssdEncodingPolicy.resolve(amharic, UssdAlphabet.AUTO).alphabet())
                .isEqualTo(UssdAlphabet.UNICODE);
        assertThat(UssdEncodingPolicy.resolve(amharic, UssdAlphabet.AUTO).cbsDcs())
                .isEqualTo(SmsTextCodec.CBS_UCS2);
    }

    @Test
    void ucs2EncodeDecodeArabicRoundTrip() {
        String arabic = "مرحبا";
        var encoded = SmsTextCodec.encode(arabic, UssdAlphabet.AUTO, 1);
        assertThat(encoded.dataCoding()).isEqualTo(SmsTextCodec.DCS_UCS2);
        assertThat(encoded.parts()).hasSize(1);
        // Decode via UTF-16BE (UCS-2)
        String decoded = new String(encoded.parts().get(0).tpUd(), java.nio.charset.StandardCharsets.UTF_16BE);
        assertThat(decoded).isEqualTo(arabic);
    }

    @Test
    void ucs2EncodeDecodeHindiRoundTrip() {
        String hindi = "नमस्ते";
        var encoded = SmsTextCodec.encode(hindi, UssdAlphabet.AUTO, 1);
        assertThat(encoded.dataCoding()).isEqualTo(SmsTextCodec.DCS_UCS2);
        String decoded = new String(encoded.parts().get(0).tpUd(), java.nio.charset.StandardCharsets.UTF_16BE);
        assertThat(decoded).isEqualTo(hindi);
    }

    @Test
    void ucs2EncodeDecodeAmharicRoundTrip() {
        String amharic = "ሰላም";
        var encoded = SmsTextCodec.encode(amharic, UssdAlphabet.AUTO, 1);
        assertThat(encoded.dataCoding()).isEqualTo(SmsTextCodec.DCS_UCS2);
        String decoded = new String(encoded.parts().get(0).tpUd(), java.nio.charset.StandardCharsets.UTF_16BE);
        assertThat(decoded).isEqualTo(amharic);
    }

    @Test
    void mixedLanguageAutoResolvesToUcs2() {
        // Mixed English + Arabic + Amharic → UCS-2 (not all GSM-7)
        String mixed = "Hello مرحبا ሰላም";
        assertThat(UssdEncodingPolicy.resolve(mixed, UssdAlphabet.AUTO).alphabet())
                .isEqualTo(UssdAlphabet.UNICODE);
        assertThat(UssdEncodingPolicy.resolve(mixed, UssdAlphabet.AUTO).cbsDcs())
                .isEqualTo(SmsTextCodec.CBS_UCS2);
    }

    @Test
    void asciiOnlyStaysGsm7() {
        // Pure ASCII (English) → GSM-7 (most efficient)
        String english = "Hello World";
        assertThat(UssdEncodingPolicy.resolve(english, UssdAlphabet.AUTO).alphabet())
                .isEqualTo(UssdAlphabet.UCS7);
        assertThat(UssdEncodingPolicy.resolve(english, UssdAlphabet.AUTO).cbsDcs())
                .isEqualTo(SmsTextCodec.CBS_GSM7);
    }
}
