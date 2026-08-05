package et.restlink.ussdgw.codec;

import et.restlink.ussdgw.api.UssdAlphabet;

/**
 * Resolve MAP CBS DCS from AS-supplied alphabet (HTTP/SMPP).
 *
 * <p>When the AS sends {@code ucs7} / {@code ucs8} / {@code unicode}, that choice is honored.
 * {@link UssdAlphabet#AUTO} is only for omitted coding (heuristic; Ethiopic → UCS-2).</p>
 */
public final class UssdEncodingPolicy {
    public static final int USSD_MAX_GSM7_SEPTETS = 182;
    public static final int USSD_MAX_OCTETS = 160;
    public static final int USSD_MAX_UCS2_CHARS = 80;

    private UssdEncodingPolicy() { }

    public record Decision(UssdAlphabet alphabet, int cbsDcs, String reason) { }

    public static Decision resolve(String text, UssdAlphabet hint) {
        UssdAlphabet h = hint == null ? UssdAlphabet.AUTO : hint;
        if (h == UssdAlphabet.AUTO) {
            if (containsEthiopic(text)) {
                return new Decision(UssdAlphabet.UNICODE, SmsTextCodec.CBS_UCS2,
                        "AUTO: ethiopic → UCS-2 (AS omitted alphabet)");
            }
            if (Gsm7Alphabet.canEncode(text)) {
                int septets = Gsm7Alphabet.septetLength(text);
                if (septets >= 0 && septets <= USSD_MAX_GSM7_SEPTETS) {
                    return new Decision(UssdAlphabet.UCS7, SmsTextCodec.CBS_GSM7,
                            "AUTO: GSM-7+ext (AS omitted alphabet)");
                }
                return new Decision(UssdAlphabet.UNICODE, SmsTextCodec.CBS_UCS2,
                        "AUTO: too long for GSM-7 USSD → UCS-2");
            }
            return new Decision(UssdAlphabet.UNICODE, SmsTextCodec.CBS_UCS2,
                    "AUTO: non-GSM → UCS-2");
        }
        if (h.isGsm7Family()) {
            if (!Gsm7Alphabet.canEncode(text)) {
                return new Decision(UssdAlphabet.UNICODE, SmsTextCodec.CBS_UCS2,
                        "AS asked ucs7 but text not GSM-encodable → UCS-2 fallback");
            }
            return new Decision(UssdAlphabet.UCS7, SmsTextCodec.CBS_GSM7, "AS alphabet=ucs7");
        }
        if (h == UssdAlphabet.UCS8) {
            return new Decision(UssdAlphabet.UCS8, SmsTextCodec.CBS_GSM8, "AS alphabet=ucs8");
        }
        return new Decision(UssdAlphabet.UNICODE, SmsTextCodec.CBS_UCS2, "AS alphabet=unicode");
    }

    public static boolean containsEthiopic(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isEthiopic(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    public static boolean isEthiopic(int cp) {
        return (cp >= 0x1200 && cp <= 0x137F)
                || (cp >= 0x1380 && cp <= 0x139F)
                || (cp >= 0x2D80 && cp <= 0x2DDF)
                || (cp >= 0xAB00 && cp <= 0xAB2F);
    }

    public static void assertFitsUssd(String text, UssdAlphabet alphabet) {
        Decision d = resolve(text, alphabet);
        if (d.alphabet().isUcs2Family()) {
            if (text != null && text.length() > USSD_MAX_UCS2_CHARS) {
                throw new IllegalArgumentException(
                        "USSD UCS-2 text length " + text.length() + " > " + USSD_MAX_UCS2_CHARS);
            }
        } else if (d.alphabet() == UssdAlphabet.UCS8) {
            byte[] raw = text == null ? new byte[0]
                    : text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            if (raw.length > USSD_MAX_OCTETS) {
                throw new IllegalArgumentException(
                        "USSD 8-bit length " + raw.length + " > " + USSD_MAX_OCTETS);
            }
        } else {
            int septets = Gsm7Alphabet.septetLength(text == null ? "" : text);
            if (septets < 0 || septets > USSD_MAX_GSM7_SEPTETS) {
                throw new IllegalArgumentException(
                        "USSD GSM-7 septets " + septets + " > " + USSD_MAX_GSM7_SEPTETS);
            }
        }
    }
}
