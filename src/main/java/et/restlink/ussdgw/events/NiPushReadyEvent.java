package et.restlink.ussdgw.events;

import com.microjainslee.api.SleeEvent;

/** Fired by SriSbb after SRI (or lab skip) so MapNiPushSbb can deliver S2. */
public record NiPushReadyEvent(
        String correlationId,
        String msisdn,
        String text,
        int networkId,
        et.restlink.ussdgw.api.UssdAlphabet alphabet
) implements SleeEvent {
    public NiPushReadyEvent(String correlationId, String msisdn, String text, int networkId) {
        this(correlationId, msisdn, text, networkId, et.restlink.ussdgw.api.UssdAlphabet.AUTO);
    }

    public static NiPushReadyEvent from(NiPushRequestEvent ni) {
        return new NiPushReadyEvent(ni.correlationId(), ni.msisdn(), ni.text(), ni.networkId(),
                ni.alphabet() == null ? et.restlink.ussdgw.api.UssdAlphabet.AUTO : ni.alphabet());
    }
}
