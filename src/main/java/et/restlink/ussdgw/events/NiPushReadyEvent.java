package et.restlink.ussdgw.events;

import com.microjainslee.api.SleeEvent;

/**
 * Fired after SRI (or lab skip) so {@code MapNiPushSbb} can deliver S2.
 * When SRI succeeded, {@code mscGt}/{@code imsi} carry networkNodeNumber + IMSI so push
 * does not depend on a VirtualSessionStore round-trip (profile get can lag or miss fields).
 *
 * <p>When {@code reuseExistingDialog} is true (JSESSIONID continue with live MSC),
 * {@code MapNiPushSbb} calls {@code MapDialogHelper.niContinue} — no new SRI / createNewDialog.
 */
public record NiPushReadyEvent(
        String correlationId,
        String msisdn,
        String text,
        int networkId,
        et.restlink.ussdgw.api.UssdAlphabet alphabet,
        boolean notifyOnly,
        String mscGt,
        String imsi,
        boolean reuseExistingDialog
) implements SleeEvent {
    public NiPushReadyEvent(String correlationId, String msisdn, String text, int networkId) {
        this(correlationId, msisdn, text, networkId, et.restlink.ussdgw.api.UssdAlphabet.AUTO,
                false, null, null, false);
    }

    public NiPushReadyEvent(String correlationId, String msisdn, String text, int networkId,
                            et.restlink.ussdgw.api.UssdAlphabet alphabet) {
        this(correlationId, msisdn, text, networkId, alphabet, false, null, null, false);
    }

    public NiPushReadyEvent(String correlationId, String msisdn, String text, int networkId,
                            et.restlink.ussdgw.api.UssdAlphabet alphabet, boolean notifyOnly) {
        this(correlationId, msisdn, text, networkId, alphabet, notifyOnly, null, null, false);
    }

    public NiPushReadyEvent(String correlationId, String msisdn, String text, int networkId,
                            et.restlink.ussdgw.api.UssdAlphabet alphabet, boolean notifyOnly,
                            String mscGt, String imsi) {
        this(correlationId, msisdn, text, networkId, alphabet, notifyOnly, mscGt, imsi, false);
    }

    public static NiPushReadyEvent from(NiPushRequestEvent ni) {
        return new NiPushReadyEvent(ni.correlationId(), ni.msisdn(), ni.text(), ni.networkId(),
                ni.alphabet() == null ? et.restlink.ussdgw.api.UssdAlphabet.AUTO : ni.alphabet(),
                ni.notifyOnly(), null, null, false);
    }

    public static NiPushReadyEvent fromSri(NiPushRequestEvent ni, String mscGt, String imsi) {
        return new NiPushReadyEvent(ni.correlationId(), ni.msisdn(), ni.text(), ni.networkId(),
                ni.alphabet() == null ? et.restlink.ussdgw.api.UssdAlphabet.AUTO : ni.alphabet(),
                ni.notifyOnly(), mscGt, imsi, false);
    }

    /**
     * AS JSESSIONID continue when MSC/IMSI already known and MAP dialog is alive —
     * skip SriSbb / createNewDialog.
     */
    public static NiPushReadyEvent continueOnDialog(
            String correlationId, String msisdn, String text, int networkId,
            et.restlink.ussdgw.api.UssdAlphabet alphabet, boolean notifyOnly,
            String mscGt, String imsi) {
        return new NiPushReadyEvent(correlationId, msisdn, text, networkId,
                alphabet == null ? et.restlink.ussdgw.api.UssdAlphabet.AUTO : alphabet,
                notifyOnly, mscGt, imsi, true);
    }
}
