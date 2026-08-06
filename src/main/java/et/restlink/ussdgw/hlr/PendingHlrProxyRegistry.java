package et.restlink.ussdgw.hlr;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks inbound HLR dialog awaiting upper MAP SRI-SM (or Diameter) resolve.
 * Keyed by outbound correlation / upper dialog id.
 */
@ApplicationScoped
public class PendingHlrProxyRegistry {

    public record Pending(
            String inboundDialogId,
            long inboundInvokeId,
            String msisdn,
            int networkId,
            String scAddress,
            HlrResolveMode mode
    ) {}

    private final ConcurrentHashMap<String, Pending> byOutboundCorr = new ConcurrentHashMap<>();

    public void put(String outboundCorr, Pending pending) {
        if (outboundCorr != null && pending != null) {
            byOutboundCorr.put(outboundCorr, pending);
        }
    }

    public Optional<Pending> take(String outboundCorr) {
        if (outboundCorr == null) return Optional.empty();
        Pending p = byOutboundCorr.remove(outboundCorr);
        return Optional.ofNullable(p);
    }

    public Optional<Pending> takeAny() {
        var it = byOutboundCorr.entrySet().iterator();
        if (!it.hasNext()) return Optional.empty();
        var e = it.next();
        it.remove();
        return Optional.of(e.getValue());
    }

    public int size() {
        return byOutboundCorr.size();
    }
}
