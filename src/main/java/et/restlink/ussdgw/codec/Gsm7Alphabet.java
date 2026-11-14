package et.restlink.ussdgw.codec;

import java.util.Arrays;

/**
 * GSM 7-bit default alphabet + default extension table (3GPP TS 23.038 §6.2.1 / §6.2.1.1).
 * Extension characters are encoded as ESC ({@code 0x1B}) + secondary septet ("shifted").
 *
 * <p>Index == septet value. Position {@code 0x1B} is the escape to the extension table, not a
 * character: it is held by {@link #ESC_PLACEHOLDER} so every later character keeps its
 * TS 23.038 position. (Without it, every septet from {@code Æ} up was off by one: 'A' encoded
 * as 0x40, i.e. '¡' on the handset.)</p>
 */
public final class Gsm7Alphabet {
    public static final byte ESCAPE = 0x1B;

    /** Occupies septet 0x1B in {@link #BASIC}; never a real character. */
    private static final char ESC_PLACEHOLDER = '￿';

    /** Basic set — index == septet value (TS 23.038 §6.2.1, 128 positions). */
    public static final String BASIC =
            "@£$¥èéùìòÇ\nØø\rÅå"                      // 0x00-0x0F
                    + "Δ_ΦΓΛΩΠΨΣΘΞ" + ESC_PLACEHOLDER + "ÆæßÉ" // 0x10-0x1F
                    + " !\"#¤%&'()*+,-./"               // 0x20-0x2F
                    + "0123456789:;<=>?"                // 0x30-0x3F
                    + "¡ABCDEFGHIJKLMNO"                // 0x40-0x4F
                    + "PQRSTUVWXYZÄÖÑÜ§"                // 0x50-0x5F
                    + "¿abcdefghijklmno"                // 0x60-0x6F
                    + "pqrstuvwxyzäöñüà";               // 0x70-0x7F

    /** Default extension table (TS 23.038 §6.2.1.1): character → septet after ESC. */
    private static final char[] EXT_CHARS = {
            '\f', '^', '{', '}', '\\', '[', '~', ']', '|', '€'
    };
    private static final byte[] EXT_SEPTETS = {
            0x0A, 0x14, 0x28, 0x29, 0x2F, 0x3C, 0x3D, 0x3E, 0x40, 0x65
    };

    /** char → basic septet, or -1. */
    private static final byte[] CHAR_TO_BASIC = new byte[0x10000];
    /** char → extension septet, or -1. */
    private static final byte[] CHAR_TO_EXT = new byte[0x10000];
    /** extension septet → char, or 0 when the position is undefined. */
    private static final char[] EXT_TO_CHAR = new char[0x80];

    static {
        if (BASIC.length() != 128) {
            throw new ExceptionInInitializerError("GSM-7 basic table must have 128 positions, has "
                    + BASIC.length());
        }
        Arrays.fill(CHAR_TO_BASIC, (byte) -1);
        Arrays.fill(CHAR_TO_EXT, (byte) -1);
        for (int i = 0; i < BASIC.length(); i++) {
            char c = BASIC.charAt(i);
            if (c != ESC_PLACEHOLDER) {
                CHAR_TO_BASIC[c] = (byte) i;
            }
        }
        // 0x09 is 'Ç' in TS 23.038 but 'ç' in the original GSM 03.38 and in jSS7's GSMCharset;
        // both are in use, neither is elsewhere in the table — accept both on encode.
        CHAR_TO_BASIC['ç'] = 0x09;
        for (int i = 0; i < EXT_CHARS.length; i++) {
            CHAR_TO_EXT[EXT_CHARS[i]] = EXT_SEPTETS[i];
            EXT_TO_CHAR[EXT_SEPTETS[i]] = EXT_CHARS[i];
        }
    }

    private Gsm7Alphabet() { }

    public static boolean inBasic(char c) {
        return CHAR_TO_BASIC[c] >= 0;
    }

    public static boolean inExtension(char c) {
        return CHAR_TO_EXT[c] >= 0;
    }

    /** True if every character is in the basic set or the default extension table. */
    public static boolean canEncode(String text) {
        if (text == null) return true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!inBasic(c) && !inExtension(c)) return false;
        }
        return true;
    }

    /** Septet count including ESC pairs for extension chars; -1 when not encodable. */
    public static int septetLength(String text) {
        if (text == null || text.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            int s = septetsFor(text.charAt(i));
            if (s < 0) return -1;
            n += s;
        }
        return n;
    }

    /** Septets for one character: 1 basic, 2 extension (ESC + septet), -1 not encodable. */
    public static int septetsFor(char c) {
        if (inBasic(c)) return 1;
        if (inExtension(c)) return 2;
        return -1;
    }

    public static byte basicSeptet(char c) {
        byte s = CHAR_TO_BASIC[c];
        if (s < 0) throw new IllegalArgumentException("not in GSM basic: U+" + Integer.toHexString(c));
        return s;
    }

    public static byte extensionSeptet(char c) {
        byte s = CHAR_TO_EXT[c];
        if (s < 0) throw new IllegalArgumentException("not in GSM extension: U+" + Integer.toHexString(c));
        return s;
    }

    /**
     * Text for jSS7's {@code GSMCharset} (the MAP wire encoder), which knows septet 0x09 only as
     * 'ç' and replaces 'Ç' with a space. Same septet on the air either way.
     */
    public static String forJss7(String text) {
        return text == null || text.indexOf('Ç') < 0 ? text : text.replace('Ç', 'ç');
    }

    /** Expand text to septet stream (ESC + ext for extension chars). */
    public static byte[] toSeptets(String text) {
        int len = septetLength(text);
        if (len < 0) throw new IllegalArgumentException("non-GSM character in text");
        byte[] out = new byte[len];
        int p = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            byte b = CHAR_TO_BASIC[c];
            if (b >= 0) {
                out[p++] = b;
            } else {
                out[p++] = ESCAPE;
                out[p++] = CHAR_TO_EXT[c];
            }
        }
        return out;
    }

    /**
     * Septets → text. An ESC followed by an undefined extension position decodes as the basic
     * character at that position (TS 23.038 §6.2.1.1 note: "display the character from the
     * main table"); a trailing lone ESC is dropped.
     */
    public static String fromSeptets(byte[] septets) {
        if (septets == null || septets.length == 0) return "";
        StringBuilder sb = new StringBuilder(septets.length);
        for (int i = 0; i < septets.length; i++) {
            int v = septets[i] & 0x7F;
            if (v == ESCAPE) {
                if (i + 1 >= septets.length) break;
                int ext = septets[++i] & 0x7F;
                char decoded = EXT_TO_CHAR[ext];
                if (decoded != 0) {
                    sb.append(decoded);
                } else if (ext != ESCAPE) {
                    sb.append(BASIC.charAt(ext));
                }
            } else {
                sb.append(BASIC.charAt(v));
            }
        }
        return sb.toString();
    }
}
