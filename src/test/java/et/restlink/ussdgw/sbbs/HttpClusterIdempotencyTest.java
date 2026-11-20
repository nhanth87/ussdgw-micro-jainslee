package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.api.classic.ClassicNiHttpPark;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.config.RuntimeConfigStore;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.service.NiFlowRegistry;
import et.restlink.ussdgw.service.SbbServices;
import et.restlink.ussdgw.tenant.CallbackAuthService;
import et.restlink.ussdgw.tenant.TenantGuard;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.httpserver.command.HttpServerCommand;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Gap (5): HTTP plane over a shared store. Two node-local registries backed by
 * one shared Infinispan cache must give exactly one flow (one MAP BEGIN) for N
 * parallel identical requests and for an idempotent retry on the other node.
 * Also pins the 202 contract, GET status variants and unknown flowId -> 404.
 */
class HttpClusterIdempotencyTest {

    private static final String XML_NI = """
            <dialog localId="ni-cluster-1" networkId="0">
              <unstructuredSSRequest_Request dataCodingScheme="15" string="cluster push">
                <msisdn nai="international_number" npi="ISDN" number="251911230398"/>
              </unstructuredSSRequest_Request>
            </dialog>
            """;

    private MicroSleeContainer container;
    private ClusterManager manager;
    private RecordingStore store;
    private CapturingHttp http;
    private ClassicNiHttpPark park;
    private NiFlowRegistry flowsA;
    private NiFlowRegistry flowsB;
    private SbbServices servicesA;
    private SbbServices servicesB;
    private HttpServerSbb sbbA;
    private HttpServerSbb sbbB;

    @BeforeEach
    void setUp() throws Exception {
        container = new MicroSleeContainer();
        container.start();

        // One shared store emulates the REPL_SYNC cache both nodes consult;
        // cluster mode is off so no transport/network is involved.
        manager = new ClusterManager(MicroSleeConfiguration.builder()
                .clusterEnabled(false).nodeId("http-shared").build(), null);
        manager.start();

        UssdConfigService config = new UssdConfigService();
        set(config, "httpServerEnabledProp", true);
        set(config, "httpNiPathProp", "/ussd");
        set(config, "asyncGateTimeoutMsProp", 80L);
        set(config, "dialogTimeoutMsProp", 60_000L);

        store = new RecordingStore();
        set(store, "container", container);
        set(store, "config", config);
        set(store, "profileTtlMs", 120_000L);

        park = new ClassicNiHttpPark();
        set(park, "adaptive", new AdaptiveTimeout());
        set(park, "config", config);
        set(park, "wireFacade", new AsWireFacade());
        http = new CapturingHttp();
        park.bindHttp(() -> http);

        flowsA = new NiFlowRegistry();
        flowsB = new NiFlowRegistry();
        ClusterBootstrapStub nodeA = new ClusterBootstrapStub(manager);
        ClusterBootstrapStub nodeB = new ClusterBootstrapStub(manager);
        set(flowsA, "cluster", nodeA);
        set(flowsB, "cluster", nodeB);

        StubAuth auth = new StubAuth();
        auth.next = new CallbackAuthService.NiAuth(CallbackAuthService.Result.OK, "lab", 0);

        servicesA = services(config, auth, flowsA);
        servicesB = services(config, auth, flowsB);
        sbbA = new HttpServerSbb(servicesA);
        sbbB = new HttpServerSbb(servicesB);
        set(sbbA, "http", http);
        set(sbbB, "http", http);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stop();
        }
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void nParallelIdenticalRequestsGiveExactlyOneFlowAndOneBegin() throws Exception {
        int n = 12;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        ConcurrentLinkedQueue<String> claims = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        HttpServerSbb sbb = idx % 2 == 0 ? sbbA : sbbB;
                        post(sbb, "sess-parallel-" + idx, "act-parallel-77");
                        claims.add((idx % 2 == 0 ? flowsA : flowsB)
                                .findByActivity("act-parallel-77").orElseThrow().flowId());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // All responses are 202 with the same flowId, and exactly one dialog
        // was created despite N concurrent identical requests on both nodes.
        assertThat(http.commands).hasSize(n);
        assertThat(http.commands).allSatisfy(cmd -> {
            Resp r = resp(cmd);
            assertThat(r.status()).isEqualTo(202);
            assertThat(r.body()).contains("\"flowId\"");
        });
        String firstFlow = flowIdOf(resp(http.commands.get(0)).body());
        assertThat(http.commands).allSatisfy(cmd ->
                assertThat(flowIdOf(resp(cmd).body())).isEqualTo(firstFlow));
        assertThat(store.started).hasSize(1).containsKey(firstFlow);
        assertThat(claims).hasSize(n);
        assertThat(claims).allSatisfy(c -> assertThat(c).isEqualTo(firstFlow));
    }

