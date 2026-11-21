package et.restlink.ussdgw.bootstrap;

import et.restlink.ussdgw.profile.UssdClusteredProfileStore;
import et.restlink.ussdgw.profile.UssdTxProfile;
import et.restlink.ussdgw.profile.UssdUserProfile;

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;

import io.quarkus.runtime.StartupEvent;

import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.infinispan.configuration.cache.CacheMode;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Feature-flagged application cluster for the USSD gateway (GMLC parity).
 *
 * <p>{@code ussd.cluster.enabled=true} builds a {@link ClusterManager} exactly the way
 * GMLC does: JGroups TCP stack + TCPPING (bundled {@code jgroups-tcp-ping.xml}, because
 * MPING multicast is blocked on VM/cloud NICs) with {@code initial_hosts} substituted,
 * then declares {@code ussdTx} / {@code ussdUser} as {@link CacheMode#REPL_SYNC} and
 * installs {@link UssdClusteredProfileStore} as the container durable store so Profile CMP
 * writes replicate to every node.</p>
 *
 * <p><b>Digicom-safe default is OFF.</b> When false the previous stub behaviour is kept
 * (log inactive, {@link #clusterManager()} returns {@code null}, Profile CMP stays
 * JVM-local, peer-route LB has no {@code ClusterManager}).</p>
 *
 * <p><b>Fail-safe:</b> if construction throws — e.g. the Infinispan API on the Digicom
 * classpath does not match {@code jainslee-cluster} ({@code Attribute.set} and friends) —
 * the error is caught, a WARN blocker is logged, and the app stays up on the stub path.
 * It never kills the gateway and never fakes an active cluster.</p>
 */
@ApplicationScoped
public class ClusterBootstrap {
    private static final Logger LOG = LogManager.getLogger(ClusterBootstrap.class);

    /** JGroups TCP stack (bundled TCPPING stack inside jainslee-cluster). */
    static final String DEFAULT_STACK = "tcp";
    /** Loopback single-node default when {@code ussd.cluster.initial-hosts} is blank. */
    static final String DEFAULT_INITIAL_HOSTS = "127.0.0.1[7800]";

    @ConfigProperty(name = "ussd.cluster.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "ussd.cluster.initial-hosts", defaultValue = "")
    String initialHosts;

    @ConfigProperty(name = "ussd.cluster.profile-dir", defaultValue = "data/ussd-profiles")
    String profileDir;

    @ConfigProperty(name = "ussd.cluster.mode", defaultValue = "REPL_SYNC")
    String mode;

    @ConfigProperty(name = "ussd.cluster.node-id", defaultValue = "ussd-1")
    String nodeId;

    @Inject MicroSleeContainer container;

    private volatile ClusterManager clusterManager;
    private volatile UssdClusteredProfileStore profileStore;
    private volatile String blocker;

    void onStart(@Observes @Priority(50) StartupEvent ev) {
        if (!enabled) {
            LOG.info("ClusterBootstrap inactive (ussd.cluster.enabled=false) — Profile CMP stays "
                    + "JVM-local; peer-route LB has no ClusterManager");
            return;
        }
        LOG.info("ClusterBootstrap: ussd.cluster.enabled=true — building ClusterManager "
                        + "stack={} initialHosts={} nodeId={} mode={} profileDir={}",
                DEFAULT_STACK, initialHostsBlank() ? DEFAULT_INITIAL_HOSTS : initialHosts,
                nodeId, mode, profileDir);
        ClusterManager manager = null;
        UssdClusteredProfileStore store = null;
        try {
            manager = buildManager();
            store = buildStore(manager);
            install(manager, store);
        } catch (Throwable t) {
            blocker = describeBlocker(t);
            LOG.warn("cluster requested but ISPN API mismatch — staying inactive: {}", blocker, t);
            cleanup(manager, store);
            return;
        }
        this.clusterManager = manager;
        this.profileStore = store;
        this.blocker = null;
        LOG.info("ClusterBootstrap active: nodeId={} clustered={} mode={} persist={} "
                        + "cache.ussdTx={} cache.ussdUser={}",
                manager.getNodeId(), manager.isClustered(), mode,
                store.status().get("cluster.profile.persist"),
                store.status().get("cluster.cache.ussdTx"),
                store.status().get("cluster.cache.ussdUser"));
    }

    /**
     * Construct + start the cluster manager and declare the {@code ussdTx}/{@code ussdUser}
     * caches in the configured clustering mode. Package-private so a test can simulate the
     * Infinispan API mismatch.
     */
    ClusterManager buildManager() {
        CacheMode cacheMode = CacheMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        String hosts = initialHostsBlank() ? DEFAULT_INITIAL_HOSTS : initialHosts.trim();
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(true)
                .clusterStack(DEFAULT_STACK)
                .clusterInitialHosts(hosts)
                .nodeId(nodeId)
                .build();
        ClusterManager manager = new ClusterManager(cfg, nodeId);
        manager.start();
        manager.getCache(UssdTxProfile.TABLE_NAME, cacheMode);
        manager.getCache(UssdUserProfile.TABLE_NAME, cacheMode);
        return manager;
    }

    /** Build the durable store (no container side effects). */
    UssdClusteredProfileStore buildStore(ClusterManager manager) {
        return new UssdClusteredProfileStore(manager, java.nio.file.Path.of(profileDir), true);
    }

    /** Bind the cluster into the container and route Profile CMP writes through it. */
    void install(ClusterManager manager, UssdClusteredProfileStore store) {
        if (container == null) {
            // Plain unit test (no CDI): construction is exercised without a live container.
            LOG.debug("ClusterBootstrap: no MicroSleeContainer — skipping bind/install");
            return;
        }
        container.bindCluster(manager);
        container.installDurableStore(store);
    }

    private void cleanup(ClusterManager manager, UssdClusteredProfileStore store) {
        try {
            if (store != null) {
                store.close();
            }
        } catch (Throwable ignored) {
            // best-effort only
        }
        try {
            if (container != null) {
                // bindCluster(null) stops the bound manager (idempotent) and clears HA seams.
                container.installDurableStore(null);
                container.bindCluster(null);
            } else if (manager != null) {
                manager.stop();
            }
        } catch (Throwable ignored) {
            // best-effort only
        }
    }

    @PreDestroy
    void shutdown() {
        try {
            if (profileStore != null) {
                profileStore.close();
            }
        } catch (Throwable ignored) {
            // best-effort only
        }
        try {
            if (container != null) {
                container.installDurableStore(null);
                container.bindCluster(null);
            } else if (clusterManager != null) {
                clusterManager.stop();
            }
        } catch (Throwable ignored) {
            // best-effort only
        }
        clusterManager = null;
        profileStore = null;
    }

    /** The live manager, or {@code null} while the feature is OFF / failed safe. */
    public ClusterManager clusterManager() {
        return clusterManager;
    }

    public UssdClusteredProfileStore profileStore() {
        return profileStore;
    }

    /** Configured flag (what the operator asked for). */
    public boolean clusterEnabled() {
        return enabled;
    }

    /** True only when a {@link ClusterManager} was actually built and is clustered. */
    public boolean clusterActive() {
        ClusterManager manager = clusterManager;
        return manager != null && manager.isClustered();
    }

    /** {@code REPL_SYNC} when active, else {@code OFF}. */
    public String clusterMode() {
        return clusterActive() ? mode : "OFF";
    }

    public String nodeId() {
        ClusterManager manager = clusterManager;
        return manager != null ? manager.getNodeId() : nodeId;
    }

    /** Non-null when {@code enabled=true} could not be honoured (fail-safe evidence). */
    public String blocker() {
        return blocker;
    }

    /** Observability payload for {@code /admin/status.json}. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cluster.enabled", enabled);
        out.put("cluster.active", clusterActive());
        out.put("cluster.mode", clusterMode());
        out.put("cluster.nodeId", nodeId());
        if (blocker != null) {
            out.put("cluster.blocker", blocker);
        }
        UssdClusteredProfileStore store = profileStore;
        if (store != null) {
            out.putAll(store.status());
        }
        return out;
    }

    private boolean initialHostsBlank() {
        return initialHosts == null || initialHosts.isBlank();
    }

    /** Compact cause chain for the status/log blocker (the head alone hides the real API mismatch). */
    private static String describeBlocker(Throwable t) {
        StringBuilder sb = new StringBuilder(String.valueOf(t));
        Throwable cause = t.getCause();
        int depth = 0;
        while (cause != null && depth++ < 6) {
            sb.append(" <- ").append(cause);
            cause = cause.getCause();
        }
        return sb.toString();
    }
}
