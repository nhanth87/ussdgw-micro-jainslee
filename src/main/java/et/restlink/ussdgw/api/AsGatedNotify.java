package et.restlink.ussdgw.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import et.restlink.ussdgw.bridge.GatedSessionMeta;

/**
 * GW→AS wire body when AdaptiveTimeout / bridge gate fires on a parked NI HTTP
 * (or when pull metadata carries a prior-gated hint). Additive RestLink fields;
 * classic AS may ignore unknowns.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AsGatedNotify(
        String correlationId,
        String sessionId,
        String virtualBridgeId,
        String jsessionId,
        Long adaptiveTimeoutMs,
        Long observedEwmaMs,
        String gateReason,
        String action
) {
    public static AsGatedNotify from(GatedSessionMeta meta) {
        if (meta == null) {
            return null;
        }
        return new AsGatedNotify(
                meta.correlationId(),
                meta.sessionId(),
                meta.virtualBridgeId(),
                meta.jsessionId(),
                meta.gateMs() > 0 ? meta.gateMs() : null,
                meta.observedEwmaMs(),
                meta.gateReason(),
                AsAction.ABORT.name());
    }
}
