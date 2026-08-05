package et.restlink.ussdgw.logging;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** SLEE boundary tracing — logger name SLEE only. Do not dual-log with LOG.info. */
public final class SleeEventTrace {
    private static final Logger LOG = LogManager.getLogger("SLEE");
    private SleeEventTrace() {}

    public static void inSbb(String sbb, Object event) {
        LOG.info("IN  SBB={} event={}", sbb, type(event));
    }
    public static void inSbb(String sbb, Object event, String detail) {
        if (detail == null || detail.isBlank()) { inSbb(sbb, event); return; }
        LOG.info("IN  SBB={} event={} {}", sbb, type(event), detail);
    }
    public static void outSbb(String sbb, Object event, String detail) {
        if (detail == null || detail.isBlank()) {
            LOG.info("OUT SBB={} event={}", sbb, type(event));
            return;
        }
        LOG.info("OUT SBB={} event={} {}", sbb, type(event), detail);
    }

    public static void raFire(String ra, Object event, String detail) {
        if (detail == null || detail.isBlank()) {
            LOG.info("FIRE RA={} event={}", ra, type(event));
            return;
        }
        LOG.info("FIRE RA={} event={} {}", ra, type(event), detail);
    }

    public static void raOut(String ra, String action, String detail) {
        if (detail == null || detail.isBlank()) {
            LOG.info("OUT RA={} action={}", ra, action);
            return;
        }
        LOG.info("OUT RA={} action={} {}", ra, action, detail);
    }

    private static String type(Object event) {
        return event == null ? "null" : event.getClass().getSimpleName();
    }
}
