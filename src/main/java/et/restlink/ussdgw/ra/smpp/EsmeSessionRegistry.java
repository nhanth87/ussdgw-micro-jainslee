package et.restlink.ussdgw.ra.smpp;

import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppServerSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks bound ESME sessions on the SMPP server (status / peer-UP, future DLR).
 *
 * <p>Holds <em>every</em> session for a {@code systemId}, not the most recent one. A single
 * slot meant a second bind on the same {@code systemId} silently took over the tenant's
 * delivery-receipt stream from whoever was already bound; now the earliest still-bound
 * receiver/transceiver keeps it, and the extra session is only a fallback.
 */
public final class EsmeSessionRegistry {

    private static final Logger LOG = LogManager.getLogger(EsmeSessionRegistry.class);

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SmppServerSession>> bySystemId =
            new ConcurrentHashMap<>();

    public void put(String systemId, SmppServerSession session) {
        if (systemId == null || session == null) {
            return;
        }
        List<SmppServerSession> list = bySystemId.computeIfAbsent(
                systemId, k -> new CopyOnWriteArrayList<>());
        list.removeIf(s -> s == session || !s.isBound());
        list.add(session);
        if (list.size() > 1) {
            LOG.warn("[esme-sessions] systemId={} now has {} bound sessions — delivery receipts "
                    + "stay with the earliest bound receiver/transceiver", systemId, list.size());
        }
        LOG.info("[esme-sessions] bound systemId={} sessions={}", systemId, list.size());
    }

    public void remove(String systemId, SmppServerSession session) {
        if (systemId == null) {
            return;
        }
        bySystemId.computeIfPresent(systemId, (k, list) -> {
            list.removeIf(s -> s == session);
            return list.isEmpty() ? null : list;
        });
        LOG.info("[esme-sessions] unbound systemId={}", systemId);
    }

    public void clear() {
        bySystemId.clear();
    }

    /**
     * Deterministic target for outbound {@code deliver_sm}: earliest still-bound session that
     * can receive (TRANSCEIVER or RECEIVER), else earliest still-bound session of any type.
     */
    public Optional<SmppServerSession> session(String systemId) {
        if (systemId == null) {
            return Optional.empty();
        }
        List<SmppServerSession> list = bySystemId.get(systemId);
        if (list == null) {
            return Optional.empty();
        }
        SmppServerSession anyBound = null;
        for (SmppServerSession s : list) {
            if (s == null || !s.isBound()) {
                continue;
            }
            if (canReceive(s)) {
                return Optional.of(s);
            }
            if (anyBound == null) {
                anyBound = s;
            }
        }
        return Optional.ofNullable(anyBound);
    }

    private static boolean canReceive(SmppServerSession s) {
        SmppBindType type = s.getConfiguration() == null ? null : s.getConfiguration().getType();
        return type == SmppBindType.TRANSCEIVER || type == SmppBindType.RECEIVER;
    }

    /** One representative session per {@code systemId} (status pages). */
    public Map<String, SmppServerSession> snapshot() {
        Map<String, SmppServerSession> out = new LinkedHashMap<>();
        for (String systemId : new ArrayList<>(bySystemId.keySet())) {
            session(systemId).ifPresent(s -> out.put(systemId, s));
        }
        return Map.copyOf(out);
    }

    /** True when at least one still-BOUND ESME session exists (peer UP for server role). */
    public boolean hasBoundSession() {
        for (String systemId : new ArrayList<>(bySystemId.keySet())) {
            if (session(systemId).isPresent()) {
                return true;
            }
        }
        return false;
    }
}
