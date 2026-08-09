package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.Map2MapCdr;
import et.restlink.ussdgw.events.Map2MapRequestEvent;
import et.restlink.ussdgw.logging.Pii;
import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.service.MapDialogHelper;
import et.restlink.ussdgw.service.SbbServices;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;

/**
 * MAP-to-MAP hop for MO pull — <strong>Case 2</strong> (no SRI):
 * <ol>
 *   <li>When rule {@code hopDestGt} is set: processUnstructuredSS-Request (op 59) toward that
 *       GT/SSN (default SSN 6; Calling SSN 6) with redirect USSD + MSISDN destRef/component</li>
 *   <li>When hop dest blank: same hop toward HLR Face {@code ussd.hlr.upper-gt} + SSN 6
 *       (or per-rule {@code hopDestSsn} when set alone) — <strong>not</strong> SRI/FAKE→MSC</li>
 *   <li>On processUnstructuredSS-Response (or legacy UnstructuredSS-Response):
 *       {@link et.restlink.ussdgw.service.Map2MapCompletionService}
 *       sync HTTP AS pull (bridge/AdaptiveTimeout armed after hop on wire), then AS text on MO</li>
 * </ol>
 *
 * <p>Case 1 HLR face SRI ({@link SriSbb} / NI) is untouched. Live fail-closed: blank/loop upper GT;
 * blank hop GT digits when hop_dest set; no MSISDN on MO. Lab ({@code ss7.live=false}): skip hop
 * and continue AS with empty enrich.
 *
 * <p>Stay-on-call ≡ AdaptiveTimeout + Virtual Session Bridge (armed at Parent ingress) — not a
 * separate app-user profile flag.
 */
public final class Map2MapSbb implements Sbb, SleeEventHandler {
    private final SbbServices services;

    @InjectRa(name = "ra-jss7")
    private volatile RaCommandPort ss7;

