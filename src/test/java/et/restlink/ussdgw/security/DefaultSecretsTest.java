package et.restlink.ussdgw.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultSecretsTest {

    @Test
    void scanFlagsBuiltInDefaults() {
        List<DefaultSecrets.Finding> findings = DefaultSecrets.scan(
                DefaultSecrets.SESSION_HMAC_SECRET,
                DefaultSecrets.ADMIN_API_KEY,
                DefaultSecrets.FIRST_RUN_PASSWORD);
        assertThat(findings).hasSize(3);
        assertThat(findings).extracting(DefaultSecrets.Finding::property)
                .containsExactly(
                        DefaultSecrets.PROP_SESSION_HMAC_SECRET,
                        DefaultSecrets.PROP_ADMIN_API_KEY,
                        DefaultSecrets.PROP_FIRST_RUN_PASSWORD);
    }

    @Test
    void scanAcceptsRotatedSecrets() {
        assertThat(DefaultSecrets.scan(
                "rotated-hmac-secret-value",
                "rotated-admin-key",
                "rotated-first-run")).isEmpty();
    }

    @Test
    void blankFirstRunPasswordIsNotAFinding() {
        List<DefaultSecrets.Finding> findings = DefaultSecrets.scan(
                "rotated-hmac", "rotated-key", "");
        assertThat(findings).isEmpty();
    }

    @Test
    void guardFailsClosedWithoutLabOptOut() {
        DefaultSecretStartupGuard guard = new DefaultSecretStartupGuard();
        guard.sessionHmacSecret = DefaultSecrets.SESSION_HMAC_SECRET;
        guard.adminApiKey = DefaultSecrets.ADMIN_API_KEY;
        guard.firstRunPassword = DefaultSecrets.FIRST_RUN_PASSWORD;
        guard.allowDefaultSecrets = false;
        assertThatThrownBy(guard::enforce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DefaultSecrets.PROP_ALLOW_DEFAULTS);
    }

    @Test
    void guardWarnsOnlyWhenLabOptOutSet() {
        DefaultSecretStartupGuard guard = new DefaultSecretStartupGuard();
        guard.sessionHmacSecret = DefaultSecrets.SESSION_HMAC_SECRET;
        guard.adminApiKey = DefaultSecrets.ADMIN_API_KEY;
        guard.firstRunPassword = DefaultSecrets.FIRST_RUN_PASSWORD;
        guard.allowDefaultSecrets = true;
        guard.enforce(); // must not throw
    }
}
