package et.restlink.ussdgw.bridge;

/**
 * Snapshot stamped when AdaptiveTimeout / bridge gate fires so routing / AS can
 * re-push with knowledge that the previous session was gated.
 *
 * <p>{@code virtualBridgeId} is usually the correlation id when the bridge arm is on.
 * {@code jsessionId} is set for classic NI HTTP park (Cookie {@code JSESSIONID}).
 */
public record GatedSessionMeta(
        String correlationId,
        String virtualBridgeId,
        String sessionId,
        String jsessionId,
        long gateMs,
        Long observedEwmaMs,
        String gateReason,
        int networkId,
        String msisdn,
        String shortCode,
        long stampedAtMs
) {
    public static final String REASON_GATE_EXPIRED = "GATE_EXPIRED";
    public static final String REASON_BRIDGED = "BRIDGED";
    public static final String REASON_GATE_NO_BRIDGE = "GATE_NO_BRIDGE";

    public static GatedSessionMeta of(VirtualSession s, String jsessionId, String gateReason,
                                      Long observedEwmaMs) {
        if (s == null) {
            throw new IllegalArgumentException("session required");
        }
        String corr = s.correlationId();
        String bridgeId = blankToNull(corr);
        return new GatedSessionMeta(
                corr,
                bridgeId,
                blankToNull(s.virtualSessionId()),
                blankToNull(jsessionId),
                s.gateMs(),
                observedEwmaMs,
                gateReason == null || gateReason.isBlank() ? REASON_GATE_EXPIRED : gateReason.trim(),
                s.networkId(),
                blankToNull(s.msisdn()),
                blankToNull(s.shortCode()),
                System.currentTimeMillis());
    }

    public static GatedSessionMeta niPark(String correlationId, String jsessionId, long gateMs,
                                          Long observedEwmaMs, int networkId,
                                          String msisdn, String shortCode, String sessionId) {
        String corr = blankToNull(correlationId);
        return new GatedSessionMeta(
                corr,
                corr,
                blankToNull(sessionId),
                blankToNull(jsessionId),
                gateMs,
                observedEwmaMs,
                REASON_GATE_EXPIRED,
                networkId,
                blankToNull(msisdn),
                blankToNull(shortCode),
                System.currentTimeMillis());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
