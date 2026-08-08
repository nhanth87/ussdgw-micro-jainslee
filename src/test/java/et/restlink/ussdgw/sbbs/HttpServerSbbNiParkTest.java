package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.api.classic.ClassicNiHttpPark;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.config.RuntimeConfigStore;
import et.restlink.ussdgw.config.UssdConfigService;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Push NI park path: map=false lab echo and map=true AdaptiveTimeout gate — must not throw
 * (regression for Digicom {@code UnsupportedOperationException} on {@code POST /ussd}).
 */
class HttpServerSbbNiParkTest {
    private static final String XML_NI = """
            <dialog localId="ni-park-1" networkId="0">
              <unstructuredSSRequest_Request dataCodingScheme="15" string="Digicom lab push">
                <msisdn nai="international_number" npi="ISDN" number="251911230398"/>
              </unstructuredSSRequest_Request>
            </dialog>
            """;

    private MicroSleeContainer container;
    private RecordingStore store;
    private CapturingHttp http;
    private ClassicNiHttpPark park;
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

        http = new CapturingHttp();
        park.bindHttp(() -> http);

        StubAuth auth = new StubAuth();
        auth.next = new CallbackAuthService.NiAuth(CallbackAuthService.Result.OK, "lab", 0);

        services = new SbbServices();
        set(services, "config", config);
        set(services, "store", store);
        set(services, "wireFacade", new AsWireFacade());
        set(services, "niHttpPark", park);
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
    void mapDisabledLabEchoParksThenRepliesWithoutThrowing() throws Exception {
        // Default mapEnabled=false (no RuntimeConfigStore) → scheduleLabEcho.
        assertThatCode(() -> post("sess-echo", XML_NI)).doesNotThrowAnyException();

        assertThat(park.findByCorr("ni-park-1")).isPresent();
        assertThat(store.started.get("ni-park-1"))
                .extracting(VirtualSession::msisdn)
                .isEqualTo("251911230398");

        waitUntil(() -> !http.commands.isEmpty(), 2_000);

        var r = (HttpServerCommand.HttpResponseExCommand) last();
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.textBody()).contains("Digicom lab push");
        assertThat(r.headers().get("Set-Cookie")).contains("JSESSIONID=");
    }

    @Test
    void mapEnabledAdaptiveGateExpiresWithAbort() throws Exception {
        RuntimeConfigStore kv = new RuntimeConfigStore();
        var cache = RuntimeConfigStore.class.getDeclaredField("cache");
        cache.setAccessible(true);
        @SuppressWarnings("unchecked")
        var map = (java.util.concurrent.ConcurrentHashMap<String, String>) cache.get(kv);
        map.put(RuntimeConfigStore.Keys.MAP_ENABLED, "true");
        map.put(RuntimeConfigStore.Keys.ASYNC_GATE_MS, "60");
        set(config, "store", kv);
        set(park, "config", config);

        assertThatCode(() -> post("sess-gate", XML_NI)).doesNotThrowAnyException();
        assertThat(park.findByCorr("ni-park-1")).isPresent();
        assertThat(http.commands).isEmpty(); // parked until gate

        // Gate reply must fire; JSESSIONID→corr stays (AS can re-push after gated abort).
        waitUntil(() -> !http.commands.isEmpty()
                && park.findByCorr("ni-park-1").map(p -> p.httpSessionId() == null
                        || p.httpSessionId().isBlank()).orElse(false),
                2_000);

        var r = (HttpServerCommand.HttpResponseExCommand) last();
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.textBody().toLowerCase()).contains("abort");
        assertThat(park.findByCorr("ni-park-1")).isPresent();
    }

    private void post(String sessionId, String body) {
        sbb.onEvent(new HttpWebRequestEvent(sessionId, "POST", "/ussd",
                Map.of("Content-Type", "text/xml; charset=utf-8"), body),
                container.createActivityContext("t"));
    }

    private OutboundCommand last() {
        assertThat(http.commands).isNotEmpty();
        return http.commands.get(http.commands.size() - 1);
    }

    private static void waitUntil(java.util.concurrent.Callable<Boolean> cond, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(cond.call())) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(cond.call()).as("condition within %dms", timeoutMs).isTrue();
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
