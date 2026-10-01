package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionState;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.events.NiPushReadyEvent;
import et.restlink.ussdgw.logging.Pii;
import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.service.MapDialogHelper;
import et.restlink.ussdgw.service.SbbServices;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;

import java.util.List;

/**
 * S2 NI push after SRI — UnstructuredSS-Request/Notify via ra-jss7 toward
 * SRI {@code networkNodeNumber} (MSC), with IMSI destReference (classic + TS 29.002).
 * Same-dialog continue when {@link NiPushReadyEvent#reuseExistingDialog()} is true.
 */
public final class MapNiPushSbb implements Sbb, SleeEventHandler {
    private final SbbServices services;

    @InjectRa(name = "ra-jss7")
    private volatile RaCommandPort ss7;

    public MapNiPushSbb() { this(null); }
    public MapNiPushSbb(SbbServices services) { this.services = services; }
    private SbbServices svc() { return services != null ? services : SbbServices.get(); }

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (!(event instanceof NiPushReadyEvent ni)) return;
        SleeEventTrace.inSbb("MapNiPushSbb", event, Pii.msisdnDetail(ni.msisdn()));
        String detail;
        try {
            detail = push(ni);
            try {
                svc().campaigns().onNiDone(ni.correlationId(), true, null);
            } catch (Throwable ignored) { }
        } catch (Throwable t) {
            detail = "error=" + t.getClass().getSimpleName();
            try {
                svc().saga().onNiFailed(ni.correlationId(), "NI_PUSH_ERROR");
            } catch (Throwable ignored) { }
            try {
                svc().campaigns().onNiDone(ni.correlationId(), false, detail);
            } catch (Throwable ignored) { }
        }
        SleeEventTrace.outSbb("MapNiPushSbb", event, detail);
    }

    private String push(NiPushReadyEvent ni) {
        // P2-4: alphabet-aware truncation (GSM-7 = 182 septets, UCS-2 = 80 chars, UCS-8 = 160 octets).
        // The old hardcoded 200-char substring cut mid-codepoint for UCS-2 and ignored GSM-7 extension chars.
        String text = et.restlink.ussdgw.codec.UssdEncodingPolicy.truncateToFit(ni.text(), ni.alphabet());

        if (ni.reuseExistingDialog()) {
            return continuePush(ni, text);
        }

        var cfg = svc().config();
        String localGt = MapDialogHelper.localGt(cfg);
        var sess = svc().store().get(ni.correlationId());
        // Prefer SRI fields carried on the ready event (classic networkNodeNumber + IMSI).
        String mscGt = ni.mscGt();
        String imsi = ni.imsi();
        if (sess.isPresent()) {
            if (mscGt == null || mscGt.isBlank()) {
                mscGt = sess.get().mscGt();
            }
            if (imsi == null || imsi.isBlank()) {
                imsi = sess.get().imsi();
            }
            if (sess.get().localGt() != null && !sess.get().localGt().isBlank()) {
                localGt = sess.get().localGt();
            }
        }

        boolean ss7Live = false;
        try {
            ss7Live = svc().linkStatus().ss7Live();
        } catch (Throwable ignored) { }

        // Live MAP: MSC must come from SRI networkNodeNumber — never MSISDN/HLR/self.
        // Lab (ss7 down): allow MSISDN fallback so NI park/echo still exercises the path.
        if (mscGt == null || mscGt.isBlank()) {
            if (ss7Live) {
                writeCdr(ni, CdrPhase.FAILED, "NI_NO_MSC", null);
                svc().saga().onNiFailed(ni.correlationId(), "NI_NO_MSC");
                return "ni-no-msc";
            }
            mscGt = ni.msisdn();
        }

        var pin = svc().pickPeerRoute(ni.networkId(), ni.correlationId());
        MapDialogHelper.niPush(ss7, ni.correlationId(), mscGt, localGt, text, ni.networkId(),
                ni.alphabet() == null ? et.restlink.ussdgw.api.UssdAlphabet.AUTO : ni.alphabet(),
                ni.notifyOnly(), imsi,
                MapDialogHelper.mscSsn(cfg), MapDialogHelper.localSsn(cfg),
                pin.preferredAspName(), pin.remotePc());
        writeCdr(ni, CdrPhase.S2_PUSH, ni.notifyOnly() ? "NI_NOTIFY" : "NI_PUSH", text);
        // P2-5: BRIDGED_DONE only when this push came off a bridged (late) row —
        // plain NI was never bridged.
        if (keepOrCompleteSession(ni)) {
            writeCdr(ni, CdrPhase.COMPLETED, "BRIDGED_DONE",
                    "service=MapNiPushSbb|VirtualSessionBridge");
        }
        return "ni-sent msc=" + Pii.maskMsisdn(mscGt)
                + (ni.notifyOnly() ? " notify" : " request")
                + (imsi == null || imsi.isBlank() ? "" : " imsi");
    }

    private String continuePush(NiPushReadyEvent ni, String text) {
        try {
            MapDialogHelper.niContinue(ss7, ni.correlationId(), text,
                    ni.alphabet() == null ? et.restlink.ussdgw.api.UssdAlphabet.AUTO : ni.alphabet(),
                    ni.notifyOnly());
        } catch (Throwable t) {
            org.apache.logging.log4j.LogManager.getLogger(MapNiPushSbb.class)
                    .warn("NI continue no live dialog corr={}: {}",
                            ni.correlationId(), t.toString());
            writeCdr(ni, CdrPhase.FAILED, "NI_CONTINUE_NO_DIALOG", t.getMessage());
            try {
                svc().saga().onNiFailed(ni.correlationId(), "NI_CONTINUE_NO_DIALOG");
            } catch (Throwable ignored) { }
            return "ni-continue-no-dialog";
        }
        writeCdr(ni, CdrPhase.S2_PUSH, ni.notifyOnly() ? "NI_CONTINUE_NOTIFY" : "NI_CONTINUE", text);
        if (keepOrCompleteSession(ni)) {
            writeCdr(ni, CdrPhase.COMPLETED, "BRIDGED_DONE",
                    "service=MapNiPushSbb|VirtualSessionBridge");
        }
        return "ni-continue"
                + (ni.notifyOnly() ? " notify" : " request")
                + (ni.mscGt() == null || ni.mscGt().isBlank()
                ? "" : " msc=" + Pii.maskMsisdn(ni.mscGt()));
    }

    /** Stamp adaptive gate / EWMA onto NI push CDR rows when the session was gated. */
    private void writeCdr(NiPushReadyEvent ni, CdrPhase phase, String status, String detail) {
        var sess = svc().store().get(ni.correlationId());
        Long gate = sess.map(s -> s.gateMs() > 0 ? s.gateMs() : null).orElse(null);
        int networkId = sess.map(VirtualSession::networkId).orElse(ni.networkId());
        String tenant = sess.map(VirtualSession::tenantId).orElse(null);
        String shortCode = sess.map(VirtualSession::shortCode).orElse(null);
        String origin = sess.map(s -> s.originationType() == null
                ? "MAP" : s.originationType().name()).orElse("MAP");
        Long ewma = null;
        try {
            double v = svc().adaptive().observedLatencyMs(networkId);
            if (v > 0d) {
                ewma = Math.round(v);
            }
        } catch (Throwable ignored) { }
        svc().cdr().write(ni.correlationId(), phase, ni.msisdn(), shortCode, status, detail,
                networkId, tenant, origin, gate, ewma);
    }

    /**
     * @return true when the push came off a bridged (late) row — the caller writes
     *         BRIDGED_DONE only then (P2-5: plain NI was never bridged).
     */
    private boolean keepOrCompleteSession(NiPushReadyEvent ni) {
        // HTTP-NI: keep session; AS HTTP stays parked until peer Notify RESULT /
        // MS continue (MapUssdParent) or AdaptiveTimeout gate. Do not completeParked here.
        boolean httpNi = false;
        try {
            httpNi = svc().niHttpPark().isHttpNi(ni.correlationId());
        } catch (Throwable ignored) { }
        String corr = ni.correlationId();
        if (httpNi) {
            // CAS-law: interactive session stays ACTIVE by transition, never detached put.
            // pendingText was already consumed from the event — no rewrite of the row.
            svc().store().compareAndTransitionAny(corr,
                    List.of(VirtualSessionState.PUSH_PENDING, VirtualSessionState.ACTIVE,
                            VirtualSessionState.RESPONDING, VirtualSessionState.AWAITING_AS),
                    VirtualSessionState.ACTIVE);
            return false;
        }
        // Late (bridged) pushes stay PUSH_PENDING for the S2 outcome (P1-5): the
        // S2 CLOSE completes the row, S2 errors retry or fall back. Plain NI pushes
        // complete here exactly as before.
        VirtualSessionState before = svc().store().get(corr)
                .map(VirtualSession::state).orElse(null);
        if (before == VirtualSessionState.PUSH_PENDING
                || before == VirtualSessionState.S1_RELEASED) {
            return true;
        }
        // CAS-law: terminal transition by CAS before drop — a concurrent claim/gate
        // winner must not have its row resurrected under it.
        if (svc().store().compareAndTransitionAny(corr,
                List.of(VirtualSessionState.ACTIVE,
                        VirtualSessionState.AWAITING_AS, VirtualSessionState.RESPONDING),
                VirtualSessionState.COMPLETED).isPresent()) {
            svc().store().setDialogAlive(corr, false);
            svc().store().remove(corr);
        }
        return false;
    }
}
