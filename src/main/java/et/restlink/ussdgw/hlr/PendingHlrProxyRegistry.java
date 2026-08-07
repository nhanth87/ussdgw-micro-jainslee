package et.restlink.ussdgw.hlr;

import com.microjainslee.api.RaCommandPort;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Inbound HLR dialog awaiting an upper MAP SRI-SM (or Diameter) resolve, keyed strictly by the
 * outbound correlation id. Classic correlates through the per-query {@code SriSbb} child, so an
 * answer that matches nothing resolves to nothing; the same holds here.
 */
@ApplicationScoped
public class PendingHlrProxyRegistry {
    static final long DEFAULT_TTL_MS = 15_000L;

    /**
     * @param enrichOnly the inbound dialog was already answered (FAKE_THEN_RESOLVE); the upper
     *                   answer only refreshes the location cache and must not be relayed
     */
    public record Pending(
            String inboundDialogId,
            long inboundInvokeId,
            String msisdn,
            int networkId,
            String scAddress,
            HlrResolveMode mode,
            boolean enrichOnly
    ) {
        public Pending(String inboundDialogId, long inboundInvokeId, String msisdn, int networkId,
                       String scAddress, HlrResolveMode mode) {
            this(inboundDialogId, inboundInvokeId, msisdn, networkId, scAddress, mode, false);
        }
    }

    private record Entry(Pending pending, long expiresAtMs) {}

    /** Canonical key matches {@code RuntimeConfigStore.Keys.HLR_PROXY_PENDING_TTL_MS}. */
    @ConfigProperty(name = "ussd.hlr.proxy.pending-ttl-ms", defaultValue = "15000")
    long ttlMsProp;

    private final ConcurrentHashMap<String, Entry> byOutboundCorr = new ConcurrentHashMap<>();

    /**
     * ra-jss7 port used to abort inbound dialogs whose upper query expired. Bound with the SS7
     * plane because TTL reclaim runs off the SLEE event path and has no injected RA.
     */
    private volatile Supplier<RaCommandPort> ss7Supplier = () -> null;

    public void bindSs7(Supplier<? extends RaCommandPort> supplier) {
        this.ss7Supplier = supplier == null ? () -> null : supplier::get;
    }

    public RaCommandPort ss7() {
        try {
            return ss7Supplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void put(String outboundCorr, Pending pending) {
        put(outboundCorr, pending, System.currentTimeMillis());
    }

    public void put(String outboundCorr, Pending pending, long nowMs) {
        if (outboundCorr == null || outboundCorr.isBlank() || pending == null) {
            return;
        }
        byOutboundCorr.put(outboundCorr.trim(), new Entry(pending, nowMs + ttlMs()));
    }

    public Optional<Pending> take(String outboundCorr) {
        if (outboundCorr == null || outboundCorr.isBlank()) {
            return Optional.empty();
        }
        Entry e = byOutboundCorr.remove(outboundCorr.trim());
        return e == null ? Optional.empty() : Optional.of(e.pending());
    }

    /** Non-destructive membership test for SRI-response dispatch. */
    public boolean contains(String outboundCorr) {
        return outboundCorr != null && !outboundCorr.isBlank()
                && byOutboundCorr.containsKey(outboundCorr.trim());
    }

    /** Remove and return every entry whose TTL elapsed; caller aborts each inbound dialog. */
    public List<Pending> sweepExpired(long nowMs) {
        List<Pending> expired = new ArrayList<>();
        byOutboundCorr.entrySet().removeIf(e -> {
            if (e.getValue().expiresAtMs() > nowMs) {
                return false;
            }
            expired.add(e.getValue().pending());
            return true;
        });
        return expired;
    }

    public long ttlMs() {
        return ttlMsProp > 0 ? ttlMsProp : DEFAULT_TTL_MS;
    }

    public int size() {
        return byOutboundCorr.size();
    }
}
