package et.restlink.ussdgw.cdr;

import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.events.Map2MapRequestEvent;

/**
 * MAP2MAP / re-route CDR status strings + detail builders.
 *
 * <p>Honesty rules (Digicom 2026-08-09):
 * <ul>
 *   <li>{@link #HLR_REJECT} — peer Dialog REJECT (never call this {@link #TIMEOUT})</li>
 *   <li>{@link #OK} — hop returned USSD text and AS pull was routed</li>
 *   <li>{@link #AS_ROUTED} — hop empty/lost; AdaptiveTimeout re-armed AS pull (not HLR OK)</li>
 *   <li>{@code gate_ms} on these rows is AdaptiveTimeout <em>budget</em>, not hop RTT</li>
 * </ul>
 */
public final class Map2MapCdr {
    private Map2MapCdr() {}

    public static final String ARMED = "MAP2MAP_ARMED";
    public static final String HOP_START = "MAP2MAP_HOP_START";
    public static final String SRI_SENT = "MAP2MAP_SRI_SENT";
    public static final String USSD_SENT = "MAP2MAP_USSD_SENT";
    public static final String SKIP_LAB = "MAP2MAP_SKIP_LAB";
    public static final String GATED_HOP = "MAP2MAP_GATED_HOP";
    /** Hop returned text + AS pull routed. */
    public static final String OK = "MAP2MAP_OK";
    /** Hop empty/lost; AS pull still routed under AdaptiveTimeout (not HLR success). */
    public static final String AS_ROUTED = "MAP2MAP_AS_ROUTED";
    /**
     * RE_ROUTE GATED#1 early AS pull ({@link #AS_USSD_HLR_PENDING}) while hop runs —
     * AS can prep in parallel (productivity).
     */
    public static final String AS_EARLY = "MAP2MAP_AS_EARLY";
    public static final String COMPLETE_AFTER_GATE = "MAP2MAP_COMPLETE_AFTER_GATE";
    public static final String AS_ROUTE_FAIL = "MAP2MAP_AS_ROUTE_FAIL";
    /** Peer Dialog REJECT on outbound hop (TC-END / MAP refuse). */
    public static final String HLR_REJECT = "HLR_REJECT";
    /** Peer USER_ABORT / PROVIDER_ABORT on outbound hop. */
    public static final String HOP_ABORT = "MAP2MAP_HOP_ABORT";
    /** True hop / dialog TIMEOUT (not REJECT). */
    public static final String TIMEOUT = "MAP2MAP_TIMEOUT";
    public static final String TIMEOUT_AFTER_BRIDGE = "MAP2MAP_TIMEOUT_AFTER_BRIDGE";

    public static final String OUTCOME_PENDING = "pending";
    public static final String OUTCOME_REJECT = "reject";
    public static final String OUTCOME_ABORT = "abort";
    public static final String OUTCOME_TIMEOUT = "timeout";
    public static final String OUTCOME_TEXT = "text";
    public static final String OUTCOME_EMPTY = "empty";
    public static final String OUTCOME_CLOSE = "close";

    /** RE_ROUTE GATED#1 early AS pull while hop in flight. */
    public static final String AS_USSD_HLR_PENDING = "hlr pending";
    /** RE_ROUTE AS pull {@code string=} when hop Dialog REJECT. */
    public static final String AS_USSD_HLR_REJECT = "hlr reject";
    /** RE_ROUTE AS pull {@code string=} when hop empty / timeout / abort / close. */
    public static final String AS_USSD_HLR_NONE = "hlr none";

    /** True when hop already finished without USSD text — do not re-arm AdaptiveTimeout. */
    public static boolean isTerminalHopOutcome(String hopOutcome) {
        return hopOutcome != null && !OUTCOME_TEXT.equals(hopOutcome.trim());
    }

