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
        assertThat(new String(saved.body())).contains("*999#").contains("saved").contains("live");
        assertThat(routing.find("*999#")).isPresent();

        AdminHttpHandler.HttpReply get = catalog.routingGet(null);
        assertThat(new String(get.body())).contains("*999#").contains("http://as/pull");
        assertThat(new String(get.body())).contains("Reload from DB");
    }

    @Test
    void deleteRemovesFromLiveMap() {
        routing.putAndPersist(new ShortCodeRule("*888#", RuleType.HTTP, "http://x", true));
        AdminHttpHandler.HttpReply del = catalog.routingPost(
                "action=delete&shortCode=%2A888%23", null);
        assertThat(new String(del.body())).contains("deleted").contains("live");
        assertThat(routing.find("*888#")).isEmpty();
        assertThat(new String(catalog.routingGet(null).body())).doesNotContain("*888#");
    }

    @Test
    void reloadCallsReloadFromDb() {
        routing.putAndPersist(new ShortCodeRule("*777#", RuleType.GRPC, "127.0.0.1:9|m", true));
        AdminHttpHandler.HttpReply r = catalog.routingPost("action=reload", null);
        assertThat(routing.reloadCalls.get()).isEqualTo(1);
        assertThat(new String(r.body())).contains("reloaded").contains("live");
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
            ShortCodeRule r = map.get(shortCode == null ? "" : shortCode.toLowerCase());
            if (r == null || !r.enabled()) return Optional.empty();
            return Optional.of(r);
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
