package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.persist.SipTrunkEntity;
import et.restlink.ussdgw.persist.TenantEntity;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.routing.ShortCodeRoutingService;
import et.restlink.ussdgw.sip.SipTrunkService;
import et.restlink.ussdgw.tenant.TenantService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void tenantsPostPassesSipTrunkIdToUpsert() {
        AtomicReference<String> capturedTrunk = new AtomicReference<>();
        set(catalog, "tenants", new TenantService() {
            @Override
            public List<TenantEntity> list() {
                return List.of();
            }

            @Override
            public Optional<TenantEntity> byId(String tenantId) {
                return Optional.empty();
            }

            @Override
            public TenantEntity upsert(String tenantId, String displayName, int networkId,
                                       boolean enabled, String httpApiKey, String smppSystemId,
                                       String smppPasswordOrBlank, String asCallbackBase, int maxTps,
                                       String httpAsWireFormat, String sipTrunkId) {
                capturedTrunk.set(sipTrunkId);
                TenantEntity e = new TenantEntity();
                e.tenantId = tenantId;
                e.networkId = networkId;
                e.httpAsWireFormat = httpAsWireFormat == null ? "XML" : httpAsWireFormat;
                e.sipTrunkId = sipTrunkId;
                e.httpApiKey = "ussd_test";
                return e;
            }
        });
        AdminHttpHandler.HttpReply r = catalog.tenantsPost(
                "action=save&tenantId=bank1&displayName=Bank&networkId=1&enabled=true"
                        + "&httpAsWireFormat=XML&sipTrunkId=trunk-as1&maxTps=50",
                new AdminAuthService.Principal("ADMIN", null));
        assertThat(r.status()).isEqualTo(200);
        assertThat(capturedTrunk.get()).isEqualTo("trunk-as1");
        assertThat(r.headers().get("HX-Trigger")).contains("sipTrunk=trunk-as1");
    }

    @Test
    void sipRouteRejectsCrossTenantTrunk() {
        SipTrunkEntity foreign = new SipTrunkEntity();
        foreign.trunkId = "bank2-trunk";
        foreign.enabled = true;
        foreign.tenantId = "bank2";
        set(catalog, "sipTrunkService", new SipTrunkService() {
            @Override
            public Optional<SipTrunkEntity> byId(String trunkId) {
                return "bank2-trunk".equals(trunkId) ? Optional.of(foreign) : Optional.empty();
            }
        });
        AdminAuthService.Principal tenant = new AdminAuthService.Principal("TENANT", "bank1");
        AdminHttpHandler.HttpReply r = catalog.routingPost(
                "action=save&shortCode=%2A9%23&ruleType=SIP&asUrl=bank2-trunk&enabled=true",
                tenant);
        assertThat(r.headers().get("HX-Trigger")).contains("does not allow tenant");
        assertThat(routing.find("*9#")).isEmpty();
    }

    @Test
    void sipRouteAllowsSharedTrunk() {
        SipTrunkEntity shared = new SipTrunkEntity();
        shared.trunkId = "shared-trunk";
        shared.enabled = true;
        shared.tenantId = null;
        set(catalog, "sipTrunkService", new SipTrunkService() {
            @Override
            public Optional<SipTrunkEntity> byId(String trunkId) {
                return "shared-trunk".equals(trunkId) ? Optional.of(shared) : Optional.empty();
            }
        });
        AdminAuthService.Principal tenant = new AdminAuthService.Principal("TENANT", "bank1");
        AdminHttpHandler.HttpReply r = catalog.routingPost(
                "action=save&shortCode=%2A9%23&ruleType=SIP&asUrl=shared-trunk&enabled=true",
                tenant);
        assertThat(r.headers().get("HX-Trigger")).contains("saved");
        assertThat(routing.find("*9#")).isPresent()
                .get().extracting(ShortCodeRule::asUrl).isEqualTo("shared-trunk");
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
            return delete(shortCode, null);
        }

        @Override
        public boolean delete(String shortCode, String appUsername) {
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
