package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.routing.ShortCodeRoutingService;
import et.restlink.ussdgw.tenant.TenantService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCatalogRoutingTest {
    private AdminCatalogHandler catalog;
    private MemoryRouting routing;

    @BeforeEach
    void setUp() {
        catalog = new AdminCatalogHandler();
        routing = new MemoryRouting();
        set(catalog, "routing", routing);
        set(catalog, "tenants", new TenantService() {
            @Override
            public java.util.List<et.restlink.ussdgw.persist.TenantEntity> list() {
                return java.util.List.of();
            }

            @Override
            public java.util.Optional<et.restlink.ussdgw.persist.TenantEntity> byId(String tenantId) {
                return java.util.Optional.empty();
            }
        });
    }

    @Test
    void saveAppearsInGetAndIsLive() {
        AdminHttpHandler.HttpReply saved = catalog.routingPost(
                "action=save&shortCode=%2A999%23&ruleType=HTTP&asUrl=http%3A%2F%2Fas%2Fpull&enabled=true",
                null);
        assertThat(new String(saved.body())).contains("*999#").contains("http://as/pull");
        assertThat(saved.headers().get("HX-Trigger")).contains("saved").contains("live");
        assertThat(routing.find("*999#")).isPresent();

        AdminHttpHandler.HttpReply get = catalog.routingGet(null);
        assertThat(new String(get.body())).contains("*999#").contains("http://as/pull");
    }

    @Test
    void saveMarkAppearsAndRoutesPrefix() {
        AdminHttpHandler.HttpReply saved = catalog.routingPost(
                "action=save&shortCode=%2A100%2A&ruleType=HTTP&asUrl=http%3A%2F%2Fas%2Fmark"
                        + "&enabled=true&mark=true",
                null);
        assertThat(new String(saved.body())).contains("*100*").contains("true");
        assertThat(routing.find("*100*123456#")).isPresent()
                .get().extracting(ShortCodeRule::asUrl).isEqualTo("http://as/mark");
        assertThat(routing.find("*100*123456#").get().mark()).isTrue();
    }

    @Test
    void deleteRemovesFromLiveMap() {
        routing.putAndPersist(new ShortCodeRule("*888#", RuleType.HTTP, "http://x", true));
        AdminHttpHandler.HttpReply del = catalog.routingPost(
                "action=delete&shortCode=%2A888%23", null);
        assertThat(del.headers().get("HX-Trigger")).contains("deleted");
        assertThat(routing.find("*888#")).isEmpty();
        assertThat(new String(catalog.routingGet(null).body())).doesNotContain("*888#");
    }

    @Test
    void reloadCallsReloadFromDb() {
        routing.putAndPersist(new ShortCodeRule("*777#", RuleType.GRPC, "127.0.0.1:9|m", true));
        AdminHttpHandler.HttpReply r = catalog.routingPost("action=reload", null);
        assertThat(routing.reloadCalls.get()).isEqualTo(1);
        assertThat(r.headers().get("HX-Trigger")).contains("reloaded").contains("live");
        assertThat(new String(r.body())).contains("*777#");
    }

    static final class MemoryRouting extends ShortCodeRoutingService {
        final ConcurrentHashMap<String, ShortCodeRule> map = new ConcurrentHashMap<>();
        final AtomicInteger reloadCalls = new AtomicInteger();

        @Override
        public void reloadFromDb() {
            reloadCalls.incrementAndGet();
        }

        @Override
        public void putAndPersist(ShortCodeRule rule) {
            map.put(rule.shortCode().toLowerCase(), rule);
        }

        @Override
        public boolean delete(String shortCode) {
            return map.remove(shortCode == null ? "" : shortCode.toLowerCase()) != null;
        }

        @Override
        public Optional<ShortCodeRule> find(String shortCode) {
            String sc = shortCode == null ? "" : shortCode.trim();
            ShortCodeRule exact = map.get(sc.toLowerCase());
            if (exact != null && exact.enabled() && !exact.mark()) {
                return Optional.of(exact);
            }
            ShortCodeRule best = null;
            int bestLen = -1;
            for (ShortCodeRule r : map.values()) {
                if (!r.enabled() || !r.mark()) continue;
                String prefix = r.shortCode() == null ? "" : r.shortCode();
                if (!prefix.isEmpty() && sc.startsWith(prefix) && prefix.length() > bestLen) {
                    best = r;
                    bestLen = prefix.length();
                }
            }
            if (best != null) return Optional.of(best);
            if (exact != null && exact.enabled()) return Optional.of(exact);
            return Optional.empty();
        }

        @Override
        public Collection<ShortCodeRule> list() {
            return map.values();
        }

        @Override
        public Collection<ShortCodeRule> listForTenant(String tenantId) {
            if (tenantId == null || tenantId.isBlank()) return list();
            return map.values().stream().filter(r -> tenantId.equals(r.tenantId())).toList();
        }
    }

    private static void set(Object target, String field, Object value) {
        try {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    var f = c.getDeclaredField(field);
                    f.setAccessible(true);
                    f.set(target, value);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            throw new NoSuchFieldException(field);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
