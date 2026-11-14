package et.restlink.ussdgw.codec;

import et.restlink.ussdgw.api.UssdAlphabet;

/**
 * Data coding scheme → character set, for the two DCS spaces the gateway meets.
 *
 * <ul>
 *   <li><b>CBS DCS</b> (MAP USSD, classic XML {@code dataCodingScheme}) — 3GPP TS 23.038 §5.</li>
 *   <li><b>SMPP {@code data_coding}</b> (USSD over SMPP) — SMPP 3.4 §5.2.19; values outside the
 *       SMPP-defined set follow the GSM SMS DCS (TS 23.038 §4).</li>
 * </ul>
 *
 * <p>Per TS 23.038, reserved coding groups are treated as the GSM 7-bit default alphabet.</p>
 */
public final class DataCodingSchemes {

    private DataCodingSchemes() { }

    /** Character set carried by an SMPP {@code data_coding} value. */
    public enum SmppCharset {
        /** 0x00 SMSC default alphabet — GSM 7-bit, one septet per octet (SMPP never packs). */
        GSM7_UNPACKED,
        /** 0x01 IA5 (CCITT T.50) / ASCII. */
        IA5,
        /** 0x03 ISO-8859-1. */
        LATIN1,
        /** 0x06 ISO-8859-5 (Cyrillic). */
        CYRILLIC,
        /** 0x07 ISO-8859-8 (Latin/Hebrew). */
        HEBREW,
        /** 0x08 and the UCS-2 DCS groups — UTF-16BE. */
        UCS2,
        /** 8-bit binary groups (0x02, 0x04, GSM 8-bit DCS) — octets passed as ISO-8859-1. */
        BINARY_8BIT
    }

    /** CBS DCS (TS 23.038 §5) → alphabet. Never returns {@link UssdAlphabet#AUTO}. */
    public static UssdAlphabet alphabetOfCbsDcs(int dcs) {
        int v = dcs & 0xFF;
        if (v == 0x08) {
            // Classic-GW compat: bare 8 on the MAP/USSD wire historically meant UCS-2
            // (SMS DCS §4 reading). The old codec mapped it to UNICODE — keep that.
            return UssdAlphabet.UNICODE;
        }
        int group = v >> 4;
        switch (group) {
            case 0x0, 0x2, 0x3:
                // Language groups using the GSM 7-bit default alphabet, plus reserved.
                return UssdAlphabet.UCS7;
            case 0x1:
                // 0001 0000 GSM7 + language indicator; 0001 0001 UCS2 + language indicator.
                return v == 0x11 ? UssdAlphabet.UNICODE : UssdAlphabet.UCS7;
            case 0x4, 0x5, 0x6, 0x7, 0x9:
                // General data coding (01xx) and "message with UDH" (1001): bits 3..2.
                return alphabetBits(v);
            case 0xE:
                // Defined by the WAP Forum — octets.
                return UssdAlphabet.UCS8;
            case 0xF:
                // Data coding / message handling: bit 2 = 0 GSM7, 1 8-bit.
                return (v & 0x04) == 0 ? UssdAlphabet.UCS7 : UssdAlphabet.UCS8;
            default:
                return UssdAlphabet.UCS7;
        }
    }

    /** CBS DCS with "message preceded by language indication" (TS 23.038 §5, 0x10 / 0x11). */
    public static boolean cbsHasLanguageIndicator(int dcs) {
        int v = dcs & 0xFF;
        return v == 0x10 || v == 0x11;
    }

    /** SMPP {@code data_coding} → character set. */
    public static SmppCharset smppCharset(int dataCoding) {
        int v = dataCoding & 0xFF;
        switch (v) {
            case 0x00: return SmppCharset.GSM7_UNPACKED;
            case 0x01: return SmppCharset.IA5;
            case 0x02, 0x04: return SmppCharset.BINARY_8BIT;
            case 0x03: return SmppCharset.LATIN1;
            case 0x06: return SmppCharset.CYRILLIC;
            case 0x07: return SmppCharset.HEBREW;
            case 0x08: return SmppCharset.UCS2;
            default: break;
        }
        if (v < 0x10) {
            // 0x05 JIS, 0x09 pictogram, 0x0A ISO-2022-JP, 0x0D/0x0E Kanji/KS C 5601 and
            // reserved values: not supported for USSD — octets, never silently GSM-7.
            return SmppCharset.BINARY_8BIT;
        }
        int group = v >> 4;
        if (group <= 0x7) {
            // GSM SMS DCS general data coding (00xx / 01xx): bits 3..2.
            return switch ((v >> 2) & 0x03) {
                case 0x01 -> SmppCharset.BINARY_8BIT;
                case 0x02 -> SmppCharset.UCS2;
                default -> SmppCharset.GSM7_UNPACKED;
            };
        }
        if (group == 0xC || group == 0xD) {
            return SmppCharset.GSM7_UNPACKED;  // message waiting, GSM 7-bit
        }
        if (group == 0xE) {
            return SmppCharset.UCS2;           // message waiting, UCS2
        }
        if (group == 0xF) {
            return (v & 0x04) == 0 ? SmppCharset.GSM7_UNPACKED : SmppCharset.BINARY_8BIT;
        }
        return SmppCharset.GSM7_UNPACKED;      // 1000..1011 reserved
    }

    /** SMPP {@code data_coding} → USSD alphabet (what the gateway should send on MAP). */
    public static UssdAlphabet alphabetOfSmpp(int dataCoding) {
        return switch (smppCharset(dataCoding)) {
            case UCS2, CYRILLIC, HEBREW -> UssdAlphabet.UNICODE;
            case LATIN1, BINARY_8BIT -> UssdAlphabet.UCS8;
            case GSM7_UNPACKED, IA5 -> UssdAlphabet.UCS7;
        };
    }

    private static UssdAlphabet alphabetBits(int v) {
        return switch ((v >> 2) & 0x03) {
            case 0x01 -> UssdAlphabet.UCS8;
            case 0x02 -> UssdAlphabet.UNICODE;
            default -> UssdAlphabet.UCS7;  // 00 GSM7, 11 reserved → GSM7
        };
    }
}
