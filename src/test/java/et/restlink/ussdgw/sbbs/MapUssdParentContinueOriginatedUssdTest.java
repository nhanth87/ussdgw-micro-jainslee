package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.classic.ClassicNiHttpPark;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionState;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Continue pull honesty: digit in {@code ussdString}, original dial in {@code originatedUssd}.
 * Also jSS7 dual UnstructuredSS-Response → one AS pull (dup-skip-continue).
 */
class MapUssdParentContinueOriginatedUssdTest {

    private final ConcurrentHashMap<String, VirtualSession> sessions = new ConcurrentHashMap<>();
    private volatile AsRequest routed;
    private final AtomicInteger routeCount = new AtomicInteger();
    private final List<AsRequest> routedAll = new ArrayList<>();
    private SbbServices services;
    private VirtualSessionStore store;
    private MapUssdParentSbb sbb;

    @BeforeEach
    void setUp() throws Exception {
        sessions.clear();
        routed = null;
        routeCount.set(0);
        routedAll.clear();
        store = identityStore();
        services = newServices(store);
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
        session.setState(VirtualSessionState.ACTIVE);
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

    @Test
    void doubleUnstructuredSsResponseOneAsPullNoSecondGenBump() {
        String corr = "corr-dup-digit";
        VirtualSession session = new VirtualSession(
                "vs-dup", corr, "req-dup", "251911000001", 1, "dlg-dup", "*100#");
        session.setOriginatedUssd("*804#");
        session.setInvokeId(7L);
        session.setDialogAlive(true);
        session.setState(VirtualSessionState.ACTIVE);
        session.setGeneration(1);
        sessions.put(corr, session);

        UnstructuredSSResponse resp = digitResponse("1", 11L);
        Ss7MapEvent.Service evt1 = new Ss7MapEvent.Service(
                "dlg-dup", MAPMessageType.unstructuredSSRequest_Response, resp);
        Ss7MapEvent.Service evt2 = new Ss7MapEvent.Service(
                "dlg-dup", MAPMessageType.unstructuredSSRequest_Response, resp);

        sbb.onEvent(evt1, null);
        assertThat(routeCount.get()).isEqualTo(1);
        assertThat(sessions.get(corr).generation()).isEqualTo(2);
        assertThat(sessions.get(corr).state()).isEqualTo(VirtualSessionState.AWAITING_AS);
        assertThat(routedAll.get(0).ussdString()).isEqualTo("1");
        assertThat(routedAll.get(0).generation()).isEqualTo(2);

        sbb.onEvent(evt2, null);
        assertThat(routeCount.get()).isEqualTo(1);
        assertThat(routedAll).hasSize(1);
        assertThat(sessions.get(corr).generation()).isEqualTo(2);
        assertThat(sessions.get(corr).state()).isEqualTo(VirtualSessionState.AWAITING_AS);
    }

    /**
     * Digicom hole: {@code store.get}/{@code byDialogId} rebuild VirtualSession from ussdTx,
     * so heap {@code lastMsContinueInvokeId} never sees the first claim. Store-level claim
     * must still dedup when each delivery resolves a <em>different</em> session instance.
     */
    @Test
    void dualResponseAcrossRehydratedSessionsOneAsPull() throws Exception {
        String corr = "corr-rehydrate-digit";
        VirtualSession session = new VirtualSession(
                "vs-rh", corr, "req-rh", "251911000001", 1, "dlg-rh", "*100#");
        session.setOriginatedUssd("*804#");
        session.setDialogAlive(true);
        session.setState(VirtualSessionState.ACTIVE);
        session.setGeneration(1);
        sessions.put(corr, session);

        store = rehydratingStore();
        services = newServices(store);
        sbb = new MapUssdParentSbb(services);

        UnstructuredSSResponse resp = digitResponse("1", 99L);
        sbb.onEvent(new Ss7MapEvent.Service(
                "dlg-rh", MAPMessageType.unstructuredSSRequest_Response, resp), null);
        sbb.onEvent(new Ss7MapEvent.Service(
                "dlg-rh", MAPMessageType.unstructuredSSRequest_Response, resp), null);

        assertThat(routeCount.get()).isEqualTo(1);
        assertThat(routedAll).hasSize(1);
        assertThat(sessions.get(corr).generation()).isEqualTo(2);
    }

    @Test
    void concurrentSameInvokeOnlyOneAsPull() throws Exception {
        String corr = "corr-race-digit";
        VirtualSession session = new VirtualSession(
                "vs-race", corr, "req-race", "251911000001", 1, "dlg-race", "*100#");
        session.setOriginatedUssd("*804#");
        session.setDialogAlive(true);
        session.setState(VirtualSessionState.ACTIVE);
        session.setGeneration(1);
        sessions.put(corr, session);

        UnstructuredSSResponse resp = digitResponse("1", 42L);
        Ss7MapEvent.Service evt = new Ss7MapEvent.Service(
                "dlg-race", MAPMessageType.unstructuredSSRequest_Response, resp);

        Thread t1 = new Thread(() -> sbb.onEvent(evt, null));
        Thread t2 = new Thread(() -> sbb.onEvent(evt, null));
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertThat(routeCount.get()).isEqualTo(1);
        assertThat(sessions.get(corr).generation()).isEqualTo(2);
        assertThat(routedAll).hasSize(1);
    }

    @Test
    void realSecondDigitAfterContinueActiveStillPulls() {
        String corr = "corr-digit2";
        VirtualSession session = new VirtualSession(
                "vs-d2", corr, "req-d2", "251911000001", 1, "dlg-d2", "*100#");
        session.setOriginatedUssd("*804#");
        session.setInvokeId(11L);
        session.setDialogAlive(true);
        session.setState(VirtualSessionState.ACTIVE);
        session.setGeneration(1);
        sessions.put(corr, session);

        sbb.onEvent(new Ss7MapEvent.Service(
                "dlg-d2", MAPMessageType.unstructuredSSRequest_Response,
                digitResponse("1", 11L)), null);
        assertThat(routeCount.get()).isEqualTo(1);
        assertThat(sessions.get(corr).generation()).isEqualTo(2);

        // AS CONTINUE → ACTIVE releases in-flight; real next digit must pull again.
        sessions.get(corr).setState(VirtualSessionState.ACTIVE);
        store.releaseMsDigitInFlight(corr);

        sbb.onEvent(new Ss7MapEvent.Service(
                "dlg-d2", MAPMessageType.unstructuredSSRequest_Response,
                digitResponse("2", 12L)), null);

        assertThat(routeCount.get()).isEqualTo(2);
        assertThat(sessions.get(corr).generation()).isEqualTo(3);
        assertThat(routed.ussdString()).isEqualTo("2");
        assertThat(routed.generation()).isEqualTo(3);
    }

    private VirtualSessionStore identityStore() {
        return new VirtualSessionStore() {
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
    }

    private VirtualSessionStore rehydratingStore() {
        return new VirtualSessionStore() {
            @Override public Optional<VirtualSession> get(String c) {
                VirtualSession src = sessions.get(c);
                if (src == null) {
                    return Optional.empty();
                }
                return Optional.of(copySession(src));
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
                        .findFirst()
                        .map(MapUssdParentContinueOriginatedUssdTest::copySession);
            }
        };
    }

    private SbbServices newServices(VirtualSessionStore sessionStore) throws Exception {
        ShortCodeRoutingService routing = new ShortCodeRoutingService();
        routing.put(new ShortCodeRule("*100#", RuleType.HTTP, "http://as/pull", true));

        SbbServices svc = new SbbServices();
        set(svc, "store", sessionStore);
        set(svc, "routing", routing);
        set(svc, "config", new UssdConfigService());
        set(svc, "niHttpPark", new ClassicNiHttpPark());
        set(svc, "bridge", new VirtualSessionBridge() {
            @Override
            public void startAwaitingAs(VirtualSession s) {
                if (s != null) {
                    s.setState(VirtualSessionState.AWAITING_AS);
                    s.setPullStartedAtMs(System.currentTimeMillis());
                    sessionStore.put(s);
                }
            }
        });
        set(svc, "asPullRouter", new AsPullRouter() {
            @Override
            public String route(ShortCodeRule rule, AsRequest asReq, String correlationId) {
                routed = asReq;
                routedAll.add(asReq);
                routeCount.incrementAndGet();
                return "routed-test";
            }
        });
        set(svc, "tenantGuard", new TenantGuard() {
            @Override
            public Decision admit(String tenantId) {
                return new Decision(Reason.OK, null);
            }
        });
        set(svc, "map2MapCompletion", new Map2MapCompletionService() {
            @Override
            public void cancelDeferredHopClose(String outboundCorr) { }
        });
        set(svc, "pendingMap2Map", new PendingMap2MapRegistry());
        return svc;
    }

    private static VirtualSession copySession(VirtualSession src) {
        VirtualSession copy = new VirtualSession(
                src.virtualSessionId(), src.correlationId(), src.requestId(),
                src.msisdn(), src.networkId(), src.dialogId(), src.shortCode());
        copy.setGeneration(src.generation());
        copy.setState(src.state());
        copy.setInvokeId(src.invokeId());
        copy.setDialogAlive(src.dialogAlive());
        copy.setOriginatedUssd(src.originatedUssd());
        copy.setRedirectUssd(src.redirectUssd());
        copy.setHopUssd(src.hopUssd());
        copy.setAdaptiveBridgeArm(src.adaptiveBridgeArm());
        copy.setTenantId(src.tenantId());
        return copy;
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
