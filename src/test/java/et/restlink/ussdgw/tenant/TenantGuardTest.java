package et.restlink.ussdgw.tenant;

import et.restlink.ussdgw.persist.TenantEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TenantGuardTest {
    private TenantGuard guard;
    private TenantEntity enabled;
    private TenantEntity disabled;

    @BeforeEach
    void setUp() {
        enabled = new TenantEntity();
        enabled.tenantId = "bank-a";
        enabled.enabled = true;
        enabled.maxTps = 3;
        enabled.httpApiKey = "key-a";

        disabled = new TenantEntity();
        disabled.tenantId = "bank-off";
        disabled.enabled = false;
        disabled.maxTps = 50;
        disabled.httpApiKey = "key-off";

        guard = new TenantGuard();
        set(guard, "tenants", new TenantService() {
            @Override
            public Optional<TenantEntity> byId(String tenantId) {
                if ("bank-a".equals(tenantId)) return Optional.of(enabled);
                if ("bank-off".equals(tenantId)) return Optional.of(disabled);
                return Optional.empty();
            }
        });
    }

    @Test
    void blankTenantAdmitted() {
        assertThat(guard.admit(null).allowed()).isTrue();
        assertThat(guard.admit("  ").allowed()).isTrue();
    }

    @Test
    void missingTenantRejected() {
        assertThat(guard.admit("nope").reason()).isEqualTo(TenantGuard.Reason.MISSING);
    }

    @Test
    void disabledTenantRejected() {
        assertThat(guard.admit("bank-off").reason()).isEqualTo(TenantGuard.Reason.DISABLED);
    }

    @Test
    void tpsBurstDropsAfterMax() {
        assertThat(guard.admit("bank-a").allowed()).isTrue();
        assertThat(guard.admit("bank-a").allowed()).isTrue();
        assertThat(guard.admit("bank-a").allowed()).isTrue();
        assertThat(guard.admit("bank-a").reason()).isEqualTo(TenantGuard.Reason.RATE_LIMITED);
    }

    @Test
    void apiKeyMatches() {
        assertThat(guard.apiKeyMatches("bank-a", "key-a")).isTrue();
        assertThat(guard.apiKeyMatches("bank-a", "wrong")).isFalse();
    }

    private static void set(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
