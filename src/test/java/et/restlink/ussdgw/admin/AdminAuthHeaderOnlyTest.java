package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.tenant.AdminUserService;
import et.restlink.ussdgw.tenant.TenantGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuthHeaderOnlyTest {
    private AdminAuthService auth;

    @BeforeEach
    void setUp() throws Exception {
        auth = new AdminAuthService();
        UssdConfigService cfg = new UssdConfigService();
        set(cfg, "adminApiKey", "ussd-admin");
        set(auth, "config", cfg);
        set(auth, "tenantGuard", new TenantGuard());
        set(auth, "users", new AdminUserService());
        set(auth, "sessionHmacSecret", "test-hmac-secret-not-default");
    }

    @Test
    void headerKeyGrantsAdmin() {
        Optional<AdminAuthService.Principal> p = auth.authenticate(
                Map.of(AdminAuthService.ADMIN_KEY_HEADER, "ussd-admin"),
                Map.of("key", "ussd-admin"));
        assertThat(p).isPresent();
        assertThat(p.get().role()).isEqualTo("ADMIN");
    }

    @Test
    void queryKeyAloneIsIgnored() {
        Optional<AdminAuthService.Principal> p = auth.authenticate(
                Map.of(), Map.of("key", "ussd-admin"));
        assertThat(p).isEmpty();
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
