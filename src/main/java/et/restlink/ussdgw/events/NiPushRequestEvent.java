package et.restlink.ussdgw.events;

import com.microjainslee.api.SleeEvent;

public record NiPushRequestEvent(
        String correlationId,
        String msisdn,
        String text,
        int networkId,
        et.restlink.ussdgw.api.UssdAlphabet alphabet
) implements SleeEvent {
    public NiPushRequestEvent(String correlationId, String msisdn, String text, int networkId) {
        this(correlationId, msisdn, text, networkId, et.restlink.ussdgw.api.UssdAlphabet.AUTO);
    }
}
