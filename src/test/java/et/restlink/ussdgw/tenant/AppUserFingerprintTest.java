package et.restlink.ussdgw.tenant;

import et.restlink.ussdgw.security.PasswordHasher;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppUserFingerprintTest {

    @Test
    void fingerprintStableEightHex() {
        String fp = AppUserService.fingerprint("ussd_secret_key_demo");
        assertThat(fp).hasSize(8);
        assertThat(AppUserService.fingerprint("ussd_secret_key_demo")).isEqualTo(fp);
        assertThat(AppUserService.fingerprint("other")).isNotEqualTo(fp);
    }

    @Test
    void generatedKeyHasPrefix() {
        assertThat(AppUserService.generateApiKey()).startsWith("ussd_");
    }

    @Test
    void bcryptRoundTripForApiKey() {
        String key = "ussd_test_key_123";
        String hash = PasswordHasher.hash(key);
        assertThat(PasswordHasher.matches(key, hash)).isTrue();
        assertThat(PasswordHasher.matches("wrong", hash)).isFalse();
    }
}
