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

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Core HLR face: FAKE / PROXY_MAP / PROXY_DIAMETER / FAKE_THEN_RESOLVE for inbound SRI-SM.
 */
@ApplicationScoped
public class HlrFaceService {
    private static final Logger LOG = LogManager.getLogger(HlrFaceService.class);

    @Inject HlrResolvePolicy policy;
    @Inject PendingHlrProxyRegistry pendingProxy;
    @Inject HlrLocationCache locationCache;
    @Inject DiameterLocationClient diameter;
    @Inject UssdConfigService config;
    @Inject CdrService cdr;

    /** TTL expiry runs off the SLEE event path, so the RA port comes from the SS7 plane binding. */
    public void bindSs7(Supplier<? extends RaCommandPort> supplier) {
        pendingProxy.bindSs7(supplier);
    }

    public String handle(InboundSriSmEvent ev, RaCommandPort ss7) {
        if (ev == null) return "null-event";
        HlrResolveMode mode = policy.modeFor(ev.networkId(), ev.msisdn());
        return switch (mode) {
            case FAKE -> doFake(ev, ss7, "HLR_FAKE", false);
            case PROXY_MAP -> doProxyMap(ev, ss7);
            case PROXY_DIAMETER -> doProxyDiameter(ev, ss7);
            case FAKE_THEN_RESOLVE -> doFakeThenResolve(ev, ss7);
        };
    }

    /**
     * Relay an upper SRI-SM Response back to the inbound dialog it belongs to. The correlation must
     * match exactly: an answer for an unknown query is dropped rather than applied to some other
     * subscriber's dialog. An enrich-only entry (FAKE_THEN_RESOLVE) has already answered its
     * inbound dialog, so it only refreshes the location cache — sending a second
     * {@code SendRoutingInfoForSmResponse} would violate the MAP state machine.
     */
    public String relayUpperResponse(String outboundCorr, String imsi, String mscGt, byte[] lmsi,
                                     RaCommandPort ss7) {
        var pend = pendingProxy.take(outboundCorr);
        if (pend.isEmpty()) {
            return "hlr-proxy-no-pending";
        }
        return relayUpperResponse(pend.get(), imsi, mscGt, lmsi, ss7);
    }

    /** Same relay for a caller that already claimed the pending entry from the registry. */
    public String relayUpperResponse(PendingHlrProxyRegistry.Pending pending, String imsi, String mscGt,
                                     byte[] lmsi, RaCommandPort ss7) {
        if (pending == null) {
            return "hlr-proxy-no-pending";
        }
        var p = pending;
        boolean resolved = imsi != null && !imsi.isBlank() && mscGt != null && !mscGt.isBlank();
        if (resolved) {
            locationCache.put(p.msisdn(), imsi, mscGt, lmsi);
        }
        if (p.enrichOnly()) {
            return resolved ? "HLR_ENRICH_OK" : "HLR_ENRICH_FAIL";
        }
        if (!resolved) {
            cdr.write(p.inboundDialogId(), CdrPhase.FAILED, p.msisdn(), null, "HLR_PROXY_FAIL", null);
            abortInbound(ss7, p.inboundDialogId());
            return "HLR_PROXY_FAIL";
        }
        sendSriResponse(ss7, p.inboundDialogId(), p.inboundInvokeId(), imsi, mscGt, lmsi, p.networkId());
        cdr.write(p.inboundDialogId(), CdrPhase.COMPLETED, p.msisdn(), null, "HLR_PROXY_OK", null);
        return "HLR_PROXY_OK";
    }