    @Test
    void idempotentRetryFromAnotherNodeGivesNoSecondBegin() {
        post(sbbA, "sess-node-a", "act-retry-88");
        String flowA = flowIdOf(lastResponse().body());
        assertThat(store.started).hasSize(1).containsKey(flowA);
        assertThat(flowsA.findByActivity("act-retry-88")).isPresent();

        post(sbbB, "sess-node-b", "act-retry-88");
        Resp retry = lastResponse();
        assertThat(retry.status()).isEqualTo(202);
        assertThat(flowIdOf(retry.body())).isEqualTo(flowA);
        assertThat(store.started).as("no second MAP BEGIN from the other node").hasSize(1);
        assertThat(flowsB.findByActivity("act-retry-88")).isPresent();
        NiFlowRegistry.Claim replay = flowsB.claim("act-retry-88", "251911230398");
        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.flow().flowId()).isEqualTo(flowA);
    }

    @Test
    void statusContractVariantsAndUnknownFlowId() {
        post(sbbA, "sess-status", "act-status-99");
        Resp accepted = lastResponse();
        assertThat(accepted.status()).isEqualTo(202);
        assertThat(accepted.body()).contains("\"flowId\"").contains("\"statusUrl\"");
        String flowId = flowIdOf(accepted.body());
        assertThat(accepted.body()).contains("/ussd/status/" + flowId);

        Resp parked = getStatus(sbbA, flowId);
        assertThat(parked.status()).isEqualTo(200);
        assertThat(parked.body()).contains("\"state\":\"PARKED\"")
                .contains("\"activityId\":\"act-status-99\"");

        park.unpark(flowId);
        store.remove(flowId);
        flowsA.mark(flowId, "COMPLETE", "done");
        Resp complete = getStatus(sbbB, flowId);
        assertThat(complete.status()).isEqualTo(200);
        assertThat(complete.body()).contains("\"state\":\"COMPLETE\"");

        flowsA.mark(flowId, "ABORTED", null);
        Resp aborted = getStatus(sbbA, flowId);
        assertThat(aborted.status()).isEqualTo(200);
        assertThat(aborted.body()).contains("\"state\":\"ABORTED\"");

        Resp missing = getStatus(sbbB, "no-such-flow");
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body()).contains("unknown flow");
    }

    @Test
    void classicPostWithoutActivityHeaderKeepsOldContract() {
        assertThatCode(() -> sbbA.onEvent(new HttpWebRequestEvent("sess-classic", "POST", "/ussd",
                        Map.of("Content-Type", "text/xml; charset=utf-8"), XML_NI),
                        container.createActivityContext("t-classic")))
                .doesNotThrowAnyException();
        // Old contract: the synchronous POST is parked (no 202/flow index);
        // the HTTP reply is delivered later by the park on the AS/UE answer.
        ClassicNiHttpPark.ParkRecord rec = park.findByCorr("ni-cluster-1").orElseThrow();
        assertThat(rec.httpSessionId()).isEqualTo("sess-classic");
        assertThat(rec.emptyHandshake()).isFalse();
        assertThat(flowsA.findByActivity("")).isEmpty();
        assertThat(store.started).containsKey("ni-cluster-1");
    }

    private SbbServices services(UssdConfigService config, StubAuth auth, NiFlowRegistry flows) {
        SbbServices services = new SbbServices();
        set(services, "config", config);
        set(services, "store", store);
        set(services, "wireFacade", new AsWireFacade());
        set(services, "niHttpPark", park);
        set(services, "niFlows", flows);
        set(services, "callbackAuth", auth);
        set(services, "adaptive", new AdaptiveTimeout());
        set(services, "tenantGuard", new TenantGuard() {
            @Override
            public Decision admit(String tenantId) {
                return new Decision(Reason.OK, null);
            }
        });
        set(services, "container", container);
        return services;
    }

    private void post(HttpServerSbb sbb, String sessionId, String activityId) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/xml; charset=utf-8");
        headers.put("X-USSD-Activity-Id", activityId);
        sbb.onEvent(new HttpWebRequestEvent(sessionId, "POST", "/ussd", headers, XML_NI),
                container.createActivityContext("t-" + sessionId));
    }

    private Resp getStatus(HttpServerSbb sbb, String flowId) {
        sbb.onEvent(new HttpWebRequestEvent("sess-get-" + flowId, "GET",
                        "/ussd/status/" + flowId, Map.of(), ""),
                container.createActivityContext("t-get"));
        return lastResponse();
    }

    private Resp lastResponse() {
        assertThat(http.commands).isNotEmpty();
        return resp(http.commands.get(http.commands.size() - 1));
    }

    private static Resp resp(OutboundCommand command) {
        if (command instanceof HttpServerCommand.HttpResponseCommand r) {
            return new Resp(r.statusCode(), r.body(), Map.of());
        }
        if (command instanceof HttpServerCommand.HttpResponseExCommand r) {
            return new Resp(r.statusCode(), r.textBody(), r.headers() == null ? Map.of() : r.headers());
        }
        throw new AssertionError("unexpected HTTP command: " + command);
    }

    private record Resp(int status, String body, Map<String, String> headers) {
    }

    private static String flowIdOf(String json) {
        int i = json.indexOf("\"flowId\"");
        int q1 = json.indexOf('"', json.indexOf(':', i) + 1);
        int q2 = json.indexOf('"', q1 + 1);
        return json.substring(q1 + 1, q2);
    }

    private static void set(Object target, String field, Object value) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(field);
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

    private static final class ClusterBootstrapStub extends et.restlink.ussdgw.bootstrap.ClusterBootstrap {
        private final ClusterManager manager;

        ClusterBootstrapStub(ClusterManager manager) {
            this.manager = manager;
        }

        @Override
        public ClusterManager clusterManager() {
            return manager;
        }
    }

    private static final class RecordingStore extends VirtualSessionStore {
        private final Map<String, VirtualSession> rows = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, VirtualSession> started = new java.util.concurrent.ConcurrentHashMap<>();

        @Override public void ensureTable() { }

        @Override
        public VirtualSession put(VirtualSession session) {
            if (session != null && session.correlationId() != null) {
                rows.put(session.correlationId(), session);
                started.putIfAbsent(session.correlationId(), session);
            }
            return session;
        }

        @Override
        public Optional<VirtualSession> get(String correlationId) {
            return correlationId == null ? Optional.empty() : Optional.ofNullable(rows.get(correlationId));
        }

        @Override public void remove(String correlationId) { rows.remove(correlationId); }
    }

    private static final class CapturingHttp implements RaCommandPort {
        final List<OutboundCommand> commands = new CopyOnWriteArrayList<>();

        @Override public void sendCommand(OutboundCommand command) { commands.add(command); }
    }

    private static final class StubAuth extends CallbackAuthService {
        volatile NiAuth next = new NiAuth(Result.OK, null, null);

        @Override
        public NiAuth authorizeNi(Map<String, String> headers, boolean authRequired) {
            return next;
        }
    }
}
