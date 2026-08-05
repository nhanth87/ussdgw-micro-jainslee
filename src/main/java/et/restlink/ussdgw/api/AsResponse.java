package et.restlink.ussdgw.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AsResponse(
        String correlationId,
        String requestId,
        int generation,
        String text,
        AsAction action,
        boolean async,
        UssdAlphabet alphabet
) {
    public AsResponse {
        if (action == null) action = AsAction.END;
        if (alphabet == null) alphabet = UssdAlphabet.AUTO;
    }

    /** Backward-compatible ctor without alphabet. */
    public AsResponse(String correlationId, String requestId, int generation,
                      String text, AsAction action, boolean async) {
        this(correlationId, requestId, generation, text, action, async, UssdAlphabet.AUTO);
    }
}
