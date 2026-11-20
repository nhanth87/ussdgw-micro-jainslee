package et.restlink.ussdgw.service;

import et.restlink.ussdgw.bootstrap.ClusterBootstrap;

import com.microjainslee.cluster.ClusterManager;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.Serializable;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;

/**
 * Idempotent NI async-flow index (HTTP plane, ADR 0007 D10 pattern).
 *
 * <p>A client starts an NI push with {@code X-USSD-Activity-Id} and gets
 * {@code 202 {flowId, statusUrl}}; a retry with the <em>same</em> activity id
 * returns the <em>same</em> flow instead of starting a second MAP dialog.
 * {@code GET {niPath}/status/{flowId}} reports the flow state.
 *
 * <p>Backing: the shared {@code ClusterManager} cache when the SS7 cluster is
 * enabled (atomic {@code putIfAbsent} across nodes), otherwise a local map
 * (single node). Flow ids are the session correlation ids, so the MAP dialog,
 * the park record and this index name the same flow.
 *
 * <p>States: {@code PARKED} → {@code COMPLETE} | {@code GATE_EXPIRED} |
 * {@code ABORTED}. A flow whose session and park are both gone but whose
 * snapshot says {@code PARKED} is reported {@code COMPLETE} (terminal rows are
 * reclaimed; the snapshot only moves forward).
 */
@ApplicationScoped
public class NiFlowRegistry {
    private static final Logger LOG = LogManager.getLogger(NiFlowRegistry.class);

    /** Shared cache name on the ClusterManager (REPL_SYNC when clustered). */
    public static final String CACHE_NAME = "ussd.http.ni-flows";

    /** Activity index key prefix ({@code act:<activityId>} → flowId). */
    static final String ACT_PREFIX = "act:";

    /** Flow key prefix ({@code flow:<flowId>} → FlowRecord). */
    static final String FLOW_PREFIX = "flow:";

    /** Best-effort bound for the local map; the shared cache relies on TTL sweep. */
    static final int LOCAL_CAP = 20_000;

    /** Flows older than this with no update are eligible for eviction (30 min). */
    static final long FLOW_TTL_MS = 30L * 60L * 1000L;

    /** Canonical idempotency header on {@code POST {niPath}}. */
    public static final String ACTIVITY_HEADER = "X-USSD-Activity-Id";

    public record FlowRecord(String flowId, String activityId, String nodeId,
                             String msisdn, String state, String lastText,
                             long createdAtMs, long updatedAtMs) implements Serializable {
        public FlowRecord withState(String newState, String text) {
            return new FlowRecord(flowId, activityId, nodeId, msisdn, newState,
                    text == null ? lastText : text, createdAtMs, System.currentTimeMillis());
        }
    }

    @Inject ClusterBootstrap cluster;

    @ConfigProperty(name = "ussd.cluster.node-id", defaultValue = "ussdgw-1")
    String nodeIdProp = "ussdgw-1";

    private final ConcurrentHashMap<String, Object> local = new ConcurrentHashMap<>();
    private volatile Cache<String, Object> shared;

    /**
     * Claim the flow for an activity id, creating it when absent. Atomic across
     * nodes when clustered: exactly one claimant wins, every retry — on any
     * node — gets the same {@link FlowRecord}.
     *
     * @return the winning record; {@link Claim#duplicate()} tells whether this
     *         call created it ({@code false}) or replayed it ({@code true})
     */
    public Claim claim(String activityId, String msisdn) {
        String act = activityId == null ? "" : activityId.trim();
        if (act.isEmpty()) {
            throw new IllegalArgumentException("activityId required");
        }
        String nodeId = nodeId();
        long now = System.currentTimeMillis();
        Cache<String, Object> c = cache();
        String actKey = ACT_PREFIX + act;
        if (c != null) {
            Object have = c.get(actKey);
            if (have instanceof FlowRecord fr) {
                return new Claim(fr, true);
            }
            FlowRecord created = new FlowRecord(UUID.randomUUID().toString(), act,
                    nodeId, msisdn == null ? "" : msisdn, "PARKED", null, now, now);
            Object raced = c.putIfAbsent(actKey, created.flowId());
            String flowId = raced instanceof String s ? s : created.flowId();
            if (raced != null) {
                // Lost the race: the winner's flow record may land a beat after
                // its activity key — poll briefly, then fall back to the id.
                FlowRecord winner = null;
                for (int i = 0; i < 10 && winner == null; i++) {
                    winner = getFlow(flowId).orElse(null);
                    if (winner == null) {
                        Thread.yield();
                    }
                }
                if (winner == null) {
                    winner = new FlowRecord(flowId, act, nodeId,
                            msisdn == null ? "" : msisdn, "PARKED", null, now, now);
                }
                return new Claim(winner, true);
            }
            c.put(FLOW_PREFIX + flowId, created);
            return new Claim(created, false);
        }
        synchronized (local) {
            evictExpiredLocked(now);
            Object have = local.get(actKey);
            if (have instanceof String flowId) {
                FlowRecord fr = getLocalFlow(flowId).orElse(null);
                if (fr != null) {
                    return new Claim(fr, true);
                }
            }
            FlowRecord created = new FlowRecord(UUID.randomUUID().toString(), act,
                    nodeId, msisdn == null ? "" : msisdn, "PARKED", null, now, now);
            local.put(actKey, created.flowId());
            local.put(FLOW_PREFIX + created.flowId(), created);
            return new Claim(created, false);
        }
    }

