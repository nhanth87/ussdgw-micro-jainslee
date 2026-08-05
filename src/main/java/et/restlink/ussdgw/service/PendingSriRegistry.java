package et.restlink.ussdgw.service;

import et.restlink.ussdgw.events.NiPushRequestEvent;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PendingSriRegistry {
    private final ConcurrentHashMap<String, NiPushRequestEvent> pending = new ConcurrentHashMap<>();

    public void put(String correlationId, NiPushRequestEvent ni) {
        pending.put(correlationId, ni);
    }

    public Optional<NiPushRequestEvent> take(String correlationId) {
        NiPushRequestEvent ni = pending.remove(correlationId);
        if (ni != null) return Optional.of(ni);
        var it = pending.entrySet().iterator();
        if (it.hasNext()) {
            var e = it.next();
            it.remove();
            return Optional.of(e.getValue());
        }
        return Optional.empty();
    }
}
