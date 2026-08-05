package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.codec.SmsTextCodec;
import et.restlink.ussdgw.events.NiPushRequestEvent;
import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.ra.smpp.SmppEndpointRegistry;
import et.restlink.ussdgw.ra.smpp.events.SmppSubmitSmEvent;
import et.restlink.ussdgw.service.SbbServices;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;

import java.util.UUID;

/**
 * Local ESME submit_sm → NI USSD toward dest_addr (plain text only).
 */
public final class SmppAsIngressSbb implements Sbb, SleeEventHandler {
    private final SbbServices services;
    private final SmppEndpointRegistry smppRegistry;

    public SmppAsIngressSbb() { this(null, null); }
    public SmppAsIngressSbb(SbbServices services, SmppEndpointRegistry smppRegistry) {
        this.services = services;
        this.smppRegistry = smppRegistry;
    }

    private SbbServices svc() { return services != null ? services : SbbServices.get(); }
    private SmppEndpointRegistry registry() {
        return smppRegistry != null ? smppRegistry : svc().smppRegistry();
    }

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (!(event instanceof SmppSubmitSmEvent submit)) return;
        String systemId = submit.getSystemId() == null ? "smpp-esme" : submit.getSystemId();
        SleeEventTrace.inSbb("SmppAsIngressSbb", event,
                "systemId=" + systemId + " dest=" + submit.getDestAddr());
        String detail;
        try {
            detail = handle(submit, systemId);
        } catch (Throwable t) {
            detail = "error=" + t.getClass().getSimpleName();
        }
        SleeEventTrace.outSbb("SmppAsIngressSbb", event, detail);
    }

    private String handle(SmppSubmitSmEvent submit, String systemId) {
        if (!registry().admitEsme(systemId)) {
            return "denied systemId=" + systemId;
        }
        if (submit.looksLikeOta()) {
            return "ignored-ota-binary";
        }
        byte[] payload = submit.getShortMessage();
        if (payload.length == 0) return "empty";
        String text = SmsTextCodec.decode(payload, submit.getDataCoding());
        if (text.isBlank()) return "blank-text";
        var alphabet = SmsTextCodec.alphabetFromSmppDcs(submit.getDataCoding());
        String msisdn = submit.getDestAddr();
        if (msisdn == null || msisdn.isBlank()) return "no-dest";
        int networkId = submit.getNetworkId();
        var binding = registry().esmeBinding(systemId);
        if (binding.isPresent()) {
            networkId = binding.get().networkId();
        }
        String corr = UUID.randomUUID().toString();
        var container = svc().container();
        container.routeEvent(new NiPushRequestEvent(corr, msisdn, text, networkId, alphabet),
                container.createActivityContext("smpp-ni-" + corr));
        return "ni-queued msisdn=" + msisdn + " alphabet=" + alphabet.toWire();
    }
}
