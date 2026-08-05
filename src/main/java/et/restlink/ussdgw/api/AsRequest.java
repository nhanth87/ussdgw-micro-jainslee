package et.restlink.ussdgw.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AsRequest(
        String sessionId,
        String correlationId,
        String requestId,
        int generation,
        String msisdn,
        String shortCode,
        String ussdString,
        int networkId
) {}
