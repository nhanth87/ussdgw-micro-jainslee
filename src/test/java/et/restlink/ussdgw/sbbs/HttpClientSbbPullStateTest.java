package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.api.WireFormatResolver;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.UssdSagaCoordinator;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.events.PullHttpEvent;
import et.restlink.ussdgw.service.AsPullClient;
import et.restlink.ussdgw.service.AsPullStateRegistry;
import et.restlink.ussdgw.service.SbbServices;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.httpclient.command.HttpCallbackCommand;
import com.microjainslee.ra.httpclient.events.HttpCallbackCompletedEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The submit and the completion of an AS pull are delivered to two different pooled
 * {@link HttpClientSbb} instances (the container derives the SBB entity id from the activity
 * context name, and the client RA names the completion activity after the bare correlation id).
 * Every test here therefore completes on a <em>second</em> instance, which is exactly the case the
 * old instance-field maps got wrong.
 */
class HttpClientSbbPullStateTest {
    private static final String URL = "http://as-alpha:8080/pull";

    private MicroSleeContainer container;
    private AsPullStateRegistry registry;
    private AsPullClient asPull;
    private AdaptiveTimeout adaptive;
    private RecordingBridge bridge;
    private RecordingSaga saga;
    private CapturingRa ra;
    private SbbServices services;

    /** The instance that submits — never the one that completes. */
    private HttpClientSbb submitter;
    /** A fresh pool object, standing in for whatever instance the completion lands on. */
    private HttpClientSbb completer;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();

        registry = new AsPullStateRegistry();
        set(registry, "ttlMsProp", 60_000L);
        asPull = new AsPullClient();
        // High by default so an earlier assertion cannot trip the breaker for a later one.
        set(asPull, "failThreshold", 100);
        set(asPull, "openMs", 30_000L);
        set(asPull, "maxRetries", 0);

        adaptive = new AdaptiveTimeout();
        bridge = new RecordingBridge(adaptive);
        saga = new RecordingSaga();
        ra = new CapturingRa();

        services = new SbbServices();
        set(services, "container", container);
        set(services, "asPull", asPull);
        set(services, "asPullState", registry);
        set(services, "saga", saga);
        set(services, "store", new EmptyStore());
        set(services, "bridge", bridge);
        set(services, "wireFacade", new AsWireFacade());
        set(services, "wireFormatResolver", new JsonResolver());

        submitter = new HttpClientSbb(services);
        completer = new HttpClientSbb(services);
        set(submitter, "httpClient", ra);
        set(completer, "httpClient", ra);
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    // -- the headline: the adaptive gate must still get a sample -----------------

    @Test
    void completionOnAnotherInstanceStillSeedsTheAdaptiveGate() {
        submitter.onEvent(pull("c-1"), aci());
        completer.onEvent(ok("c-1", asResponseJson("c-1")), aci());

        assertThat(bridge.latencies).hasSize(1);
        assertThat(bridge.latencies).doesNotContain(-1L);
        assertThat(bridge.latencies.get(0)).isPositive();
        // EWMA seeded — without a sample the gate silently degenerates to a fixed ceiling.
        assertThat(adaptive.observedLatencyMs(0)).isPositive();
        assertThat(registry.size()).isZero();
    }

    @Test
    void manyConcurrentPullsAllReportPositiveLatency() {
        for (int i = 0; i < 50; i++) {
            submitter.onEvent(pull("c-" + i), aci());
        }
        // Completions interleave across instances, in a different order than the submits.
        for (int i = 49; i >= 0; i--) {
            HttpClientSbb any = (i % 2 == 0) ? completer : new HttpClientSbb(services);
            set(any, "httpClient", ra);
            any.onEvent(ok("c-" + i, asResponseJson("c-" + i)), aci());
        }
        assertThat(bridge.latencies).hasSize(50).allMatch(l -> l > 0);
        assertThat(registry.size()).isZero();
    }

    // -- circuit breaker identity ------------------------------------------------

    @Test
    void circuitBreakerKeysOnTheRealAsUrlNotEmptyString() {
        set(asPull, "failThreshold", 1);
        submitter.onEvent(pull("c-2"), aci());
        completer.onEvent(fail("c-2", 503, null), aci());

        assertThat(asPull.stateOf(URL)).isEqualTo(AsPullClient.CircuitState.OPEN);
        assertThat(asPull.stateOf("")).isEqualTo(AsPullClient.CircuitState.CLOSED);
        assertThat(asPull.stateOf("http://as-beta:8080/pull"))
                .isEqualTo(AsPullClient.CircuitState.CLOSED);
        assertThat(saga.reasons).containsExactly("AS_HTTP_503");
        assertThat(registry.size()).isZero();
    }

    @Test
    void unknownCorrelationNeverTouchesABreaker() {
        set(asPull, "failThreshold", 1);
        completer.onEvent(fail("ghost", 503, null), aci());

        assertThat(asPull.stateOf("")).isEqualTo(AsPullClient.CircuitState.CLOSED);
        assertThat(asPull.stateOf(URL)).isEqualTo(AsPullClient.CircuitState.CLOSED);
        assertThat(saga.reasons).containsExactly("AS_HTTP_503");
    }

    // -- retry -------------------------------------------------------------------

    @Test
    void retryResendsTheRealPayloadFromAnotherInstance() {
        set(asPull, "maxRetries", 1);
        submitter.onEvent(pull("c-3"), aci());
        String firstBody = lastPost().body();

        completer.onEvent(fail("c-3", 500, null), aci());

        assertThat(ra.commands).hasSize(2);
        HttpCallbackCommand.JsonPostRequest retry = lastPost();
        assertThat(retry.body()).isEqualTo(firstBody).contains("c-3");
        assertThat(retry.url()).isEqualTo(URL);
        assertThat(retry.sessionId()).isEqualTo("c-3");
        // Still in flight — the retry has not completed yet.
        assertThat(registry.peek("c-3").orElseThrow().attempt()).isEqualTo(1);
        assertThat(saga.reasons).isEmpty();
    }

