package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.events.NiPushReadyEvent;
import et.restlink.ussdgw.events.NiPushRequestEvent;
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

/** Starts MAP SRI-SM for NI push; responses handled by MapUssdParentSbb via PendingSriRegistry. */
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
        SleeEventTrace.inSbb("SriSbb", event, "msisdn=" + ni.msisdn());
        String detail;
        try { detail = startSri(ni); }
        catch (Throwable t) { detail = "error=" + t.getClass().getSimpleName(); }
        SleeEventTrace.outSbb("SriSbb", event, detail);
    }

    private String startSri(NiPushRequestEvent ni) {
        svc().pendingSri().put(ni.correlationId(), ni);
        RaCommandPort port = ss7;
        if (port == null || !svc().linkStatus().ss7Live()) {
            handoff(ni);
            return "sri-skipped-lab";
        }
        var cfg = svc().config();
        String localGt = MapDialogHelper.localGt(cfg);
        int hlrSsn = MapDialogHelper.hlrSsn(cfg);
        int localSsn = MapDialogHelper.localSsn(cfg);
        Ss7Address hlr = Ss7Address.of(ni.msisdn(), hlrSsn);
        Ss7Address local = Ss7Address.of(localGt, localSsn);
        port.sendCommand(new Ss7Command.MapSendRoutingInfoForSm(
                ni.correlationId(), hlr, local, ni.msisdn(), localGt, ni.networkId()));
        return "sri-sent";
    }

    static void handoff(SbbServices svc, NiPushRequestEvent ni) {
        svc.container().routeEvent(NiPushReadyEvent.from(ni),
                svc.container().createActivityContext("ni-push-" + ni.correlationId()));
    }

    private void handoff(NiPushRequestEvent ni) {
        handoff(svc(), ni);
    }
}
