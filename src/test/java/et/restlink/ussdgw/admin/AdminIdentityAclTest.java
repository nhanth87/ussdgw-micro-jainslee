package et.restlink.ussdgw.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminIdentityAclTest {

    @Test
    void identityPathsAreUsersAndTenantsOnly() {
        assertThat(AdminHttpHandler.isIdentityAdminPath("/admin/users")).isTrue();
        assertThat(AdminHttpHandler.isIdentityAdminPath("/admin/tenants")).isTrue();
        assertThat(AdminHttpHandler.isIdentityAdminPath("/admin/users/partial")).isTrue();
        assertThat(AdminHttpHandler.isIdentityAdminPath("/admin/ss7")).isFalse();
        assertThat(AdminHttpHandler.isIdentityAdminPath("/admin/routing")).isFalse();
    }

    @Test
    void onlyAdminRolePassesIdentityGate() {
        assertThat(AdminHttpHandler.isAdminRole(new AdminAuthService.Principal("ADMIN", null)))
                .isTrue();
        assertThat(AdminHttpHandler.isAdminRole(new AdminAuthService.Principal("OPS", null)))
                .isFalse();
        assertThat(AdminHttpHandler.isAdminRole(
                new AdminAuthService.Principal("TENANT", "t1"))).isFalse();
        assertThat(AdminHttpHandler.isAdminRole(null)).isTrue(); // internal/automation path
    }

    @Test
    void catalogDeniesOpsOnUsersPost() {
        AdminCatalogHandler catalog = new AdminCatalogHandler();
        AdminHttpHandler.HttpReply r = catalog.usersPost(
                "action=save&username=evil&password=x&role=ADMIN",
                new AdminAuthService.Principal("OPS", null));
        assertThat(r.status()).isEqualTo(403);
        assertThat(new String(r.body())).contains("ADMIN");
    }

    @Test
    void catalogDeniesOpsOnTenantsPost() {
        AdminCatalogHandler catalog = new AdminCatalogHandler();
        AdminHttpHandler.HttpReply r = catalog.tenantsPost(
                "action=save&tenantId=t1&name=x",
                new AdminAuthService.Principal("OPS", null));
        assertThat(r.status()).isEqualTo(403);
    }
}
