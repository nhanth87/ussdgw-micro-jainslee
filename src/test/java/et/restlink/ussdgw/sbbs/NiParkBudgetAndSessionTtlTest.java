package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.api.classic.ClassicNiHttpPark;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionState;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.service.Map2MapCompletionService;
import et.restlink.ussdgw.service.PendingMap2MapRegistry;
import et.restlink.ussdgw.service.SbbServices;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.httpserver.command.HttpServerCommand;
import com.microjainslee.ra.jss7.command.Ss7Command;
import com.microjainslee.ra.jss7.event.Ss7MapEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restcomm.protocols.ss7.map.api.MAPMessageType;
import org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSResponse;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 4 (P1-1 / P1-2): NI Request parks run on the UI budget and close the MAP
 * leg on expiry; ACTIVE sessions live to the session cap; a digit on a missing
 * row ends hard instead of hanging the UE.
 */
class NiParkBudgetAndSessionTtlTest {
    private MicroSleeContainer container;
    private VirtualSessionStore store;
    private UssdConfigService config;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        store = new VirtualSessionStore();
        set(store, "container", container);
        config = new UssdConfigService();
        set(config, "asyncGateTimeoutMsProp", 25_000L);
        set(config, "dialogTimeoutMsProp", 60_000L);
        set(config, "niRequestUiTimeoutMsProp", 120_000L);
        set(store, "config", config);
        set(store, "profileTtlMs", 120_000L);
        store.ensureTable();
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    // ------------------------------------------------------------------ P1-1

    @Test
    void requestParkUsesUiBudgetNotifyKeepsCeiling() {
        ClassicNiHttpPark park = new ClassicNiHttpPark();
        set(park, "adaptive", new AdaptiveTimeout());
        set(park, "config", config);
        set(park, "wireFacade", new AsWireFacade());

        ClassicNiHttpPark.ParkRecord request =
                park.park("h1", "js-req", "corr-req", AsHttpWireFormat.XML, 0, false);
        request.setRequestUi(true);
        park.scheduleAdaptiveGate(request);
        // UI 120s clamped to the 60s dialog timeout — never outlives the MAP leg.
        assertThat(request.appliedGateMs()).isEqualTo(60_000L);

        ClassicNiHttpPark.ParkRecord notify =
                park.park("h2", "js-ntf", "corr-ntf", AsHttpWireFormat.XML, 0, false);
        park.scheduleAdaptiveGate(notify);
        assertThat(notify.appliedGateMs()).isEqualTo(25_000L);
    }

    @Test
    void expiredRequestParkAbortsMapLegAndSettlesDigitClaim() throws Exception {
        String corr = "corr-expire-1";
        VirtualSession s = new VirtualSession("vs", corr, corr, "251911000001", 0,
                corr, "");
        s.setOriginationType(OriginationType.MAP);
        s.setState(VirtualSessionState.ACTIVE);
        s.setDialogAlive(true);
        store.put(s);

        ClassicNiHttpPark park = new ClassicNiHttpPark();
        set(park, "adaptive", new AdaptiveTimeout());
        set(park, "config", config);
        set(park, "wireFacade", new AsWireFacade());
        set(park, "store", store);
        CapturingHttp http = new CapturingHttp();
        CapturingSs7 ss7 = new CapturingSs7();
        park.bindHttp(() -> http);
        park.bindSs7(() -> ss7);

        ClassicNiHttpPark.ParkRecord rec =
                park.park("http-1", "js-exp", corr, AsHttpWireFormat.XML, 0, false);
        rec.setRequestUi(true);

        // UE digit in flight, then the park expires with the MAP leg still open.
        assertThat(store.tryClaimMsDigitContinue(corr, 501)).isEmpty();
        park.scheduleGate(rec, 5L);
        Thread.sleep(400L);

        assertThat(ss7.abortsFor(corr)).as("MAP abort on expiry").isEqualTo(1);
        assertThat(http.replies()).as("AS gated-abort reply").isEqualTo(1);
        VirtualSession after = store.get(corr).orElseThrow();
        assertThat(after.state()).isEqualTo(VirtualSessionState.COMPLETED);
        assertThat(after.dialogAlive()).isFalse();
        // Digit claim dropped with the session — the next digit is not IN_FLIGHT.
        assertThat(store.tryClaimMsDigitContinue(corr, 502)).isEmpty();
    }

