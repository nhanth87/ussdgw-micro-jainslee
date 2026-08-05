package et.restlink.ussdgw.codec;

import et.restlink.ussdgw.api.UssdAlphabet;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SMS/USSD text codec — GSM-7 (ucs7 + extension), 8-bit (ucs8), UCS-2 (unicode).
 * MAP CBS DCS selection is driven by AS alphabet (HTTP {@code alphabet} / SMPP {@code data_coding});
 * {@link UssdAlphabet#AUTO} is only the fallback when the AS omits a coding.
 */
public final class SmsTextCodec {
    public static final byte DCS_GSM7 = 0x00;
    public static final byte DCS_LATIN1 = 0x03;
    public static final byte DCS_UCS2 = 0x08;
    /** CBS Data Coding Scheme: GSM 7-bit default alphabet. */
    public static final int CBS_GSM7 = 0x0F;
    /** CBS Data Coding Scheme: 8-bit (General Data Coding, uncompressed, no class). */
    public static final int CBS_GSM8 = 0x44;
    /** CBS Data Coding Scheme: UCS-2. */
    public static final int CBS_UCS2 = 0x48;

    private static final int GSM7_SINGLE = 160;
    private static final int UCS2_SINGLE = 70;
    private static final int UCS2_CONCAT = 67;

    private SmsTextCodec() { }

    public record EncodedPart(byte[] tpUd, boolean udhi) { }
    public record EncodedMessage(byte dataCoding, List<EncodedPart> parts) { }

    public static EncodedMessage encode(String text, int maxParts) {
        return encode(text, UssdAlphabet.AUTO, maxParts);
    }

    public static EncodedMessage encode(String text, UssdAlphabet alphabet, int maxParts) {
        if (text == null) throw new IllegalArgumentException("text is required");
        if (maxParts < 1) throw new IllegalArgumentException("maxParts must be >= 1");
        var decision = UssdEncodingPolicy.resolve(text, alphabet);
        return switch (decision.alphabet()) {
            case UCS8 -> {
                byte[] raw = text.getBytes(StandardCharsets.ISO_8859_1);
                yield new EncodedMessage(DCS_LATIN1, List.of(new EncodedPart(raw, false)));
            }
            case UNICODE, UCS2 -> encodeUcs2(text, maxParts);
            case UCS7, GSM7, ASCII, AUTO -> {
                if (!Gsm7Alphabet.canEncode(text)) {
                    yield encodeUcs2(text, maxParts);
                }
                int septets = Gsm7Alphabet.septetLength(text);
                if (septets > GSM7_SINGLE) {
                    yield encodeUcs2(text, maxParts);
                }
                byte[] packed = packGsm7(Gsm7Alphabet.toSeptets(text));
                yield new EncodedMessage(DCS_GSM7, List.of(new EncodedPart(packed, false)));
            }
        };
    }

    /** MAP CBS DCS — honors AS alphabet; AUTO uses content heuristic only. */
    public static int chooseCbsDataCoding(String text, UssdAlphabet hint) {
        return UssdEncodingPolicy.resolve(text, hint).cbsDcs();
    }

    /**
     * Map SMPP {@code data_coding} (PDU octet) → {@link UssdAlphabet}.
     * AS ESME chooses the coding; GW does not hardcode.
     */
    public static UssdAlphabet alphabetFromSmppDcs(byte dataCoding) {
        int dcs = dataCoding & 0xFF;
        if (dcs == 0x08 || dcs == 0x18 || (dcs & 0x0C) == 0x08) {
            return UssdAlphabet.UNICODE;
        }
        // Message class / general 8-bit
        if (dcs == 0x03 || dcs == 0x04 || dcs == 0xF5 || dcs == 0xF6
                || dcs == 0x44 || dcs == 0x54 || dcs == 0x55 || dcs == 0x56 || dcs == 0x57) {
            return UssdAlphabet.UCS8;
        }
        // Default / GSM-7 / IA5
        return UssdAlphabet.UCS7;
    }

    public static String decode(byte[] payload, byte dataCoding) {
        if (payload == null || payload.length == 0) return "";
        UssdAlphabet a = alphabetFromSmppDcs(dataCoding);
        if (a.isUcs2Family()) {
            return new String(payload, StandardCharsets.UTF_16BE);
        }
        if (a == UssdAlphabet.UCS8) {
            return new String(payload, StandardCharsets.ISO_8859_1);
        }
        // Prefer packed GSM-7 unpack when short_message looks binary-packed;
        // ESME often sends unpacked septets as ISO-8859-1 — keep Latin-1 fallback.
        try {
            byte[] septets = unpackGsm7(payload, estimateSeptetCount(payload));
            String viaGsm = Gsm7Alphabet.fromSeptets(septets);
            if (!viaGsm.isBlank()) return viaGsm;
        } catch (RuntimeException ignored) { }
        return new String(payload, StandardCharsets.ISO_8859_1).trim();
    }

    public static boolean canGsm7(String text) {
        return Gsm7Alphabet.canEncode(text);
    }

    public static boolean isAscii(String text) {
        if (text == null) return true;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) return false;
        }
        return true;
    }

    private static EncodedMessage encodeUcs2(String text, int maxParts) {
        Charset ucs2 = StandardCharsets.UTF_16BE;
        if (text.length() <= UCS2_SINGLE) {
            return new EncodedMessage(DCS_UCS2,
                    List.of(new EncodedPart(text.getBytes(ucs2), false)));
        }
        int chunk = UCS2_CONCAT;
        int parts = (text.length() + chunk - 1) / chunk;
        if (parts > maxParts) {
            throw new IllegalArgumentException(
                    "text too long for UCS-2: needs " + parts + " parts (max " + maxParts + ")");
        }
        byte ref = (byte) (System.nanoTime() & 0xFF);
        List<EncodedPart> out = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            int from = i * chunk;
            int to = Math.min(text.length(), from + chunk);
            byte[] body = text.substring(from, to).getBytes(ucs2);
            out.add(new EncodedPart(withConcatUdh(ref, (byte) parts, (byte) (i + 1), body), true));
        }
        return new EncodedMessage(DCS_UCS2, List.copyOf(out));
    }

    private static byte[] withConcatUdh(byte ref, byte total, byte seq, byte[] body) {
        byte[] udh = {0x05, 0x00, 0x03, ref, total, seq};
        byte[] out = new byte[udh.length + body.length];
        System.arraycopy(udh, 0, out, 0, udh.length);
        System.arraycopy(body, 0, out, udh.length, body.length);
        return out;
    }

    static byte[] packGsm7(byte[] septets) {
        if (septets.length == 0) return new byte[0];
        int outLen = (septets.length * 7 + 7) / 8;
        byte[] out = new byte[outLen];
        int bitOffset = 0;
        for (byte septet : septets) {
            int value = septet & 0x7F;
            int byteIndex = bitOffset / 8;
            int bitIndex = bitOffset % 8;
            out[byteIndex] |= (byte) ((value << bitIndex) & 0xFF);
            if (bitIndex > 1 && byteIndex + 1 < out.length) {
                out[byteIndex + 1] |= (byte) (value >> (8 - bitIndex));
            }
            bitOffset += 7;
        }
        return out;
    }

    private static int estimateSeptetCount(byte[] packed) {
        return packed.length * 8 / 7;
    }

    static byte[] unpackGsm7(byte[] packed, int septetCount) {
        if (packed == null || packed.length == 0 || septetCount <= 0) return new byte[0];
        byte[] septets = new byte[septetCount];
        int bitOffset = 0;
        for (int i = 0; i < septetCount; i++) {
            int byteIndex = bitOffset / 8;
            int bitIndex = bitOffset % 8;
            int value = (packed[byteIndex] & 0xFF) >> bitIndex;
            if (bitIndex > 1 && byteIndex + 1 < packed.length) {
                value |= (packed[byteIndex + 1] & 0xFF) << (8 - bitIndex);
            }
            septets[i] = (byte) (value & 0x7F);
            bitOffset += 7;
        }
        return septets;
    }
}
