package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.api.AsWireCodec;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.UssdSagaCoordinator;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.events.PullGrpcEvent;
import et.restlink.ussdgw.service.AsPullClient;
import et.restlink.ussdgw.service.AsPullStateRegistry;
import et.restlink.ussdgw.service.SbbServices;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.grpc.command.InvokeGrpc;
import com.microjainslee.ra.grpc.events.GrpcInvokeResponseEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** gRPC counterpart of {@link HttpClientSbbPullStateTest} — same cross-instance completion. */
class GrpcClientSbbPullStateTest {
    private static final String ENDPOINT = "as-alpha:50051";
    private static final String METHOD = "et.restlink.ussdgw.as.UssdAs/Pull";
    private static final String KEY = ENDPOINT + "|" + METHOD;

    private MicroSleeContainer container;
    private AsPullStateRegistry registry;
    private AsPullClient asPull;
    private AdaptiveTimeout adaptive;
    private RecordingBridge bridge;
    private RecordingSaga saga;
    private CapturingRa ra;
    private SbbServices services;

    private GrpcClientSbb submitter;
    private GrpcClientSbb completer;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();

        registry = new AsPullStateRegistry();
        set(registry, "ttlMsProp", 60_000L);
        asPull = new AsPullClient();
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
        set(services, "bridge", bridge);
        set(services, "config", new UssdConfigService());

        submitter = new GrpcClientSbb(services);
        completer = new GrpcClientSbb(services);
        set(submitter, "grpc", ra);
        set(completer, "grpc", ra);
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void completionOnAnotherInstanceStillSeedsTheAdaptiveGate() {
        submitter.onEvent(pull("g-1"), aci());
        completer.onEvent(ok("g-1"), aci());

        assertThat(bridge.latencies).hasSize(1).doesNotContain(-1L);
        assertThat(bridge.latencies.get(0)).isPositive();
        assertThat(adaptive.observedLatencyMs(0)).isPositive();
        assertThat(registry.size()).isZero();
    }

    @Test
    void circuitBreakerKeysOnEndpointAndMethodNotEmptyString() {
        set(asPull, "failThreshold", 1);
        submitter.onEvent(pull("g-2"), aci());
        completer.onEvent(error("g-2", 14, "UNAVAILABLE"), aci());

        assertThat(asPull.stateOf(KEY)).isEqualTo(AsPullClient.CircuitState.OPEN);
        assertThat(asPull.stateOf("")).isEqualTo(AsPullClient.CircuitState.CLOSED);
        assertThat(registry.size()).isZero();
    }

    @Test
    void retryReEncodesTheRealRequestFromAnotherInstance() {
        set(asPull, "maxRetries", 1);
        submitter.onEvent(pull("g-3"), aci());
        byte[] first = lastInvoke().payload();

        completer.onEvent(error("g-3", 14, "UNAVAILABLE"), aci());

        assertThat(ra.commands).hasSize(2);
        InvokeGrpc retry = lastInvoke();
        assertThat(retry.payload()).isEqualTo(first);
        assertThat(retry.target()).isEqualTo(ENDPOINT);
        assertThat(retry.fullMethod()).isEqualTo(METHOD);
        assertThat(retry.correlationId()).isEqualTo("g-3");
        assertThat(registry.peek("g-3").orElseThrow().attempt()).isEqualTo(1);
    }

    @Test
    void retryFailsClosedWhenStateIsAbsent() {
        set(asPull, "maxRetries", 5);
        completer.onEvent(error("ghost", 14, "UNAVAILABLE"), aci());

        assertThat(ra.commands).isEmpty();
        assertThat(asPull.stateOf("")).isEqualTo(AsPullClient.CircuitState.CLOSED);
        assertThat(saga.reasons).hasSize(1);
        assertThat(registry.size()).isZero();
    }

    @Test
    void noRaPathLeavesZeroEntries() {
        set(submitter, "grpc", null);
        submitter.onEvent(pull("g-4"), aci());

        assertThat(registry.size()).isZero();
        assertThat(ra.commands).isEmpty();
        assertThat(saga.reasons).containsExactly("NO_GRPC_RA");
    }

    // -- helpers -----------------------------------------------------------------

    private com.microjainslee.api.ActivityContextInterface aci() {
        return container.createActivityContext("t-" + System.nanoTime());
    }

    private static PullGrpcEvent pull(String corr) {
        return new PullGrpcEvent(ENDPOINT, METHOD,
                new AsRequest("vs", corr, corr, 1, "251911000000", "*123#", "*123#", 0));
    }

    private static GrpcInvokeResponseEvent ok(String corr) {
        byte[] payload = AsWireCodec.encodeResponse(
                new AsResponse(corr, corr, 1, "ok", AsAction.END, false));
        return new GrpcInvokeResponseEvent(corr, ENDPOINT, METHOD, payload, 0, null);
    }

    private static GrpcInvokeResponseEvent error(String corr, int status, String desc) {
        return new GrpcInvokeResponseEvent(corr, ENDPOINT, METHOD, null, status, desc);
    }

    private InvokeGrpc lastInvoke() {
        return (InvokeGrpc) ra.commands.get(ra.commands.size() - 1);
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

    private static final class CapturingRa implements RaCommandPort {
        final List<OutboundCommand> commands = new CopyOnWriteArrayList<>();
        @Override public void sendCommand(OutboundCommand command) { commands.add(command); }
    }

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
}
