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
        String asMode
) {
    /** Backward-compatible ctor without late-push metadata. */
    public AsRequest(String sessionId, String correlationId, String requestId, int generation,
                     String msisdn, String shortCode, String ussdString, int networkId) {
        this(sessionId, correlationId, requestId, generation, msisdn, shortCode, ussdString,
                networkId, null, null, null);
    }

    public AsRequest withMetadata(String virtualBridgeId, Long adaptiveTimeoutMs, String asMode) {
        return new AsRequest(sessionId, correlationId, requestId, generation, msisdn, shortCode,
                ussdString, networkId, virtualBridgeId, adaptiveTimeoutMs, asMode);
    }
}
