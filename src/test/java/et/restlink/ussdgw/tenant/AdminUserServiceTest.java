package et.restlink.ussdgw.tenant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminUserServiceTest {

    @Test
    void tenantRoleRequiresUsernameEqualsTenantId() {
        assertThatCode(() -> AdminUserService.enforceTenantUsername("TENANT", "ethio-bank", "ethio-bank"))
                .doesNotThrowAnyException();
    }

    @Test
    void tenantRoleRejectsMismatch() {
        assertThatThrownBy(() -> AdminUserService.enforceTenantUsername("TENANT", "ops1", "ethio-bank"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must equal tenantId");
    }

    @Test
    void tenantRoleRejectsBlankTenantId() {
        assertThatThrownBy(() -> AdminUserService.enforceTenantUsername("TENANT", "ethio-bank", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires tenantId");
    }

    @Test
    void adminAndOpsAllowAnyUsername() {
        assertThatCode(() -> AdminUserService.enforceTenantUsername("ADMIN", "root", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> AdminUserService.enforceTenantUsername("OPS", "ops1", "other"))
                .doesNotThrowAnyException();
    }
}
