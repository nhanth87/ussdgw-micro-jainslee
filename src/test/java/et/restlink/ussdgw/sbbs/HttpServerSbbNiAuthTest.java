package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.api.classic.ClassicNiHttpPark;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
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

/**
 * B1 — the classic {@code /ussd} NI ingress fires a real UnstructuredSS-Request at an arbitrary
 * MSISDN, so it must be authenticated. Classic sat on an internal VLAN with no application-level
 * auth; that is not a safe default here, so the gateway requires an API key unless a lab opts out.
 */
class HttpServerSbbNiAuthTest {
    private static final String XML_NI = """
            <dialog localId="ni-corr-1" networkId="7">
              <destinationReference>251911000001</destinationReference>
              <unstructuredSSNotify_Request><ussdString>hello</ussdString></unstructuredSSNotify_Request>
            </dialog>
            """;
    private static final String JSON_NI = """
            {"correlationId":"ni-corr-2","msisdn":"251911000001","ussdString":"hello","networkId":7}
            """;

    private MicroSleeContainer container;
    private RecordingStore store;
    private CapturingHttp http;
    private StubAuth auth;
    private ClassicNiHttpPark park;
    private SbbServices services;
    private HttpServerSbb sbb;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();

        UssdConfigService config = new UssdConfigService();
        set(config, "httpServerEnabledProp", true);
        set(config, "httpNiPathProp", "/ussd");

        store = new RecordingStore();
        set(store, "container", container);
        set(store, "config", config);
        set(store, "profileTtlMs", 120_000L);

        park = new ClassicNiHttpPark();
        set(park, "adaptive", new AdaptiveTimeout());
        set(park, "config", config);
        set(park, "wireFacade", new AsWireFacade());

        http = new CapturingHttp();
        auth = new StubAuth();
        park.bindHttp(() -> http);

        services = new SbbServices();
        set(services, "config", config);
        set(services, "store", store);
        set(services, "wireFacade", new AsWireFacade());
        set(services, "niHttpPark", park);
        set(services, "callbackAuth", auth);
        // Admit without a TenantService bean — these tests assert NI auth + networkId only.
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
    void unauthenticatedXmlPushIsRejectedInXml() {
        auth.next = unauthorized();

        post("sess-x", "text/xml", XML_NI);

        var r = (HttpServerCommand.HttpResponseExCommand) last();
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.contentType()).contains("xml");
        assertThat(r.textBody()).contains("<dialog");
        assertNoPushStarted();
    }

    @Test
    void unauthenticatedJsonPushIsRejectedInJson() {
        auth.next = unauthorized();

        post("sess-j", "application/json", JSON_NI);

        var r = (HttpServerCommand.HttpResponseCommand) last();
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.body()).contains("unauthorized");
        assertNoPushStarted();
    }

    @Test
    void authorizedPushStillParksAndStartsTheSession() {
        auth.next = new CallbackAuthService.NiAuth(CallbackAuthService.Result.OK, null, null);

        post("sess-ok", "text/xml", XML_NI);

        // Classic NI sync parks the HTTP request until MAP/AS progress — no immediate body.
        assertThat(http.commands).isEmpty();
        assertThat(park.findByCorr("ni-corr-1")).isPresent();
        assertThat(store.started.get("ni-corr-1"))
                .extracting(VirtualSession::msisdn)
                .isEqualTo("251911000001");

        assertThat(park.completeParked("ni-corr-1", "hello", AsAction.CONTINUE)).isTrue();
        var r = (HttpServerCommand.HttpResponseExCommand) last();
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().get("Set-Cookie")).contains("JSESSIONID=");
    }

    @Test
    void networkIdComesFromTheDialogNotAHardcodedZero() {
        auth.next = new CallbackAuthService.NiAuth(CallbackAuthService.Result.OK, "tenant-a", 3);

        post("sess-net", "text/xml", XML_NI);

        assertThat(store.started.get("ni-corr-1"))
                .extracting(VirtualSession::networkId, VirtualSession::tenantId)
                .containsExactly(7, "tenant-a");
    }

    @Test
    void tenantNetworkIdAppliesWhenTheDialogCarriesNone() {
        auth.next = new CallbackAuthService.NiAuth(CallbackAuthService.Result.OK, "tenant-a", 3);
        String noNetwork = """
                <dialog localId="ni-corr-3">
                  <destinationReference>251911000001</destinationReference>
                  <unstructuredSSNotify_Request><ussdString>hi</ussdString></unstructuredSSNotify_Request>
                </dialog>
                """;

        post("sess-tenant", "text/xml", noNetwork);

        assertThat(store.started.get("ni-corr-3"))
                .extracting(VirtualSession::networkId)
                .isEqualTo(3);
    }

    @Test
    void authRequiredDefaultsToTrue() {
        assertThat(new UssdConfigService().httpNiAuthRequired()).isTrue();
    }

    private void assertNoPushStarted() {
        assertThat(auth.lastAuthRequired).isTrue();
        assertThat(store.started).isEmpty();
    }

    private static CallbackAuthService.NiAuth unauthorized() {
        return new CallbackAuthService.NiAuth(CallbackAuthService.Result.UNAUTHORIZED, null, null);
    }

    private void post(String sessionId, String contentType, String body) {
        sbb.onEvent(new HttpWebRequestEvent(sessionId, "POST", "/ussd",
                Map.of("Content-Type", contentType), body), container.createActivityContext("t"));
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

    /**
     * In-memory stand-in so assertions do not depend on the profile facility. {@code started} keeps
     * every session ever written, because the lab echo completes and removes it well before the
     * assertions run.
     */
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
        volatile Boolean lastAuthRequired;

        @Override
        public NiAuth authorizeNi(Map<String, String> headers, boolean authRequired) {
            lastAuthRequired = authRequired;
            return next;
        }
    }
}
