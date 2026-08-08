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
        String text = ni.text();
        if (text != null && text.length() > 200) text = text.substring(0, 200);

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

        MapDialogHelper.niPush(ss7, ni.correlationId(), mscGt, localGt, text, ni.networkId(),
                ni.alphabet() == null ? et.restlink.ussdgw.api.UssdAlphabet.AUTO : ni.alphabet(),
                ni.notifyOnly(), imsi,
                MapDialogHelper.mscSsn(cfg), MapDialogHelper.localSsn(cfg));
        writeCdr(ni, CdrPhase.S2_PUSH, ni.notifyOnly() ? "NI_NOTIFY" : "NI_PUSH", text);
        keepOrCompleteSession(ni);
        writeCdr(ni, CdrPhase.COMPLETED, "BRIDGED_DONE",
                "service=MapNiPushSbb|VirtualSessionBridge");
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
        keepOrCompleteSession(ni);
        writeCdr(ni, CdrPhase.COMPLETED, "BRIDGED_DONE",
                "service=MapNiPushSbb|VirtualSessionBridge");
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

    private void keepOrCompleteSession(NiPushReadyEvent ni) {
        // HTTP-NI: keep session; AS HTTP stays parked until peer Notify RESULT /
        // MS continue (MapUssdParent) or AdaptiveTimeout gate. Do not completeParked here.
        boolean httpNi = false;
        try {
            httpNi = svc().niHttpPark().isHttpNi(ni.correlationId());
        } catch (Throwable ignored) { }
        boolean keepHttpNi = httpNi;
        svc().store().get(ni.correlationId()).ifPresent(s -> {
            if (keepHttpNi) {
                s.setState(VirtualSessionState.ACTIVE);
                s.setPendingText(null);
                svc().store().put(s);
            } else {
                s.setState(VirtualSessionState.COMPLETED);
                s.setPendingText(null);
                s.setDialogAlive(false);
                // Profile get() returns a detached snapshot — must write-through then drop.
                svc().store().put(s);
                svc().store().remove(s.correlationId());
            }
        });
    }
}
