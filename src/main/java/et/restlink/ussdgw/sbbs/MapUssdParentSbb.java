package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.cdr.CdrPhase;
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

import org.restcomm.protocols.ss7.map.api.MAPMessageType;
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
                detail = "error=" + t.getClass().getSimpleName();
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
            detail = "error=" + t.getClass().getSimpleName();
        }
        SleeEventTrace.outSbb("MapUssdParentSbb", event, detail);
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
        if (type == MAPMessageType.sendRoutingInfoForSM_Response) {
            return onSriResponse(svc);
        }
        return "ignored type=" + type;
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
        session.setAdaptiveBridgeArm(r.ruleType() == RuleType.HTTP
                ? svc().config().httpClientBridgeEnabled()
                : svc().config().grpcClientBridgeEnabled());
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
        s.setAdaptiveBridgeArm(r.ruleType() == RuleType.HTTP
                ? svc().config().httpClientBridgeEnabled()
                : svc().config().grpcClientBridgeEnabled());
        svc().bridge().startAwaitingAs(s);
        AsRequest asReq = new AsRequest(
                s.virtualSessionId(), s.correlationId(), s.requestId(), s.generation(),
                s.msisdn(), s.shortCode(), ussd, s.networkId());
        return svc().asPullRouter().route(r, asReq, s.correlationId()) + " continue gen=" + s.generation();
    }

    private String onSriResponse(Ss7MapEvent.Service svc) {
        var niOpt = svc().pendingSri().take(svc.dialogId());
        if (niOpt.isEmpty()) return "sri-no-pending";
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
}
