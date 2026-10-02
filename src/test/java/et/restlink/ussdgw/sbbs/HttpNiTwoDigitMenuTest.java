package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.admin.LinkStatusService;
import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.api.classic.ClassicNiHttpPark;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.UssdSagaCoordinator;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionState;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.campaign.CampaignService;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.events.NiPushReadyEvent;
import et.restlink.ussdgw.service.SbbServices;
import et.restlink.ussdgw.tenant.CallbackAuthService;
import et.restlink.ussdgw.tenant.TenantGuard;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;
import com.microjainslee.ra.jss7.command.Ss7Command;

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
 * P0-1 (Step 1): HTTP-NI interactive menu must survive two UE digits.
 *
 * <p>Flow: first menu parked (no Cookie) → digit 1 → AS posts menu 2 with Cookie →
 * digit 2. The AS continue carries no generation (classic XML has none), so the
 * session CAS — not a hardcoded gen — is the authority. Before the fix, menu 2 was
 * dropped ({@code AS_DROP reason=genMismatch}), the digit claim leaked in-flight,
 * and digit 2 died as {@code dup-skip-continue}.
 *
 * <p>Uses the real {@link VirtualSessionBridge} with a CAS-faithful in-memory
 * store (unlike {@code HttpServerSbbNiContinueTest}, which no-ops the bridge).
 */
class HttpNiTwoDigitMenuTest {
    private MicroSleeContainer container;
    private CasMemoryStore store;
    private CapturingHttp http;
    private CapturingSs7 ss7;
    private ClassicNiHttpPark park;
    private UssdConfigService config;
    private RecordingCdr cdr;
    private SbbServices services;
    private HttpServerSbb httpSbb;
    private MapNiPushSbb mapNiPushSbb;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();

        config = new UssdConfigService();
        set(config, "httpServerEnabledProp", true);
        set(config, "httpNiPathProp", "/ussd");
        set(config, "mapEnabledProp", true);
        // Production-like gate props: an unset ceiling collapses to a 1ms gate
        // and fires mid-test (P1-1 closeLiveMapLeg then owns the assertions).
        set(config, "asyncGateTimeoutMsProp", 25_000L);
        set(config, "dialogTimeoutMsProp", 60_000L);

        store = new CasMemoryStore();

        park = new ClassicNiHttpPark();
        set(park, "adaptive", new AdaptiveTimeout());
        set(park, "config", config);
        set(park, "wireFacade", new AsWireFacade());
        set(park, "store", store);
        cdr = new RecordingCdr();
        set(park, "cdr", cdr);

        http = new CapturingHttp();
        ss7 = new CapturingSs7();
        park.bindHttp(() -> http);

        StubAuth auth = new StubAuth();
        auth.next = new CallbackAuthService.NiAuth(CallbackAuthService.Result.OK, "lab", 0);

        VirtualSessionBridge bridge = new VirtualSessionBridge();
        set(bridge, "store", store);
        set(bridge, "adaptive", new AdaptiveTimeout());
        set(bridge, "config", config);
        set(bridge, "cdr", cdr);

        services = new SbbServices();
        set(services, "config", config);
        set(services, "store", store);
        set(services, "wireFacade", new AsWireFacade());
        set(services, "niHttpPark", park);
        set(services, "callbackAuth", auth);
        set(services, "adaptive", new AdaptiveTimeout());
        set(services, "bridge", bridge);
        set(services, "pendingSri", new et.restlink.ussdgw.service.PendingSriRegistry());
        set(services, "linkStatus", new LinkStatusService());
        set(services, "cdr", cdr);
        set(services, "saga", new UssdSagaCoordinator() {
            @Override
            public void onNiFailed(String correlationId, String reason) { }
        });
        set(services, "campaigns", new CampaignService() {
            @Override
            public void onNiDone(String correlationId, boolean ok, String error) { }
        });
        set(services, "tenantGuard", new TenantGuard() {
            @Override
            public Decision admit(String tenantId) {
                return new Decision(Reason.OK, null);
            }
        });
        set(services, "container", container);
        setStatic(SbbServices.class, "INSTANCE", services);

