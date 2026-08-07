package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.events.InboundSriSmEvent;
import et.restlink.ussdgw.hlr.PendingHlrProxyRegistry;
import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.routing.ShortCodeRoutingService;
import et.restlink.ussdgw.service.MapDialogHelper;
import et.restlink.ussdgw.service.SbbServices;
import et.restlink.ussdgw.tenant.TenantGuard;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ra.jss7.event.Ss7MapEvent;

import java.util.Optional;
import java.util.UUID;

import org.restcomm.protocols.ss7.map.api.MAPMessage;
import org.restcomm.protocols.ss7.map.api.MAPMessageType;
import org.restcomm.protocols.ss7.map.api.primitives.IMSI;
import org.restcomm.protocols.ss7.map.api.service.sms.LocationInfoWithLMSI;
import org.restcomm.protocols.ss7.map.api.service.sms.SendRoutingInfoForSMRequest;
import org.restcomm.protocols.ss7.map.api.service.sms.SendRoutingInfoForSMResponse;
import org.restcomm.protocols.ss7.map.api.service.supplementary.ProcessUnstructuredSSRequest;
import org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSResponse;

public final class MapUssdParentSbb implements Sbb, SleeEventHandler {
    private final SbbServices services;

    @InjectRa(name = "ra-jss7")
    private volatile RaCommandPort ss7;

    public MapUssdParentSbb() { this(null); }
    public MapUssdParentSbb(SbbServices services) { this.services = services; }

