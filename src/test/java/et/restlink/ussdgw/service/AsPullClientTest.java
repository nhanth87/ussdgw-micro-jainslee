package et.restlink.ussdgw.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AsPullClientTest {
    private AsPullClient client;

    @BeforeEach
    void setUp() {
        client = new AsPullClient();
        set(client, "failThreshold", 3);
        set(client, "openMs", 60_000L);
        set(client, "maxRetries", 1);
    }

    @Test
    void retryOnlyNetworkAnd5xx() {
        assertThat(AsPullClient.isRetryable(503, null)).isTrue();
        assertThat(AsPullClient.isRetryable(0, "timeout")).isTrue();
        assertThat(AsPullClient.isRetryable(404, null)).isFalse();
        assertThat(AsPullClient.isRetryable(200, null)).isFalse();
        assertThat(client.shouldRetry("http://as", 0, 503, null)).isTrue();
        assertThat(client.shouldRetry("http://as", 1, 503, null)).isFalse();
    }

    @Test
    void circuitOpensAfterFailures() {
        String url = "http://as/fail";
        assertThat(client.tryAdmit(url).allow()).isTrue();
        client.recordFailure(url);
        client.recordFailure(url);
        client.recordFailure(url);
        assertThat(client.stateOf(url)).isEqualTo(AsPullClient.CircuitState.OPEN);
        assertThat(client.tryAdmit(url).allow()).isFalse();
        assertThat(client.tryAdmit(url).reason()).isEqualTo("CIRCUIT_OPEN");
        assertThat(client.openRejects()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void successResetsCircuit() {
        String url = "http://as/ok";
        client.recordFailure(url);
        client.recordFailure(url);
        client.recordFailure(url);
        assertThat(client.stateOf(url)).isEqualTo(AsPullClient.CircuitState.OPEN);
        // Force half-open by zeroing open window
        set(client, "openMs", 0L);
        assertThat(client.tryAdmit(url).allow()).isTrue();
        client.recordSuccess(url);
        assertThat(client.stateOf(url)).isEqualTo(AsPullClient.CircuitState.CLOSED);
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
