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

    /**
     * Same-dialog NI continue — Request/Notify on the live MAP dialog keyed by correlation id.
     * Does not create a new dialog or re-run SRI.
     */
    public static void niContinue(RaCommandPort ss7, String correlationId, String text,
                                  UssdAlphabet alphabet, boolean notifyOnly) {
        if (ss7 == null) {
            LOG.warn("niContinue: no ra-jss7");
            return;
        }
        int dcs = SmsTextCodec.chooseCbsDataCoding(text, alphabet == null ? UssdAlphabet.AUTO : alphabet);
        ss7.sendCommand(new Ss7Command.MapUnstructuredSsContinue(
                correlationId, text, notifyOnly, dcs));
    }

    /**
     * Close the live MAP dialog for this correlation ({@code prearrangedEnd} per classic XML).
     */
    public static void niClose(RaCommandPort ss7, String correlationId, boolean prearrangedEnd) {
        if (ss7 == null) {
            LOG.warn("niClose: no ra-jss7");
            return;
        }
        ss7.sendCommand(new Ss7Command.MapDialogClose(correlationId, prearrangedEnd));
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
     * Case 2 MAP2MAP hop must use {@link #map2mapProcessHop} (opcode 59), not this path.
     */
    public static void niPush(RaCommandPort ss7, String correlationId,
                              String mscGt, String localGt, String text, int networkId,
                              UssdAlphabet alphabet, boolean notifyOnly, String imsi,
                              int mscSsn, int localSsn) {
        niPush(ss7, correlationId, mscGt, localGt, text, networkId, alphabet, notifyOnly, imsi,
                mscSsn, localSsn, null, -1);
    }

    /**
     * NI push with optional N–N sticky ASP / peer PC pin (from {@code Ss7PeerRouteAffinity}).
     */
    public static void niPush(RaCommandPort ss7, String correlationId,
                              String mscGt, String localGt, String text, int networkId,
                              UssdAlphabet alphabet, boolean notifyOnly, String imsi,
                              int mscSsn, int localSsn, String preferredAspName, int remotePc) {
        if (ss7 == null) {
            LOG.warn("niPush: no ra-jss7");
            return;
        }
        Ss7Address msc = Ss7Address.of(mscGt == null || mscGt.isBlank() ? "0" : mscGt, mscSsn);
        Ss7Address local = Ss7Address.of(localGt == null || localGt.isBlank() ? "100" : localGt, localSsn);
        int dcs = SmsTextCodec.chooseCbsDataCoding(text, alphabet);
        // preferredAsp/remotePc require ra-jss7 with pin ctor — Digicom classpath may lag; ignore pins.
        if (preferredAspName != null && !preferredAspName.isBlank()) {
            LOG.debug("niPush: peer-route pin ignored asp={} pc={}", preferredAspName, remotePc);
        }
        ss7.sendCommand(new Ss7Command.MapUnstructuredSsRequest(
                correlationId, msc, local, text, networkId, notifyOnly, dcs, imsi, null, false));
    }

    /**
     * Case 2 MAP2MAP hop (Ethio Brook wire): {@code processUnstructuredSS-Request} (opcode 59)
     * toward hop dest GT/SSN; Calling SSN typically 6; MAP destReference + component = MSISDN.
     */
    public static void map2mapProcessHop(RaCommandPort ss7, String correlationId,
                                         String hopGt, String localGt, String text, int networkId,
                                         UssdAlphabet alphabet, String msisdn,
                                         int hopSsn, int localSsn) {
        map2mapProcessHop(ss7, correlationId, hopGt, localGt, text, networkId, alphabet, msisdn,
                hopSsn, localSsn, null, -1);
    }

    public static void map2mapProcessHop(RaCommandPort ss7, String correlationId,
                                         String hopGt, String localGt, String text, int networkId,
                                         UssdAlphabet alphabet, String msisdn,
                                         int hopSsn, int localSsn,
                                         String preferredAspName, int remotePc) {
        if (ss7 == null) {
            LOG.warn("map2mapProcessHop: no ra-jss7");
            return;
        }
        Ss7Address hop = Ss7Address.of(hopGt == null || hopGt.isBlank() ? "0" : hopGt, hopSsn);
        Ss7Address local = Ss7Address.of(localGt == null || localGt.isBlank() ? "100" : localGt, localSsn);
        int dcs = SmsTextCodec.chooseCbsDataCoding(text, alphabet == null ? UssdAlphabet.AUTO : alphabet);
        if (preferredAspName != null && !preferredAspName.isBlank()) {
            LOG.debug("map2mapProcessHop: peer-route pin ignored asp={} pc={}", preferredAspName, remotePc);
        }
        ss7.sendCommand(new Ss7Command.MapUnstructuredSsRequest(
                correlationId, hop, local, text, networkId, false, dcs, null, msisdn, true));
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
