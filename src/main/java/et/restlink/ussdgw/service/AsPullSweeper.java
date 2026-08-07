package et.restlink.ussdgw.service;

import io.quarkus.scheduler.Scheduled;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * TTL reclaim for {@link AsPullStateRegistry}, on the Quarkus scheduler — no raw threads and no
 * SLEE timers, so nothing here can leak a timer.
 *
 * <p>Deliberately does not compensate the saga or trip a breaker. An entry only reaches its TTL
 * when the RA never delivered a completion at all, and by then the adaptive gate
 * ({@code BridgeGateScheduler}) has long since expired the session. Tripping a breaker here would
 * also punish an AS for an RA restart. The sweep exists to bound the map, nothing more; a non-zero
 * count is the signal that completions are being dropped.
 */
@ApplicationScoped
public class AsPullSweeper {
    private static final Logger LOG = LogManager.getLogger(AsPullSweeper.class);

    @Inject AsPullStateRegistry registry;

    @Scheduled(every = "${ussd.as.pull.sweep-every:10s}")
    void sweepAbandonedPulls() {
        List<AsPullState> expired = registry.sweepExpired(System.currentTimeMillis());
        if (!expired.isEmpty()) {
            LOG.warn("AS pull state TTL sweep evicted={} inFlight={} totalEvicted={} "
                            + "(RA delivered no completion for these correlations)",
                    expired.size(), registry.size(), registry.evictedCount());
        }
    }
}
