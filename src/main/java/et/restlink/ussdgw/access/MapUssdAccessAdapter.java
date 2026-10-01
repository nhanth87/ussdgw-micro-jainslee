package et.restlink.ussdgw.access;

import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.events.NiPushRequestEvent;

import com.microjainslee.core.MicroSleeContainer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Live MAP (TS 29.002) access: NI push via existing {@link NiPushRequestEvent} → SriSbb/MapNiPushSbb.
 * MO pull stays in {@code MapUssdParentSbb}.
 */
@ApplicationScoped
public class MapUssdAccessAdapter implements UssdAccessPort {
    private static final Logger LOG = LogManager.getLogger(MapUssdAccessAdapter.class);

    @Inject MicroSleeContainer container;

    @Override
    public OriginationType type() {
        return OriginationType.MAP;
    }

    @Override
    public void requestNiPush(VirtualSession session, String text) {
        requestNiPush(session, text, false);
    }

    /**
     * Late reconcile (P1-3, D2b): END / bridged CONTINUE go out as one-shot
     * {@code unstructuredSS-Notify} — never an interactive Request on a released leg.
     */
    public void requestNiPush(VirtualSession session, String text, boolean notifyOnly) {
        if (session == null || container == null) {
            LOG.warn("MAP NI push skipped (no session/container)");
            return;
        }
        container.routeEvent(new NiPushRequestEvent(
                        session.correlationId(), session.msisdn(), text, session.networkId(),
                        session.pendingAlphabet(), notifyOnly),
                container.createActivityContext("ni-" + session.correlationId()));
    }

    @Override
    public VirtualSession acceptMoPull(UssdAccessSession access) {
        throw new UnsupportedOperationException("MAP MO pull is handled by MapUssdParentSbb");
    }
}
