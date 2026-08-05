package et.restlink.ussdgw.access;

import et.restlink.ussdgw.bridge.VirtualSession;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Routes NI push to the adapter matching {@link VirtualSession#originationType()}.
 */
@ApplicationScoped
public class AccessNiDispatcher {
    @Inject MapUssdAccessAdapter map;
    @Inject DiameterUssdAccessAdapter diameter;
    @Inject SmppUssdAccessAdapter smpp;
    @Inject SipUssiAccessAdapter sip;

    public void requestNiPush(VirtualSession session, String text) {
        if (session == null) return;
        port(session.originationType()).requestNiPush(session, text);
    }

    public UssdAccessPort port(OriginationType type) {
        return switch (type == null ? OriginationType.MAP : type) {
            case MAP -> map;
            case DIAMETER -> diameter;
            case SMPP -> smpp;
            case SIP -> sip;
        };
    }
}