    public Map2MapSbb() { this(null); }
    public Map2MapSbb(SbbServices services) { this.services = services; }
    private SbbServices svc() { return services != null ? services : SbbServices.get(); }

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (!(event instanceof Map2MapRequestEvent req)) return;
        SleeEventTrace.inSbb("Map2MapSbb", event, Pii.msisdnDetail(req.msisdn()));
        String detail;
        try {
            detail = startHop(req);
        } catch (Throwable t) {
            detail = "error=" + t.getClass().getSimpleName();
            try {
                failInbound(req, "MAP2MAP_ERROR");
            } catch (Throwable ignored) { }
        }
        SleeEventTrace.outSbb("Map2MapSbb", event, detail);
    }

    private String startHop(Map2MapRequestEvent req) {
        if (req.redirectUssd() == null || req.redirectUssd().isBlank()) {
            failInbound(req, "MAP2MAP_NO_GT");
            return "map2map-no-gt";
        }
        String ussdCode = MapUssdParentSbb.resolveHopUssdForReq(svc(), req);
        if (ussdCode.isEmpty()) {
            failInbound(req, "MAP2MAP_NO_GT");
            return "map2map-no-gt";
        }

        boolean ss7Live = false;
        try {
            ss7Live = svc().linkStatus().ss7Live();
        } catch (Throwable ignored) { }
        if (ss7 == null || !ss7Live) {
            // Lab: no peer — continue AS with empty hop text (bypass-like enrich).
            svc().map2MapCompletion().onMap2MapResponse(req, "");
            writeCdr(req, CdrPhase.S1_ACTIVE, Map2MapCdr.SKIP_LAB,
                    Map2MapCdr.detail(req, "code=" + ussdCode, "hopUssd=" + ussdCode, "path=lab"));
            try {
                svc().map2MapTelemetry().labSkipped();
            } catch (Throwable ignored) { }
            return "map2map-skipped-lab code=" + ussdCode;
        }

        if (req.msisdn() == null || req.msisdn().isBlank()) {
            failInbound(req, "MAP2MAP_NO_MSISDN");
            return "map2map-no-msisdn";
        }

        try {
            svc().map2MapTelemetry().hopStarted();
        } catch (Throwable ignored) { }

        // Case 2: explicit hop_dest_gt → that GT/SSN; else upper-gt from HLR Face (no SRI/FAKE).
        if (req.fixedHopArmed()) {
            writeCdr(req, CdrPhase.S1_ACTIVE, Map2MapCdr.HOP_START,
                    Map2MapCdr.detailHopStart(req, "fixed"));
            return startHopViaFixedGt(req, ussdCode);
        }

        writeCdr(req, CdrPhase.S1_ACTIVE, Map2MapCdr.HOP_START,
                Map2MapCdr.detailHopStart(req, "upper-gt"));
        return startHopViaUpperHlr(req, ussdCode);
    }

    /**
     * Fixed hop dest GT/SSN (service-provider peer, often SSN 6): processUnstructuredSS-Request
     * toward that address with redirect USSD; MSISDN destRef/component; no SRI.
     */
    private String startHopViaFixedGt(Map2MapRequestEvent req, String ussdCode) {
        String destGt = req.hopDestGtDigits();
        if (destGt.isEmpty()) {
            failInbound(req, "MAP2MAP_HOP_DEST_FAIL");
            return "map2map-hop-dest-fail";
        }
        int destSsn = req.effectiveHopDestSsn();
        try {
            svc().map2MapTelemetry().hopFixedGt();
        } catch (Throwable ignored) { }
        String detail = MapUssdParentSbb.applyMap2MapFixedHop(ss7, svc(), req, destGt, destSsn);
        return "map2map-fixed-ussd gt=" + destGt + " ssn=" + destSsn + " code=" + ussdCode
                + (detail != null ? " " + detail : "");
    }

    /**
     * Case 2 default: processUnstructuredSS-Request toward HLR Face {@code ussd.hlr.upper-gt}
     * (SSN from per-rule hopDestSsn when set, else 6). Fail-closed if GT blank/self-loop.
     * Does <strong>not</strong> SRI or FAKE→MSC (Case 1 NI / SriSbb unchanged).
     */
    private String startHopViaUpperHlr(Map2MapRequestEvent req, String ussdCode) {
        var cfg = svc().config();
        String upperGtRaw = SriSbb.resolveUpperHlrGt(cfg, svc().hlrPolicy());
        if (SriSbb.isUnusableUpperHlrGt(cfg, svc().hlrPolicy(), upperGtRaw)) {
            failInbound(req, "MAP2MAP_UPPER_GT_FAIL");
            return "map2map-upper-gt-fail";
        }
        String destGt = ShortCodeRule.map2mapCalledGtDigits(upperGtRaw);
        if (destGt.isEmpty()) {
            failInbound(req, "MAP2MAP_UPPER_GT_FAIL");
            return "map2map-upper-gt-fail";
        }
        int destSsn = req.effectiveHopDestSsn();
        try {
            svc().map2MapTelemetry().hopUpperGt();
        } catch (Throwable ignored) { }
        String detail = MapUssdParentSbb.applyMap2MapFixedHop(ss7, svc(), req, destGt, destSsn);
        return "map2map-upper-ussd gt=" + destGt + " ssn=" + destSsn + " code=" + ussdCode
                + (detail != null ? " " + detail : "");
    }

    private void failInbound(Map2MapRequestEvent req, String reason) {
        svc().pendingMap2Map().take(req.outboundCorr());
        writeCdr(req, CdrPhase.FAILED, reason, Map2MapCdr.detail(req, "phase=fail-closed"));
        try {
            svc().map2MapTelemetry().failClosed();
        } catch (Throwable ignored) { }
        try {
            MapDialogHelper.replyAndEnd(ss7, req.inboundDialogId(), req.inboundInvokeId(),
                    hardFail());
        } catch (Throwable ignored) { }
        try {
            svc().store().get(req.correlationId()).ifPresent(s -> {
                s.setDialogAlive(false);
                svc().store().put(s);
            });
        } catch (Throwable ignored) { }
    }

    private String hardFail() {
        try {
            String msg = svc().config().asyncHardFailMessage();
            if (msg != null && !msg.isBlank()) return msg;
        } catch (Throwable ignored) { }
        return "ማው ማውማው ማውማው ማውማው ማው";
    }

    private void writeCdr(Map2MapRequestEvent req, CdrPhase phase, String status, String detail) {
        try {
            Long gate = null;
            Long ewma = null;
            try {
                var sess = svc().store().get(req.correlationId()).orElse(null);
                gate = Map2MapCdr.gateMs(sess);
                double v = svc().adaptive().observedLatencyMs(req.networkId());
                if (v > 0d) {
                    ewma = Math.round(v);
                }
            } catch (Throwable ignored) { }
            svc().cdr().write(req.correlationId(), phase, req.msisdn(), req.shortCode(),
                    status, detail, req.networkId(), req.tenantId(), "MAP", gate, ewma);
        } catch (Throwable ignored) { }
    }
}
