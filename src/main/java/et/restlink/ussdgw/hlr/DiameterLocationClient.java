package et.restlink.ussdgw.hlr;

import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Thin S6a ULR/ULA stub client. Live path uses {@link SendDiameterRequest};
 * unit tests inject stub answers without ra-diameter.
 */
@ApplicationScoped
public class DiameterLocationClient {
    private static final Logger LOG = LogManager.getLogger(DiameterLocationClient.class);

    /** 3GPP S6a application id. */
    public static final long S6A_APP_ID = 16777251L;
    /** Update-Location Request command code. */
    public static final int ULR_CODE = 316;

    public record Location(String imsi, String mscGt, byte[] lmsi) {}

    @Inject et.restlink.ussdgw.config.UssdConfigService config;

    private volatile Supplier<RaCommandPort> diameterPort = () -> null;
    private volatile boolean stubForced;
    private final ConcurrentHashMap<String, Location> stubByMsisdn = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Location> pendingUla = new ConcurrentHashMap<>();

    /** Test/lab: force stub answers regardless of RA. */
    public void forceStub(boolean on) {
        this.stubForced = on;
    }

    public void putStub(String msisdn, Location loc) {
        if (msisdn != null && loc != null) {
            stubByMsisdn.put(digits(msisdn), loc);
        }
    }

    public void setDiameterPortSupplier(Supplier<RaCommandPort> supplier) {
        this.diameterPort = supplier != null ? supplier : () -> null;
    }

    /**
     * Resolve location for MSISDN. Stub or config fake when no Diameter peer;
     * otherwise sends ULR and returns empty (async ULA via {@link #completeUla}).
     */
    public Optional<Location> lookupSyncOrStub(String msisdn) {
        String ms = digits(msisdn);
        Location stub = stubByMsisdn.get(ms);
        if (stub != null) {
            return Optional.of(stub);
        }
        if (stubForced || !config.diameterEnabled()) {
            String imsi = config.hlrFakeImsi();
            String msc = config.hlrFakeMscGt();
            if (imsi == null || imsi.isBlank() || msc == null || msc.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Location(imsi, msc, null));
        }
        RaCommandPort port = diameterPort.get();
        if (port == null) {
            return Optional.empty();
        }
        String sessionId = "ulr-" + UUID.randomUUID();
        Map<Integer, String> avps = new LinkedHashMap<>();
        avps.put(1, ms); // User-Name / MSISDN carrier for stub
        avps.put(600, config.hlrDiamDestinationHost()); // optional
        try {
            port.sendCommand(new SendDiameterRequest(
                    sessionId,
                    S6A_APP_ID,
                    ULR_CODE,
                    config.hlrDiamDestinationHost(),
                    config.hlrDiamDestinationRealm(),
                    avps));
            LOG.info("ULR stub sent session={} msisdn={}", sessionId, ms);
            // Sync stub path: if pending ULA pre-seeded for tests
            Location pre = pendingUla.remove(sessionId);
            if (pre != null) {
                return Optional.of(pre);
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            LOG.warn("ULR send failed: {}", e.toString());
            return Optional.empty();
        }
    }

    /** Complete async ULA for a prior ULR session (lab / future RA event). */
    public void completeUla(String sessionId, Location loc) {
        if (sessionId != null && loc != null) {
            pendingUla.put(sessionId, loc);
        }
    }

    private static String digits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') b.append(c);
        }
        return b.toString();
    }
}
