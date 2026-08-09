package et.restlink.ussdgw.service;

import com.microjainslee.cluster.Ss7PeerRouteAffinity;
import com.microjainslee.cluster.Ss7PeerRouteAffinity.PeerRoute;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.restcomm.protocols.ss7.config.Ss7Config;

/**
 * N–N peer-route LB for <em>new</em> NI / GTT / MAP2MAP outbound (no ingress ASP).
 * Candidates derived from {@link Ss7Config} (AS links + route DPCs); Digicom AS-BP
 * example is N=2 (1403/1404) but N is not limited to 2.
 */
    @ApplicationScoped
public class Ss7PeerRouteService {
    private static final Logger LOG = LogManager.getLogger(Ss7PeerRouteService.class);

    @ConfigProperty(name = "ussd.ss7.peer-route-lb.enabled", defaultValue = "false")
    boolean enabled;

    private volatile Ss7PeerRouteAffinity affinity;
    private final ConcurrentMap<Integer, List<PeerRoute>> candidatesByNetworkId = new ConcurrentHashMap<>();

    public void refreshFromConfig(Ss7Config cfg) {
        Map<Integer, List<PeerRoute>> built = buildCandidates(cfg);
        candidatesByNetworkId.clear();
        candidatesByNetworkId.putAll(built);
        ensureAffinity();
        LOG.info("Ss7PeerRouteService refreshed: networks={} enabled={} (N–N LB)",
                built.keySet(), enabled);
        for (Map.Entry<Integer, List<PeerRoute>> e : built.entrySet()) {
            LOG.info("  networkId={} candidates={}", e.getKey(), e.getValue());
        }
    }

    public void clear() {
        candidatesByNetworkId.clear();
    }

    /**
     * Pick+pin for a new outbound session. Returns null when LB disabled / no candidates /
     * no ClusterManager — caller keeps classic SLS.
     */
    public PeerRoute pickForNewSession(int networkId, String affinityKey) {
        if (!enabled) {
            return null;
        }
        List<PeerRoute> candidates = candidatesByNetworkId.getOrDefault(networkId, List.of());
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        Ss7PeerRouteAffinity aff = ensureAffinity();
        if (aff == null) {
            // Deterministic local hash when ISPN not yet bound (tests / early boot).
            int idx = Math.floorMod(affinityKey == null ? 0 : affinityKey.hashCode(), candidates.size());
            return candidates.get(idx);
        }
        String pool = "ni:networkId=" + networkId;
        return aff.pickAndPin(pool, affinityKey, candidates);
    }

    public List<PeerRoute> candidates(int networkId) {
        return candidatesByNetworkId.getOrDefault(networkId, List.of());
    }

    private Ss7PeerRouteAffinity ensureAffinity() {
        // ISPN ClusterManager bind deferred until Digicom Infinispan API aligns —
        // without it, pickForNewSession uses local hash when N>1 (or null when disabled).
        return affinity;
    }

    /**
     * Derive N candidates: for each M3UA route DPC via an AS, match an AS link whose name
     * contains that DPC (Digicom {@code L1-BP-1404} / {@code L2-BP-1403}), else zip by order.
     * SCCP {@code networkId} on localPoints scopes which DPCs belong to which plane.
     */
    static Map<Integer, List<PeerRoute>> buildCandidates(Ss7Config cfg) {
        if (cfg == null || cfg.m3ua() == null || cfg.m3ua().as() == null || cfg.m3ua().routes() == null) {
            return Map.of();
        }
        Map<String, Ss7Config.As> asByName = new LinkedHashMap<>();
        for (Ss7Config.As as : cfg.m3ua().as()) {
            if (as != null && as.name() != null) {
                asByName.put(as.name(), as);
            }
        }
        Map<Integer, List<Integer>> dpcsByNetwork = new LinkedHashMap<>();
        if (cfg.sccp() != null && cfg.sccp().localPoints() != null) {
            for (Ss7Config.LocalPoint lp : cfg.sccp().localPoints()) {
                if (lp == null) {
                    continue;
                }
                int nid = lp.networkId();
                List<Integer> dpcs = dpcsByNetwork.computeIfAbsent(nid, k -> new ArrayList<>());
                if (lp.reachablePointCodes() != null) {
                    for (int pc : lp.reachablePointCodes()) {
                        if (!dpcs.contains(pc)) {
                            dpcs.add(pc);
                        }
                    }
                }
            }
        }
        if (dpcsByNetwork.isEmpty()) {
            dpcsByNetwork.put(0, new ArrayList<>());
        }

        Map<Integer, List<PeerRoute>> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Integer>> e : dpcsByNetwork.entrySet()) {
            int nid = e.getKey();
            List<Integer> reachable = e.getValue();
            List<PeerRoute> routes = new ArrayList<>();
            for (Ss7Config.Route r : cfg.m3ua().routes()) {
                if (r == null || r.to() == null || r.via() == null) {
                    continue;
                }
                int dpc = r.to().dpc();
                if (!reachable.isEmpty() && !reachable.contains(dpc)) {
                    continue;
                }
                Ss7Config.As as = asByName.get(r.via());
                if (as == null || as.links() == null || as.links().isEmpty()) {
                    continue;
                }
                String asp = matchAspForDpc(as.links(), dpc);
                if (asp == null) {
                    continue;
                }
                PeerRoute pr = new PeerRoute(asp, dpc);
                if (!routes.contains(pr)) {
                    routes.add(pr);
                }
            }
            if (!routes.isEmpty()) {
                out.put(nid, Collections.unmodifiableList(routes));
            }
        }
        return out;
    }

    static String matchAspForDpc(List<String> links, int dpc) {
        String dpcStr = Integer.toString(dpc);
        String exact = null;
        String contains = null;
        for (String link : links) {
            if (link == null || link.isBlank()) {
                continue;
            }
            String name = link.trim();
            if (name.equals(dpcStr) || name.endsWith("-" + dpcStr) || name.endsWith("_" + dpcStr)) {
                exact = name + "-ASP";
                break;
            }
            if (name.toLowerCase(Locale.ROOT).contains(dpcStr) && contains == null) {
                contains = name + "-ASP";
            }
        }
        if (exact != null) {
            return exact;
        }
        if (contains != null) {
            return contains;
        }
        // Single-link AS: one ASP for that route DPC.
        if (links.size() == 1 && links.get(0) != null && !links.get(0).isBlank()) {
            return links.get(0).trim() + "-ASP";
        }
        return null;
    }
}
