package et.restlink.ussdgw.codec;

/**
 * GSM 7-bit default alphabet + default extension table (3GPP TS 23.038 §6.2.1).
 * Extension characters are encoded as ESC ({@code 0x1B}) + secondary septet ("shifted").
 */
public final class Gsm7Alphabet {
    public static final byte ESCAPE = 0x1B;

    /** Basic set — index == septet value. */
    public static final String BASIC =
            "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
                    + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

    /**
     * Default extension table characters (after ESC). Order is not positional —
     * use {@link #extensionSeptet(char)}.
     */
    private static final char[] EXT_CHARS = {
            '|', '^', '€', '{', '}', '[', '~', ']', '\\', '\f'
    };
    private static final byte[] EXT_SEPTETS = {
            0x40, 0x14, 0x65, 0x28, 0x29, 0x3C, 0x3D, 0x3E, 0x2F, 0x0A
    };

    private Gsm7Alphabet() { }

    public static boolean inBasic(char c) {
        return BASIC.indexOf(c) >= 0;
    }

    public static boolean inExtension(char c) {
        for (char e : EXT_CHARS) {
            if (e == c) return true;
        }
        return false;
    }

    /** True if every code point is in basic or default extension table. */
    public static boolean canEncode(String text) {
        if (text == null) return true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!inBasic(c) && !inExtension(c)) return false;
        }
        return true;
    }

    /** Septet count including ESC pairs for extension chars. */
    public static int septetLength(String text) {
        if (text == null || text.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inBasic(c)) n += 1;
            else if (inExtension(c)) n += 2;
            else return -1;
        }
        return n;
    }

    public static byte basicSeptet(char c) {
        int idx = BASIC.indexOf(c);
        if (idx < 0) throw new IllegalArgumentException("not in GSM basic: U+" + Integer.toHexString(c));
        return (byte) idx;
    }

    public static byte extensionSeptet(char c) {
        for (int i = 0; i < EXT_CHARS.length; i++) {
            if (EXT_CHARS[i] == c) return EXT_SEPTETS[i];
        }
        throw new IllegalArgumentException("not in GSM extension: U+" + Integer.toHexString(c));
    }

    /** Expand text to septet stream (ESC + ext for extension chars). */
    public static byte[] toSeptets(String text) {
        int len = septetLength(text);
        if (len < 0) throw new IllegalArgumentException("non-GSM character in text");
        byte[] out = new byte[len];
        int p = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inBasic(c)) {
                out[p++] = basicSeptet(c);
            } else {
                out[p++] = ESCAPE;
                out[p++] = extensionSeptet(c);
            }
        }
        return out;
    }

    public static String fromSeptets(byte[] septets) {
        if (septets == null || septets.length == 0) return "";
        StringBuilder sb = new StringBuilder(septets.length);
        for (int i = 0; i < septets.length; i++) {
            int v = septets[i] & 0x7F;
            if (v == (ESCAPE & 0x7F)) {
                if (i + 1 >= septets.length) break;
                int ext = septets[++i] & 0x7F;
                char decoded = 0;
                for (int j = 0; j < EXT_SEPTETS.length; j++) {
                    if ((EXT_SEPTETS[j] & 0x7F) == ext) {
                        decoded = EXT_CHARS[j];
                        break;
                    }
                }
                if (decoded != 0) sb.append(decoded);
                // else skip unknown escape
            } else if (v < BASIC.length()) {
                sb.append(BASIC.charAt(v));
            }
        }
        return sb.toString();
    }
}
