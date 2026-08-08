package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.admin.LinkStatusService;
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
import et.restlink.ussdgw.events.NiPushRequestEvent;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSESSIONID continue reuses MAP dialog (NiPushReadyEvent.reuseExistingDialog);
 * ni-end / abort issue MapDialogClose / MapDialogAbort.
 */
class HttpServerSbbNiContinueTest {
    private MicroSleeContainer container;
    private RecordingStore store;
    private CapturingHttp http;
    private CapturingSs7 ss7;
    private ClassicNiHttpPark park;
    private UssdConfigService config;
    private SbbServices services;
    private HttpServerSbb httpSbb;
    private MapNiPushSbb mapNiPushSbb;
    private SriSbb sriSbb;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();

        config = new UssdConfigService();
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
        ss7 = new CapturingSs7();
        park.bindHttp(() -> http);

        StubAuth auth = new StubAuth();
        auth.next = new CallbackAuthService.NiAuth(CallbackAuthService.Result.OK, "lab", 0);

        VirtualSessionBridge bridge = new VirtualSessionBridge() {
            @Override
            public void onAsResponse(et.restlink.ussdgw.api.AsResponse response, long latencyMs) {
                // no-op for continue unit test
            }
        };

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
        set(services, "cdr", new CdrService() {
            @Override
            public void write(String correlationId, CdrPhase phase, String msisdn,
                              String shortCode, String status, String detail) { }
        });
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
        sriSbb = new SriSbb(services);
        set(sriSbb, "ss7", ss7);

        container.registerSbbType(MapNiPushSbb.class, () -> mapNiPushSbb);
        container.registerSbbType(SriSbb.class, () -> sriSbb);
        container.mapEventToSbb(NiPushReadyEvent.class, "MapNiPushSbb");
        container.mapEventToSbb(NiPushRequestEvent.class, "SriSbb");
    }

    @AfterEach
    void tearDown() {
        setStatic(SbbServices.class, "INSTANCE", null);
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void continueWithMscRoutesReuseExistingDialog() throws Exception {
        String corr = "corr-reuse-1";
        String jsession = "js-reuse-1";
        seedSession(corr, "251911230398", "251971200146", "636010024533522", true);
        park.park("http-sess", jsession, corr, AsHttpWireFormat.XML, 0, false);

        String body = """
                <dialog localId="%s" networkId="0">
                  <unstructuredSSRequest_Request dataCodingScheme="15" string="Pick 1">
                    <msisdn nai="international_number" npi="ISDN" number="251911230398"/>
                  </unstructuredSSRequest_Request>
                </dialog>
                """.formatted(corr);

        httpSbb.onEvent(new HttpWebRequestEvent("http-sess-2", "POST", "/ussd",
                Map.of("Content-Type", "text/xml", "Cookie", "JSESSIONID=" + jsession), body),
                container.createActivityContext("t"));

        waitUntil(() -> !ss7.cmds.isEmpty(), 2_000);
        assertThat(ss7.cmds.get(ss7.cmds.size() - 1))
                .isInstanceOf(Ss7Command.MapUnstructuredSsContinue.class);
        var c = (Ss7Command.MapUnstructuredSsContinue) ss7.cmds.get(ss7.cmds.size() - 1);
        assertThat(c.dialogId()).isEqualTo(corr);
        assertThat(c.text()).contains("Pick 1");
    }

    @Test
    void continueWithoutMscFallsBackToSriPath() throws Exception {
        String corr = "corr-sri-1";
        String jsession = "js-sri-1";
        seedSession(corr, "251911230398", null, null, true);
        park.park("http-sess", jsession, corr, AsHttpWireFormat.XML, 0, false);

        String body = """
                <dialog localId="%s">
                  <unstructuredSSRequest_Request dataCodingScheme="15" string="Again">
                    <msisdn number="251911230398"/>
                  </unstructuredSSRequest_Request>
                </dialog>
                """.formatted(corr);

        httpSbb.onEvent(new HttpWebRequestEvent("http-sess-2", "POST", "/ussd",
                Map.of("Content-Type", "text/xml", "Cookie", "JSESSIONID=" + jsession), body),
                container.createActivityContext("t"));

        // Lab (ss7 not live): SriSbb handoff → first-push MapUnstructuredSsRequest (not Continue).
        waitUntil(() -> !ss7.cmds.isEmpty(), 2_000);
        assertThat(ss7.cmds.get(ss7.cmds.size() - 1))
                .isInstanceOf(Ss7Command.MapUnstructuredSsRequest.class);
        assertThat(ss7.cmds.stream().noneMatch(c -> c instanceof Ss7Command.MapUnstructuredSsContinue))
                .isTrue();
    }

    @Test
    void endDialogClosesMapWithPrearrangedEnd() {
        String corr = "corr-end-1";
        String jsession = "js-end-1";
        seedSession(corr, "251911230398", "251971200146", "63601", true);
        park.park("http-sess", jsession, corr, AsHttpWireFormat.XML, 0, false);

        String body = """
                <dialog localId="%s" mapMessagesSize="0" prearrangedEnd="true"/>
                """.formatted(corr);

        httpSbb.onEvent(new HttpWebRequestEvent("http-sess-2", "POST", "/ussd",
                Map.of("Content-Type", "text/xml", "Cookie", "JSESSIONID=" + jsession), body),
                container.createActivityContext("t"));

        assertThat(ss7.cmds).hasSize(1);
        assertThat(ss7.cmds.get(0)).isInstanceOf(Ss7Command.MapDialogClose.class);
        var c = (Ss7Command.MapDialogClose) ss7.cmds.get(0);
        assertThat(c.dialogId()).isEqualTo(corr);
        assertThat(c.prearrangedEnd()).isTrue();
        assertThat(store.get(corr)).isEmpty();
    }

    @Test
    void abortDialogSendsMapDialogAbort() {
        String corr = "corr-abort-1";
        String jsession = "js-abort-1";
        seedSession(corr, "251911230398", "251971200146", "63601", true);
        park.park("http-sess", jsession, corr, AsHttpWireFormat.XML, 0, false);

        String body = """
                <dialog localId="%s" type="Abort">
                  <mapUserAbortChoice userSpecificReason="true"/>
                </dialog>
                """.formatted(corr);

        httpSbb.onEvent(new HttpWebRequestEvent("http-sess-2", "POST", "/ussd",
                Map.of("Content-Type", "text/xml", "Cookie", "JSESSIONID=" + jsession), body),
                container.createActivityContext("t"));

        assertThat(ss7.cmds).hasSize(1);
        assertThat(ss7.cmds.get(0)).isInstanceOf(Ss7Command.MapDialogAbort.class);
        assertThat(((Ss7Command.MapDialogAbort) ss7.cmds.get(0)).dialogId()).isEqualTo(corr);
    }

    private void seedSession(String corr, String msisdn, String mscGt, String imsi, boolean alive) {
        VirtualSession s = new VirtualSession(
                "sid", corr, "dlg", msisdn, 0, corr, "");
        s.setOriginationType(OriginationType.MAP);
        s.setState(VirtualSessionState.ACTIVE);
        s.setDialogAlive(alive);
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

    private static final class RecordingStore extends VirtualSessionStore {
        private final Map<String, VirtualSession> rows = new java.util.concurrent.ConcurrentHashMap<>();

        @Override public void ensureTable() { }

        @Override
        public VirtualSession put(VirtualSession session) {
            if (session != null && session.correlationId() != null) {
                rows.put(session.correlationId(), session);
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
