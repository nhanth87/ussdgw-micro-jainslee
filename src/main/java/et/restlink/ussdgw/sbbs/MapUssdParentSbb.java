package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.classic.ClassicNiHttpPark;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionState;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.Map2MapCdr;
import et.restlink.ussdgw.events.InboundSriSmEvent;
import et.restlink.ussdgw.events.Map2MapRequestEvent;
import et.restlink.ussdgw.hlr.PendingHlrProxyRegistry;
import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.routing.ShortCodeRoutingService;
import et.restlink.ussdgw.service.MapDialogHelper;
import et.restlink.ussdgw.service.PendingMap2MapRegistry;
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
import org.restcomm.protocols.ss7.map.api.service.supplementary.ProcessUnstructuredSSResponse;
import org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSNotifyResponse;
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
            SleeEventTrace.inSbb("MapUssdParentSbb", event,
                    "kind=" + d.kind() + " dialogId=" + d.dialogId());
            String detail = "ok";
            try {
                detail = handleDialog(d);
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
            case USER_ABORT, PROVIDER_ABORT, TIMEOUT, CLOSE, RELEASE, REJECT -> true;
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
        return "ማው ማውማው ማውማው ማውማው ማው";
    }

    private void markDialogDead(String dialogId) {
        try {
            svc().store().byDialogId(dialogId).ifPresent(s -> {
                s.setDialogAlive(false);
                svc().store().put(s);
            });
        } catch (Throwable ignored) { }
    }

    /**
     * @return slee OUT detail (must expose REJECT→AS sync for Digicom prove)
     */
    private String handleDialog(Ss7MapEvent.Dialog d) {
        switch (d.kind()) {
            // CLOSE/RELEASE on outbound hop: TC-END often fires Dialog before Service RESULT.
            // Defer empty AS so processUnstructuredSS-Response can claim hop text first.
            case CLOSE, RELEASE -> {
                var pending = svc().pendingMap2Map().peek(d.dialogId());
                if (pending.isPresent()) {
                    String outbound = d.dialogId();
                    String kind = d.kind().name();
                    String detail = d.detail();
                    svc().map2MapCompletion().deferHopClose(outbound, () -> {
                        var m2m = svc().pendingMap2Map().take(outbound);
                        if (m2m.isPresent()) {
                            onMap2MapDialogLost(m2m.get().req(), kind, detail);
                        }
                    });
                    return "map2map-hop-close-deferred dialogId=" + outbound;
                }
                return handleInboundOrAbort(d, false);
            }
            // REJECT / Abort / TIMEOUT: Digicom hop peer refuse — take pending + AS immediately.
            case USER_ABORT, PROVIDER_ABORT, TIMEOUT, REJECT -> {
                svc().map2MapCompletion().cancelDeferredHopClose(d.dialogId());
                var m2m = svc().pendingMap2Map().take(d.dialogId());
                if (m2m.isPresent()) {
                    return onMap2MapDialogLost(m2m.get().req(), d.kind().name(), d.detail());
                }
                return handleInboundOrAbort(d, d.kind() == Ss7MapEvent.Kind.REJECT);
            }
            default -> {
                return "ok kind=" + d.kind();
            }
        }
    }

    /** Inbound MO dialog end / abort when outbound hop key was not present. */
    private String handleInboundOrAbort(Ss7MapEvent.Dialog d, boolean rejectMissSurface) {
        Optional<VirtualSession> inbound = svc().store().byDialogId(d.dialogId());
        if (inbound.isPresent()) {
            VirtualSession s = inbound.get();
            boolean hardAbort = d.kind() == Ss7MapEvent.Kind.USER_ABORT
                    || d.kind() == Ss7MapEvent.Kind.PROVIDER_ABORT
                    || d.kind() == Ss7MapEvent.Kind.TIMEOUT;
            // Gate already replyAndEnd'd async-wait — CLOSE/RELEASE is expected.
            // Keep pending MAP2MAP hop + S1_RELEASED so hop response / hop-lost
            // can still AsPullRouter → webhook (never zombie here).
            if (!hardAbort && isBridgedStayOnCall(s)) {
                return "bridged-stay dialogId=" + d.dialogId();
            }
            String outbound = PendingMap2MapRegistry.outboundCorr(s.correlationId());
            svc().map2MapCompletion().cancelDeferredHopClose(outbound);
            svc().pendingMap2Map().take(outbound);
        } else if (rejectMissSurface) {
            return "map2map-reject-miss dialogId=" + d.dialogId()
                    + " pending=" + svc().pendingMap2Map().size();
        }
        svc().bridge().onNetworkAbort(d.dialogId());
        return "network-abort dialogId=" + d.dialogId();
    }

    private void clearMap2mapHopOutstanding(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return;
        }
        try {
            svc().store().get(correlationId).ifPresent(s -> {
                if (s.map2mapHopOutstanding()) {
                    s.setMap2mapHopOutstanding(false);
                    svc().store().put(s);
                }
            });
        } catch (Throwable ignored) { }
    }

    /** UE already got async-wait / late-reconcile path — inbound TC-END is not a network abort. */
    private static boolean isBridgedStayOnCall(VirtualSession s) {
        if (s == null || s.state() == null) {
            return false;
        }
        VirtualSessionState st = s.state();
        return st == VirtualSessionState.S1_RELEASED
                || st == VirtualSessionState.PUSH_PENDING
                || st == VirtualSessionState.RESPONDING;
    }

    private String handleService(Ss7MapEvent.Service svc) {
        MAPMessageType type = svc.type();
        if (type == MAPMessageType.processUnstructuredSSRequest_Request
                && svc.message() instanceof ProcessUnstructuredSSRequest req) {
            return onProcessUnstructured(svc.dialogId(), req);
        }
        // Case 2 hop RESULT: Ethio uses processUnstructuredSS-Response (op 59 pair);
        // keep unstructuredSSRequest_Response for legacy NI-style hops.
        if (type == MAPMessageType.processUnstructuredSSRequest_Response
                && svc.message() instanceof ProcessUnstructuredSSResponse procRsp) {
            svc().map2MapCompletion().cancelDeferredHopClose(svc.dialogId());
            var m2m = svc().pendingMap2Map().takeIfPhase(svc.dialogId(),
                    et.restlink.ussdgw.service.PendingMap2MapRegistry.Phase.AWAITING_USSD);
            if (m2m.isPresent()) {
                return onMap2MapHopResponse(m2m.get().req(), ussdText(procRsp));
            }
            return "ignored processUnstructuredSS-Response no-pending";
        }
        if (type == MAPMessageType.unstructuredSSRequest_Response
                && svc.message() instanceof UnstructuredSSResponse resp) {
            svc().map2MapCompletion().cancelDeferredHopClose(svc.dialogId());
            var m2m = svc().pendingMap2Map().takeIfPhase(svc.dialogId(),
                    et.restlink.ussdgw.service.PendingMap2MapRegistry.Phase.AWAITING_USSD);
            if (m2m.isPresent()) {
                return onMap2MapHopResponse(m2m.get().req(), ussdText(resp));
            }
            return onUserContinue(svc.dialogId(), resp);
        }
        if (type == MAPMessageType.unstructuredSSNotify_Response) {
            return onNotifyResponse(svc.dialogId(), svc.message());
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
        // jSS7 / IES can deliver the same processUnstructured component twice on one dialog.
        // Second pass must not open a second ussdTx / dual AS POST.
        if (dialogId != null && !dialogId.isBlank()) {
            Optional<VirtualSession> existing = svc().store().byDialogId(dialogId);
            if (existing.isPresent()) {
                return "dup-skip dialog=" + dialogId + " corr=" + existing.get().correlationId();
            }
        }
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
        // Prefer SCCP/MAP dialog networkId (Digicom lab sim plane=1, live BP=0).
        // Short-code / tenant network_id stays the live-routing key and must not
        // overwrite a non-zero dialog plane — else lab MO replies GTT on net 0.
        int networkId = 0;
        try {
            if (req.getMAPDialog() != null) {
                networkId = req.getMAPDialog().getNetworkId();
            }
        } catch (Throwable ignored) { }
        if (networkId == 0) {
            networkId = r.networkId();
            if (admit.tenant() != null && networkId == 0) {
                networkId = admit.tenant().networkId;
            }
        }
        VirtualSession session = new VirtualSession(
                UUID.randomUUID().toString(), corr, reqId, msisdn, networkId, dialogId, shortCode);
        session.setInvokeId(invokeId);
        session.setDialogAlive(true);
        session.setTenantId(r.tenantId());
        session.setOriginationType(OriginationType.MAP);
        session.setLocalGt(MapDialogHelper.localGt(svc().config()));
        // Persist dialed for digit continues — AS pull ussdString=digit, originatedUssd stays MO dial.
        session.setOriginatedUssd(ussd);
        if (r.map2mapArmed()) {
            session.setRedirectUssd(r.redirectUssdString());
        }

        if (r.map2mapArmed()) {
            // RE_ROUTE order: hop to upper HLR first; arm AdaptiveTimeout only after hop
            // USSD is sent (countdown for HLR+AS). AS pull runs on hop result — no early
            // "hlr pending". Gate fires (BRIDGED) only if still waiting when budget elapses.
            session.setAdaptiveBridgeArm(true);
            svc().store().put(session);
            try {
                svc().map2MapTelemetry().armed();
            } catch (Throwable ignored) { }
            String outboundCorr = PendingMap2MapRegistry.outboundCorr(corr);
            Map2MapRequestEvent m2m = new Map2MapRequestEvent(
                    corr, outboundCorr, dialogId, invokeId, msisdn, shortCode, ussd,
                    r.redirectUssdString(), r.asUrl(), r.asPullType(), networkId, r.tenantId(),
                    session.virtualSessionId(), reqId, r.mark(), r.hlrMode(),
                    r.hopDestGt(), r.hopDestSsn());
            try {
                svc().cdr().write(corr, CdrPhase.S1_ACTIVE, msisdn, shortCode,
                        Map2MapCdr.ARMED, Map2MapCdr.detailArmed(m2m, session),
                        networkId, r.tenantId(), "MAP", null, null);
            } catch (Throwable ignored) { }
            svc().container().routeEvent(m2m,
                    svc().container().createActivityContext("map2map-" + corr));
            String hopPreview = resolveHopUssdForReq(svc(), m2m);
            return "map2map-queued sc=" + shortCode + " redirect=" + r.redirectUssdString()
                    + " hopUssd=" + hopPreview
                    + (r.fixedHopArmed()
                    ? (" hopGt=" + r.hopDestGtDigits() + " hopSsn=" + r.effectiveHopDestSsn())
                    : " hop=upper-gt");
        }

        session.setAdaptiveBridgeArm(adaptiveBridgeArmFor(r.ruleType()));
        svc().store().put(session);
        svc().bridge().startAwaitingAs(session);

        AsRequest asReq = new AsRequest(
                session.virtualSessionId(), corr, reqId, session.generation(),
                msisdn, shortCode, ussd, networkId)
                .withOriginated(ussd, r.codeKindForDial(ussd));
        return svc().asPullRouter().route(r, asReq, corr);
    }

    /**
     * Outbound MAP2MAP hop result (processUnstructuredSS-Response or UnstructuredSS-Response)
     * — must sync-pull AS via {@code AsPullRouter} before UE final reply.
     */
    private String onMap2MapHopResponse(Map2MapRequestEvent req, String hop) {
        if (hop == null) {
            hop = "";
        }
        // Hop USSD Response = terminal for outbound dialog; clear MO hold before AS.
        clearMap2mapHopOutstanding(req.correlationId());
        // Close outbound dialog (prearranged) — inbound MO dialog stays open for AS reply.
        try {
            MapDialogHelper.niClose(ss7, req.outboundCorr(), true);
        } catch (Throwable ignored) { }
        String routed = svc().map2MapCompletion().onMap2MapResponse(req, hop);
        SleeEventTrace.outSbb("MapUssdParentSbb", null,
                "map2map-hop-result-as-sync hopLen=" + hop.length() + " " + routed);
        return routed;
    }

    private static String ussdText(ProcessUnstructuredSSResponse resp) {
        try {
            return resp == null || resp.getUSSDString() == null
                    ? "" : resp.getUSSDString().getString(null);
        } catch (Exception e) {
            return "";
        }
    }

    private static String ussdText(UnstructuredSSResponse resp) {
        try {
            return resp == null || resp.getUSSDString() == null
                    ? "" : resp.getUSSDString().getString(null);
        } catch (Exception e) {
            return "";
        }
    }

    /** @return slee OUT detail including AS route result (RE_ROUTE hop lost → hlr reject/none AS). */
    private String onMap2MapDialogLost(Map2MapRequestEvent req, String kind, String refuseDetail) {
        // Hop Abort/Reject/Timeout IS the hop response (TCAP, often invisible under gsm_map
        // filter). Clear MO hold before AS — never replyAndEnd MO while hop still outstanding.
        clearMap2mapHopOutstanding(req.correlationId());
        // Gate may already have released the UE with async-wait — never double replyAndEnd.
        var session = svc().store().get(req.correlationId()).orElse(null);
        boolean alreadyBridged = session != null && (!session.dialogAlive()
                || isBridgedStayOnCall(session)
                || session.state().terminal());
        String outcome = Map2MapCdr.hopOutcomeForDialogLost(kind);
        String status = Map2MapCdr.statusForDialogLost(kind, alreadyBridged);
        // Hop USSD text → HOP_CLOSE + S1_ACTIVE (amber). No text / reject / abort / timer → FAILED.
        et.restlink.ussdgw.cdr.CdrPhase lostPhase = Map2MapCdr.HOP_CLOSE.equals(status)
                ? et.restlink.ussdgw.cdr.CdrPhase.S1_ACTIVE
                : et.restlink.ussdgw.cdr.CdrPhase.FAILED;
        try {
            svc().cdr().write(req.correlationId(),
                    lostPhase, req.msisdn(), req.shortCode(),
                    status,
                    Map2MapCdr.detail(req, "kind=" + kind,
                            "hopOutcome=" + outcome,
                            refuseDetail == null || refuseDetail.isBlank()
                                    ? null : ("refuseReason=" + refuseDetail.trim()),
                            "gateRole=budget",
                            alreadyBridged ? "phase=after-bridge" : "phase=hop"),
                    req.networkId(), req.tenantId(), "MAP",
                    Map2MapCdr.gateMs(session), null);
        } catch (Throwable ignored) { }
        try {
            // hopTimeout / timeoutAfterBridge = real timers only (not CLOSE/REJECT/abort).
            if (Map2MapCdr.isTimerDialogLost(kind)) {
                if (alreadyBridged) {
                    svc().map2MapTelemetry().timeoutAfterBridge();
                } else {
                    svc().map2MapTelemetry().hopTimeout();
                }
            } else {
                svc().map2MapTelemetry().failClosed();
            }
        } catch (Throwable ignored) { }
        // RE_ROUTE: still AsPullRouter with string=hlr reject|empty (hlrResult=none) (no second GATED).
        String routed;
        try {
            routed = svc().map2MapCompletion().onMap2MapResponse(req, "", outcome);
        } catch (Throwable t) {
            routed = "map2map-as-ex=" + t.getClass().getSimpleName();
        }
        if (routed != null && routed.startsWith("map2map-ok")) {
            if (alreadyBridged) {
                markDialogDead(req.inboundDialogId());
            }
            // Else keep inbound MO dialog open for AS reply.
            return "map2map-hop-lost-as-sync kind=" + kind + " " + routed;
        }
        if (alreadyBridged) {
            markDialogDead(req.inboundDialogId());
            return "map2map-hop-lost-as-fail kind=" + kind + " bridged routed=" + routed;
        }
        try {
            MapDialogHelper.replyAndEnd(ss7, req.inboundDialogId(), req.inboundInvokeId(),
                    hardFailMessage());
        } catch (Throwable ignored) { }
        markDialogDead(req.inboundDialogId());
        return "map2map-hop-lost-hard-fail kind=" + kind + " routed=" + routed;
    }

    /**
     * Peer MAP Notify RESULT (TS 23.090 §5.2.5 / 29.002 §11.11) — classic
     * {@code HttpServerSbb.onUnstructuredSSNotifyResponse} → parked HTTP with Notify_Response.
     * Settles AdaptiveTimeout park early; keeps JSESSIONID for AS END. Bridge stays on top.
     */
    private String onNotifyResponse(String dialogId, MAPMessage msg) {
        Optional<VirtualSession> opt = resolveNiSession(dialogId);
        if (opt.isEmpty()) {
            return "notify-ack-no-session";
        }
        VirtualSession s = opt.get();
        if (msg instanceof UnstructuredSSNotifyResponse ntfy) {
            try {
                s.setInvokeId(ntfy.getInvokeId());
            } catch (Throwable ignored) { }
        }
        s.setDialogAlive(true);
        svc().store().put(s);
        if (!svc().niHttpPark().isHttpNi(s.correlationId())) {
            return "notify-ack-not-http-ni";
        }
        var parkRec = svc().niHttpPark().findByCorr(s.correlationId());
        var format = parkRec.map(ClassicNiHttpPark.ParkRecord::format)
                .orElse(AsHttpWireFormat.XML);
        String body = svc().wireFacade().encodeNiNotifyResponse(s.correlationId(), format);
        boolean done = svc().niHttpPark().completeParkedEncoded(s.correlationId(), body);
        return done ? "http-ni-notify-ack" : "http-ni-notify-no-park";
    }

    /** NI events publish dialogId = correlationId (ra-jss7 ussd correlate). */
    private Optional<VirtualSession> resolveNiSession(String dialogId) {
        if (dialogId == null || dialogId.isBlank()) {
            return Optional.empty();
        }
        Optional<VirtualSession> byDialog = svc().store().byDialogId(dialogId);
        if (byDialog.isPresent()) {
            return byDialog;
        }
        return svc().store().get(dialogId);
    }

    private String onUserContinue(String dialogId, UnstructuredSSResponse resp) {
        Optional<VirtualSession> opt = resolveNiSession(dialogId);
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
        // Digit in ussdString only — never overwrite originatedUssd with the digit alone.
        String originated = blankToFallback(s.originatedUssd(), s.shortCode());
        AsRequest asReq = new AsRequest(
                s.virtualSessionId(), s.correlationId(), s.requestId(), s.generation(),
                s.msisdn(), s.shortCode(), ussd, s.networkId())
                .withOriginated(originated, r.codeKindForDial(originated));
        if (notBlank(s.redirectUssd()) || notBlank(s.hopUssd())) {
            asReq = asReq.withMap2MapCodes(
                    blankToNull(s.redirectUssd()), blankToNull(s.hopUssd()));
        }
        return svc().asPullRouter().route(r, asReq, s.correlationId()) + " continue gen=" + s.generation();
    }

    private static String blankToFallback(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return fallback == null || fallback.isBlank() ? null : fallback.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Strictly correlated: an SRI-SM Response is matched to the outbound leg that asked for it.
     * With no match there is no session to resolve — the TTL sweep on either registry fails the
     * corresponding saga. Guessing a pending entry here cross-wires subscribers.
     *
     * <p>Classic {@code HttpServerSbb.onSRIResult}: store IMSI + {@code LocationInfoWithLMSI},
     * then open NI USSD dialog toward {@code networkNodeNumber} (MSC) with destReference=IMSI.
     * Missing MSC → fail-closed (never push toward MSISDN / HLR / self).
     */
    private String onSriResponse(Ss7MapEvent.Service svc) {
        String corr = svc.dialogId();
        var proxy = svc().pendingHlrProxy().take(corr);
        if (proxy.isPresent()) {
            return relayHlrProxy(svc, proxy.get());
        }

        // MAP2MAP SRI (before NI pending) — same MSC/IMSI extract as NI, then USSD to MSC.
        var m2mSri = svc().pendingMap2Map().takeIfPhase(corr,
                et.restlink.ussdgw.service.PendingMap2MapRegistry.Phase.AWAITING_SRI);
        if (m2mSri.isPresent()) {
            if (svc.message() instanceof SendRoutingInfoForSMResponse rsp) {
                return applyMap2MapSriResult(m2mSri.get().req(), rsp);
            }
            failMap2MapAfterSri(m2mSri.get().req(), "MAP2MAP_SRI_FAIL");
            return "map2map-sri-fail";
        }

        var niOpt = svc().pendingSri().take(corr);
        if (niOpt.isPresent()) {
            var ni = niOpt.get();
            if (svc.message() instanceof SendRoutingInfoForSMResponse rsp) {
                return applyNiSriResult(ni, rsp);
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

    /**
     * After MAP2MAP SRI: UnstructuredSS-Request toward MSC with IMSI (NI pattern) and
     * USSD string = {@code map2mapGt} ({@code *8744#}). Never CalledParty=MSISDN when live.
     */
    String applyMap2MapSriResult(Map2MapRequestEvent req, SendRoutingInfoForSMResponse rsp) {
        Optional<SriNiRouting> routing = extractSriNiRouting(rsp);
        if (routing.isEmpty()) {
            failMap2MapAfterSri(req, "MAP2MAP_SRI_NO_MSC");
            return "map2map-sri-no-msc";
        }
        return applyMap2MapRouting(ss7, svc(), req, routing.get());
    }

    /**
     * Shared MAP2MAP USSD hop after MSC+IMSI are known (real SRI or HLR Face FAKE).
     * Package-private for {@link Map2MapSbb} FAKE path.
     */
    static String applyMap2MapRouting(RaCommandPort ss7, SbbServices svc, Map2MapRequestEvent req,
                                      SriNiRouting r) {
        if (svc == null || req == null || r == null || r.mscGt() == null || r.mscGt().isBlank()) {
            return "map2map-sri-no-msc";
        }
        svc.store().get(req.correlationId()).ifPresent(s -> {
            s.setMscGt(r.mscGt());
            s.setImsi(r.imsi());
            s.setLmsi(r.lmsi());
            svc.store().put(s);
        });
        svc.pendingMap2Map().putUssd(req.outboundCorr(), req, r.mscGt(), r.imsi());
        String ussdCode = resolveHopUssdForReq(svc, req);
        var cfg = svc.config();
        String localGt = MapDialogHelper.localGt(cfg);
        // Live GTT plane — never inherit lab MO networkId (Digicom lab=1, BP=0).
        int hopNetworkId = map2mapHopNetworkId(cfg, req);
        var pin = svc.pickPeerRoute(hopNetworkId, req.outboundCorr());
        MapDialogHelper.niPush(ss7, req.outboundCorr(), r.mscGt(), localGt, ussdCode,
                hopNetworkId, et.restlink.ussdgw.api.UssdAlphabet.AUTO, false, r.imsi(),
                MapDialogHelper.mscSsn(cfg), MapDialogHelper.localSsn(cfg),
                pin.preferredAspName(), pin.remotePc());
        armGateAfterHopSent(svc, req);
        try {
            Long gate = Map2MapCdr.gateMs(svc.store().get(req.correlationId()).orElse(null));
            svc.cdr().write(req.correlationId(), CdrPhase.S1_ACTIVE, req.msisdn(), req.shortCode(),
                    Map2MapCdr.USSD_SENT,
                    Map2MapCdr.detail(req, "msc=" + r.mscGt(), "code=" + ussdCode,
                            "hopUssd=" + ussdCode, "path=sri-or-fake",
                            "hopNet=" + hopNetworkId),
                    req.networkId(), req.tenantId(), "MAP", gate, null);
        } catch (Throwable ignored) { }
        return "map2map-ussd-sent msc=" + r.mscGt() + " code=" + ussdCode
                + " hopNet=" + hopNetworkId;
    }

    /**
     * Fixed hop dest (SP peer): processUnstructuredSS-Request (op 59) toward configured GT/SSN
     * with redirect USSD string — Ethio Brook shape (MSISDN destRef + component; Calling SSN 6).
     * Does not use MSC SSN; default peer SSN is 6.
     */
    static String applyMap2MapFixedHop(RaCommandPort ss7, SbbServices svc, Map2MapRequestEvent req,
                                       String destGt, int destSsn) {
        if (svc == null || req == null || destGt == null || destGt.isBlank()) {
            return "map2map-hop-dest-fail";
        }
        int ssn = destSsn >= 1 && destSsn <= 255 ? destSsn : 6;
        svc.store().get(req.correlationId()).ifPresent(s -> {
            s.setMscGt(destGt);
            s.setImsi(null);
            svc.store().put(s);
        });
        svc.pendingMap2Map().putUssd(req.outboundCorr(), req, destGt, null);
        String ussdCode = resolveHopUssdForReq(svc, req);
        var cfg = svc.config();
        String localGt = MapDialogHelper.localGt(cfg);
        // Brook: Calling SSN 6 (HLR), not ussd/MSC SSN 8. Fail-closed default 6.
        int localSsn = MapDialogHelper.hlrSsn(cfg);
        if (localSsn < 1 || localSsn > 255) {
            localSsn = 6;
        }
        // Case 2 hop toward Ethio / SP peer always on live SCCP plane (networkId 0 on Digicom).
        // Lab sim MO arrives on networkId 1 — inheriting that would GTT-miss on net1.
        int hopNetworkId = map2mapHopNetworkId(cfg, req);
        var pin = svc.pickPeerRoute(hopNetworkId, req.outboundCorr());
        MapDialogHelper.map2mapProcessHop(ss7, req.outboundCorr(), destGt, localGt, ussdCode,
                hopNetworkId, et.restlink.ussdgw.api.UssdAlphabet.AUTO, req.msisdn(),
                ssn, localSsn, pin.preferredAspName(), pin.remotePc());
        armGateAfterHopSent(svc, req);
        try {
            Long gate = Map2MapCdr.gateMs(svc.store().get(req.correlationId()).orElse(null));
            svc.cdr().write(req.correlationId(), CdrPhase.S1_ACTIVE, req.msisdn(), req.shortCode(),
                    Map2MapCdr.USSD_SENT,
                    Map2MapCdr.detail(req, "fixedGt=" + destGt, "ssn=" + ssn, "code=" + ussdCode,
                            "hopUssd=" + ussdCode,
                            "op=processUnstructuredSS-Request", "localSsn=" + localSsn,
                            "hopNet=" + hopNetworkId, "moNet=" + req.networkId()),
                    req.networkId(), req.tenantId(), "MAP", gate, null);
        } catch (Throwable ignored) { }
        return "map2map-ussd-sent gt=" + destGt + " ssn=" + ssn + " code=" + ussdCode
                + " hopNet=" + hopNetworkId;
    }

    /**
     * MAP2MAP hop USSD: mark/long suffix preserve + RE_ROUTE chain fold via live routing table.
     * Falls back to single-rule {@link ShortCodeRule#resolveHopUssd} when routing is unavailable.
     */
    static String resolveHopUssdForReq(SbbServices svc, Map2MapRequestEvent req) {
        if (req == null) {
            return "";
        }
        try {
            if (svc != null && svc.routing() != null) {
                String chained = svc.routing().resolveMap2MapHopUssd(
                        req.dialedUssd(), req.mark(), req.shortCode(), req.redirectUssd());
                if (chained != null && !chained.isBlank()) {
                    return chained;
                }
            }
        } catch (Throwable ignored) { }
        return ShortCodeRule.resolveHopUssd(
                req.dialedUssd(), req.mark(), req.shortCode(), req.redirectUssd());
    }

    /**
     * Outbound MAP2MAP hop SCCP networkId = configured live plane, not MO dialog networkId.
     * Session/CDR keep MO {@code req.networkId()} (lab=1 / live=0).
     */
    static int map2mapHopNetworkId(et.restlink.ussdgw.config.UssdConfigService cfg,
                                   Map2MapRequestEvent req) {
        if (cfg != null) {
            return cfg.liveNetworkId();
        }
        return req == null ? 0 : req.networkId();
    }

    /**
     * RE_ROUTE: start the AdaptiveTimeout countdown only after the outbound hop invoke is
     * on the wire — UE stays on the open MO dialog until hop+AS finish, or until the budget
     * elapses ({@code BRIDGED} / async-wait). Idempotent if already {@code AWAITING_AS}.
     */
    static void armGateAfterHopSent(SbbServices svc, Map2MapRequestEvent req) {
        if (svc == null || req == null) {
            return;
        }
        try {
            VirtualSession session = svc.store().get(req.correlationId()).orElse(null);
            if (session == null) {
                return;
            }
            // Always mark hop outstanding when USSD is on the wire — MO must not end until
            // hop Response/Abort/Reject/Timeout clears this flag.
            session.setAdaptiveBridgeArm(true);
            session.setMap2mapHopOutstanding(true);
            if (session.state() == VirtualSessionState.AWAITING_AS && session.gateDeadlineMs() > 0) {
                svc.store().put(session);
                recordUserMap2MapPending(svc, req, session);
                return;
            }
            svc.store().put(session);
            svc.bridge().startAwaitingAs(session, "hop");
            recordUserMap2MapPending(svc, req, session);
        } catch (Throwable t) {
            SleeEventTrace.outSbb("MapUssdParentSbb", null,
                    "map2map-arm-gate-fail " + t.getClass().getSimpleName());
        }
    }

    /** Stamp durable ussdUser with hop-armed (pending) MAP2MAP TX for this MSISDN. */
    private static void recordUserMap2MapPending(SbbServices svc, Map2MapRequestEvent req,
                                                 VirtualSession session) {
        try {
            if (svc.userProfiles() == null) {
                return;
            }
            Long gate = Map2MapCdr.gateMs(session);
            Long ewma = null;
            if (svc.adaptive() != null) {
                double v = svc.adaptive().observedLatencyMs(req.networkId(), req.msisdn());
                if (v > 0d) {
                    ewma = Math.round(v);
                }
            }
            svc.userProfiles().recordMap2Map(req, Map2MapCdr.OUTCOME_PENDING, gate, ewma);
        } catch (Throwable ignored) { }
    }

    private void failMap2MapAfterSri(Map2MapRequestEvent req, String reason) {
        svc().pendingMap2Map().take(req.outboundCorr());
        try {
            svc().map2MapTelemetry().failClosed();
        } catch (Throwable ignored) { }
        try {
            svc().cdr().write(req.correlationId(), CdrPhase.FAILED, req.msisdn(), req.shortCode(),
                    reason, Map2MapCdr.detail(req, "phase=sri-fail"),
                    req.networkId(), req.tenantId(), "MAP", null, null);
        } catch (Throwable ignored) { }
        var session = svc().store().get(req.correlationId()).orElse(null);
        if (session != null && (!session.dialogAlive()
                || session.state() == VirtualSessionState.S1_RELEASED
                || session.state() == VirtualSessionState.PUSH_PENDING
                || session.state().terminal())) {
            markDialogDead(req.inboundDialogId());
            return;
        }
        try {
            MapDialogHelper.replyAndEnd(ss7, req.inboundDialogId(), req.inboundInvokeId(),
                    hardFailMessage());
        } catch (Throwable ignored) { }
        markDialogDead(req.inboundDialogId());
    }

    /** Routing fields from SRI-SM for NI USSD (classic LocationInfoWithLMSI + IMSI). */
    record SriNiRouting(String imsi, String mscGt, byte[] lmsi) {}

    static Optional<SriNiRouting> extractSriNiRouting(SendRoutingInfoForSMResponse rsp) {
        if (rsp == null) {
            return Optional.empty();
        }
        String imsi = null;
        String msc = null;
        byte[] lmsi = null;
        IMSI i = rsp.getIMSI();
        if (i != null) {
            imsi = i.getData();
        }
        LocationInfoWithLMSI loc = rsp.getLocationInfoWithLMSI();
        if (loc != null && loc.getNetworkNodeNumber() != null) {
            msc = loc.getNetworkNodeNumber().getAddress();
        }
        if (loc != null && loc.getLMSI() != null) {
            lmsi = loc.getLMSI().getData();
        }
        if (msc == null || msc.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new SriNiRouting(
                imsi == null || imsi.isBlank() ? null : imsi.trim(),
                msc.trim(),
                lmsi));
    }

    /**
     * Persist SRI routing info onto the NI session, then hand off to {@link MapNiPushSbb}.
     * Package-private for unit tests.
     */
    String applyNiSriResult(et.restlink.ussdgw.events.NiPushRequestEvent ni,
                            SendRoutingInfoForSMResponse rsp) {
        Optional<SriNiRouting> routing = extractSriNiRouting(rsp);
        if (routing.isEmpty()) {
            String imsiHint = "";
            if (rsp != null && rsp.getIMSI() != null && rsp.getIMSI().getData() != null) {
                imsiHint = rsp.getIMSI().getData();
            }
            svc().cdr().write(ni.correlationId(), CdrPhase.FAILED, ni.msisdn(), null,
                    "SRI_NO_MSC", "imsi=" + imsiHint);
            svc().saga().onNiFailed(ni.correlationId(), "SRI_NO_MSC");
            try {
                svc().campaigns().onNiDone(ni.correlationId(), false, "SRI_NO_MSC");
            } catch (Throwable ignored) { }
            return "sri-no-msc";
        }
        SriNiRouting r = routing.get();
        svc().store().get(ni.correlationId()).ifPresentOrElse(s -> {
            s.setMscGt(r.mscGt());
            s.setImsi(r.imsi());
            s.setLmsi(r.lmsi());
            svc().store().put(s);
        }, () -> {
            // Campaign / access-NI may not have pre-created a VirtualSession — seed one for push.
            VirtualSession s = new VirtualSession(
                    UUID.randomUUID().toString(), ni.correlationId(), ni.correlationId(),
                    ni.msisdn(), ni.networkId(), ni.correlationId(), "");
            s.setOriginationType(OriginationType.MAP);
            s.setMscGt(r.mscGt());
            s.setImsi(r.imsi());
            s.setLmsi(r.lmsi());
            s.setLocalGt(MapDialogHelper.localGt(svc().config()));
            svc().store().put(s);
        });
        try {
            svc().hlrFace().rememberSri(ni.msisdn(), r.imsi(), r.mscGt(), r.lmsi());
        } catch (Throwable ignored) { }
        try {
            if (svc().container() != null) {
                svc().container().routeEvent(
                        et.restlink.ussdgw.events.NiPushReadyEvent.fromSri(ni, r.mscGt(), r.imsi()),
                        svc().container().createActivityContext("ni-push-" + ni.correlationId()));
            }
        } catch (Throwable ignored) { }
        return "sri-ok msc=" + r.mscGt();
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
        RuleType plane = type == null ? RuleType.HTTP : type.asPullPlane();
        if (plane.usesHttpAsPull()) {
            return svc().config().httpClientBridgeEnabled();
        }
        if (plane == RuleType.GRPC) {
            return svc().config().grpcClientBridgeEnabled();
        }
        // SIP (and any future plane): global bridge flag — reply correlation is best-effort.
        return svc().config().bridgeEnabled();
    }

}
