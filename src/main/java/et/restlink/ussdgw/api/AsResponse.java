package et.restlink.ussdgw.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * AS→GW pull response or callback body.
 *
 * <p>{@link #correlationId()} is the real push-back / store key. Optional
 * {@link #sessionId()} / {@link #virtualBridgeId()} may echo pull metadata so a
 * late callback can still resolve the session if correlationId is omitted.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AsResponse(
        String correlationId,
        String requestId,
        int generation,
        String text,
        AsAction action,
        boolean async,
        UssdAlphabet alphabet,
        String sessionId,
        String virtualBridgeId,
        Long adaptiveTimeoutMs
) {
    public AsResponse {
        if (action == null) action = AsAction.END;
        if (alphabet == null) alphabet = UssdAlphabet.AUTO;
    }

    /** Backward-compatible ctor without alphabet / metadata. */
    public AsResponse(String correlationId, String requestId, int generation,
                      String text, AsAction action, boolean async) {
        this(correlationId, requestId, generation, text, action, async, UssdAlphabet.AUTO,
                null, null, null);
    }

    /** Backward-compatible ctor with alphabet, no late-push metadata. */
    public AsResponse(String correlationId, String requestId, int generation,
                      String text, AsAction action, boolean async, UssdAlphabet alphabet) {
        this(correlationId, requestId, generation, text, action, async, alphabet,
                null, null, null);
    }

    /**
     * Real session key for bridge / store lookup: correlationId, else virtualBridgeId,
     * else sessionId (virtualSessionId echo).
     */
    public String resolvePushBackId() {
        if (correlationId != null && !correlationId.isBlank()) {
            return correlationId.trim();
        }
        if (virtualBridgeId != null && !virtualBridgeId.isBlank()) {
            return virtualBridgeId.trim();
        }
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId.trim();
        }
        return null;
    }

    public AsResponse withPushBackIds(String sessionId, String virtualBridgeId, Long adaptiveTimeoutMs) {
        return new AsResponse(correlationId, requestId, generation, text, action, async, alphabet,
                sessionId, virtualBridgeId, adaptiveTimeoutMs);
    }

    /**
     * Align wire generation to the live session generation (Sip / HTTP / gRPC parity).
     *
     * <p>Applies to <strong>any</strong> AS wire: classic XML ({@code ClassicDialogXmlCodec}
     * hardcodes {@code 1}) and JSON (AS may echo {@code "generation":1} or omit it → 0).
     * After an MS digit the session is ≥2; an unstamped reply loses
     * {@code claimForAsResponse} and is dropped as late/zombie.
     */
    public AsResponse stampedToSessionGeneration(int sessionGeneration) {
        if (sessionGeneration <= 0) {
            return this;
        }
        if (generation > 0 && generation == sessionGeneration) {
            return this;
        }
        return new AsResponse(correlationId, requestId, sessionGeneration, text, action, async,
                alphabet, sessionId, virtualBridgeId, adaptiveTimeoutMs);
    }
}
