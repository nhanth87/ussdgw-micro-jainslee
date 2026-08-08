package et.restlink.ussdgw.cdr;

import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.events.Map2MapRequestEvent;

/**
 * MAP2MAP / re-route CDR status strings + detail builders (2026-08-08).
 * Keep statuses aligned with {@code Map2MapTelemetry} / BridgeGateScheduler language.
 */
public final class Map2MapCdr {
    private Map2MapCdr() {}

    public static final String ARMED = "MAP2MAP_ARMED";
    public static final String HOP_START = "MAP2MAP_HOP_START";
    public static final String SRI_SENT = "MAP2MAP_SRI_SENT";
    public static final String USSD_SENT = "MAP2MAP_USSD_SENT";
    public static final String SKIP_LAB = "MAP2MAP_SKIP_LAB";
    public static final String GATED_HOP = "MAP2MAP_GATED_HOP";
    public static final String OK = "MAP2MAP_OK";
    public static final String COMPLETE_AFTER_GATE = "MAP2MAP_COMPLETE_AFTER_GATE";
    public static final String AS_ROUTE_FAIL = "MAP2MAP_AS_ROUTE_FAIL";
    public static final String TIMEOUT = "MAP2MAP_TIMEOUT";
    public static final String TIMEOUT_AFTER_BRIDGE = "MAP2MAP_TIMEOUT_AFTER_BRIDGE";

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
        return detail(req, "phase=hop-ingress", gate, vb);
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
