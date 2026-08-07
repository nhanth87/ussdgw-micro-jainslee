package et.restlink.ussdgw.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AsUrlValidatorTest {

    @Test
    void rejectsMetadataAndPrivateLiterals() {
        Set<String> none = Set.of();
        assertThat(AsUrlValidator.reject("http://169.254.169.254/latest", none, false, false))
                .isPresent();
        assertThat(AsUrlValidator.reject("http://127.0.0.1:8090/ussd/pull", none, false, false))
                .isPresent();
        assertThat(AsUrlValidator.reject("http://10.0.0.5/x", none, false, false)).isPresent();
        assertThat(AsUrlValidator.reject("ftp://example.com/x", none, false, false)).isPresent();
    }

    @Test
    void allowlistPermitsLabLoopback() {
        Set<String> lab = AsUrlValidator.parseAllowlist("127.0.0.1,localhost,::1");
        assertThat(AsUrlValidator.reject("http://127.0.0.1:8090/ussd/pull", lab, false, false))
                .isEmpty();
        assertThat(AsUrlValidator.reject("http://localhost:8090/ussd/pull", lab, false, false))
                .isEmpty();
    }

    @Test
    void publicHostAcceptedWithoutDns() {
        assertThat(AsUrlValidator.reject(
                "https://as.example.com/ussd/pull", Set.of(), false, false)).isEmpty();
    }

    @Test
    void allowPrivateHostsEscapeHatch() {
        assertThat(AsUrlValidator.reject(
                "http://10.1.2.3/pull", Set.of(), true, false)).isEmpty();
    }
}