    // ------------------------------------------------------------------ P1-2

    @Test
    void activeSessionSurvivesToSessionCap() {
        long now = System.currentTimeMillis();

        VirtualSession active = new VirtualSession("vs", "ttl-active", "r", "2519", 0,
                "dlg-active", "*1#");
        active.setCreatedAtMs(now - 300_000L); // 5 min ago — past the 120s profile TTL
        active.setState(VirtualSessionState.ACTIVE);
        store.put(active);

        VirtualSession awaiting = new VirtualSession("vs", "ttl-awaiting", "r", "2519", 0,
                "dlg-awaiting", "*1#");
        awaiting.setCreatedAtMs(now - 300_000L);
        awaiting.setState(VirtualSessionState.AWAITING_AS);
        store.put(awaiting);

        assertThat(store.reclaimExpired(now)).isEqualTo(1);
        assertThat(store.get("ttl-active"))
                .as("ACTIVE (open menu) lives to the 10 min session cap")
                .isPresent();
        assertThat(store.get("ttl-awaiting")).isEmpty();
    }

    @Test
    void digitOnMissingSessionEndsHard() throws Exception {
        VirtualSessionStore empty = new VirtualSessionStore() {
            @Override public void ensureTable() { }
            @Override public java.util.Optional<VirtualSession> get(String corr) {
                return java.util.Optional.empty();
            }
            @Override public java.util.Optional<VirtualSession> byDialogId(String dialogId) {
                return java.util.Optional.empty();
            }
        };
        SbbServices services = new SbbServices();
        set(services, "store", empty);
        set(services, "config", new UssdConfigService());
        set(services, "wireFacade", new AsWireFacade());
        set(services, "pendingMap2Map", new PendingMap2MapRegistry());
        set(services, "map2MapCompletion", new Map2MapCompletionService());
        MapUssdParentSbb sbb = new MapUssdParentSbb(services);
        CapturingSs7 ss7 = new CapturingSs7();
        set(sbb, "ss7", ss7);

        UnstructuredSSResponse resp = (UnstructuredSSResponse) Proxy.newProxyInstance(
                UnstructuredSSResponse.class.getClassLoader(),
                new Class<?>[] { UnstructuredSSResponse.class },
                (proxy, method, args) -> {
                    if ("getInvokeId".equals(method.getName())) return 5L;
                    if ("getMessageType".equals(method.getName())) {
                        return MAPMessageType.unstructuredSSRequest_Response;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == long.class || rt == Long.class) return 0L;
                    if (rt == int.class || rt == Integer.class) return 0;
                    return null;
                });
        sbb.onEvent(new Ss7MapEvent.Service(
                "ghost-dlg", MAPMessageType.unstructuredSSRequest_Response, resp), null);

        List<Ss7Command.MapProcessUnstructuredSsResponse> replies = ss7.cmds.stream()
                .filter(c -> c instanceof Ss7Command.MapProcessUnstructuredSsResponse)
                .map(c -> (Ss7Command.MapProcessUnstructuredSsResponse) c)
                .toList();
        assertThat(replies)
                .as("missing row must hard-end the MAP dialog, never stay silent")
                .hasSize(1);
        assertThat(replies.get(0).dialogId()).isEqualTo("ghost-dlg");
    }

    // ---------------------------------------------------------------- helpers

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

    private static final class CapturingHttp implements RaCommandPort {
        final List<OutboundCommand> commands = new CopyOnWriteArrayList<>();
        @Override public void sendCommand(OutboundCommand command) { commands.add(command); }

        int replies() {
            return (int) commands.stream()
                    .filter(c -> c instanceof HttpServerCommand.HttpResponseExCommand)
                    .count();
        }
    }

    private static final class CapturingSs7 implements RaCommandPort {
        final List<OutboundCommand> cmds = new CopyOnWriteArrayList<>();
        @Override public void sendCommand(OutboundCommand command) { cmds.add(command); }

        int abortsFor(String dialogId) {
            return (int) cmds.stream()
                    .filter(c -> c instanceof Ss7Command.MapDialogAbort a
                            && dialogId.equals(a.dialogId()))
                    .count();
        }
    }
}
