package et.restlink.ussdgw.telemetry;

import et.restlink.ussdgw.service.BridgeGateScheduler;
import et.restlink.ussdgw.service.PendingMap2MapRegistry;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.telemetry.MicrometerTelemetryPort;
import com.microjainslee.telemetry.TelemetryDispatchObserver;
import com.microjainslee.telemetry.TelemetryPort;
import com.microjainslee.telemetry.TelemetryRaObserver;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Boots {@link MicrometerTelemetryPort} for Monitor Hub {@code /api/telemetry/*}
 * and registers GW gauges (MAP2MAP / bridge gate). No second Prometheus listen port —
 * scrape stays on the HTTP RA plane.
 */
@ApplicationScoped
public class AppTelemetry {
    private static final Logger LOG = LogManager.getLogger(AppTelemetry.class);

    @Inject Map2MapTelemetry map2Map;
    @Inject BridgeGateScheduler bridgeGate;
    @Inject PendingMap2MapRegistry pendingMap2Map;

    private volatile MicrometerTelemetryPort port;

    public TelemetryPort install(MicroSleeContainer container) {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MicrometerTelemetryPort tp = new MicrometerTelemetryPort(registry, container);
        tp.start();
        container.getEventRouter().setDispatchObserver(new TelemetryDispatchObserver(tp));
        container.setRaObserver(new TelemetryRaObserver(tp));

        tp.customGauge("ussdgw_map2map_armed", map2Map::armedCount);
        tp.customGauge("ussdgw_map2map_pending",
                () -> pendingMap2Map == null ? 0 : pendingMap2Map.size());
        tp.customGauge("ussdgw_scheduler_gate_ticks",
                () -> bridgeGate == null ? 0 : bridgeGate.gateTicks());
        tp.customGauge("ussdgw_scheduler_gate_expired",
                () -> bridgeGate == null ? 0 : bridgeGate.gateExpired());
        tp.customGauge("ussdgw_map2map_as_routed", map2Map::asRoutedCount);
        tp.customGauge("ussdgw_map2map_gated_during_hop", map2Map::gatedDuringHopCount);

        this.port = tp;
        LOG.info("USSD GW telemetry armed (MicrometerTelemetryPort → /api/telemetry + Monitor Hub charts)");
        return tp;
    }

    public TelemetryPort port() {
        return port;
    }

    public Map2MapTelemetry map2Map() {
        return map2Map;
    }

    public void close() {
        MicrometerTelemetryPort tp = port;
        port = null;
        if (tp != null) {
            try {
                tp.stop();
            } catch (RuntimeException e) {
                LOG.debug("telemetry stop: {}", e.toString());
            }
        }
    }
}
