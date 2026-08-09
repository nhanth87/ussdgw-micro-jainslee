package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.classic.ClassicNiHttpPark;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.routing.ShortCodeRoutingService;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.service.AsPullRouter;
import et.restlink.ussdgw.service.Map2MapCompletionService;
import et.restlink.ussdgw.service.PendingMap2MapRegistry;
import et.restlink.ussdgw.service.SbbServices;
import et.restlink.ussdgw.tenant.TenantGuard;

import com.microjainslee.ra.jss7.event.Ss7MapEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restcomm.protocols.ss7.map.api.MAPMessageType;
import org.restcomm.protocols.ss7.map.api.primitives.USSDString;
import org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSResponse;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Continue pull honesty: digit in {@code ussdString}, original dial in {@code originatedUssd}.
 */
class MapUssdParentContinueOriginatedUssdTest {

    private final ConcurrentHashMap<String, VirtualSession> sessions = new ConcurrentHashMap<>();
    private volatile AsRequest routed;
    private MapUssdParentSbb sbb;

    @BeforeEach
    void setUp() throws Exception {
        sessions.clear();
        routed = null;

        VirtualSessionStore store = new VirtualSessionStore() {
            @Override public Optional<VirtualSession> get(String corr) {
                return Optional.ofNullable(sessions.get(corr));
            }
            @Override public VirtualSession put(VirtualSession s) {
                if (s != null && s.correlationId() != null) {
                    sessions.put(s.correlationId(), s);
                }
                return s;
            }
            @Override public Optional<VirtualSession> byDialogId(String dialogId) {
                return sessions.values().stream()
                        .filter(s -> dialogId != null && dialogId.equals(s.dialogId()))
                        .findFirst();
            }
        };

        ShortCodeRoutingService routing = new ShortCodeRoutingService();
        routing.put(new ShortCodeRule("*100#", RuleType.HTTP, "http://as/pull", true));

        SbbServices services = new SbbServices();
        set(services, "store", store);
        set(services, "routing", routing);
        set(services, "config", new UssdConfigService());
        set(services, "niHttpPark", new ClassicNiHttpPark());
        set(services, "bridge", new VirtualSessionBridge() {
            @Override
            public void startAwaitingAs(VirtualSession s) {
                if (s != null) {
                    store.put(s);
                }
            }
        });
        set(services, "asPullRouter", new AsPullRouter() {
            @Override
            public String route(ShortCodeRule rule, AsRequest asReq, String correlationId) {
                routed = asReq;
                return "routed-test";
            }
        });
        set(services, "tenantGuard", new TenantGuard() {
            @Override
            public Decision admit(String tenantId) {
                return new Decision(Reason.OK, null);
            }
        });
        set(services, "map2MapCompletion", new Map2MapCompletionService() {
            @Override
            public void cancelDeferredHopClose(String outboundCorr) { }
        });
        set(services, "pendingMap2Map", new PendingMap2MapRegistry());
        sbb = new MapUssdParentSbb(services);
    }

    @Test
    void continuePullKeepsOriginalDialInOriginatedUssd() {
        String corr = "corr-orig";
        VirtualSession session = new VirtualSession(
                "vs-1", corr, "req-1", "251911000001", 1, "dlg-orig", "*100#");
        session.setOriginatedUssd("*100#");
        session.setRedirectUssd("*875#");
        session.setHopUssd("*8775#");
        session.setInvokeId(7L);
        session.setDialogAlive(true);
        sessions.put(corr, session);

        UnstructuredSSResponse resp = digitResponse("2", 9L);
        Ss7MapEvent.Service evt = new Ss7MapEvent.Service(
                "dlg-orig", MAPMessageType.unstructuredSSRequest_Response, resp);
        sbb.onEvent(evt, null);

        AsRequest asReq = routed;
        assertThat(asReq).isNotNull();
        assertThat(asReq.correlationId()).isEqualTo(corr);
        assertThat(asReq.ussdString()).isEqualTo("2");
        assertThat(asReq.originatedUssd()).isEqualTo("*100#");
        assertThat(asReq.originatedUssd()).isNotEqualTo("2");
        assertThat(asReq.redirectUssd()).isEqualTo("*875#");
        assertThat(asReq.hopUssd()).isEqualTo("*8775#");
        assertThat(asReq.generation()).isGreaterThan(1);
    }

    private static UnstructuredSSResponse digitResponse(String digit, long invokeId) {
        USSDString ussd = (USSDString) Proxy.newProxyInstance(
                USSDString.class.getClassLoader(),
                new Class<?>[] {USSDString.class},
                (proxy, method, args) -> {
                    if ("getString".equals(method.getName())) {
                        return digit;
                    }
                    if ("toString".equals(method.getName())) {
                        return digit;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class || rt == Integer.class) return 0;
                    if (rt == long.class || rt == Long.class) return 0L;
                    return null;
                });
        return (UnstructuredSSResponse) Proxy.newProxyInstance(
                UnstructuredSSResponse.class.getClassLoader(),
                new Class<?>[] {UnstructuredSSResponse.class},
                (proxy, method, args) -> {
                    if ("getInvokeId".equals(method.getName())) {
                        return invokeId;
                    }
                    if ("getUSSDString".equals(method.getName())) {
                        return ussd;
                    }
                    if ("getMessageType".equals(method.getName())) {
                        return MAPMessageType.unstructuredSSRequest_Response;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class || rt == Integer.class) return 0;
                    if (rt == long.class || rt == Long.class) return 0L;
                    return null;
                });
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                var f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(field);
    }
}
