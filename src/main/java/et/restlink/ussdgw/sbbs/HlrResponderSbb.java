package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.events.InboundSriSmEvent;
import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.service.SbbServices;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;

/** SLEE entry for inbound SRI-SM (HLR face). */
public final class HlrResponderSbb implements Sbb, SleeEventHandler {
    private final SbbServices services;

    @InjectRa(name = "ra-jss7")
    private volatile RaCommandPort ss7;

    public HlrResponderSbb() { this(null); }
    public HlrResponderSbb(SbbServices services) { this.services = services; }

    private SbbServices svc() { return services != null ? services : SbbServices.get(); }

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (!(event instanceof InboundSriSmEvent sri)) return;
        SleeEventTrace.inSbb("HlrResponderSbb", event, "msisdn=" + sri.msisdn());
        String detail;
        try {
            detail = svc().hlrFace().handle(sri, ss7);
        } catch (Throwable t) {
            detail = "error=" + t.getClass().getSimpleName();
        }
        SleeEventTrace.outSbb("HlrResponderSbb", event, detail);
    }
}
