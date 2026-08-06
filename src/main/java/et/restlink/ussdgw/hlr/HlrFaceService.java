package et.restlink.ussdgw.hlr;

import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.events.InboundSriSmEvent;
import et.restlink.ussdgw.service.MapDialogHelper;

import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.jss7.Ss7Address;
import com.microjainslee.ra.jss7.command.Ss7Command;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;

/**
 * Core HLR face: FAKE / PROXY_MAP / PROXY_DIAMETER / FAKE_THEN_RESOLVE for inbound SRI-SM.
 */
@ApplicationScoped
public class HlrFaceService {
    private static final Logger LOG = LogManager.getLogger(HlrFaceService.class);

    @Inject HlrResolvePolicy policy;
    @Inject PendingHlrProxyRegistry pendingProxy;
    @Inject DiameterLocationClient diameter;
    @Inject UssdConfigService config;
    @Inject CdrService cdr;

    public String handle(InboundSriSmEvent ev, RaCommandPort ss7) {
        if (ev == null) return "null-event";
        HlrResolveMode mode = policy.modeFor(ev.networkId(), ev.msisdn());
        return switch (mode) {
            case FAKE -> doFake(ev, ss7, "HLR_FAKE");
            case PROXY_MAP -> doProxyMap(ev, ss7);
            case PROXY_DIAMETER -> doProxyDiameter(ev, ss7);
            case FAKE_THEN_RESOLVE -> doFakeThenResolve(ev, ss7);
        };
    }

    /** Relay upper SRI-SM Response back to the original inbound dialog. */
    public String relayUpperResponse(String outboundCorr, String imsi, String mscGt, byte[] lmsi,
                                     RaCommandPort ss7) {
        var pend = pendingProxy.take(outboundCorr);
        if (pend.isEmpty()) {
            pend = pendingProxy.takeAny();
        }
        if (pend.isEmpty()) {
            return "hlr-proxy-no-pending";
        }
        var p = pend.get();
        if (imsi == null || imsi.isBlank() || mscGt == null || mscGt.isBlank()) {
            cdr.write(p.inboundDialogId(), CdrPhase.FAILED, p.msisdn(), null, "HLR_PROXY_FAIL", null);
            abortInbound(ss7, p.inboundDialogId());
            return "HLR_PROXY_FAIL";
        }
        sendSriResponse(ss7, p.inboundDialogId(), p.inboundInvokeId(), imsi, mscGt, lmsi, p.networkId());
        cdr.write(p.inboundDialogId(), CdrPhase.COMPLETED, p.msisdn(), null, "HLR_PROXY_OK", null);
        return "HLR_PROXY_OK";
    }

    private String doFake(InboundSriSmEvent ev, RaCommandPort ss7, String cdrCode) {
        if (!policy.canFake()) {
            cdr.write(ev.dialogId(), CdrPhase.FAILED, ev.msisdn(), null, "HLR_FAKE_MISCONFIG", null);
            abortInbound(ss7, ev.dialogId());
            return "HLR_FAKE_MISCONFIG";
        }
        sendSriResponse(ss7, ev.dialogId(), ev.invokeId(),
                policy.fakeImsi(), policy.fakeMscGt(), null, ev.networkId());
        cdr.write(ev.dialogId(), CdrPhase.COMPLETED, ev.msisdn(), null, cdrCode, null);
        return cdrCode;
    }

    private String doProxyMap(InboundSriSmEvent ev, RaCommandPort ss7) {
        String upper = policy.upperHlrGt();
        if (policy.upperWouldLoop(upper)) {
            cdr.write(ev.dialogId(), CdrPhase.FAILED, ev.msisdn(), null, "HLR_PROXY_FAIL", null);
            abortInbound(ss7, ev.dialogId());
            return "HLR_PROXY_FAIL";
        }
        if (ss7 == null) {
            cdr.write(ev.dialogId(), CdrPhase.FAILED, ev.msisdn(), null, "HLR_PROXY_FAIL", null);
            return "HLR_PROXY_FAIL";
        }
        String corr = "hlr-proxy-" + UUID.randomUUID();
        pendingProxy.put(corr, new PendingHlrProxyRegistry.Pending(
                ev.dialogId(), ev.invokeId(), ev.msisdn(), ev.networkId(),
                ev.serviceCentreAddress(), HlrResolveMode.PROXY_MAP));
        int hlrSsn = MapDialogHelper.hlrSsn(config);
        int localSsn = MapDialogHelper.localSsn(config);
        String localGt = MapDialogHelper.localGt(config);
        String sc = ev.serviceCentreAddress() != null && !ev.serviceCentreAddress().isBlank()
                ? ev.serviceCentreAddress() : localGt;
        ss7.sendCommand(new Ss7Command.MapSendRoutingInfoForSm(
                corr,
                Ss7Address.of(upper, hlrSsn),
                Ss7Address.of(localGt, localSsn),
                ev.msisdn(),
                sc,
                ev.networkId()));
        LOG.info("HLR PROXY_MAP outbound SRI corr={} upper={} msisdn={}", corr, upper, ev.msisdn());
        return "HLR_PROXY_MAP_SENT";
    }

