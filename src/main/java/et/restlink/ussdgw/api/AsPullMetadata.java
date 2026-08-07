package et.restlink.ussdgw.api;

import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.config.UssdConfigService;

/**
 * Enriches GW→AS pull {@link AsRequest} with late-push metadata shared by HTTP and gRPC.
 */
public final class AsPullMetadata {
    private AsPullMetadata() {}

    /**
     * @param req     base request (sessionId = virtualSessionId; correlationId = push-back key)
     * @param session in-flight session after {@code startAwaitingAs} (may be null)
     * @param adaptive EWMA gate
     * @param config  async/dialog ceilings + bridge flags
     * @param bridgePlaneArmed whether HTTP or gRPC client bridge is enabled for this route
     */
    public static AsRequest enrich(AsRequest req, VirtualSession session,
                                   AdaptiveTimeout adaptive, UssdConfigService config,
                                   boolean bridgePlaneArmed) {
        if (req == null) {
            return null;
        }
        int networkId = session != null ? session.networkId() : req.networkId();
        long asyncGate = config == null ? 7000L : config.asyncGateTimeoutMs();
        long dialog = config == null ? 60_000L : config.dialogTimeoutMs();
        long gateMs;
        if (session != null && session.gateMs() > 0) {
            gateMs = session.gateMs();
        } else if (adaptive != null) {
            gateMs = adaptive.effectiveGateMs(networkId, asyncGate, dialog);
        } else {
            gateMs = asyncGate > 0 && asyncGate < dialog ? asyncGate : dialog;
        }

        boolean arm = session != null
                ? session.adaptiveBridgeArm()
                : bridgePlaneArmed;
        String corr = blankToNull(req.correlationId());
        String bridgeId = arm ? firstNonBlank(corr, blankToNull(req.sessionId())) : null;
        AsMode mode = arm ? AsMode.BRIDGE : AsMode.SYNC;
        return req.withMetadata(bridgeId, gateMs, mode.wire());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }
}