    /**
     * TTL reclaim for upper queries the peer never answered. A relay entry aborts its still-open
     * inbound dialog (classic {@code onSriError} → abort + failed CDR); an enrich-only entry is
     * simply dropped because its inbound dialog already completed.
     *
     * @return number of entries reclaimed
     */
    public int expirePending(long nowMs) {
        List<PendingHlrProxyRegistry.Pending> expired = pendingProxy.sweepExpired(nowMs);
        if (expired.isEmpty()) {
            return 0;
        }
        RaCommandPort ss7 = pendingProxy.ss7();
        for (PendingHlrProxyRegistry.Pending p : expired) {
            if (p.enrichOnly()) {
                LOG.warn("HLR enrich timed out msisdn={} (inbound already answered)", p.msisdn());
                continue;
            }
            LOG.warn("HLR proxy timed out dialog={} msisdn={} — aborting inbound",
                    p.inboundDialogId(), p.msisdn());
            cdr.write(p.inboundDialogId(), CdrPhase.FAILED, p.msisdn(), null, "HLR_PROXY_TIMEOUT", null);
            abortInbound(ss7, p.inboundDialogId());
        }
        return expired.size();
    }

    /**
     * @param preferCached FAKE_THEN_RESOLVE serves a previously learned real location when one is
     *                     cached; plain FAKE always answers with the configured fake
     */
    private String doFake(InboundSriSmEvent ev, RaCommandPort ss7, String cdrCode, boolean preferCached) {
        if (!policy.canFake()) {
            cdr.write(ev.dialogId(), CdrPhase.FAILED, ev.msisdn(), null, "HLR_FAKE_MISCONFIG", null);
            abortInbound(ss7, ev.dialogId());
            return "HLR_FAKE_MISCONFIG";
        }
        Optional<HlrLocationCache.Location> known =
                preferCached ? locationCache.get(ev.msisdn()) : Optional.empty();
        String imsi = known.map(HlrLocationCache.Location::imsi).orElseGet(policy::fakeImsi);
        String mscGt = known.map(HlrLocationCache.Location::mscGt).orElseGet(policy::fakeMscGt);
        byte[] lmsi = known.map(HlrLocationCache.Location::lmsi).orElse(null);
        sendSriResponse(ss7, ev.dialogId(), ev.invokeId(), imsi, mscGt, lmsi, ev.networkId());
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
                ev.serviceCentreAddress(), HlrResolveMode.PROXY_MAP, false));
        sendUpperSri(ss7, corr, upper, ev, scOrLocal(ev));
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
        String first = doFake(ev, ss7, "HLR_FAKE_THEN", true);
        if ("HLR_FAKE_MISCONFIG".equals(first)) {
            return first;
        }
        // The inbound dialog is answered and closed. Any upper resolve from here is enrich-only:
        // it may refresh the location cache but must never emit a second inbound response.
        String upper = policy.upperHlrGt();
        if (!policy.upperWouldLoop(upper) && ss7 != null) {
            String corr = "hlr-enrich-" + UUID.randomUUID();
            pendingProxy.put(corr, new PendingHlrProxyRegistry.Pending(
                    ev.dialogId(), ev.invokeId(), ev.msisdn(), ev.networkId(),
                    ev.serviceCentreAddress(), HlrResolveMode.FAKE_THEN_RESOLVE, true));
            sendUpperSri(ss7, corr, upper, ev, MapDialogHelper.localGt(config));
            return "HLR_FAKE_THEN_RESOLVE_MAP";
        }
        diameter.lookupSyncOrStub(ev.msisdn())
                .ifPresent(l -> locationCache.put(ev.msisdn(), l.imsi(), l.mscGt(), l.lmsi()));
        return "HLR_FAKE_THEN_RESOLVE_DIAM";
    }

    private void sendUpperSri(RaCommandPort ss7, String corr, String upper,
                              InboundSriSmEvent ev, String scAddress) {
        int hlrSsn = MapDialogHelper.hlrSsn(config);
        int localSsn = MapDialogHelper.localSsn(config);
        String localGt = MapDialogHelper.localGt(config);
        ss7.sendCommand(new Ss7Command.MapSendRoutingInfoForSm(
                corr,
                Ss7Address.of(upper, hlrSsn),
                Ss7Address.of(localGt, localSsn),
                ev.msisdn(),
                scAddress,
                ev.networkId()));
    }

    private String scOrLocal(InboundSriSmEvent ev) {
        String sc = ev.serviceCentreAddress();
        return sc != null && !sc.isBlank() ? sc : MapDialogHelper.localGt(config);
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
