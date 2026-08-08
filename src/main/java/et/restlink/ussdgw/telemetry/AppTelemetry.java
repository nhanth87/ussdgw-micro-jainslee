package et.restlink.ussdgw.telemetry;

import com.microjainslee.core.MicroSleeContainer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Boot hook for GW telemetry. MAP2MAP counters live on {@link Map2MapTelemetry}
 * and are merged into {@code /admin/status.json}.
 */
@ApplicationScoped
public class AppTelemetry {
    private static final Logger LOG = LogManager.getLogger(AppTelemetry.class);

    @Inject Map2MapTelemetry map2Map;

    public void install(MicroSleeContainer container) {
        LOG.info("USSD GW telemetry install (container state={}) map2map counters armed",
                container.getState());
    }

    public Map2MapTelemetry map2Map() {
        return map2Map;
    }

    public void close() {}
}
