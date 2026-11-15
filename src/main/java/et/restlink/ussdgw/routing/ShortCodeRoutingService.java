package et.restlink.ussdgw.routing;

import et.restlink.ussdgw.persist.ShortCodeEntity;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Short-code routing table: which AS answers a dialed USSD code.
 *
 * <h2>One code, many tenants</h2>
 * Several companies (tenants) can own the same short code. Each one reaches the gateway over
 * its own SS7 link, and the link carries a networkId (the SCCP network the dialog arrived on).
 * A rule is identified by {@code (shortCode, networkId, appUsername)}, so company A can have
 * {@code *123#} on network 1 pointing at its AS, and company B {@code *123#} on network 2
 * pointing at another AS:
 *
 * <pre>
 *   *123#  net=1  tenant=compA  → http://asp1.compA/ussd
 *   *123#  net=2  tenant=compB  → http://asp2.compB/ussd
 *   *123#  net=0                → http://default/ussd      (shared rule, any link)
 * </pre>
 *
 * <h2>How a dial is resolved</h2>
 * {@link #find(String, String, int, String)} looks in two tiers and stops at the first hit:
 * <ol>
 *   <li>rules of the dialog's own network ({@code rule.networkId == dialog networkId});</li>
 *   <li>shared rules ({@code rule.networkId == 0}).</li>
 * </ol>
 * Another tenant's network is never used, so company B's subscriber can never reach company
 * A's AS. A dialog on network 0 (single-link installs, Diameter/SIP planes) has no tenant of its
 * own: it tries shared rules first, then any rule — the behaviour before tenants existed.
 * Inside a tier the usual short-code rules apply: exact code beats prefix ({@code mark}),
 * longest prefix wins, then the preferred app user, then the unbound rule.
 */
@ApplicationScoped
public class ShortCodeRoutingService {
    private static final Logger LOG = LogManager.getLogger(ShortCodeRoutingService.class);
    private final ConcurrentHashMap<String, ShortCodeRule> rules = new ConcurrentHashMap<>();

    @ConfigProperty(name = "ussd.seed.http-url", defaultValue = "http://127.0.0.1:8090/ussd/pull")
    String seedHttpUrl;
    @ConfigProperty(name = "ussd.seed.grpc-target", defaultValue = "127.0.0.1:9090")
    String seedGrpcTarget;
    @ConfigProperty(name = "ussd.seed.grpc-method", defaultValue = "et.restlink.ussdgw.as.UssdAs/Pull")
    String seedGrpcMethod;

    @PostConstruct
    void loadOrSeed() {
        try {
            reloadFromDb();
            if (rules.isEmpty()) {
                seedDefaults();
            }
        } catch (RuntimeException ex) {
            LOG.warn("short-code DB load failed; seeding memory only: {}", ex.getMessage());
            if (rules.isEmpty()) seedDefaultsInMemory();
        }
    }

    @Transactional
    public void reloadFromDb() {
        rules.clear();
        List<ShortCodeEntity> rows = ShortCodeEntity.listAll();
        for (ShortCodeEntity e : rows) {
            RuleType type;
            try {
                type = RuleType.parse(e.ruleType);
            } catch (RuntimeException ex) {
                continue;
            }
            boolean reroute = type.impliesReroute()
                    || e.rerouteEnable
                    || (!e.bypass && e.map2mapGt != null && !e.map2mapGt.isBlank());
            rules.put(mapKey(e.shortCode, e.networkId, e.appUsername),
                    ShortCodeRule.ofReroute(e.shortCode, type, e.asUrl, e.enabled,
                            e.tenantId, e.networkId, e.mark, blankToNull(e.appUsername),
                            reroute, blankToNull(e.map2mapGt), blankToNull(e.hlrMode),
                            blankToNull(e.hopDestGt), e.hopDestSsn));
        }
        LOG.info("Loaded {} short-code rules from DB", rules.size());
    }

    @Transactional
    public void seedDefaults() {
        putAndPersist(new ShortCodeRule("*123#", RuleType.HTTP, seedHttpUrl, true, null, 0, false, null));
        putAndPersist(new ShortCodeRule("*456#", RuleType.GRPC,
                seedGrpcTarget + "|" + seedGrpcMethod, true, null, 0, false, null));
    }

    private void seedDefaultsInMemory() {
        put(new ShortCodeRule("*123#", RuleType.HTTP, seedHttpUrl, true, null, 0, false, null));
        put(new ShortCodeRule("*456#", RuleType.GRPC,
                seedGrpcTarget + "|" + seedGrpcMethod, true, null, 0, false, null));
    }

    public void put(ShortCodeRule rule) {
        rules.put(keyOf(rule), rule);
    }

    @Transactional
    public void putAndPersist(ShortCodeRule rule) {
        put(rule);
        persistRow(rule);
    }

    /** Write one rule to {@code ussd_short_code} (insert or update by its key). */
    protected void persistRow(ShortCodeRule rule) {
        ShortCodeEntity e = findEntity(rule.shortCode(), rule.networkId(), rule.appUsername());
        if (e == null) {
            e = new ShortCodeEntity();
            e.shortCode = rule.shortCode();
        }
        e.ruleType = rule.ruleType().name();
        e.asUrl = rule.asUrl();
        e.enabled = rule.enabled();
        e.tenantId = blankToNull(rule.tenantId());
        e.networkId = rule.networkId();
        e.mark = rule.mark();
        // DB stores unbound as '' (NOT NULL) for UNIQUE(short_code, network_id, app_username).
        e.appUsername = blankToEmpty(rule.appUsername());
        e.rerouteEnable = rule.rerouteEnable();
        e.bypass = rule.bypass(); // mirror !rerouteEnable for V9 readers
        e.map2mapGt = blankToNull(rule.redirectUssdString());
        e.hlrMode = blankToNull(rule.hlrMode());
        e.hopDestGt = blankToNull(rule.hopDestGt());
        e.hopDestSsn = rule.hopDestSsn();
        e.persist();
    }

    @Transactional
    public boolean delete(String shortCode) {
        return delete(shortCode, null);
    }

    /** Delete the rule for this code + app user, preferring the shared (network 0) one. */
    @Transactional
    public boolean delete(String shortCode, String appUsername) {
        int net = byKey(shortCode, appUsername).map(ShortCodeRule::networkId).orElse(0);
        return delete(shortCode, net, appUsername);
    }

    @Transactional
    public boolean delete(String shortCode, int networkId, String appUsername) {
        boolean wasLive = rules.remove(mapKey(shortCode, networkId, appUsername)) != null;
        try {
            return deleteRow(shortCode, networkId, appUsername) || wasLive;
        } catch (RuntimeException ex) {
            LOG.warn("short-code DB delete skipped: {}", ex.getMessage());
            return wasLive;
        }
    }

    /** Remove one rule row; true when a row was there. */
    protected boolean deleteRow(String shortCode, int networkId, String appUsername) {
        ShortCodeEntity e = findEntity(shortCode, networkId, appUsername);
        if (e == null) {
            return false;
        }
        e.delete();
        return true;
    }

    /** Lookup without a link (network 0): shared rules first, then any rule. */
    public Optional<ShortCodeRule> find(String shortCode) {
        return find(shortCode, null, 0, null);
    }

    public Optional<ShortCodeRule> find(String shortCode, String preferredAppUsername) {
        return find(shortCode, preferredAppUsername, 0, null);
    }

    /**
     * Find the rule for a dial that arrived on {@code networkId}.
     *
     * @param shortCode            dialed code ({@code *123#})
     * @param preferredAppUsername app user to prefer when several rules tie (may be null)
     * @param networkId            SCCP network of the inbound link; 0 = no tenant link known
     * @param tenantId             tenant already bound to the session (continue turns, NI);
     *                             rules of another tenant are skipped. null = not known yet
     */
    public Optional<ShortCodeRule> find(String shortCode, String preferredAppUsername,
                                        int networkId, String tenantId) {
        String sc = normalize(shortCode);
        if (sc.isEmpty()) {
            return Optional.empty();
        }
        String prefer = blankToNull(preferredAppUsername);
        String tenant = blankToNull(tenantId);
        Predicate<ShortCodeRule> sameTenant =
                r -> tenant == null || r.tenantId() == null || tenant.equals(r.tenantId());

        if (networkId != 0) {
            // Tenant link: its own rules, then shared ones. Never another tenant's network.
            Optional<ShortCodeRule> own = findIn(sc, prefer,
                    sameTenant.and(r -> r.networkId() == networkId));
            if (own.isPresent()) {
                return own;
            }
            return findIn(sc, prefer, sameTenant.and(r -> r.networkId() == 0));
        }
        // No link known: shared rules first, then any rule (pre-tenant behaviour).
        Optional<ShortCodeRule> shared = findIn(sc, prefer, sameTenant.and(r -> r.networkId() == 0));
        if (shared.isPresent()) {
            return shared;
        }
        return findIn(sc, prefer, sameTenant);
    }

    /**
     * Short-code match among the rules that pass {@code allowed}: an exact (non-mark) rule wins;
     * otherwise the longest enabled mark prefix. Ties go to {@link #preferAmong}.
     */
    private Optional<ShortCodeRule> findIn(String sc, String prefer, Predicate<ShortCodeRule> allowed) {
        List<ShortCodeRule> exact = new ArrayList<>();
        List<ShortCodeRule> marks = new ArrayList<>();
        int bestLen = -1;
        for (ShortCodeRule r : rules.values()) {
            if (!r.enabled() || !allowed.test(r)) {
                continue;
            }
            String code = normalize(r.shortCode());
            if (!r.mark()) {
                if (sc.equals(code)) {
                    exact.add(r);
                }
                continue;
            }
            if (code.isEmpty() || !sc.startsWith(code)) {
                continue;
            }
            if (code.length() > bestLen) {
                bestLen = code.length();
                marks.clear();
                marks.add(r);
            } else if (code.length() == bestLen) {
                marks.add(r);
            }
        }
        Optional<ShortCodeRule> exactPick = preferAmong(exact, prefer);
        return exactPick.isPresent() ? exactPick : preferAmong(marks, prefer);
    }

    static Optional<ShortCodeRule> preferAmong(List<ShortCodeRule> candidates, String preferApp) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.size() == 1) {
            return Optional.of(candidates.getFirst());
        }
        if (preferApp != null) {
            for (ShortCodeRule r : candidates) {
                if (preferApp.equals(r.appUsername())) {
                    return Optional.of(r);
                }
            }
        }
        for (ShortCodeRule r : candidates) {
            if (r.appUsername() == null || r.appUsername().isBlank()) {
                return Optional.of(r);
            }
        }
        return Optional.of(candidates.stream()
                .min(Comparator.comparing(r -> nullToEmpty(r.appUsername())))
                .orElse(candidates.getFirst()));
    }

    /**
     * Exact map lookup by short-code + optional app user (admin edit / hop preserve).
     * Unlike {@link #find}, does not apply mark-prefix matching or enabled filter.
     */
    public Optional<ShortCodeRule> byKey(String shortCode, int networkId, String appUsername) {
        return Optional.ofNullable(rules.get(mapKey(shortCode, networkId, appUsername)));
    }

    /** Same as {@link #byKey(String, int, String)} on any network; the shared (0) rule first. */
    public Optional<ShortCodeRule> byKey(String shortCode, String appUsername) {
        Optional<ShortCodeRule> shared = byKey(shortCode, 0, appUsername);
        if (shared.isPresent()) {
            return shared;
        }
        String sc = normalize(shortCode);
        String app = blankToNull(appUsername);
        return rules.values().stream()
                .filter(r -> sc.equals(normalize(r.shortCode())))
                .filter(r -> java.util.Objects.equals(app, blankToNull(r.appUsername())))
                .min(Comparator.comparingInt(ShortCodeRule::networkId));
    }

    public Collection<ShortCodeRule> list() {
        return rules.values();
    }

    public Collection<ShortCodeRule> listForTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return list();
        String tid = tenantId.trim();
        return rules.values().stream()
                .filter(r -> tid.equals(r.tenantId()))
                .toList();
    }

    public static String extractShortCode(String ussd) {
        if (ussd == null) return "";
        String s = ussd.trim();
        int hash = s.indexOf('#');
        if (hash >= 0) return s.substring(0, hash + 1);
        return s;
    }

    /** Cap RE_ROUTE hop-string chain folds (loop / deep nesting guard). */
    public static final int MAP2MAP_HOP_CHAIN_MAX = 8;

    /**
     * MAP2MAP hop USSD: apply {@link ShortCodeRule#resolveHopUssd(String)} for {@code firstRule},
     * then keep rewriting while the current hop string matches another armed RE_ROUTE rule
     * (chain always). Example: {@code *804*1234#} → {@code *875*1234#} → {@code *8775*1234#}.
     *
     * @param dialedUssd UE / current USSD string being hopped
     * @param firstRule  rule already matched for this hop arm (must be {@link ShortCodeRule#map2mapArmed()})
     * @return resolved hop USSD, or empty when redirect cannot be formed
     */
    public String resolveMap2MapHopUssd(String dialedUssd, ShortCodeRule firstRule) {
        if (firstRule == null || !firstRule.map2mapArmed()) {
            return "";
        }
        String hop = firstRule.resolveHopUssd(dialedUssd);
        if (hop.isEmpty()) {
            return "";
        }
        HashSet<String> seen = new HashSet<>();
        seen.add(keyOf(firstRule));
        String current = hop;
        for (int i = 0; i < MAP2MAP_HOP_CHAIN_MAX; i++) {
            Optional<ShortCodeRule> next = find(extractShortCode(current));
            if (next.isEmpty() || !next.get().map2mapArmed()) {
                break;
            }
            ShortCodeRule r = next.get();
            String key = keyOf(r);
            if (!seen.add(key)) {
                break;
            }
            String nextHop = r.resolveHopUssd(current);
            if (nextHop.isEmpty() || nextHop.equals(current)) {
                break;
            }
            current = nextHop;
        }
        return current;
    }

    /**
     * Resolve hop USSD from fields already on a MAP2MAP request (then chain via live rules).
     */
    public String resolveMap2MapHopUssd(String dialedUssd, boolean mark, String markKey,
                                        String redirectUssd) {
        ShortCodeRule seed = ShortCodeRule.ofReroute(
                markKey == null ? "" : markKey,
                RuleType.HTTP,
                "http://127.0.0.1/",
                true,
                null,
                0,
                mark,
                null,
                true,
                redirectUssd,
                null);
        if (!seed.map2mapArmed()) {
            return "";
        }
        return resolveMap2MapHopUssd(dialedUssd, seed);
    }

    private ShortCodeEntity findEntity(String shortCode, int networkId, String appUsername) {
        String sc = normalize(shortCode);
        String app = blankToEmpty(appUsername);
        return ShortCodeEntity.find("shortCode = ?1 and networkId = ?2 and appUsername = ?3",
                sc, networkId, app).firstResult();
    }

    /** In-memory key = the DB unique key (short_code, network_id, app_username). */
    private static String mapKey(String shortCode, int networkId, String appUsername) {
        return normalize(shortCode) + "\t" + networkId + "\t" + blankToEmpty(appUsername);
    }

    private static String keyOf(ShortCodeRule r) {
        return mapKey(r.shortCode(), r.networkId(), r.appUsername());
    }

    private static String normalize(String code) {
        return code == null ? "" : code.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Persist form: unbound app user is empty string (matches V8 unique key). */
    private static String blankToEmpty(String s) {
        return s == null || s.isBlank() ? "" : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