    @Test
    void retryBudgetIsEnforcedAcrossInstances() {
        set(asPull, "maxRetries", 1);
        submitter.onEvent(pull("c-4"), aci());
        completer.onEvent(fail("c-4", 500, null), aci());
        // Second failure: attempt is now 1, the budget is spent — no third POST.
        new HttpClientSbbFactory().build(services, ra).onEvent(fail("c-4", 500, null), aci());

        assertThat(ra.commands).hasSize(2);
        assertThat(saga.reasons).containsExactly("AS_HTTP_500");
        assertThat(registry.size()).isZero();
    }

    @Test
    void retryFailsClosedWhenStateIsAbsent() {
        set(asPull, "maxRetries", 5);
        // No submit — the state was swept, or the RA replayed a stale completion.
        completer.onEvent(fail("ghost", 500, null), aci());

        assertThat(ra.commands).isEmpty();
        assertThat(saga.reasons).containsExactly("AS_HTTP_500");
        assertThat(registry.size()).isZero();
    }

    // -- leak-free exit paths ----------------------------------------------------

    @Test
    void noRaPathLeavesZeroEntries() {
        set(asPull, "failThreshold", 1);
        set(submitter, "httpClient", null);
        submitter.onEvent(pull("c-5"), aci());

        assertThat(registry.size()).isZero();
        assertThat(ra.commands).isEmpty();
        assertThat(saga.reasons).containsExactly("NO_HTTP_RA");
        assertThat(asPull.stateOf(URL)).isEqualTo(AsPullClient.CircuitState.OPEN);
    }

    @Test
    void emptyBodyAndTransportFailureBothClearState() {
        submitter.onEvent(pull("c-6"), aci());
        completer.onEvent(ok("c-6", "  "), aci());
        assertThat(registry.size()).isZero();

        submitter.onEvent(pull("c-7"), aci());
        completer.onEvent(fail("c-7", 0, "connection refused"), aci());
        assertThat(registry.size()).isZero();
        assertThat(saga.reasons).containsExactly("AS_EMPTY_BODY", "AS_TRANSPORT");
    }

    @Test
    void circuitOpenPullLeavesZeroEntries() {
        set(asPull, "failThreshold", 1);
        asPull.recordFailure(URL); // threshold reached on the first failure → OPEN
        submitter.onEvent(pull("c-8"), aci());

        assertThat(registry.size()).isZero();
        assertThat(ra.commands).isEmpty();
        assertThat(saga.reasons).containsExactly("CIRCUIT_OPEN");
    }

    // -- helpers -----------------------------------------------------------------

    private com.microjainslee.api.ActivityContextInterface aci() {
        return container.createActivityContext("t-" + System.nanoTime());
    }

    private static PullHttpEvent pull(String corr) {
        return new PullHttpEvent(URL,
                new AsRequest("vs", corr, corr, 1, "251911000000", "*123#", "*123#", 0));
    }

    private static HttpCallbackCompletedEvent ok(String corr, String body) {
        return new HttpCallbackCompletedEvent(corr, 200, body, null);
    }

    private static HttpCallbackCompletedEvent fail(String corr, int status, String err) {
        return new HttpCallbackCompletedEvent(corr, status, null, err);
    }

    private static String asResponseJson(String corr) {
        return "{\"correlationId\":\"" + corr + "\",\"requestId\":\"" + corr + "\","
                + "\"generation\":1,\"text\":\"ok\",\"action\":\"END\",\"async\":false}";
    }

    private HttpCallbackCommand.JsonPostRequest lastPost() {
        return (HttpCallbackCommand.JsonPostRequest) ra.commands.get(ra.commands.size() - 1);
    }

    private static void set(Object target, String field, Object value) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                var f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("No field " + field + " on " + target.getClass());
    }

    /** Hands out yet another fresh pool object, to make instance independence explicit. */
    private static final class HttpClientSbbFactory {
        HttpClientSbb build(SbbServices services, RaCommandPort ra) {
            HttpClientSbb sbb = new HttpClientSbb(services);
            set(sbb, "httpClient", ra);
            return sbb;
        }
    }

    private static final class CapturingRa implements RaCommandPort {
        final List<OutboundCommand> commands = new CopyOnWriteArrayList<>();
        @Override public void sendCommand(OutboundCommand command) { commands.add(command); }
    }

    /** Mirrors {@code VirtualSessionBridge}'s EWMA feed so a bad sample is visible. */
    private static final class RecordingBridge extends VirtualSessionBridge {
        final List<Long> latencies = new CopyOnWriteArrayList<>();
        private final AdaptiveTimeout adaptive;

        RecordingBridge(AdaptiveTimeout adaptive) { this.adaptive = adaptive; }

        @Override
        public void onAsResponse(AsResponse response, long latencyMs) {
            latencies.add(latencyMs);
            if (latencyMs > 0) {
                adaptive.recordLatency(0, latencyMs);
            }
        }
    }

    private static final class RecordingSaga extends UssdSagaCoordinator {
        final List<String> reasons = new CopyOnWriteArrayList<>();
        @Override
        public void onAsPullFailed(String correlationId, String reason) { reasons.add(reason); }
    }

    private static final class EmptyStore extends VirtualSessionStore {
        @Override public Optional<VirtualSession> get(String correlationId) {
            return Optional.empty();
        }
    }

    private static final class JsonResolver extends WireFormatResolver {
        @Override public AsHttpWireFormat resolve(String tenantId) { return AsHttpWireFormat.JSON; }
    }
}
