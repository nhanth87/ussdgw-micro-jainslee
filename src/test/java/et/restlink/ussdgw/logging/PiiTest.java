package et.restlink.ussdgw.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiTest {
    @Test
    void masksLeavingLastFour() {
        assertThat(Pii.maskMsisdn("251911223344")).isEqualTo("********3344");
        assertThat(Pii.maskMsisdn("1234")).isEqualTo("****");
        assertThat(Pii.maskMsisdn("")).isEmpty();
        assertThat(Pii.msisdnDetail("251911223344")).isEqualTo("msisdn=********3344");
    }
}
