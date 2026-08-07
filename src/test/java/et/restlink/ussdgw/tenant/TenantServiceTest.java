package et.restlink.ussdgw.tenant;

import et.restlink.ussdgw.persist.TenantEntity;
import et.restlink.ussdgw.security.PasswordHasher;

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
    void passwordHashIsSaltedSoTwoHashesOfTheSamePasswordDiffer() {
        AdminUserService users = new AdminUserService();
        String a = users.hashPassword("secret");
        String b = users.hashPassword("secret");
        assertThat(a).startsWith("$2");
        assertThat(a).isNotEqualTo(b);
        assertThat(PasswordHasher.matches("secret", a)).isTrue();
        assertThat(PasswordHasher.matches("secret", b)).isTrue();
        assertThat(PasswordHasher.matches("other", a)).isFalse();
    }
}