        httpSbb = new HttpServerSbb(services);
        set(httpSbb, "http", http);
        set(httpSbb, "ss7", ss7);

        mapNiPushSbb = new MapNiPushSbb(services);
        set(mapNiPushSbb, "ss7", ss7);

        container.registerSbbType(MapNiPushSbb.class, () -> mapNiPushSbb);
        container.mapEventToSbb(NiPushReadyEvent.class, "MapNiPushSbb");
    }

    @AfterEach
    void tearDown() {
        setStatic(SbbServices.class, "INSTANCE", null);
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void secondDigitSurvivesNiContinue() throws Exception {
        String corr = "corr-2digit-1";
        String jsession = "js-2digit-1";
        seedSession(corr, "251911230398", "251971200146", "636010024533522");
        park.park("http-sess-1", jsession, corr, AsHttpWireFormat.XML, 0, false);

        // Digit 1 — mirrors MapUssdParentSbb.onUserContinue HTTP-NI branch.
        assertThat(store.tryClaimMsDigitContinue(corr, 101)).isEmpty();
        VirtualSession d1 = store.get(corr).orElseThrow();
        d1.nextGeneration();
        store.put(d1);
        assertThat(d1.generation()).isEqualTo(2);
        assertThat(park.completeParked(corr, "1", AsAction.CONTINUE)).isTrue();

        // AS posts menu 2 with the JSESSIONID Cookie.
        String body = """
                <dialog localId="%s" networkId="0">
                  <unstructuredSSRequest_Request dataCodingScheme="15" string="Menu 2 pick">
                    <msisdn nai="international_number" npi="ISDN" number="251911230398"/>
                  </unstructuredSSRequest_Request>
                </dialog>
                """.formatted(corr);
        httpSbb.onEvent(new HttpWebRequestEvent("http-sess-2", "POST", "/ussd",
                Map.of("Content-Type", "text/xml", "Cookie", "JSESSIONID=" + jsession), body),
                container.createActivityContext("t"));

        // Menu 2 went out on the same MAP dialog…
        waitUntil(() -> ss7.cmds.stream()
                .anyMatch(c -> c instanceof Ss7Command.MapUnstructuredSsContinue cont
                        && cont.dialogId().equals(corr)
                        && cont.text() != null && cont.text().contains("Menu 2 pick")), 2_000);
        // …the session stayed interactive (no forced AWAITING_AS, no AS_DROP)…
        assertThat(store.get(corr)).isPresent();
        assertThat(store.get(corr).get().state()).isEqualTo(VirtualSessionState.ACTIVE);
        assertThat(cdr.statuses(corr)).doesNotContain("AS_DROP");

        // …so digit 2 wins the claim and reaches the parked AS HTTP.
        assertThat(store.tryClaimMsDigitContinue(corr, 102)).isEmpty();
        VirtualSession d2 = store.get(corr).orElseThrow();
        d2.nextGeneration();
        store.put(d2);
        assertThat(d2.generation()).isEqualTo(3);
        assertThat(park.completeParked(corr, "2", AsAction.CONTINUE)).isTrue();
    }

    private void seedSession(String corr, String msisdn, String mscGt, String imsi) {
        VirtualSession s = new VirtualSession(
                "sid", corr, "dlg", msisdn, 0, corr, "");
        s.setOriginationType(OriginationType.MAP);
        s.setState(VirtualSessionState.ACTIVE);
        s.setDialogAlive(true);
        s.setMscGt(mscGt);
        s.setImsi(imsi);
        store.put(s);
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

    /**
     * In-memory store with real CAS semantics on {@code state} (mirrors
     * {@code ProfileFacility.compareAndSetField}); digit claims reuse the
     * process-wide map from the superclass.
     */
    private static final class CasMemoryStore extends VirtualSessionStore {
        private final Map<String, VirtualSession> rows = new ConcurrentHashMap<>();

        @Override public void ensureTable() { }

        @Override
        public VirtualSession put(VirtualSession session) {
            if (session != null && session.correlationId() != null) {
                rows.put(session.correlationId(), session);
            }
            return session;
        }

        @Override
        public Optional<VirtualSession> get(String correlationId) {
            return correlationId == null
                    ? Optional.empty()
                    : Optional.ofNullable(rows.get(correlationId));
        }

        @Override
        public Optional<VirtualSession> byDialogId(String dialogId) {
            if (dialogId == null || dialogId.isBlank()) return Optional.empty();
            return rows.values().stream()
                    .filter(s -> dialogId.equals(s.dialogId()))
                    .findFirst();
        }

        @Override public void remove(String correlationId) {
            if (correlationId == null) return;
            rows.remove(correlationId);
            clearMsDigitClaim(correlationId);
        }

        @Override
        public Optional<VirtualSession> compareAndTransition(String correlationId,
                                                            VirtualSessionState expected,
                                                            VirtualSessionState next) {
            VirtualSession s = correlationId == null ? null : rows.get(correlationId);
            if (s == null || expected == null || next == null) return Optional.empty();
            synchronized (s) {
                if (s.state() != expected) return Optional.empty();
                s.setState(next);
                return Optional.of(s);
            }
        }

        @Override
        public Optional<VirtualSession> acceptAsResponse(String correlationId, int generation) {
            VirtualSession s = correlationId == null ? null : rows.get(correlationId);
            if (s == null) return Optional.empty();
            VirtualSessionState st = s.state();
            if (st.terminal()
                    || st == VirtualSessionState.PUSH_PENDING
                    || st == VirtualSessionState.RESPONDING) {
                return Optional.empty();
            }
            if (generation > 0 && generation != s.generation()) {
                return Optional.empty();
            }
            return Optional.of(s);
        }

        @Override
        public Optional<AsResponseClaim> claimForAsResponse(String correlationId, int generation) {
            Optional<VirtualSession> opt = acceptAsResponse(correlationId, generation);
            if (opt.isEmpty()) return Optional.empty();
            VirtualSession s = opt.get();
            VirtualSessionState seen = s.state();
            if (seen != VirtualSessionState.AWAITING_AS
                    && seen != VirtualSessionState.S1_RELEASED) {
                return Optional.empty();
            }
            if (compareAndTransition(correlationId, seen, VirtualSessionState.RESPONDING)
                    .isEmpty()) {
                if (seen != VirtualSessionState.AWAITING_AS
                        || compareAndTransition(correlationId, VirtualSessionState.S1_RELEASED,
                                VirtualSessionState.RESPONDING).isEmpty()) {
                    return Optional.empty();
                }
                seen = VirtualSessionState.S1_RELEASED;
            }
            return Optional.of(new AsResponseClaim(s, seen));
        }
    }

    private static final class RecordingCdr extends CdrService {
        final List<String[]> rows = new CopyOnWriteArrayList<>();

        @Override
        public void write(String correlationId, CdrPhase phase, String msisdn,
                          String shortCode, String status, String detail,
                          int networkId, String tenantId, String originationType,
                          Long gateMs, Long observedEwmaMs) {
            rows.add(new String[]{correlationId, status});
        }

        List<String> statuses(String correlationId) {
            return rows.stream()
                    .filter(r -> correlationId.equals(r[0]))
                    .map(r -> r[1])
                    .toList();
        }
    }

    private static final class CapturingHttp implements RaCommandPort {
        final List<OutboundCommand> commands = new CopyOnWriteArrayList<>();
        @Override public void sendCommand(OutboundCommand command) { commands.add(command); }
    }

    private static final class CapturingSs7 implements RaCommandPort {
        final List<OutboundCommand> cmds = new CopyOnWriteArrayList<>();
        @Override public void sendCommand(OutboundCommand command) { cmds.add(command); }
    }

    private static final class StubAuth extends CallbackAuthService {
        volatile NiAuth next = new NiAuth(Result.OK, null, null);

        @Override
        public NiAuth authorizeNi(Map<String, String> headers, boolean authRequired) {
            return next;
        }
    }
}
