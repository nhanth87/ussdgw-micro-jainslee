package et.restlink.ussdgw.telemetry;

import com.microjainslee.core.MicroSleeContainer;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@ApplicationScoped
public class AppTelemetry {
    private static final Logger LOG = LogManager.getLogger(AppTelemetry.class);

    public void install(MicroSleeContainer container) {
        LOG.info("USSD GW telemetry install (container state={})", container.getState());
    }

    public void close() {}
}
