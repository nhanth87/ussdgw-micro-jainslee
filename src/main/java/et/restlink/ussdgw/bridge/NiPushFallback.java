package et.restlink.ussdgw.bridge;

import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Last resort when a late NI push exhausts its retries or hits a terminal
 * subscriber verdict (P1-5, classic {@code NotificationFallback} parity).
 */
public interface NiPushFallback {
    void fallback(VirtualSession session, String reason);

    /**
     * Default (D4): log + CDR only. An SMS-via-SMPP fallback can implement this
     * interface behind a flag later without touching the bridge.
     */
    @ApplicationScoped
    class LoggingFallback implements NiPushFallback {
        private static final Logger LOG = LogManager.getLogger(LoggingFallback.class);

        @Inject CdrService cdr;

        @Override
        public void fallback(VirtualSession session, String reason) {
            if (session == null) return;
            LOG.warn("NI push fallback (log/CDR only) corr={} msisdn={} reason={}",
                    session.correlationId(),
                    AdaptiveTimeout.normalizeMsisdn(session.msisdn()), reason);
            if (cdr == null) return;
            try {
                cdr.write(session.correlationId(), CdrPhase.FAILED, session.msisdn(),
                        session.shortCode(), "NI_FALLBACK",
                        "service=NiPushFallback|reason=" + reason,
                        session.networkId(), session.tenantId(),
                        session.originationType().name(),
                        session.gateMs() > 0 ? session.gateMs() : null, null);
            } catch (RuntimeException ignored) {
                // best-effort telemetry
            }
        }
    }
}
