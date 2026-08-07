package et.restlink.ussdgw.hlr;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Subscriber location learned from an upper HLR, so a later {@code FAKE_THEN_RESOLVE} answers with
 * the real IMSI/MSC instead of the configured fake. Bounded and TTL-scoped — a stale entry is
 * dropped rather than served.
 */
@ApplicationScoped
public class HlrLocationCache {
    static final long DEFAULT_TTL_MS = 300_000L;
    static final int MAX_ENTRIES = 50_000;

    public record Location(String imsi, String mscGt, byte[] lmsi) {}

    private record Entry(Location location, long expiresAtMs) {}

    @ConfigProperty(name = "ussd.hlr.location-cache-ttl-ms", defaultValue = "300000")
    long ttlMsProp;

    private final ConcurrentHashMap<String, Entry> byMsisdn = new ConcurrentHashMap<>();

    public void put(String msisdn, String imsi, String mscGt, byte[] lmsi) {
        put(msisdn, imsi, mscGt, lmsi, System.currentTimeMillis());
    }

    public void put(String msisdn, String imsi, String mscGt, byte[] lmsi, long nowMs) {
        String key = digits(msisdn);
        if (key.isEmpty() || imsi == null || imsi.isBlank() || mscGt == null || mscGt.isBlank()) {
            return;
        }
        if (byMsisdn.size() >= MAX_ENTRIES && !byMsisdn.containsKey(key)) {
            sweepExpired(nowMs);
            if (byMsisdn.size() >= MAX_ENTRIES) {
                return;
            }
        }
        byMsisdn.put(key, new Entry(new Location(imsi, mscGt, lmsi), nowMs + ttlMs()));
    }

    public Optional<Location> get(String msisdn) {
        return get(msisdn, System.currentTimeMillis());
    }

    public Optional<Location> get(String msisdn, long nowMs) {
        String key = digits(msisdn);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        Entry e = byMsisdn.get(key);
        if (e == null) {
            return Optional.empty();
        }
        if (e.expiresAtMs() <= nowMs) {
            byMsisdn.remove(key, e);
            return Optional.empty();
        }
        return Optional.of(e.location());
    }

    public int sweepExpired(long nowMs) {
        int before = byMsisdn.size();
        byMsisdn.entrySet().removeIf(e -> e.getValue().expiresAtMs() <= nowMs);
        return before - byMsisdn.size();
    }

    public long ttlMs() {
        return ttlMsProp > 0 ? ttlMsProp : DEFAULT_TTL_MS;
    }

    public int size() {
        return byMsisdn.size();
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
