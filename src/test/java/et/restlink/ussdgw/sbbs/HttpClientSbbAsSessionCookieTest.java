package et.restlink.ussdgw.sbbs;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.httpclient.command.HttpCallbackCommand;
import com.microjainslee.ra.httpclient.events.HttpCallbackCompletedEvent;
import et.restlink.ussdgw.api.AsCookieJar;
import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.api.WireFormatResolver;
import et.restlink.ussdgw.bridge.UssdSagaCoordinator;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.events.PullHttpEvent;
import et.restlink.ussdgw.service.AsPullClient;
import et.restlink.ussdgw.service.AsPullStateRegistry;
import et.restlink.ussdgw.service.SbbServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP sync pull keeps the AS's servlet session the way the classic gateway did (one cookie
 * store per MAP dialog): turn 1's {@code Set-Cookie: JSESSIONID} is sent back as {@code Cookie}
 * on every later turn of the SAME dialog, never on another subscriber's dialog.
 */
class HttpClientSbbAsSessionCookieTest {

    private static final String URL = "http://as-classic:8080/ussd";

    private MicroSleeContainer container;
    private SbbServices services;
    private final CapturingRa ra = new CapturingRa();
    private final CookieStore store = new CookieStore();
    private HttpClientSbb sbb;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        AsPullStateRegistry registry = new AsPullStateRegistry();
        set(registry, "ttlMsProp", 60_000L);
        AsPullClient asPull = new AsPullClient();
        set(asPull, "failThreshold", 100);
        set(asPull, "openMs", 30_000L);
        set(asPull, "maxRetries", 0);
        services = new SbbServices();
        set(services, "container", container);
        set(services, "asPull", asPull);
        set(services, "asPullState", registry);
        set(services, "saga", new UssdSagaCoordinator() {
            @Override public void onAsPullFailed(String correlationId, String reason) { }
        });
        set(services, "store", store);
        set(services, "bridge", new VirtualSessionBridge() {
            @Override public void onAsResponse(AsResponse response, long latencyMs) { }
        });
        set(services, "wireFacade", new AsWireFacade());
        set(services, "wireFormatResolver", new WireFormatResolver() {
            @Override public AsHttpWireFormat resolve(String tenantId) { return AsHttpWireFormat.XML; }
        });
        sbb = new HttpClientSbb(services);
        set(sbb, "httpClient", ra);
    }

    @AfterEach
    void tearDown() {
        container.stop();
    }

    @Test
    void jsessionIdFollowsTheDialogAcrossMenuTurns() {
        // Turn 1 (MO *123#): no cookie yet; classic Accept header.
        sbb.onEvent(pull("dlg-A", 0, "*123#"), aci());
        HttpCallbackCommand.PostRequest t1 = last();
        assertThat(t1.headers()).containsEntry("Accept", "text/xml").doesNotContainKey("Cookie");
        assertThat(t1.contentType()).startsWith("text/xml");

        // AS opens its HttpSession and answers with a menu.
        sbb.onEvent(new HttpCallbackCompletedEvent("dlg-A", 200, menuXml("dlg-A"), null,
                Map.of("set-cookie", List.of("JSESSIONID=8F2A9C; Path=/ussd; HttpOnly"))), aci());
        assertThat(store.asCookie("dlg-A")).isEqualTo("JSESSIONID=8F2A9C");

        // Turn 2 (user picks "1") on the same dialog must carry the AS session.
        sbb.onEvent(pull("dlg-A", 1, "1"), aci());
        assertThat(last().headers()).containsEntry("Cookie", "JSESSIONID=8F2A9C");

        // Another subscriber's dialog never sees it.
        sbb.onEvent(pull("dlg-B", 0, "*123#"), aci());
        assertThat(last().headers()).doesNotContainKey("Cookie");

        // The AS rotates its session id (e.g. after login) and adds a sticky-route cookie.
        sbb.onEvent(new HttpCallbackCompletedEvent("dlg-A", 200, menuXml("dlg-A"), null,
                Map.of("set-cookie", List.of("JSESSIONID=NEW77; Path=/ussd", "route=as2"))), aci());
        sbb.onEvent(pull("dlg-A", 2, "2"), aci());
        assertThat(last().headers()).containsEntry("Cookie", "JSESSIONID=NEW77; route=as2");
    }

    @Test
    void cookieJarFollowsRfc6265Deletion() {
        assertThat(AsCookieJar.merge(null, List.of("JSESSIONID=1; Path=/"))).isEqualTo("JSESSIONID=1");
        assertThat(AsCookieJar.merge("JSESSIONID=1; a=b", List.of("JSESSIONID=; Max-Age=0"))).isEqualTo("a=b");
        assertThat(AsCookieJar.merge("a=b", List.of("a=x; Expires=Thu, 01 Jan 1970 00:00:00 GMT"))).isNull();
        // Max-Age wins over a past Expires, whatever the order.
        assertThat(AsCookieJar.merge(null, List.of("a=x; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=60")))
                .isEqualTo("a=x");
        assertThat(AsCookieJar.merge(null, List.of("q=\"quoted\""))).isEqualTo("q=quoted");
        assertThat(AsCookieJar.merge("a=1", null)).isEqualTo("a=1");
    }

    // ------------------------------------------------------------------ harness

    private PullHttpEvent pull(String corr, int generation, String ussd) {
        return new PullHttpEvent(URL, new AsRequest("vs", corr, corr, generation, "251911000000",
                "*123#", ussd, 0));
    }

    private static String menuXml(String corr) {
        return "<dialog localId=\"" + corr + "\" mapMessagesSize=\"1\">"
                + "<unstructuredSSRequest_Request dataCodingScheme=\"15\" string=\"1. Balance 2. Bundles\"/>"
                + "</dialog>";
    }

    private HttpCallbackCommand.PostRequest last() {
        OutboundCommand c = ra.commands.get(ra.commands.size() - 1);
        assertThat(c).isInstanceOf(HttpCallbackCommand.PostRequest.class);
        return (HttpCallbackCommand.PostRequest) c;
    }

    private com.microjainslee.api.ActivityContextInterface aci() {
        return container.createActivityContext("t-" + System.nanoTime());
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

    /** ussdTx stand-in: only the cookie field matters here. */
    private static final class CookieStore extends VirtualSessionStore {
        private final Map<String, String> cookies = new ConcurrentHashMap<>();
        @Override public Optional<VirtualSession> get(String correlationId) { return Optional.empty(); }
        @Override public String asCookie(String correlationId) { return cookies.get(correlationId); }
        @Override public void setAsCookie(String correlationId, String cookieHeader) {
            if (cookieHeader == null) {
                cookies.remove(correlationId);
            } else {
                cookies.put(correlationId, cookieHeader);
            }
        }
    }
}
