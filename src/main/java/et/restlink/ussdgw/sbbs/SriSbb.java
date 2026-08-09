package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.events.NiPushReadyEvent;
import et.restlink.ussdgw.events.NiPushRequestEvent;
import et.restlink.ussdgw.hlr.HlrResolvePolicy;
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
import com.microjainslee.ra.jss7.Ss7Address;
import com.microjainslee.ra.jss7.command.Ss7Command;

/**
 * Starts MAP SRI-SM for NI push; responses handled by MapUssdParentSbb via PendingSriRegistry.
 * CalledParty / dest GT = resolved {@code ussd.hlr.upper-gt} (admin overlay, else props) — never MSISDN.
 */
public final class SriSbb implements Sbb, SleeEventHandler {
    private final SbbServices services;

    @InjectRa(name = "ra-jss7")
    private volatile RaCommandPort ss7;

    public SriSbb() { this(null); }
    public SriSbb(SbbServices services) { this.services = services; }
    private SbbServices svc() { return services != null ? services : SbbServices.get(); }

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (!(event instanceof NiPushRequestEvent ni)) return;
        SleeEventTrace.inSbb("SriSbb", event, Pii.msisdnDetail(ni.msisdn()));
        String detail;
        try { detail = startSri(ni); }
        catch (Throwable t) {
            detail = "error=" + t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
            org.apache.logging.log4j.LogManager.getLogger(SriSbb.class)
                    .error("SriSbb startSri failed corr={}", ni.correlationId(), t);
        }
        SleeEventTrace.outSbb("SriSbb", event, detail);
    }

    private String startSri(NiPushRequestEvent ni) {
        svc().pendingSri().put(ni.correlationId(), ni);
        RaCommandPort port = ss7;
        if (port == null || !svc().linkStatus().ss7Live()) {
            // No SRI goes out, so no response will ever claim the entry — drop it now rather
            // than let the TTL sweep fail a saga that actually completed via handoff.
            svc().pendingSri().take(ni.correlationId());
            handoff(ni);
            return "sri-skipped-lab";
        }
        var cfg = svc().config();
        String upperGt = resolveUpperHlrGt(cfg, svc().hlrPolicy());
        if (isUnusableUpperHlrGt(cfg, svc().hlrPolicy(), upperGt)) {
            failHlrGt(ni, upperGt);
            return "sri-hlr-gt-fail";
        }
        String localGt = MapDialogHelper.localGt(cfg);
        int hlrSsn = MapDialogHelper.hlrSsn(cfg);
        int localSsn = MapDialogHelper.localSsn(cfg);
        // SCCP CalledParty = configured upper HLR GT (not subscriber MSISDN)
        Ss7Address hlr = Ss7Address.of(upperGt, hlrSsn);
        Ss7Address local = Ss7Address.of(localGt, localSsn);
        port.sendCommand(new Ss7Command.MapSendRoutingInfoForSm(
                ni.correlationId(), hlr, local, ni.msisdn(), localGt, ni.networkId()));
        return "sri-sent";
    }

    /**
     * Admin KV overlay wins when non-blank; blank overlay falls back to
     * {@code @ConfigProperty} / application.properties via {@link UssdConfigService#hlrUpperGt()}.
     */
    public static String resolveUpperHlrGt(UssdConfigService cfg, HlrResolvePolicy policy) {
        if (policy != null) {
            return policy.upperHlrGt();
        }
        return cfg == null ? "" : cfg.hlrUpperGt();
    }

    /** Fail-closed only when resolved dest is blank/unusable or equals local USSD GT (loop). */
    public static boolean isUnusableUpperHlrGt(UssdConfigService cfg, HlrResolvePolicy policy, String upperGt) {
        if (policy != null) {
            return policy.upperWouldLoop(upperGt);
        }
        if (upperGt == null || upperGt.isBlank()) {
            return true;
        }
        String u = digits(upperGt);
        String local = digits(MapDialogHelper.localGt(cfg));
        return u.isEmpty() || (!local.isEmpty() && u.equals(local));
    }

    private void failHlrGt(NiPushRequestEvent ni, String upperGt) {
        svc().pendingSri().take(ni.correlationId());
        svc().cdr().write(ni.correlationId(), CdrPhase.FAILED, ni.msisdn(), null,
                "SRI_HLR_GT_FAIL", "upper-gt=" + (upperGt == null ? "" : upperGt));
        svc().saga().onNiFailed(ni.correlationId(), "SRI_HLR_GT_FAIL");
        if (svc().campaigns() != null) {
            svc().campaigns().onNiDone(ni.correlationId(), false, "SRI_HLR_GT_FAIL");
        }
    }

    private static String digits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') b.append(c);
        }
        return b.toString();
    }

    static void handoff(SbbServices svc, NiPushRequestEvent ni) {
        svc.container().routeEvent(NiPushReadyEvent.from(ni),
                svc.container().createActivityContext("ni-push-" + ni.correlationId()));
    }

    private void handoff(NiPushRequestEvent ni) {
        handoff(svc(), ni);
    }
}
