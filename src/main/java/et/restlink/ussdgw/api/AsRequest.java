package et.restlink.ussdgw.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * GW→AS pull body (JSON or mapped from classic XML).
 *
 * <p>Identity for late push-back ({@code /as/callback}, gRPC {@code Callback}):
 * use {@link #correlationId()} — that is the VirtualSessionStore / bridge key.
 * {@link #sessionId()} is the logical {@code virtualSessionId}.
 * {@link #virtualBridgeId()} is set when the bridge arm is enabled (usually equals correlationId).
 *
 * <p>When a prior AdaptiveTimeout / bridge gate fired, optional {@link #gateReason()},
 * {@link #jsessionId()}, and {@link #observedEwmaMs()} tell the AS the previous session
 * was gated so it can re-push (classic NI uses Cookie {@code JSESSIONID}).
 *
 * <p>MAP2MAP / MO enrich (additive): {@link #originatedUssd()} = full UE dialed string;
 * {@link #codeKind()} = {@code SHORT}|{@code LONG}; {@link #shortCode()} = matched rule key;
 * {@link #redirectUssd()} = rule redirect (e.g. {@code *875#}); {@link #hopUssd()} = resolved
 * hop code actually sent toward upper HLR (may be long {@code *875*…#});
 * {@link #ussdString()} = upper HLR/MSC hop USSD body only (never dialed/redirect codes),
 * or empty / {@code hlr reject} when the hop had no text / REJECT
 * ({@code hlrResult} carries {@code none}|{@code reject}; empty hop uses empty {@code string=}
 * so AS default menus are safe to return without echoing a placeholder).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AsRequest(
        String sessionId,
        String correlationId,
        String requestId,
        int generation,
        String msisdn,
        String shortCode,
        String ussdString,
        int networkId,
        String virtualBridgeId,
        Long adaptiveTimeoutMs,
        String asMode,
        String jsessionId,
        String gateReason,
        Long observedEwmaMs,
        String originatedUssd,
        String codeKind,
        String redirectUssd,
        String hopUssd
) {
    /** Backward-compatible ctor without late-push / gated metadata. */
    public AsRequest(String sessionId, String correlationId, String requestId, int generation,
                     String msisdn, String shortCode, String ussdString, int networkId) {
        this(sessionId, correlationId, requestId, generation, msisdn, shortCode, ussdString,
                networkId, null, null, null, null, null, null, null, null, null, null);
    }

    /** Backward-compatible ctor with bridge metadata only. */
    public AsRequest(String sessionId, String correlationId, String requestId, int generation,
                     String msisdn, String shortCode, String ussdString, int networkId,
                     String virtualBridgeId, Long adaptiveTimeoutMs, String asMode) {
        this(sessionId, correlationId, requestId, generation, msisdn, shortCode, ussdString,
                networkId, virtualBridgeId, adaptiveTimeoutMs, asMode, null, null, null, null, null,
                null, null);
    }

    public AsRequest withMetadata(String virtualBridgeId, Long adaptiveTimeoutMs, String asMode) {
        return new AsRequest(sessionId, correlationId, requestId, generation, msisdn, shortCode,
                ussdString, networkId, virtualBridgeId, adaptiveTimeoutMs, asMode,
                jsessionId, gateReason, observedEwmaMs, originatedUssd, codeKind,
                redirectUssd, hopUssd);
    }

    public AsRequest withGatedHint(String jsessionId, String gateReason, Long observedEwmaMs) {
        return new AsRequest(sessionId, correlationId, requestId, generation, msisdn, shortCode,
                ussdString, networkId, virtualBridgeId, adaptiveTimeoutMs, asMode,
                jsessionId, gateReason, observedEwmaMs, originatedUssd, codeKind,
                redirectUssd, hopUssd);
    }

    public AsRequest withOriginated(String originatedUssd, String codeKind) {
        return new AsRequest(sessionId, correlationId, requestId, generation, msisdn, shortCode,
                ussdString, networkId, virtualBridgeId, adaptiveTimeoutMs, asMode,
                jsessionId, gateReason, observedEwmaMs, originatedUssd, codeKind,
                redirectUssd, hopUssd);
    }

    /** MAP2MAP re-route codes: rule redirect + resolved hop USSD sent to upper HLR. */
    public AsRequest withMap2MapCodes(String redirectUssd, String hopUssd) {
        return new AsRequest(sessionId, correlationId, requestId, generation, msisdn, shortCode,
                ussdString, networkId, virtualBridgeId, adaptiveTimeoutMs, asMode,
                jsessionId, gateReason, observedEwmaMs, originatedUssd, codeKind,
                redirectUssd, hopUssd);
    }
}
