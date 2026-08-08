package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.admin.LinkStatusService;
import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.api.UssdAlphabet;
import et.restlink.ussdgw.api.classic.ClassicNiHttpPark;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.UssdSagaCoordinator;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionState;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.campaign.CampaignService;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.events.NiPushReadyEvent;
import et.restlink.ussdgw.events.NiPushRequestEvent;
import et.restlink.ussdgw.service.SbbServices;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.jss7.command.Ss7Command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MapNiPushSbb: first push vs same-dialog continue command selection.
 */
class MapNiPushSbbReuseTest {
    private MicroSleeContainer container;
    private RecordingStore store;
    private CapturingSs7 ss7;
    private SbbServices services;
    private MapNiPushSbb sbb;
    private ClassicNiHttpPark park;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        store = new RecordingStore();
        ss7 = new CapturingSs7();

        UssdConfigService config = new UssdConfigService();
        park = new ClassicNiHttpPark();
        set(park, "adaptive", new AdaptiveTimeout());
        set(park, "config", config);
        set(park, "wireFacade", new AsWireFacade());

        services = new SbbServices();
        set(services, "config", config);
        set(services, "store", store);
        set(services, "container", container);
        set(services, "niHttpPark", park);
        set(services, "cdr", new CdrService() {
            @Override
            public void write(String correlationId, CdrPhase phase, String msisdn,
                              String shortCode, String status, String detail) {
                // no-op
            }
        });
        set(services, "saga", new UssdSagaCoordinator() {
            @Override
            public void onNiFailed(String correlationId, String reason) {
                // no-op
            }
        });
        set(services, "campaigns", new CampaignService() {
            @Override
            public void onNiDone(String correlationId, boolean ok, String error) {
                // no-op
            }
        });
        set(services, "linkStatus", new LinkStatusService() {
            @Override
            public boolean ss7Live() {
                return false;
            }
        });
        setStatic(SbbServices.class, "INSTANCE", services);

        sbb = new MapNiPushSbb(services);
        set(sbb, "ss7", ss7);
    }

    @AfterEach
    void tearDown() {
        setStatic(SbbServices.class, "INSTANCE", null);
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void reuseExistingDialogSendsContinueNotNewNi() {
        String corr = "c-reuse";
        park.park("http", "js", corr, AsHttpWireFormat.XML, 0, false);
        VirtualSession s = new VirtualSession("sid", corr, "d", "251911", 0, corr, "");
        s.setState(VirtualSessionState.ACTIVE);
        s.setMscGt("251971200146");
        s.setImsi("63601");
        store.put(s);

        sbb.onEvent(NiPushReadyEvent.continueOnDialog(
                corr, "251911", "Next", 0, UssdAlphabet.AUTO, false,
                "251971200146", "63601"),
                container.createActivityContext("t"));

        assertThat(ss7.cmds).hasSize(1);
        assertThat(ss7.cmds.get(0)).isInstanceOf(Ss7Command.MapUnstructuredSsContinue.class);
        var c = (Ss7Command.MapUnstructuredSsContinue) ss7.cmds.get(0);
        assertThat(c.dialogId()).isEqualTo(corr);
        assertThat(c.text()).isEqualTo("Next");
    }

    @Test
    void firstPushSendsUnstructuredSsRequest() {
        String corr = "c-first";
        park.park("http", "js", corr, AsHttpWireFormat.XML, 0, false);
        VirtualSession s = new VirtualSession("sid", corr, "d", "251911", 0, corr, "");
        s.setState(VirtualSessionState.ACTIVE);
        s.setMscGt("251971200146");
        s.setImsi("63601");
        store.put(s);

        sbb.onEvent(NiPushReadyEvent.fromSri(
                new NiPushRequestEvent(corr, "251911", "Hello", 0, UssdAlphabet.AUTO, false),
                "251971200146", "63601"),
                container.createActivityContext("t"));

        assertThat(ss7.cmds).hasSize(1);
        assertThat(ss7.cmds.get(0)).isInstanceOf(Ss7Command.MapUnstructuredSsRequest.class);
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
        throw new IllegalStateException("No field " + field);
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
        private final java.util.Map<String, VirtualSession> rows =
                new java.util.concurrent.ConcurrentHashMap<>();

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
            return java.util.Optional.ofNullable(rows.get(correlationId));
        }

        @Override public void remove(String correlationId) { rows.remove(correlationId); }
    }

    private static final class CapturingSs7 implements RaCommandPort {
        final List<OutboundCommand> cmds = new CopyOnWriteArrayList<>();
        @Override public void sendCommand(OutboundCommand command) { cmds.add(command); }
    }
}
