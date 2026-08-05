package et.restlink.ussdgw.tenant;

import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.bridge.VirtualSessionState;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.persist.TenantEntity;

import com.microjainslee.core.MicroSleeContainer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CallbackAuthServiceTest {
    private MicroSleeContainer container;
    private VirtualSessionStore store;
    private CallbackAuthService auth;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        store = new VirtualSessionStore();
        set(store, "container", container);
        set(store, "config", new UssdConfigService());
        set(store, "profileTtlMs", 120_000L);
        store.ensureTable();

        VirtualSession s = new VirtualSession("vs", "corr-1", "r1", "2519", 1, "dlg", "*123#");
        s.setTenantId("bank-a");
        s.setState(VirtualSessionState.AWAITING_AS);
        store.put(s);

        TenantGuard guard = new TenantGuard();
        set(guard, "tenants", new TenantService() {
            @Override
            public Optional<TenantEntity> byId(String tenantId) {
                if (!"bank-a".equals(tenantId)) return Optional.empty();
                TenantEntity t = new TenantEntity();
                t.tenantId = "bank-a";
                t.enabled = true;
                t.httpApiKey = "tenant-secret";
                return Optional.of(t);
            }
        });

        UssdConfigService cfg = new UssdConfigService();
        set(cfg, "adminApiKey", "ussd-admin");

        auth = new CallbackAuthService();
        set(auth, "tenantGuard", guard);
        set(auth, "store", store);
        set(auth, "config", cfg);
    }

    @AfterEach
    void tearDown() {
        if (container != null) container.stop();
    }

    @Test
    void wrongKeyUnauthorized() {
        assertThat(auth.authorizeCallback("corr-1", Map.of("X-USSD-Api-Key", "bad")))
                .isEqualTo(CallbackAuthService.Result.UNAUTHORIZED);
    }

    @Test
    void tenantKeyAccepted() {
        assertThat(auth.authorizeCallback("corr-1", Map.of("X-USSD-Api-Key", "tenant-secret")))
                .isEqualTo(CallbackAuthService.Result.OK);
    }

    @Test
    void adminKeyAccepted() {
        assertThat(auth.authorizeCallback("corr-1", Map.of("X-API-Key", "ussd-admin")))
                .isEqualTo(CallbackAuthService.Result.OK);
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
