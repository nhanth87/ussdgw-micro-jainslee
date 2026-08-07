package et.restlink.ussdgw.sip;

import java.util.Optional;

/**
 * Extract / classify USSD strings from SIP MESSAGE body or SDP (TS 24.390 style).
 */
public final class SipUssdBodyCodec {
    /** E.164-ish: digits only, length in [{@value #MSISDN_MIN}, {@value #MSISDN_MAX}]. */
    public static final int MSISDN_MIN = 8;
    public static final int MSISDN_MAX = 15;

    private SipUssdBodyCodec() {}

    public enum InboundKind {
        /** AS → GW network-initiated push (deliver to UE). */
        NI_PUSH,
        /** UE-facing / dial string MO pull toward AS. */
        MO_PULL,
        /** Unknown / empty. */
        UNKNOWN
    }

    /**
     * @param explicitNi {@code true} when SDP / {@code a=ussd-string:} / NI header forced the
     *                   classification (not free-text soft NI)
     */
    public record Decoded(InboundKind kind, String ussdText, String msisdnHint, boolean explicitNi) {
        public Decoded(InboundKind kind, String ussdText, String msisdnHint) {
            this(kind, ussdText, msisdnHint, false);
        }
    }

    /**
     * Classify inbound MESSAGE. SDP or {@code a=ussd-string:} → NI_PUSH (explicit);
     * dial-shaped body ({@code *…#}) → MO_PULL; optional {@code niHeader} forces NI;
     * other non-empty text → soft NI_PUSH (may be an AS pull menu reply — SBB correlates first).
     */
    public static Decoded decode(String contentType, String body, boolean niHeader,
                                  String inboundBodyMode) {
        String raw = body == null ? "" : body.trim();
        String ct = contentType == null ? "" : contentType.toLowerCase();
        boolean sdpMode = "SDP".equalsIgnoreCase(inboundBodyMode)
                || ct.contains("application/sdp");
        if (niHeader || sdpMode || rawContainsUssdStringAttr(raw)) {
            String text = extractUssdString(raw);
            String msisdn = extractSdpMsisdn(raw);
            if (text.isEmpty() && !sdpMode) {
                text = raw;
            }
            return new Decoded(InboundKind.NI_PUSH, text, msisdn, true);
        }
        if (looksLikeShortCodeDial(raw)) {
            return new Decoded(InboundKind.MO_PULL, raw, null, false);
        }
        if (!raw.isEmpty()) {
            return new Decoded(InboundKind.NI_PUSH, raw, null, false);
        }
        return new Decoded(InboundKind.UNKNOWN, "", null, false);
    }

    public static String extractUssdString(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        for (String line : body.split("\\R")) {
            String t = line.trim();
            if (t.regionMatches(true, 0, "a=ussd-string:", 0, "a=ussd-string:".length())) {
                return t.substring("a=ussd-string:".length()).trim();
            }
        }
        return body.trim();
    }

    static String extractSdpMsisdn(String body) {
        if (body == null) {
            return null;
        }
        for (String line : body.split("\\R")) {
            String t = line.trim();
            if (t.regionMatches(true, 0, "a=msisdn:", 0, "a=msisdn:".length())) {
                return t.substring("a=msisdn:".length()).trim();
            }
        }
        return null;
    }

    static boolean rawContainsUssdStringAttr(String body) {
        if (body == null) {
            return false;
        }
        for (String line : body.split("\\R")) {
            if (line.trim().regionMatches(true, 0, "a=ussd-string:", 0, "a=ussd-string:".length())) {
                return true;
            }
        }
        return false;
    }

    public static boolean looksLikeShortCodeDial(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String s = body.trim();
        return s.startsWith("*") || s.startsWith("#");
    }

    /** Encode pull payload for outbound MESSAGE to AS trunk. */
    public static String encodePullPlain(String correlationId, String msisdn, String shortCode,
                                         String text) {
        return "correlationId=" + nullToEmpty(correlationId) + '\n'
                + "msisdn=" + nullToEmpty(msisdn) + '\n'
                + "shortCode=" + nullToEmpty(shortCode) + '\n'
                + "text=" + nullToEmpty(text) + '\n';
    }

    /**
     * Digits-only MSISDN of reasonable length for NI push. Rejects empty / too short / too long.
     */
    public static Optional<String> normalizeMsisdn(String raw) {
        String d = SipTrunkService.digitsOnly(raw);
        if (d.length() < MSISDN_MIN || d.length() > MSISDN_MAX) {
            return Optional.empty();
        }
        return Optional.of(d);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
