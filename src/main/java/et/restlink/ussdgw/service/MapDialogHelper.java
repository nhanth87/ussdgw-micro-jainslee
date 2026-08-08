package et.restlink.ussdgw.service;

import et.restlink.ussdgw.api.UssdAlphabet;
import et.restlink.ussdgw.codec.SmsTextCodec;
import et.restlink.ussdgw.config.UssdConfigService;

import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.jss7.Ss7Address;
import com.microjainslee.ra.jss7.command.Ss7Command;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.protocols.ss7.map.api.service.supplementary.ProcessUnstructuredSSRequest;

/** USSD MAP helpers — replies via ra-jss7 commands with CBS DCS (GSM-7 / UCS-2). */
public final class MapDialogHelper {
    private static final Logger LOG = LogManager.getLogger(MapDialogHelper.class);
    private MapDialogHelper() {}

    public static String ussdString(ProcessUnstructuredSSRequest req) {
        try {
            if (req == null || req.getUSSDString() == null) return "";
            return req.getUSSDString().getString(null);
        } catch (Exception e) {
            return "";
        }
    }

    public static String msisdnHint(ProcessUnstructuredSSRequest req) {
        try {
            if (req.getMSISDNAddressString() != null) {
                return req.getMSISDNAddressString().getAddress();
            }
        } catch (Exception ignored) {}
        return "";
    }

    public static void replyAndEnd(RaCommandPort ss7, String dialogId, long invokeId, String text) {
        replyAndEnd(ss7, dialogId, invokeId, text, UssdAlphabet.AUTO);
    }

    public static void replyAndEnd(RaCommandPort ss7, String dialogId, long invokeId,
                                   String text, UssdAlphabet alphabet) {
        if (ss7 == null) {
            LOG.warn("replyAndEnd: no ra-jss7");
            return;
        }
        int dcs = SmsTextCodec.chooseCbsDataCoding(text, alphabet);
        ss7.sendCommand(new Ss7Command.MapProcessUnstructuredSsResponse(
                dialogId, invokeId, text, true, dcs));
    }

    public static void replyContinue(RaCommandPort ss7, String dialogId, long invokeId, String text) {
        replyContinue(ss7, dialogId, invokeId, text, UssdAlphabet.AUTO);
    }

    public static void replyContinue(RaCommandPort ss7, String dialogId, long invokeId,
                                     String text, UssdAlphabet alphabet) {
        if (ss7 == null) {
            LOG.warn("replyContinue: no ra-jss7");
            return;
        }
        int dcs = SmsTextCodec.chooseCbsDataCoding(text, alphabet);
        ss7.sendCommand(new Ss7Command.MapProcessUnstructuredSsResponse(
                dialogId, invokeId, text, false, dcs));
    }

    public static void abort(RaCommandPort ss7, String dialogId) {
        if (ss7 == null) return;
        ss7.sendCommand(new Ss7Command.MapDialogAbort(dialogId));
    }

    public static void niPush(RaCommandPort ss7, String correlationId,
                              String mscGt, String localGt, String text, int networkId) {
        niPush(ss7, correlationId, mscGt, localGt, text, networkId, UssdAlphabet.AUTO,
                false, null, 8, 8);
    }

    public static void niPush(RaCommandPort ss7, String correlationId,
                              String mscGt, String localGt, String text, int networkId,
                              UssdAlphabet alphabet) {
        niPush(ss7, correlationId, mscGt, localGt, text, networkId, alphabet, false, null, 8, 8);
    }

    public static void niPush(RaCommandPort ss7, String correlationId,
                              String mscGt, String localGt, String text, int networkId,
                              UssdAlphabet alphabet, int mscSsn, int localSsn) {
        niPush(ss7, correlationId, mscGt, localGt, text, networkId, alphabet, false, null,
                mscSsn, localSsn);
    }

    /**
     * NI UnstructuredSS-Request/Notify toward SRI {@code networkNodeNumber} (MSC).
     * {@code imsi} is MAP destReference (land_mobile); {@code notifyOnly} selects Notify vs Request.
     */
    public static void niPush(RaCommandPort ss7, String correlationId,
                              String mscGt, String localGt, String text, int networkId,
                              UssdAlphabet alphabet, boolean notifyOnly, String imsi,
                              int mscSsn, int localSsn) {
        if (ss7 == null) {
            LOG.warn("niPush: no ra-jss7");
            return;
        }
        Ss7Address msc = Ss7Address.of(mscGt == null || mscGt.isBlank() ? "0" : mscGt, mscSsn);
        Ss7Address local = Ss7Address.of(localGt == null || localGt.isBlank() ? "100" : localGt, localSsn);
        int dcs = SmsTextCodec.chooseCbsDataCoding(text, alphabet);
        ss7.sendCommand(new Ss7Command.MapUnstructuredSsRequest(
                correlationId, msc, local, text, networkId, notifyOnly, dcs, imsi));
    }

    /** Resolve local GT/SSN from config for NI/SRI. */
    public static String localGt(UssdConfigService cfg) {
        return cfg == null ? "100" : cfg.ussdGt();
    }

    public static int localSsn(UssdConfigService cfg) {
        return cfg == null ? 8 : cfg.ussdSsn();
    }

    public static int hlrSsn(UssdConfigService cfg) {
        return cfg == null ? 6 : cfg.hlrSsn();
    }

    /**
     * Outbound SRI-SM CalledParty GT — admin overlay {@code ussd.hlr.upper-gt} when non-blank,
     * else application.properties / {@code @ConfigProperty}.
     */
    public static String upperHlrGt(UssdConfigService cfg) {
        return cfg == null ? "" : cfg.hlrUpperGt();
    }

    public static int mscSsn(UssdConfigService cfg) {
        return cfg == null ? 8 : cfg.mscSsn();
    }
}
