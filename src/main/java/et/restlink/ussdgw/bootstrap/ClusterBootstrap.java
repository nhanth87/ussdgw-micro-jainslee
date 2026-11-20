package et.restlink.ussdgw.bootstrap;

import et.restlink.ussdgw.config.UssdConfigService;

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.core.MicroSleeConfiguration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Active/active SS7 cluster bootstrap (ADR 0007 D11/D12).
 *
 * <p>OFF by default — single-node behaviour is unchanged. When
 * {@code ussd.cluster.enabled=true} this builds the app-owned
 * {@link ClusterManager} (JGroups/Infinispan, same fabric as the GMLC profile
 * store) and {@code Ss7ApplyService} hands it to ra-jss7 via
 * {@code setClusterManager} <em>before</em> {@code raActive()}:
 * <ul>
 *   <li>OTID ranges per node — cluster mode <strong>refuses to start</strong>
 *       unless {@code ussd.ss7.otid-range-start > 0} and
 *       {@code end > start}; disjointness across nodes is enforced at join
 *       time by {@code Ss7InboundDialogRouter} (overlap throws).</li>
 *   <li>Cross-node CONTINUE/END/ABORT forwarding ({@code Ss7InboundDialogRouter}
 *       + {@code ClusterUnicast}), lease fence and takeover
 *       ({@code RaDialogLeaseCaches}), witness in
 *       {@code ussd.cluster.initial-hosts}, per-node
 *       {@code ussd.ss7.sctp-local-endpoints[index]}, preferred ASP pinning
 *       via {@code Ss7PeerRouteService}.</li>
 * </ul>
 */
@ApplicationScoped
public class ClusterBootstrap {
    private static final Logger LOG = LogManager.getLogger(ClusterBootstrap.class);

    @Inject UssdConfigService config;

    private volatile ClusterManager clusterManager;

    /** CDI hook — also called explicitly by tests via {@link #startIfEnabled()}. */
    @PostConstruct
    void init() {
        startIfEnabled();
    }

    /**
     * Build + start the cluster when enabled, else leave {@link #clusterManager()}
     * {@code null} (single node). Fail-fast on a bad OTID range or blank
     * initial-hosts — a half-joined node must never start.
     */
    public synchronized void startIfEnabled() {
        if (clusterManager != null) {
            return;
        }
        UssdConfigService cfg = config != null ? config : new UssdConfigService();
        if (!cfg.clusterEnabled()) {
            LOG.info("ClusterBootstrap: cluster OFF (ussd.cluster.enabled=false) — "
                    + "single-node SS7, peer-route LB local");
            return;
        }
        validateClusterConfig(cfg.clusterNodeId(), cfg.clusterStack(),
                cfg.clusterInitialHosts(), cfg.otidRangeStart(), cfg.otidRangeEnd());
        MicroSleeConfiguration ms = MicroSleeConfiguration.builder()
                .clusterEnabled(true)
                .clusterStack(cfg.clusterStack())
                .clusterInitialHosts(cfg.clusterInitialHosts())
                .nodeId(cfg.clusterNodeId())
                .build();
        ClusterManager manager = new ClusterManager(ms, cfg.clusterNodeId());
        manager.start();
        clusterManager = manager;
        LOG.info("ClusterBootstrap: cluster ON node={} stack={} otid=[{}, {}] clustered={}",
                manager.getNodeId(), cfg.clusterStack(),
                cfg.otidRangeStart(), cfg.otidRangeEnd(), manager.isClusterMode());
    }

    /**
     * Fail-fast validation for cluster boot. Pure (no network) so unit tests pin it.
     *
     * @throws IllegalStateException when enabled config cannot form a safe view
     */
    public static void validateClusterConfig(String nodeId, String stack, String initialHosts,
                                             long otidStart, long otidEnd) {
        if (otidStart <= 0 || otidEnd <= otidStart) {
            throw new IllegalStateException(
                    "Cluster refuses to start: ussd.ss7.otid-range-start/end must be "
                            + "start > 0 and end > start (got [" + otidStart + ", " + otidEnd + "]); "
                            + "ranges must be disjoint across nodes — ADR 0007 D11");
        }
        if (initialHosts == null || initialHosts.isBlank()) {
            throw new IllegalStateException(
                    "Cluster refuses to start: ussd.cluster.initial-hosts is blank — "
                            + "list every node plus the witness (ADR 0007 D12)");
        }
        String[] members = initialHosts.split(",");
        int count = 0;
        boolean selfListed = false;
        String self = nodeId == null ? "" : nodeId.trim();
        for (String m : members) {
            String host = m == null ? "" : m.trim();
            if (host.isEmpty()) {
                continue;
            }
            count++;
            String name = host.contains("[") ? host.substring(0, host.indexOf('[')) : host;
            if (!self.isEmpty() && name.equalsIgnoreCase(self)) {
                selfListed = true;
            }
        }
        if (count < 3) {
            LOG.warn("Cluster initial-hosts has {} member(s) — a 2-node view without a "
                    + "witness cannot take over under partition (both sides fence, "
                    + "ADR 0007 D12). Add witness-1 (or a third node). hosts={}",
                    count, initialHosts);
        }
        if (!selfListed) {
            LOG.warn("Cluster node-id '{}' not listed in ussd.cluster.initial-hosts={} — "
                    + "TCPPING discovery may still work via gossip, but list it explicitly. "
                    + "stack={}", nodeId, initialHosts, stack == null ? "tcp" : stack);
        }
    }

    /** The running cluster, or {@code null} when clustering is OFF (single node). */
    public ClusterManager clusterManager() {
        return clusterManager;
    }

    public boolean enabled() {
        return clusterManager != null;
    }

    /** Flat status for {@code /admin/status.json} ({@code cluster.*}). */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        ClusterManager cm = clusterManager;
        UssdConfigService cfg = config != null ? config : new UssdConfigService();
        boolean on = cm != null && cfg.clusterEnabled();
        out.put("cluster.enabled", on);
        out.put("cluster.nodeId", cm != null ? cm.getNodeId() : cfg.clusterNodeId());
        out.put("cluster.mode", cm != null && cm.isClusterMode() ? "REPL_SYNC" : "LOCAL");
        out.put("cluster.otidRange",
                "[" + cfg.otidRangeStart() + ", " + cfg.otidRangeEnd() + "]");
        out.put("cluster.initialHosts", cfg.clusterInitialHosts());
        return out;
    }

    @PreDestroy
    void stop() {
        ClusterManager cm = clusterManager;
        clusterManager = null;
        if (cm != null) {
            try {
                cm.stop();
            } catch (RuntimeException e) {
                LOG.warn("Cluster stop: {}", e.toString());
            }
        }
    }
}
