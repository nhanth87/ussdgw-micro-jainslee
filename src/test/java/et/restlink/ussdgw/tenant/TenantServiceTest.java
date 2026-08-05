package et.restlink.ussdgw.tenant;

import et.restlink.ussdgw.persist.TenantEntity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantServiceTest {
    @Test
    void generateKeyHasPrefix() {
        String k = TenantService.generateKey();
        assertThat(k).startsWith("ussd_");
        assertThat(k.length()).isGreaterThan(10);
    }

    @Test
    void passwordHashStable() {
        String a = AdminUserService.hashPassword("secret");
        String b = AdminUserService.hashPassword("secret");
        assertThat(a).isEqualTo(b).hasSize(64);
        assertThat(AdminUserService.hashPassword("other")).isNotEqualTo(a);
    }
}