    private SbbServices svc() { return services != null ? services : SbbServices.get(); }

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (event instanceof Ss7MapEvent.Dialog d) {
            SleeEventTrace.inSbb("MapUssdParentSbb", event, "kind=" + d.kind());
            String detail = "ok";
            try {
                handleDialog(d);
            } catch (Throwable t) {
                detail = "error=" + t.getClass().getSimpleName() + " " + endDialogOnFailure(d);
            }
            SleeEventTrace.outSbb("MapUssdParentSbb", event, detail);
            return;
        }
        if (!(event instanceof Ss7MapEvent.Service svc)) return;
        SleeEventTrace.inSbb("MapUssdParentSbb", event, "type=" + svc.type());
        String detail;
        try {
            detail = handleService(svc);
        } catch (Throwable t) {
            detail = "error=" + t.getClass().getSimpleName() + " " + endDialogOnFailure(svc);
        }
        SleeEventTrace.outSbb("MapUssdParentSbb", event, detail);
    }

    /**
     * A handler that threw leaves the dialog open: the handset waits for the network timer and the
     * dialog leaks. End the MS-facing leg with the hard-fail text, abort anything else.
     */
    private String endDialogOnFailure(Ss7MapEvent.Service svc) {
        String dialogId = svc.dialogId();
        if (dialogId == null || dialogId.isBlank()) return "no-dialog";
        try {
            long invokeId = msFacingInvokeId(svc);
            if (invokeId >= 0) {
                MapDialogHelper.replyAndEnd(ss7, dialogId, invokeId, hardFailMessage());
                return "dialog-ended";
            }
            MapDialogHelper.abort(ss7, dialogId);
            return "dialog-aborted";
        } catch (Throwable t) {
            return "dialog-end-failed=" + t.getClass().getSimpleName();
        } finally {
            markDialogDead(dialogId);
        }
    }

    /**
     * A terminal dialog event (abort/close/release/timeout) means the peer already tore the dialog
     * down — sending an abort back would itself break the MAP state machine.
     */
    private String endDialogOnFailure(Ss7MapEvent.Dialog d) {
        String dialogId = d.dialogId();
        if (dialogId == null || dialogId.isBlank()) return "no-dialog";
        try {
            if (isTerminal(d.kind())) {
                return "dialog-already-terminal";
            }
            MapDialogHelper.abort(ss7, dialogId);
            return "dialog-aborted";
        } catch (Throwable t) {
            return "dialog-end-failed=" + t.getClass().getSimpleName();
        } finally {
            markDialogDead(dialogId);
        }
    }

    private static boolean isTerminal(Ss7MapEvent.Kind kind) {
        return switch (kind) {
            case USER_ABORT, PROVIDER_ABORT, TIMEOUT, CLOSE, RELEASE -> true;
            default -> false;
        };
    }

    /** InvokeId of a leg the handset is waiting on, or {@code -1} when there is none. */
    private static long msFacingInvokeId(Ss7MapEvent.Service svc) {
        MAPMessageType type = svc.type();
        if (type != MAPMessageType.processUnstructuredSSRequest_Request
                && type != MAPMessageType.unstructuredSSRequest_Response) {
            return -1L;
        }
        try {
            MAPMessage msg = svc.message();
            return msg == null ? -1L : msg.getInvokeId();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private String hardFailMessage() {
        try {
            String msg = svc().config().asyncHardFailMessage();
            if (msg != null && !msg.isBlank()) return msg;
        } catch (Throwable ignored) { }
        return "Service temporarily unavailable. Please try again.";
    }

    private void markDialogDead(String dialogId) {
        try {
            svc().store().byDialogId(dialogId).ifPresent(s -> {
                s.setDialogAlive(false);
                svc().store().put(s);
            });
        } catch (Throwable ignored) { }
    }

    private void handleDialog(Ss7MapEvent.Dialog d) {
        switch (d.kind()) {
            case USER_ABORT, PROVIDER_ABORT, TIMEOUT, CLOSE, RELEASE ->
                    svc().bridge().onNetworkAbort(d.dialogId());
            default -> {}
        }
    }

    private String handleService(Ss7MapEvent.Service svc) {
        MAPMessageType type = svc.type();
        if (type == MAPMessageType.processUnstructuredSSRequest_Request
                && svc.message() instanceof ProcessUnstructuredSSRequest req) {
            return onProcessUnstructured(svc.dialogId(), req);
        }
        if (type == MAPMessageType.unstructuredSSRequest_Response
                && svc.message() instanceof UnstructuredSSResponse resp) {
            return onUserContinue(svc.dialogId(), resp);
        }
        if (type == MAPMessageType.sendRoutingInfoForSM_Request
                && svc.message() instanceof SendRoutingInfoForSMRequest sriReq) {
            return onInboundSriRequest(svc, sriReq);
        }
        if (type == MAPMessageType.sendRoutingInfoForSM_Response) {
            return onSriResponse(svc);
        }
        return "ignored type=" + type;
    }

    private String onInboundSriRequest(Ss7MapEvent.Service svc, SendRoutingInfoForSMRequest req) {
        String msisdn = "";
        if (req.getMsisdn() != null && req.getMsisdn().getAddress() != null) {
            msisdn = req.getMsisdn().getAddress();
        }
        String sc = "";
        if (req.getServiceCentreAddress() != null && req.getServiceCentreAddress().getAddress() != null) {
            sc = req.getServiceCentreAddress().getAddress();
        }
        int networkId = 0;
        try {
            if (req.getMAPDialog() != null) {
                networkId = req.getMAPDialog().getNetworkId();
            }
        } catch (Throwable ignored) { }
        InboundSriSmEvent ev = new InboundSriSmEvent(
                svc.dialogId(), req.getInvokeId(), msisdn, sc, networkId);
        svc().container().routeEvent(ev,
                svc().container().createActivityContext("hlr-sri-" + svc.dialogId()));
        return "hlr-face-routed";
    }

    private String onProcessUnstructured(String dialogId, ProcessUnstructuredSSRequest req) {
        String ussd = MapDialogHelper.ussdString(req);
        String shortCode = ShortCodeRoutingService.extractShortCode(ussd);
        String msisdn = MapDialogHelper.msisdnHint(req);
        Optional<ShortCodeRule> rule = svc().routing().find(shortCode);
        long invokeId = req.getInvokeId();
        if (rule.isEmpty()) {
            MapDialogHelper.replyAndEnd(ss7, dialogId, invokeId, "Not a valid short code.");
            return "no-route sc=" + shortCode;
        }
        ShortCodeRule r = rule.get();
        TenantGuard.Decision admit = svc().tenantGuard().admit(r.tenantId());
        if (!admit.allowed()) {
            String msg = admit.reason() == TenantGuard.Reason.RATE_LIMITED
                    ? "Service busy. Please try again."
                    : "Service temporarily unavailable.";
            MapDialogHelper.replyAndEnd(ss7, dialogId, invokeId, msg);
            return "tenant-reject sc=" + shortCode + " reason=" + admit.reason();
        }
        String corr = UUID.randomUUID().toString();
        String reqId = UUID.randomUUID().toString();
        int networkId = r.networkId();
        if (admit.tenant() != null && networkId == 0) {
            networkId = admit.tenant().networkId;
        }
        VirtualSession session = new VirtualSession(
                UUID.randomUUID().toString(), corr, reqId, msisdn, networkId, dialogId, shortCode);
        session.setInvokeId(invokeId);
        session.setDialogAlive(true);
        session.setTenantId(r.tenantId());
        session.setOriginationType(OriginationType.MAP);
        session.setLocalGt(MapDialogHelper.localGt(svc().config()));
        session.setAdaptiveBridgeArm(adaptiveBridgeArmFor(r.ruleType()));
        svc().store().put(session);
        svc().bridge().startAwaitingAs(session);

        AsRequest asReq = new AsRequest(
                session.virtualSessionId(), corr, reqId, session.generation(),
                msisdn, shortCode, ussd, networkId);
        return svc().asPullRouter().route(r, asReq, corr);
    }

    private String onUserContinue(String dialogId, UnstructuredSSResponse resp) {
        Optional<VirtualSession> opt = svc().store().byDialogId(dialogId);
        if (opt.isEmpty()) return "no-session";
        VirtualSession s = opt.get();
        s.setInvokeId(resp.getInvokeId());
        s.setDialogAlive(true);
        String ussd;
        try {
            ussd = resp.getUSSDString() == null ? "" : resp.getUSSDString().getString(null);
        } catch (Exception e) {
            ussd = "";
        }
        // Classic HTTP-NI sync: MS digits complete the parked AS HTTP (not AS pull).
        if (svc().niHttpPark().isHttpNi(s.correlationId())) {
            s.nextGeneration();
            svc().store().put(s);
            boolean done = svc().niHttpPark().completeParked(
                    s.correlationId(), ussd, et.restlink.ussdgw.api.AsAction.CONTINUE);
            return done ? "http-ni-ms-continue gen=" + s.generation()
                    : "http-ni-no-park gen=" + s.generation();
        }
        Optional<ShortCodeRule> rule = svc().routing().find(s.shortCode());
        if (rule.isEmpty()) {
            MapDialogHelper.replyAndEnd(ss7, dialogId, resp.getInvokeId(), "Session ended.");
            return "no-rule";
        }
        ShortCodeRule r = rule.get();
        TenantGuard.Decision admit = svc().tenantGuard().admit(s.tenantId() != null ? s.tenantId() : r.tenantId());
        if (!admit.allowed()) {
            MapDialogHelper.replyAndEnd(ss7, dialogId, resp.getInvokeId(),
                    admit.reason() == TenantGuard.Reason.RATE_LIMITED
                            ? "Service busy. Please try again."
                            : "Service temporarily unavailable.");
            return "tenant-reject continue reason=" + admit.reason();
        }
        s.nextGeneration();
        s.setAdaptiveBridgeArm(adaptiveBridgeArmFor(r.ruleType()));
        svc().bridge().startAwaitingAs(s);
        AsRequest asReq = new AsRequest(
                s.virtualSessionId(), s.correlationId(), s.requestId(), s.generation(),
                s.msisdn(), s.shortCode(), ussd, s.networkId());
        return svc().asPullRouter().route(r, asReq, s.correlationId()) + " continue gen=" + s.generation();
    }

    /**
     * Strictly correlated: an SRI-SM Response is matched to the outbound leg that asked for it.
     * With no match there is no session to resolve — the TTL sweep on either registry fails the
     * corresponding saga. Guessing a pending entry here cross-wires subscribers.
     */
    private String onSriResponse(Ss7MapEvent.Service svc) {
        String corr = svc.dialogId();
        var proxy = svc().pendingHlrProxy().take(corr);
        if (proxy.isPresent()) {
            return relayHlrProxy(svc, proxy.get());
        }

        var niOpt = svc().pendingSri().take(corr);
        if (niOpt.isPresent()) {
            var ni = niOpt.get();
            if (svc.message() instanceof SendRoutingInfoForSMResponse) {
                SriSbb.handoff(svc(), ni);
                return "sri-ok";
            }
            svc().cdr().write(ni.correlationId(), CdrPhase.FAILED, ni.msisdn(), null, "SRI_FAIL", null);
            svc().saga().onNiFailed(ni.correlationId(), "SRI_FAIL");
            try {
                svc().campaigns().onNiDone(ni.correlationId(), false, "SRI_FAIL");
            } catch (Throwable ignored) { }
            return "sri-fail";
        }
        return "sri-no-pending corr=" + corr;
    }

    private String relayHlrProxy(Ss7MapEvent.Service svc, PendingHlrProxyRegistry.Pending pending) {
        String imsi = null;
        String msc = null;
        byte[] lmsi = null;
        if (svc.message() instanceof SendRoutingInfoForSMResponse rsp) {
            IMSI i = rsp.getIMSI();
            if (i != null) imsi = i.getData();
            LocationInfoWithLMSI loc = rsp.getLocationInfoWithLMSI();
            if (loc != null && loc.getNetworkNodeNumber() != null) {
                msc = loc.getNetworkNodeNumber().getAddress();
            }
            if (loc != null && loc.getLMSI() != null) {
                lmsi = loc.getLMSI().getData();
            }
        }
        return svc().hlrFace().relayUpperResponse(pending, imsi, msc, lmsi, ss7);
    }

    private boolean adaptiveBridgeArmFor(RuleType type) {
        if (type == RuleType.HTTP) {
            return svc().config().httpClientBridgeEnabled();
        }
        if (type == RuleType.GRPC) {
            return svc().config().grpcClientBridgeEnabled();
        }
        // SIP (and any future plane): global bridge flag — reply correlation is best-effort.
        return svc().config().bridgeEnabled();
    }
}