    private String doProxyDiameter(InboundSriSmEvent ev, RaCommandPort ss7) {
        var loc = diameter.lookupSyncOrStub(ev.msisdn());
        if (loc.isEmpty()) {
            cdr.write(ev.dialogId(), CdrPhase.FAILED, ev.msisdn(), null, "HLR_DIAM_FAIL", null);
            abortInbound(ss7, ev.dialogId());
            return "HLR_DIAM_FAIL";
        }
        var l = loc.get();
        sendSriResponse(ss7, ev.dialogId(), ev.invokeId(), l.imsi(), l.mscGt(), l.lmsi(), ev.networkId());
        cdr.write(ev.dialogId(), CdrPhase.COMPLETED, ev.msisdn(), null, "HLR_DIAM_OK", null);
        return "HLR_DIAM_OK";
    }

    private String doFakeThenResolve(InboundSriSmEvent ev, RaCommandPort ss7) {
        String first = doFake(ev, ss7, "HLR_FAKE_THEN");
        if (!"HLR_FAKE_THEN".equals(first) && !"HLR_FAKE".equals(first)) {
            // doFake returns HLR_FAKE_THEN via cdrCode param
            if (first.startsWith("HLR_FAKE_MISCONFIG")) {
                return first;
            }
        }
        // Best-effort enrich: prefer MAP upper if configured, else Diameter stub
        String upper = policy.upperHlrGt();
        if (!policy.upperWouldLoop(upper) && ss7 != null) {
            String corr = "hlr-enrich-" + UUID.randomUUID();
            pendingProxy.put(corr, new PendingHlrProxyRegistry.Pending(
                    ev.dialogId(), ev.invokeId(), ev.msisdn(), ev.networkId(),
                    ev.serviceCentreAddress(), HlrResolveMode.FAKE_THEN_RESOLVE));
            int hlrSsn = MapDialogHelper.hlrSsn(config);
            int localSsn = MapDialogHelper.localSsn(config);
            String localGt = MapDialogHelper.localGt(config);
            ss7.sendCommand(new Ss7Command.MapSendRoutingInfoForSm(
                    corr,
                    Ss7Address.of(upper, hlrSsn),
                    Ss7Address.of(localGt, localSsn),
                    ev.msisdn(),
                    localGt,
                    ev.networkId()));
            return "HLR_FAKE_THEN_RESOLVE_MAP";
        }
        diameter.lookupSyncOrStub(ev.msisdn());
        return "HLR_FAKE_THEN_RESOLVE_DIAM";
    }

    private void sendSriResponse(RaCommandPort ss7, String dialogId, long invokeId,
                                 String imsi, String mscGt, byte[] lmsi, int networkId) {
        if (ss7 == null) {
            LOG.warn("HLR face: no ra-jss7 for SRI response dialog={}", dialogId);
            return;
        }
        ss7.sendCommand(new Ss7Command.MapSendRoutingInfoForSmResponse(
                dialogId, invokeId, imsi, mscGt, lmsi, networkId));
    }

    private void abortInbound(RaCommandPort ss7, String dialogId) {
        if (ss7 == null || dialogId == null) return;
        try {
            ss7.sendCommand(new Ss7Command.MapDialogAbort(dialogId));
        } catch (RuntimeException e) {
            LOG.warn("HLR abort failed dialog={}: {}", dialogId, e.toString());
        }
    }
}
