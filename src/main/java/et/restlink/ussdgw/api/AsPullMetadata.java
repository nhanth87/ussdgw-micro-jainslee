package et.restlink.ussdgw.api;

import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.GatedSessionMeta;
import et.restlink.ussdgw.bridge.GatedSessionRegistry;
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
     * @param adaptive EWMA model (telemetry) + {@code effectiveGateMs} ceiling helper
     * @param config  async/dialog ceilings + bridge flags
     * @param bridgePlaneArmed whether HTTP or gRPC client bridge is enabled for this route
     */
    public static AsRequest enrich(AsRequest req, VirtualSession session,
                                   AdaptiveTimeout adaptive, UssdConfigService config,
                                   boolean bridgePlaneArmed) {
        return enrich(req, session, adaptive, config, bridgePlaneArmed, null);
    }

    /**
     * Same as {@link #enrich(AsRequest, VirtualSession, AdaptiveTimeout, UssdConfigService, boolean)}
     * plus optional prior-gated hint from {@link GatedSessionRegistry}.
     */
    public static AsRequest enrich(AsRequest req, VirtualSession session,
                                   AdaptiveTimeout adaptive, UssdConfigService config,
                                   boolean bridgePlaneArmed, GatedSessionRegistry gated) {
        if (req == null) {
            return null;
        }
        int networkId = session != null ? session.networkId() : req.networkId();
        String msisdn = session != null ? session.msisdn() : req.msisdn();
        long asyncGate = config == null ? 25_000L : config.asyncGateTimeoutMs();
        long dialog = config == null ? 60_000L : config.dialogTimeoutMs();
        long gateMs;
        if (session != null && session.gateMs() > 0) {
            gateMs = session.gateMs();
        } else if (adaptive != null) {
            gateMs = adaptive.effectiveGateMs(networkId, msisdn, asyncGate, dialog);
        } else {
            gateMs = asyncGate > 0 && asyncGate < dialog ? asyncGate : dialog;
        }

        boolean arm = session != null
                ? session.adaptiveBridgeArm()
                : bridgePlaneArmed;
        String corr = blankToNull(req.correlationId());
        String bridgeId = arm ? firstNonBlank(corr, blankToNull(req.sessionId())) : null;
        AsMode mode = arm ? AsMode.BRIDGE : AsMode.SYNC;
        AsRequest out = req.withMetadata(bridgeId, gateMs, mode.wire());

        if (gated != null) {
            var hint = gated.resolveForPull(
                    corr,
                    session != null ? session.msisdn() : req.msisdn(),
                    session != null ? session.shortCode() : req.shortCode());
            if (hint.isPresent()) {
                out = applyGatedHint(out, hint.get(), arm);
            }
        }
        return out;
    }

    static AsRequest applyGatedHint(AsRequest req, GatedSessionMeta meta, boolean bridgeArmed) {
        if (req == null || meta == null) {
            return req;
        }
        String bridgeId = firstNonBlank(meta.virtualBridgeId(), req.virtualBridgeId(),
                bridgeArmed ? req.correlationId() : null);
        Long gateMs = meta.gateMs() > 0 ? meta.gateMs()
                : req.adaptiveTimeoutMs();
        AsRequest withBridge = req.withMetadata(
                bridgeId,
                gateMs,
                req.asMode() != null ? req.asMode() : (bridgeArmed ? AsMode.BRIDGE.wire() : AsMode.SYNC.wire()));
        return withBridge.withGatedHint(meta.jsessionId(), meta.gateReason(), meta.observedEwmaMs());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }

    private static String firstNonBlank(String a, String b, String c) {
        String x = firstNonBlank(a, b);
        return x != null ? x : firstNonBlank(c, null);
    }
}