    public Optional<FlowRecord> findByActivity(String activityId) {
        if (activityId == null || activityId.isBlank()) {
            return Optional.empty();
        }
        Cache<String, Object> c = cache();
        String actKey = ACT_PREFIX + activityId.trim();
        if (c != null) {
            Object v = c.get(actKey);
            if (v instanceof String flowId) {
                return getFlow(flowId);
            }
            return Optional.empty();
        }
        Object v = local.get(actKey);
        if (v instanceof String flowId) {
            return getLocalFlow(flowId);
        }
        return Optional.empty();
    }

    public Optional<FlowRecord> findByFlow(String flowId) {
        if (flowId == null || flowId.isBlank()) {
            return Optional.empty();
        }
        return getFlow(flowId.trim());
    }

    private Optional<FlowRecord> getFlow(String flowId) {
        Cache<String, Object> c = cache();
        if (c != null) {
            Object v = c.get(FLOW_PREFIX + flowId);
            return v instanceof FlowRecord fr ? Optional.of(fr) : Optional.empty();
        }
        return getLocalFlow(flowId);
    }

    private Optional<FlowRecord> getLocalFlow(String flowId) {
        Object v = local.get(FLOW_PREFIX + flowId);
        return v instanceof FlowRecord fr ? Optional.of(fr) : Optional.empty();
    }

    /** Move a flow snapshot forward (COMPLETE / GATE_EXPIRED / ABORTED). Never backwards. */
    public void mark(String flowId, String state, String lastText) {
        if (flowId == null || flowId.isBlank() || state == null || state.isBlank()) {
            return;
        }
        String key = FLOW_PREFIX + flowId.trim();
        Cache<String, Object> c = cache();
        if (c != null) {
            Object v = c.get(key);
            if (v instanceof FlowRecord fr && !fr.state().equals(state)) {
                c.put(key, fr.withState(state, lastText));
            }
            return;
        }
        synchronized (local) {
            Object v = local.get(key);
            if (v instanceof FlowRecord fr && !fr.state().equals(state)) {
                local.put(key, fr.withState(state, lastText));
            }
        }
    }

    /** Test / admin visibility. */
    public int size() {
        Cache<String, Object> c = cache();
        if (c != null) {
            return c.size();
        }
        return local.size() / 2;
    }

    private String nodeId() {
        try {
            ClusterManager cm = cluster != null ? cluster.clusterManager() : null;
            if (cm != null) {
                return cm.getNodeId();
            }
        } catch (RuntimeException ignored) {
            // fall through to configured id
        }
        String id = nodeIdProp == null ? "" : nodeIdProp.trim();
        return id.isEmpty() ? "ussdgw-1" : id;
    }

    private Cache<String, Object> cache() {
        ClusterManager cm = null;
        try {
            cm = cluster != null ? cluster.clusterManager() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
        if (cm == null) {
            return null;
        }
        Cache<String, Object> c = shared;
        if (c == null) {
            synchronized (this) {
                c = shared;
                if (c == null) {
                    CacheMode mode = cm.isClusterMode() ? CacheMode.REPL_SYNC : CacheMode.LOCAL;
                    c = cm.getCache(CACHE_NAME, mode);
                    shared = c;
                    LOG.info("NiFlowRegistry shared backing node={} clustered={}",
                            cm.getNodeId(), cm.isClusterMode());
                }
            }
        }
        return c;
    }

    private void evictExpiredLocked(long now) {
        if (local.size() < LOCAL_CAP) {
            return;
        }
        local.entrySet().removeIf(e -> e.getValue() instanceof FlowRecord fr
                && now - fr.updatedAtMs() > FLOW_TTL_MS);
    }

    public record Claim(FlowRecord flow, boolean duplicate) {}

    /** Test seam — header lookup is case-insensitive. */
    public static String activityHeader(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && ACTIVITY_HEADER.equalsIgnoreCase(e.getKey())) {
                String v = e.getValue();
                if (v != null && !v.isBlank()) {
                    return v.trim();
                }
            }
        }
        return null;
    }
}
