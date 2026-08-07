package et.restlink.ussdgw.routing;

import et.restlink.ussdgw.persist.ShortCodeEntity;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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
                type = RuleType.valueOf(e.ruleType);
            } catch (RuntimeException ex) {
                continue;
            }
            rules.put(mapKey(e.shortCode, e.appUsername),
                    new ShortCodeRule(e.shortCode, type, e.asUrl, e.enabled,
                            e.tenantId, e.networkId, e.mark, blankToNull(e.appUsername)));
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
        rules.put(mapKey(rule.shortCode(), rule.appUsername()), rule);
    }

    @Transactional
    public void putAndPersist(ShortCodeRule rule) {
        put(rule);
        ShortCodeEntity e = findEntity(rule.shortCode(), rule.appUsername());
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
        // DB stores unbound as '' (NOT NULL) for composite UNIQUE(short_code, app_username).
        e.appUsername = blankToEmpty(rule.appUsername());
        e.persist();
    }

    @Transactional
    public boolean delete(String shortCode) {
        return delete(shortCode, null);
    }

    @Transactional
    public boolean delete(String shortCode, String appUsername) {
        rules.remove(mapKey(shortCode, appUsername));
        // Also drop any legacy map key without app user when deleting unbound rule.
        if (appUsername == null || appUsername.isBlank()) {
            rules.remove(normalize(shortCode));
        }
        try {
            ShortCodeEntity e = findEntity(shortCode, appUsername);
            if (e != null) {
                e.delete();
                return true;
            }
            return false;
        } catch (RuntimeException ex) {
            LOG.warn("short-code DB delete skipped: {}", ex.getMessage());
            return true;
        }
    }

    /** MO / unbound resolve — prefer rules with null appUsername. */
    public Optional<ShortCodeRule> find(String shortCode) {
        return find(shortCode, null);
    }

    /**
     * Resolve AS rule for a dialed USSD string.
     * Exact (non-mark) rules win on equality; otherwise longest enabled mark prefix.
     * When multiple candidates share the same match strength, prefer
     * {@code preferredAppUsername}, else unbound ({@code appUsername == null}).
     */
    public Optional<ShortCodeRule> find(String shortCode, String preferredAppUsername) {
        String sc = normalize(shortCode);
        if (sc.isEmpty()) {
            return Optional.empty();
        }
        String prefer = blankToNull(preferredAppUsername);

        List<ShortCodeRule> exact = new ArrayList<>();
        for (ShortCodeRule r : rules.values()) {
            if (!r.enabled() || r.mark()) {
                continue;
            }
            if (sc.equals(normalize(r.shortCode()))) {
                exact.add(r);
            }
        }
        Optional<ShortCodeRule> exactPick = preferAmong(exact, prefer);
        if (exactPick.isPresent()) {
            return exactPick;
        }

        List<ShortCodeRule> marks = new ArrayList<>();
        int bestLen = -1;
        for (ShortCodeRule r : rules.values()) {
            if (!r.enabled() || !r.mark()) {
                continue;
            }
            String prefix = normalize(r.shortCode());
            if (prefix.isEmpty() || !sc.startsWith(prefix)) {
                continue;
            }
            if (prefix.length() > bestLen) {
                bestLen = prefix.length();
                marks.clear();
                marks.add(r);
            } else if (prefix.length() == bestLen) {
                marks.add(r);
            }
        }
        Optional<ShortCodeRule> markPick = preferAmong(marks, prefer);
        if (markPick.isPresent()) {
            return markPick;
        }

        // Legacy: mark=false rule stored but treated as fallback exact.
        return preferAmong(exact, prefer);
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

    private ShortCodeEntity findEntity(String shortCode, String appUsername) {
        String sc = normalize(shortCode);
        String app = blankToEmpty(appUsername);
        return ShortCodeEntity.find("shortCode = ?1 and appUsername = ?2", sc, app).firstResult();
    }

    private static String mapKey(String shortCode, String appUsername) {
        String sc = normalize(shortCode);
        String app = blankToNull(appUsername);
        return app == null ? sc : sc + "\t" + app;
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