    /**
     * RE_ROUTE only: AS ussdString after hop.
     * Text hop → hop body; REJECT → {@link #AS_USSD_HLR_REJECT}; else empty → {@link #AS_USSD_HLR_NONE}.
     */
    public static String asUssdForReRouteHop(String hopText, String hopOutcome) {
        String hop = hopText == null ? "" : hopText.trim();
        if (!hop.isEmpty()) {
            return hop;
        }
        if (OUTCOME_REJECT.equals(hopOutcome == null ? "" : hopOutcome.trim())) {
            return AS_USSD_HLR_REJECT;
        }
        return AS_USSD_HLR_NONE;
    }

    /** Map dialog-lost kind → CDR status (reject ≠ timeout). */
    public static String statusForDialogLost(String kind, boolean alreadyBridged) {
        String k = kind == null ? "" : kind.trim().toUpperCase();
        if ("REJECT".equals(k)) {
            return HLR_REJECT;
        }
        if ("USER_ABORT".equals(k) || "PROVIDER_ABORT".equals(k)) {
            return HOP_ABORT;
        }
        if ("TIMEOUT".equals(k)) {
            return alreadyBridged ? TIMEOUT_AFTER_BRIDGE : TIMEOUT;
        }
        // CLOSE / RELEASE / unknown — not a peer refuse; keep timeout family for TTL-ish loss.
        return alreadyBridged ? TIMEOUT_AFTER_BRIDGE : TIMEOUT;
    }

    public static String hopOutcomeForDialogLost(String kind) {
        String k = kind == null ? "" : kind.trim().toUpperCase();
        return switch (k) {
            case "REJECT" -> OUTCOME_REJECT;
            case "USER_ABORT", "PROVIDER_ABORT" -> OUTCOME_ABORT;
            case "TIMEOUT" -> OUTCOME_TIMEOUT;
            case "CLOSE", "RELEASE" -> OUTCOME_CLOSE;
            default -> OUTCOME_EMPTY;
        };
    }

    /** Pipe-joined detail: sc / redirect / dialed / hop path / extras (≤1000 via CdrService). */
    public static String detail(Map2MapRequestEvent req, String... extras) {
        StringBuilder b = new StringBuilder(96);
        if (req != null) {
            append(b, "sc", req.shortCode());
            append(b, "redirect", req.redirectUssd());
            append(b, "dialed", req.dialedUssd());
            if (req.fixedHopArmed()) {
                append(b, "hopGt", req.hopDestGtDigits());
                append(b, "hopSsn", Integer.toString(req.effectiveHopDestSsn()));
                append(b, "path", "fixed");
            }
            if (req.hlrMode() != null && !req.hlrMode().isBlank()) {
                append(b, "hlrMode", req.hlrMode());
            }
        }
        if (extras != null) {
            for (String e : extras) {
                if (e != null && !e.isBlank()) {
                    if (!b.isEmpty()) {
                        b.append('|');
                    }
                    b.append(e.trim());
                }
            }
        }
        return b.isEmpty() ? null : b.toString();
    }

    public static String detailArmed(Map2MapRequestEvent req, VirtualSession session) {
        String gate = session != null && session.gateMs() > 0
                ? ("gateMs=" + session.gateMs()) : null;
        String vb = session != null && session.virtualSessionId() != null
                ? ("virtualBridgeId=" + session.virtualSessionId()) : null;
        return detail(req, "phase=hop-ingress", "gateRole=budget", gate, vb);
    }

    public static String detailHopStart(Map2MapRequestEvent req, String path) {
        return detail(req, "path=" + (path == null ? "?" : path));
    }

    public static Long gateMs(VirtualSession s) {
        return s != null && s.gateMs() > 0 ? s.gateMs() : null;
    }

    private static void append(StringBuilder b, String k, String v) {
        if (v == null || v.isBlank()) {
            return;
        }
        if (!b.isEmpty()) {
            b.append('|');
        }
        b.append(k).append('=').append(v.trim());
    }
}
