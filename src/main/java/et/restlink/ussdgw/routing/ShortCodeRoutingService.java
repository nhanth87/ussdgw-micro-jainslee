package et.restlink.ussdgw.routing;

import et.restlink.ussdgw.persist.ShortCodeEntity;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Collection;
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
            rules.put(normalize(e.shortCode),
                    new ShortCodeRule(e.shortCode, type, e.asUrl, e.enabled,
                            e.tenantId, e.networkId));
        }
        LOG.info("Loaded {} short-code rules from DB", rules.size());
    }

    @Transactional
    public void seedDefaults() {
        putAndPersist(new ShortCodeRule("*123#", RuleType.HTTP, seedHttpUrl, true, null, 0));
        putAndPersist(new ShortCodeRule("*456#", RuleType.GRPC,
                seedGrpcTarget + "|" + seedGrpcMethod, true, null, 0));
    }

    private void seedDefaultsInMemory() {
        put(new ShortCodeRule("*123#", RuleType.HTTP, seedHttpUrl, true, null, 0));
        put(new ShortCodeRule("*456#", RuleType.GRPC,
                seedGrpcTarget + "|" + seedGrpcMethod, true, null, 0));
    }

    public void put(ShortCodeRule rule) {
        rules.put(normalize(rule.shortCode()), rule);
    }

    @Transactional
    public void putAndPersist(ShortCodeRule rule) {
        put(rule);
        ShortCodeEntity e = ShortCodeEntity.find("shortCode", rule.shortCode()).firstResult();
        if (e == null) {
            e = new ShortCodeEntity();
            e.shortCode = rule.shortCode();
        }
        e.ruleType = rule.ruleType().name();
        e.asUrl = rule.asUrl();
        e.enabled = rule.enabled();
        e.tenantId = blankToNull(rule.tenantId());
        e.networkId = rule.networkId();
        e.persist();
    }

    @Transactional
    public boolean delete(String shortCode) {
        rules.remove(normalize(shortCode));
        return ShortCodeEntity.delete("shortCode", shortCode) > 0;
    }

    public Optional<ShortCodeRule> find(String shortCode) {
        ShortCodeRule r = rules.get(normalize(shortCode));
        if (r == null || !r.enabled()) return Optional.empty();
        return Optional.of(r);
    }

    public Collection<ShortCodeRule> list() { return rules.values(); }

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

    private static String normalize(String code) {
        return code == null ? "" : code.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
