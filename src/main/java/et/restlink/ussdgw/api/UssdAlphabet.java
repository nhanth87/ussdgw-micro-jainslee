package et.restlink.ussdgw.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * USSD / SMS text alphabet (3GPP TS 23.038 / CBS DCS).
 *
 * <p><strong>Source of truth = AS</strong> (HTTP JSON {@code alphabet} or SMPP {@code data_coding}).
 * The gateway maps that choice to MAP CBS DCS — it does not hardcode UCS-7 / UCS-8 / Unicode.</p>
 *
 * <ul>
 *   <li>{@link #UCS7} / {@link #GSM7} — GSM 7-bit (+ default extension ESC)</li>
 *   <li>{@link #UCS8} — 8-bit CBS GSM8</li>
 *   <li>{@link #UNICODE} / {@link #UCS2} — UCS-2 (Amharic / Ethiopic)</li>
 *   <li>{@link #AUTO} — only when AS omits coding (content heuristic)</li>
 * </ul>
 */
public enum UssdAlphabet {
    AUTO,
    ASCII,
    /** GSM 7-bit — AS/operator slang "ucs7". */
    UCS7,
    GSM7,
    /** 8-bit — AS/operator slang "ucs8". */
    UCS8,
    /** UCS-2 — AS/operator slang "unicode". */
    UNICODE,
    UCS2;

    @JsonCreator
    public static UssdAlphabet parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        String s = raw.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        return switch (s) {
            case "AUTO", "DEFAULT" -> AUTO;
            case "ASCII", "US_ASCII" -> ASCII;
            case "UCS7", "GSM7", "GSM_7", "7BIT", "SEPTET" -> UCS7;
            case "UCS8", "GSM8", "GSM_8", "8BIT", "LATIN1", "ISO8859_1", "ISO_8859_1" -> UCS8;
            case "UNICODE", "UCS2", "UCS_2", "UTF16", "UTF_16", "UTF16BE" -> UNICODE;
            default -> {
                try {
                    yield UssdAlphabet.valueOf(s);
                } catch (IllegalArgumentException e) {
                    yield AUTO;
                }
            }
        };
    }

    @JsonValue
    public String toWire() {
        return switch (this) {
            case UCS7, GSM7, ASCII -> "ucs7";
            case UCS8 -> "ucs8";
            case UNICODE, UCS2 -> "unicode";
            case AUTO -> "auto";
        };
    }

    public boolean isGsm7Family() {
        return this == UCS7 || this == GSM7 || this == ASCII;
    }

    public boolean isUcs2Family() {
        return this == UNICODE || this == UCS2;
    }
}
