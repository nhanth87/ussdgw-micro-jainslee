package et.restlink.ussdgw.access;

import et.restlink.ussdgw.bridge.VirtualSession;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Routes NI push to the adapter matching {@link VirtualSession#originationType()}.
 */
@ApplicationScoped
public class AccessNiDispatcher {
    private static final Logger LOG = LogManager.getLogger(AccessNiDispatcher.class);

    @Inject MapUssdAccessAdapter map;
    @Inject DiameterUssdAccessAdapter diameter;
    @Inject SmppUssdAccessAdapter smpp;
    @Inject SipUssiAccessAdapter sip;

    public void requestNiPush(VirtualSession session, String text) {
        requestNiPush(session, text, false);
    }

    public void requestNiPush(VirtualSession session, String text, boolean notifyOnly) {
        if (session == null) return;
        UssdAccessPort p = port(session.originationType());
        if (p == null) {
            LOG.warn("NI push skipped (no adapter for origination={})",
                    session.originationType());
            return;
        }
        p.requestNiPush(session, text, notifyOnly);
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
