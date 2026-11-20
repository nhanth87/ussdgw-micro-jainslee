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
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.httpserver.command.HttpServerCommand;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * WP5 idempotent NI async contract: {@code POST /ussd} with
 * {@code X-USSD-Activity-Id} answers {@code 202 {flowId,statusUrl}} and a
 * duplicate POST replays the same flow without a second MAP dialog; {@code GET
 * /ussd/status/{flowId}} reports it. Without the header the classic sync-park
 * is untouched (see {@code HttpServerSbbNiParkTest}).
 */
class HttpServerSbbNiFlowTest {
    private static final String XML_NI = """
            <dialog localId="ni-flow-1" networkId="0">
              <unstructuredSSRequest_Request dataCodingScheme="15" string="Digicom lab push">
                <msisdn nai="international_number" npi="ISDN" number="251911230398"/>
              </unstructuredSSRequest_Request>
            </dialog>
            """;

    private MicroSleeContainer container;
    private RecordingStore store;
    private CapturingHttp http;
    private ClassicNiHttpPark park;
    private NiFlowRegistry flows;
    private UssdConfigService config;
    private SbbServices services;
    private HttpServerSbb sbb;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();

        config = new UssdConfigService();
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

        flows = new NiFlowRegistry();

        http = new CapturingHttp();
        park.bindHttp(() -> http);

        StubAuth auth = new StubAuth();
        auth.next = new CallbackAuthService.NiAuth(CallbackAuthService.Result.OK, "lab", 0);

        services = new SbbServices();
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
        setStatic(SbbServices.class, "INSTANCE", services);

        sbb = new HttpServerSbb(services);
        set(sbb, "http", http);
    }

    @AfterEach
    void tearDown() {
        setStatic(SbbServices.class, "INSTANCE", null);
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void asyncPostAnswers202WithFlowAndStatus() {
        assertThatCode(() -> postAsync("sess-a1", XML_NI, "act-202-1"))
                .doesNotThrowAnyException();

        var r = (HttpServerCommand.HttpResponseCommand) last();
        assertThat(r.statusCode()).isEqualTo(202);
        assertThat(r.body()).contains("\"flowId\"");
        assertThat(r.body()).contains("\"statusUrl\"");

        String flowId = flowIdOf(r.body());
        // flow == correlation == session; MAP leg routed like the classic path.
        assertThat(store.started).containsKey(flowId);
        assertThat(park.findByCorr(flowId)).isPresent();
        assertThat(flows.findByActivity("act-202-1")).isPresent();
    }

    @Test
    void duplicatePostReplaysSameFlowWithoutSecondDialog() {
        postAsync("sess-d1", XML_NI, "act-dup-9");
        String first = flowIdOf(((HttpServerCommand.HttpResponseCommand) last()).body());
        int sessions = store.started.size();

        postAsync("sess-d2", XML_NI, "act-dup-9");
        var r = (HttpServerCommand.HttpResponseCommand) last();
        assertThat(r.statusCode()).isEqualTo(202);
        assertThat(flowIdOf(r.body())).isEqualTo(first);
        assertThat(store.started).hasSize(sessions);
    }

    @Test
    void getStatusReportsParkedThen404ForUnknown() {
        postAsync("sess-s1", XML_NI, "act-status-3");
        String flowId = flowIdOf(((HttpServerCommand.HttpResponseCommand) last()).body());

        sbb.onEvent(new HttpWebRequestEvent("sess-s2", "GET",
                "/ussd/status/" + flowId, Map.of(), ""),
                container.createActivityContext("t"));
        var ok = (HttpServerCommand.HttpResponseCommand) last();
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("\"state\":\"PARKED\"");

        sbb.onEvent(new HttpWebRequestEvent("sess-s3", "GET",
                "/ussd/status/no-such-flow", Map.of(), ""),
                container.createActivityContext("t"));
        var missing = (HttpServerCommand.HttpResponseCommand) last();
        assertThat(missing.statusCode()).isEqualTo(404);
    }

    @Test
    void classicPostWithoutActivityHeaderStillParks() {
        sbb.onEvent(new HttpWebRequestEvent("sess-c1", "POST", "/ussd",
                Map.of("Content-Type", "text/xml; charset=utf-8"), XML_NI),
                container.createActivityContext("t"));
        assertThat(park.findByCorr("ni-flow-1")).isPresent();
        assertThat(flows.findByActivity("")).isEmpty();
    }

    private void postAsync(String sessionId, String body, String activityId) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/xml; charset=utf-8");
        headers.put("X-USSD-Activity-Id", activityId);
        sbb.onEvent(new HttpWebRequestEvent(sessionId, "POST", "/ussd", headers, body),
                container.createActivityContext("t"));
    }

    private static String flowIdOf(String json) {
        int i = json.indexOf("\"flowId\"");
        int q1 = json.indexOf('"', json.indexOf(':', i) + 1);
        int q2 = json.indexOf('"', q1 + 1);
        return json.substring(q1 + 1, q2);
    }

    private OutboundCommand last() {
        assertThat(http.commands).isNotEmpty();
        return http.commands.get(http.commands.size() - 1);
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

    private static void setStatic(Class<?> type, String field, Object value) {
        try {
            var f = type.getDeclaredField(field);
            f.setAccessible(true);
            f.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
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
        public java.util.Optional<VirtualSession> get(String correlationId) {
            return correlationId == null
                    ? java.util.Optional.empty()
                    : java.util.Optional.ofNullable(rows.get(correlationId));
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
