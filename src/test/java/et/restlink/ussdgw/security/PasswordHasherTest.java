package et.restlink.ussdgw.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {

    @Test
    void bcryptRoundTrip() {
        String hash = PasswordHasher.hash("ussd-admin");
        assertThat(hash).startsWith("$2");
        assertThat(PasswordHasher.matches("ussd-admin", hash)).isTrue();
        assertThat(PasswordHasher.matches("wrong", hash)).isFalse();
        assertThat(PasswordHasher.needsRehash(hash)).isFalse();
    }

    @Test
    void legacySha256StillVerifiesAndNeedsRehash() {
        String legacy = PasswordHasher.legacySha256Hex("ussd-admin");
        assertThat(legacy).hasSize(64);
        assertThat(PasswordHasher.isLegacySha256(legacy)).isTrue();
        assertThat(PasswordHasher.matches("ussd-admin", legacy)).isTrue();
        assertThat(PasswordHasher.matches("wrong", legacy)).isFalse();
        assertThat(PasswordHasher.needsRehash(legacy)).isTrue();
    }
}
