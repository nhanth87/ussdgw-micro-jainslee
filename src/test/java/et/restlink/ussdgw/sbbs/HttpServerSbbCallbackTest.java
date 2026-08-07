package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.api.classic.ClassicDialogXmlCodec;
import et.restlink.ussdgw.api.classic.ClassicNiHttpPark;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.service.SbbServices;
import et.restlink.ussdgw.tenant.CallbackAuthService;

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
 * Dual-mode {@code /as/callback} via constructor-injected {@link SbbServices} stubs.
 */
class HttpServerSbbCallbackTest {
    private MicroSleeContainer container;
    private VirtualSessionStore store;
    private CapturingHttp http;
    private RecordingBridge bridge;
    private SbbServices services;
    private HttpServerSbb sbb;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        store = new VirtualSessionStore();
        set(store, "container", container);
        set(store, "config", new UssdConfigService());
        set(store, "profileTtlMs", 120_000L);
        store.ensureTable();

        http = new CapturingHttp();
        bridge = new RecordingBridge();
        ClassicNiHttpPark park = new ClassicNiHttpPark();
        set(park, "adaptive", new AdaptiveTimeout());
        set(park, "config", new UssdConfigService());
        set(park, "wireFacade", new AsWireFacade());

        UssdConfigService config = new UssdConfigService();
        // Force http server enabled via reflection on store if present; otherwise defaults.
        services = new SbbServices();
        set(services, "config", config);
        set(services, "bridge", bridge);
        set(services, "store", store);
        set(services, "wireFacade", new AsWireFacade());
        set(services, "niHttpPark", park);
        set(services, "callbackAuth", new AllowAllAuth());
        set(services, "adminHttp", null);
        set(services, "container", container);

        // Mark INSTANCE for svc() fallback paths if any
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
    void callbackJsonPreservesAccepted202() {
        forceHttpServerEnabled(true);
        String body = """
                {"correlationId":"c-json","requestId":"c-json","generation":1,"text":"ok","action":"END","async":false}
                """;
        HttpWebRequestEvent req = new HttpWebRequestEvent(
                "sess-j", "POST", "/as/callback",
                Map.of("Content-Type", "application/json"), body);
        sbb.onEvent(req, container.createActivityContext("t"));

        assertThat(bridge.last).isNotNull();
        assertThat(bridge.last.correlationId()).isEqualTo("c-json");
        assertThat(http.commands).isNotEmpty();
        OutboundCommand cmd = http.commands.get(http.commands.size() - 1);
        assertThat(cmd).isInstanceOf(HttpServerCommand.HttpResponseCommand.class);
        var r = (HttpServerCommand.HttpResponseCommand) cmd;
        assertThat(r.statusCode()).isEqualTo(202);
        assertThat(r.body()).contains("\"accepted\":true");
    }

    @Test
    void callbackXmlReturnsDialogAck() {
        forceHttpServerEnabled(true);
        String xml = ClassicDialogXmlCodec.encodeNiSnapshot("c-xml", "bye", AsAction.END, false);
        HttpWebRequestEvent req = new HttpWebRequestEvent(
                "sess-x", "POST", "/as/callback",
                Map.of("Content-Type", "text/xml"), xml);
        sbb.onEvent(req, container.createActivityContext("t"));

        assertThat(bridge.last).isNotNull();
        assertThat(bridge.last.correlationId()).isEqualTo("c-xml");
        OutboundCommand cmd = http.commands.get(http.commands.size() - 1);
        assertThat(cmd).isInstanceOf(HttpServerCommand.HttpResponseExCommand.class);
        var r = (HttpServerCommand.HttpResponseExCommand) cmd;
        assertThat(r.statusCode()).isEqualTo(202);
        assertThat(r.contentType()).contains("xml");
        assertThat(r.textBody()).contains("<dialog");
    }

    private void forceHttpServerEnabled(boolean on) {
        // UssdConfigService.httpServerEnabled reads RuntimeConfigStore; when store is null, uses prop default.
        // Inject a tiny stub store via reflection if needed.
        try {
            var storeField = UssdConfigService.class.getDeclaredField("store");
            storeField.setAccessible(true);
            Object existing = storeField.get(services.config());
            if (existing == null) {
                // httpServerEnabledProp default from @ConfigProperty may be unset in unit test;
                // set the boolean field directly.
            }
            var prop = UssdConfigService.class.getDeclaredField("httpServerEnabledProp");
            prop.setAccessible(true);
            prop.setBoolean(services.config(), on);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
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

    private static final class CapturingHttp implements RaCommandPort {
        final List<OutboundCommand> commands = new CopyOnWriteArrayList<>();
        @Override public void sendCommand(OutboundCommand command) { commands.add(command); }
    }

    private static final class AllowAllAuth extends CallbackAuthService {
        @Override
        public Result authorizeCallback(String correlationId, Map<String, String> headers) {
            return Result.OK;
        }
    }

    /** Minimal bridge stub — records onAsResponse without MAP. */
    private static final class RecordingBridge extends VirtualSessionBridge {
        volatile AsResponse last;
        @Override
        public void onAsResponse(AsResponse response, long latencyMs) {
            last = response;
        }
    }
}
