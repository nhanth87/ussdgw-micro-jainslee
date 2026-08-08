package et.restlink.ussdgw.events;

import com.microjainslee.api.SleeEvent;

/**
 * Fired after SRI (or lab skip) so {@code MapNiPushSbb} can deliver S2.
 * When SRI succeeded, {@code mscGt}/{@code imsi} carry networkNodeNumber + IMSI so push
 * does not depend on a VirtualSessionStore round-trip (profile get can lag or miss fields).
 */
public record NiPushReadyEvent(
        String correlationId,
        String msisdn,
        String text,
        int networkId,
        et.restlink.ussdgw.api.UssdAlphabet alphabet,
        boolean notifyOnly,
        String mscGt,
        String imsi
) implements SleeEvent {
    public NiPushReadyEvent(String correlationId, String msisdn, String text, int networkId) {
        this(correlationId, msisdn, text, networkId, et.restlink.ussdgw.api.UssdAlphabet.AUTO,
                false, null, null);
    }

    public NiPushReadyEvent(String correlationId, String msisdn, String text, int networkId,
                            et.restlink.ussdgw.api.UssdAlphabet alphabet) {
        this(correlationId, msisdn, text, networkId, alphabet, false, null, null);
    }

    public NiPushReadyEvent(String correlationId, String msisdn, String text, int networkId,
                            et.restlink.ussdgw.api.UssdAlphabet alphabet, boolean notifyOnly) {
        this(correlationId, msisdn, text, networkId, alphabet, notifyOnly, null, null);
    }

    public static NiPushReadyEvent from(NiPushRequestEvent ni) {
        return new NiPushReadyEvent(ni.correlationId(), ni.msisdn(), ni.text(), ni.networkId(),
                ni.alphabet() == null ? et.restlink.ussdgw.api.UssdAlphabet.AUTO : ni.alphabet(),
                ni.notifyOnly(), null, null);
    }

    public static NiPushReadyEvent fromSri(NiPushRequestEvent ni, String mscGt, String imsi) {
        return new NiPushReadyEvent(ni.correlationId(), ni.msisdn(), ni.text(), ni.networkId(),
                ni.alphabet() == null ? et.restlink.ussdgw.api.UssdAlphabet.AUTO : ni.alphabet(),
                ni.notifyOnly(), mscGt, imsi);
    }
}
