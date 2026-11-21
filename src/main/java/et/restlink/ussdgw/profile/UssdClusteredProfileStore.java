package et.restlink.ussdgw.profile;

import com.microjainslee.api.DurableProfileStore;
import com.microjainslee.api.ProfileID;
import com.microjainslee.api.ProfileMutation;
import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.profile.infinispan.InfinispanProfileStore;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Profile CMP store for the feature-flagged USSD application cluster.
 *
 * <p>One Infinispan cache per table ({@code ussdTx} = in-flight saga PK correlationId,
 * {@code ussdUser} = per-MSISDN snapshot). Local mode uses {@link CacheMode#LOCAL};
 * cluster mode uses {@link CacheMode#REPL_SYNC} on the {@link ClusterManager} so every
 * node sees the same CMP rows. The optional SIFS store under {@code ussd.cluster.profile-dir}
 * survives a restart and seeds the cache on boot (best-effort: a disk failure never
 * prevents the cluster layer from coming up).</p>
 *
 * <p>Field values stay JDK-only ({@code HashMap<String,Object>}) — same contract as the
 * GMLC clustered store. This class never adds or renames a CMP field; it only moves the
 * storage of the existing {@code ussdTx}/{@code ussdUser} rows.</p>
 */
public final class UssdClusteredProfileStore implements DurableProfileStore, AutoCloseable {
    public static final String CACHE_TX = UssdTxProfile.TABLE_NAME;
    public static final String CACHE_USER = UssdUserProfile.TABLE_NAME;

    private static final Logger LOG = LogManager.getLogger(UssdClusteredProfileStore.class);

    private final ClusterManager cluster;
    private final InfinispanProfileStore disk;
    private final Cache<String, HashMap<String, Object>> txCache;
    private final Cache<String, HashMap<String, Object>> userCache;
    private final boolean clustered;

    public UssdClusteredProfileStore(ClusterManager cluster, Path profileDir, boolean persist) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.clustered = cluster.isClustered();
        CacheMode mode = clustered ? CacheMode.REPL_SYNC : CacheMode.LOCAL;
        this.txCache = cluster.getCache(CACHE_TX, mode);
        this.userCache = cluster.getCache(CACHE_USER, mode);
        InfinispanProfileStore opened = null;
        if (persist && profileDir != null) {
            try {
                opened = new InfinispanProfileStore(profileDir, cluster.getNodeId(), true);
                seedFromDisk(opened);
            } catch (RuntimeException e) {
                LOG.warn("USSD cluster profile-dir {} unusable — continuing cluster-only (no SIFS): {}",
                        profileDir, e.toString());
                if (opened != null) {
                    try {
                        opened.close();
                    } catch (RuntimeException ignored) {
                        // best-effort cleanup only
                    }
                }
                opened = null;
            }
        }
        this.disk = opened;
        LOG.info("UssdClusteredProfileStore ready node={} clustered={} mode={} persist={}",
                cluster.getNodeId(), clustered, mode, this.disk != null);
    }

    public ClusterManager clusterManager() {
        return cluster;
    }

    public boolean clustered() {
        return clustered;
    }

    public String nodeId() {
        return cluster.getNodeId();
    }

    /** Observability for {@code /admin/status.json} ({@code cluster.cache.*}). */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cluster.mode", clustered ? CacheMode.REPL_SYNC.name() : CacheMode.LOCAL.name());
        out.put("cluster.nodeId", cluster.getNodeId());
        out.put("cluster.profile.persist", disk != null);
        out.put("cluster.cache.ussdTx", txCache.size());
        out.put("cluster.cache.ussdUser", userCache.size());
        return out;
    }

    @Override
    public Map<String, Object> loadFields(ProfileID id) {
        Objects.requireNonNull(id, "id");
        HashMap<String, Object> cached = cache(id.getProfileTableName()).get(id.getProfileName());
        if (cached != null) {
            return new HashMap<>(cached);
        }
        if (disk != null) {
            Map<String, Object> fromDisk = disk.loadFields(id);
            if (fromDisk != null) {
                cache(id.getProfileTableName()).putIfAbsent(id.getProfileName(), copyOf(fromDisk));
                return new HashMap<>(fromDisk);
            }
        }
        LOG.warn("ussd cluster loadFields MISS table={} key={} cacheSize={} clustered={}",
                id.getProfileTableName(), id.getProfileName(),
                cache(id.getProfileTableName()).size(), clustered);
        return null;
    }

    @Override
    public void storeFields(ProfileID id, Map<String, Object> fields) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fields, "fields");
        HashMap<String, Object> copy = copyOf(fields);
        cache(id.getProfileTableName()).put(id.getProfileName(), copy);
        if (disk != null) {
            disk.storeFields(id, copy);
        }
    }

    @Override
    public void remove(ProfileID id) {
        Objects.requireNonNull(id, "id");
        cache(id.getProfileTableName()).remove(id.getProfileName());
        if (disk != null) {
            disk.remove(id);
        }
    }

    @Override
    public void storeBatch(List<ProfileMutation> mutations) {
        Objects.requireNonNull(mutations, "mutations");
        for (ProfileMutation mutation : mutations) {
            switch (mutation.getType()) {
                case UPSERT -> storeFields(mutation.getId(), mutation.getFields());
                case DELETE -> remove(mutation.getId());
                default -> LOG.warn("ussd cluster storeBatch: unknown mutation type {}", mutation.getType());
            }
        }
    }

    @Override
    public Map<String, Map<String, Object>> loadTable(String tableName) {
        Objects.requireNonNull(tableName, "tableName");
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (disk != null) {
            out.putAll(disk.loadTable(tableName));
        }
        for (Map.Entry<String, HashMap<String, Object>> e : cache(tableName).entrySet()) {
            if (e.getValue() != null) {
                out.put(e.getKey(), new HashMap<>(e.getValue()));
            }
        }
        return out;
    }

    /** Profile names currently in the cluster cache for a table (ops / tests). */
    public Set<String> listProfileNames(String tableName) {
        return Set.copyOf(cache(tableName).keySet());
    }

    @Override
    public void close() {
        if (disk != null) {
            disk.close();
        }
    }

    private void seedFromDisk(InfinispanProfileStore disk) {
        seedTable(disk, UssdTxProfile.TABLE_NAME);
        seedTable(disk, UssdUserProfile.TABLE_NAME);
    }

    private void seedTable(InfinispanProfileStore disk, String table) {
        Map<String, Map<String, Object>> rows = disk.loadTable(table);
        Cache<String, HashMap<String, Object>> cache = cache(table);
        int seeded = 0;
        for (Map.Entry<String, Map<String, Object>> e : rows.entrySet()) {
            if (cache.putIfAbsent(e.getKey(), copyOf(e.getValue())) == null) {
                seeded++;
            }
        }
        if (seeded > 0) {
            LOG.info("Seeded {} {} rows from SIFS into the USSD cluster cache", seeded, table);
        }
    }

    private Cache<String, HashMap<String, Object>> cache(String table) {
        if (UssdUserProfile.TABLE_NAME.equals(table)) {
            return userCache;
        }
        return txCache;
    }

    private static HashMap<String, Object> copyOf(Map<String, Object> fields) {
        return new HashMap<>(fields);
    }
}
