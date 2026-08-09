package et.restlink.ussdgw.bridge;

import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.jss7.command.Ss7Command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeGateBehaviourTest {
    private MicroSleeContainer container;
    private VirtualSessionStore store;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        store = new VirtualSessionStore();
        set(store, "container", container);
        set(store, "config", new UssdConfigService());
        set(store, "profileTtlMs", 120_000L);
        store.ensureTable();
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void continueDoesNotBumpGeneration_onlyMsInputDoes() {
        VirtualSession s = new VirtualSession("vs", "c-gen", "r1", "2519", 0, "dlg-g", "*123#");
        s.setInvokeId(1);
        s.setDialogAlive(true);
        s.setState(VirtualSessionState.AWAITING_AS);
        assertThat(s.generation()).isEqualTo(1);
        store.put(s);

        CapturingPort port = new CapturingPort();
        VirtualSessionBridge bridge = newBridge(store, true);
        bridge.bindSs7(() -> port);

        bridge.onAsResponse(new AsResponse("c-gen", "r1", 1, "Menu?", AsAction.CONTINUE, false), 10);
        VirtualSession after = store.get("c-gen").orElseThrow();
        assertThat(after.state()).isEqualTo(VirtualSessionState.ACTIVE);
        assertThat(after.generation()).isEqualTo(1); // AS CONTINUE must not bump
    }

    @Test
    void asyncAckLeavesSessionAwaitingRealCallback() {
        VirtualSession s = new VirtualSession("vs", "c-async", "r1", "2519", 0, "dlg-a", "*123#");
        s.setInvokeId(1);
        s.setDialogAlive(true);
        s.setState(VirtualSessionState.AWAITING_AS);
        store.put(s);

        VirtualSessionBridge bridge = newBridge(store, true);
        bridge.bindSs7(() -> new CapturingPort());
        bridge.onAsResponse(new AsResponse("c-async", "r1", 1, "", AsAction.END, true), 5);

        VirtualSession still = store.get("c-async").orElseThrow();
        assertThat(still.state()).isEqualTo(VirtualSessionState.AWAITING_AS);
        assertThat(still.dialogAlive()).isTrue();
    }

    @Test
    void asyncAckDoesNotPoisonEwma_callbackRecordsFromPullStart() {
        AdaptiveTimeout adaptive = new AdaptiveTimeout();
        VirtualSessionBridge bridge = newBridge(store, true);
        set(bridge, "adaptive", adaptive);
        bridge.bindSs7(() -> new CapturingPort());

        VirtualSession s = new VirtualSession("vs", "c-ewma", "r1", "2519", 42, "dlg-e", "*123#");
        s.setInvokeId(1);
        s.setDialogAlive(true);
        s.setState(VirtualSessionState.AWAITING_AS);
        s.setPullStartedAtMs(System.currentTimeMillis() - 2500);
        store.put(s);

        // Fast ASYNC_ACK must not shrink the gate model
        bridge.onAsResponse(new AsResponse("c-ewma", "r1", 1, "", AsAction.END, true), 8);
        assertThat(adaptive.observedLatencyMs(42)).isEqualTo(0d);
        assertThat(adaptive.suggestGateMs(42, 7000)).isEqualTo(7000);

        // Content callback with latencyMs=-1 derives sample from pullStartedAt
        bridge.onAsResponse(new AsResponse("c-ewma", "r1", 1, "Hello", AsAction.END, false), -1);
        assertThat(adaptive.observedLatencyMs(42)).isGreaterThanOrEqualTo(2000d);
        assertThat(store.get("c-ewma")).isEmpty(); // COMPLETED removed
    }

    @Test
    void syncContentRecordsProvidedLatency() {
        AdaptiveTimeout adaptive = new AdaptiveTimeout();
        VirtualSessionBridge bridge = newBridge(store, true);
        set(bridge, "adaptive", adaptive);
        bridge.bindSs7(() -> new CapturingPort());

        VirtualSession s = new VirtualSession("vs", "c-sync", "r1", "2519", 7, "dlg-s", "*123#");
        s.setInvokeId(1);
        s.setDialogAlive(true);
        s.setState(VirtualSessionState.AWAITING_AS);
        store.put(s);

        bridge.onAsResponse(new AsResponse("c-sync", "r1", 1, "Bye", AsAction.END, false), 2000);
        assertThat(adaptive.observedLatencyMs(7)).isEqualTo(2000d);
        assertThat(adaptive.suggestGateMs(7, 7000)).isEqualTo(3000L);
    }

    @Test
    void syncAsResponseEndsDialogViaCommand() {
        VirtualSession s = new VirtualSession("vs", "c1", "r1", "2519", 0, "dlg-9", "*123#");
        s.setInvokeId(3);
        s.setDialogAlive(true);
        s.setState(VirtualSessionState.AWAITING_AS);
        store.put(s);

        CapturingPort port = new CapturingPort();
        VirtualSessionBridge bridge = newBridge(store, true);
        bridge.bindSs7(() -> port);

        bridge.onAsResponse(new AsResponse("c1", "r1", 1, "OK", AsAction.END, false), 50);
        assertThat(port.cmds).isNotEmpty();
        assertThat(port.cmds.get(0)).isInstanceOf(Ss7Command.MapProcessUnstructuredSsResponse.class);
        assertThat(store.get("c1")).isEmpty(); // terminal → removed from Profile
    }

    @Test
    void gateExpiryReleasesMapWithWaitMessage() {
        VirtualSession s = new VirtualSession("vs", "c2", "r2", "2519", 0, "dlg-2", "*123#");
        s.setInvokeId(1);
        s.setDialogAlive(true);
        s.setState(VirtualSessionState.AWAITING_AS);
        store.put(s);

        CapturingPort port = new CapturingPort();
        VirtualSessionBridge bridge = newBridge(store, true);
        bridge.bindSs7(() -> port);
        bridge.onGateExpired(s);

        assertThat(store.get("c2").orElseThrow().state()).isEqualTo(VirtualSessionState.S1_RELEASED);
        assertThat(port.cmds.get(0)).isInstanceOf(Ss7Command.MapProcessUnstructuredSsResponse.class);
        var cmd = (Ss7Command.MapProcessUnstructuredSsResponse) port.cmds.get(0);
        assertThat(cmd.text()).isEqualTo("Please wait...");
        assertThat(cmd.endDialog()).isTrue();
    }

    @Test
    void gateExpiryHardFailsWhenLegBridgeDisarmed() {
        VirtualSession s = new VirtualSession("vs", "c3", "r3", "2519", 0, "dlg-3", "*123#");
        s.setInvokeId(1);
        s.setDialogAlive(true);
        s.setState(VirtualSessionState.AWAITING_AS);
        s.setAdaptiveBridgeArm(false);
        store.put(s);

        CapturingPort port = new CapturingPort();
        VirtualSessionBridge bridge = newBridge(store, true);
        bridge.bindSs7(() -> port);
        bridge.onGateExpired(s);

        assertThat(store.get("c3")).isEmpty(); // COMPLETED removed
        assertThat(port.cmds.get(0)).isInstanceOf(Ss7Command.MapProcessUnstructuredSsResponse.class);
        var cmd = (Ss7Command.MapProcessUnstructuredSsResponse) port.cmds.get(0);
        assertThat(cmd.text()).isEqualTo("unavailable");
    }

    @Test
    void lateAsAfterGateExpiryPushesNiOnce() {
        CountingNi ni = new CountingNi();
        CapturingPort port = new CapturingPort();
        VirtualSessionBridge bridge = newBridge(store, true, ni);
        bridge.bindSs7(() -> port);

        VirtualSession s = new VirtualSession("vs", "c-late", "r1", "251911000001", 0, "dlg-late", "*123#");
        s.setInvokeId(2);
        s.setDialogAlive(true);
        s.setState(VirtualSessionState.AWAITING_AS);
        s.setGateDeadlineMs(System.currentTimeMillis() - 1);
        store.put(s);

        assertThat(bridge.onGateExpired(s)).isTrue();
        assertThat(store.get("c-late").orElseThrow().state()).isEqualTo(VirtualSessionState.S1_RELEASED);
        assertThat(port.cmds).hasSize(1);

        bridge.onAsResponse(new AsResponse("c-late", "r1", 1, "Late menu", AsAction.CONTINUE, false), 80);
        assertThat(ni.pushes).isEqualTo(1);
        assertThat(store.get("c-late").orElseThrow().state()).isEqualTo(VirtualSessionState.PUSH_PENDING);
        // Second late AS must not double-push (generation claim / wrong state).
        bridge.onAsResponse(new AsResponse("c-late", "r1", 1, "Again", AsAction.CONTINUE, false), 10);
        assertThat(ni.pushes).isEqualTo(1);
    }

    @Test
    void lateAsAfterHardFailCompletedDoesNotPushNi() {
        CountingNi ni = new CountingNi();
        CapturingPort port = new CapturingPort();
        VirtualSessionBridge bridge = newBridge(store, false, ni); // bridgeEnabled=false → hard fail
        bridge.bindSs7(() -> port);

        VirtualSession s = new VirtualSession("vs", "c-hard", "r1", "2519", 0, "dlg-hard", "*123#");
        s.setInvokeId(1);
        s.setDialogAlive(true);
        s.setState(VirtualSessionState.AWAITING_AS);
        store.put(s);

        assertThat(bridge.onGateExpired(s)).isTrue();
        assertThat(store.get("c-hard")).isEmpty();

        bridge.onAsResponse(new AsResponse("c-hard", "r1", 1, "Too late", AsAction.END, false), 20);
        assertThat(ni.pushes).isZero();
        assertThat(port.cmds).hasSize(1); // only the hard-fail MAP end
    }

    @Test
    void niFailMarksFailedAndRemovesProfile() {
        VirtualSession s = new VirtualSession("vs", "c-ni", "r1", "2519", 0, "dlg-ni", "*123#");
        s.setInvokeId(1);
        s.setDialogAlive(true);
        s.setState(VirtualSessionState.PUSH_PENDING);
        store.put(s);

        CapturingPort port = new CapturingPort();
        UssdSagaCoordinator saga = new UssdSagaCoordinator();
        set(saga, "store", store);
        set(saga, "bridge", newBridge(store, true));
        set(saga, "cdr", new CdrService() {
            @Override
            public void write(String correlationId, et.restlink.ussdgw.cdr.CdrPhase phase,
                              String msisdn, String shortCode, String status, String detail,
                              int networkId, String tenantId, String originationType) { }
        });
        UssdConfigService cfg = new UssdConfigService();
        set(cfg, "asyncWaitMessageProp", "Please wait...");
        set(saga, "config", cfg);
        saga.bindSs7(() -> port);

        saga.onNiFailed("c-ni", "NI_FAIL");
        assertThat(store.get("c-ni")).isEmpty();
        assertThat(port.cmds).isNotEmpty();
        assertThat(saga.niFailCount()).isEqualTo(1);
    }

    @Test
    void startAwaitingAsHopPhaseUsesConfiguredCeilingNotEwma() {
        AdaptiveTimeout adaptive = new AdaptiveTimeout();
        // Floored EWMA would formerly shrink live gate to ~1500ms (1000 * 1.5).
        adaptive.recordLatency(7, 1000);
        assertThat(adaptive.suggestGateMs(7, 25_000)).isEqualTo(1500L);

        VirtualSessionBridge bridge = newBridge(store, true);
        set(bridge, "adaptive", adaptive);
        UssdConfigService cfg = new UssdConfigService();
        set(cfg, "bridgeEnabledProp", true);
        set(cfg, "asyncGateTimeoutMsProp", 25_000L);
        set(cfg, "dialogTimeoutMsProp", 60_000L);
        set(cfg, "asyncWaitMessageProp", "Please wait...");
        set(cfg, "asyncHardFailMessageProp", "unavailable");
        set(bridge, "config", cfg);

        VirtualSession s = new VirtualSession("vs", "c-ceiling", "r1", "2519", 7, "dlg-c", "*804#");
        s.setDialogAlive(true);
        store.put(s);

        bridge.startAwaitingAs(s, "hop");
        VirtualSession armed = store.get("c-ceiling").orElseThrow();
        assertThat(armed.gateMs()).isEqualTo(25_000L);
        assertThat(armed.gateDeadlineMs() - armed.pullStartedAtMs()).isEqualTo(25_000L);
        assertThat(armed.state()).isEqualTo(VirtualSessionState.AWAITING_AS);
    }

    private static VirtualSessionBridge newBridge(VirtualSessionStore store, boolean bridgeEnabled) {
        return newBridge(store, bridgeEnabled, new CountingNi());
    }

    private static VirtualSessionBridge newBridge(VirtualSessionStore store, boolean bridgeEnabled,
                                                  CountingNi ni) {
        VirtualSessionBridge bridge = new VirtualSessionBridge();
        set(bridge, "store", store);
        set(bridge, "adaptive", new AdaptiveTimeout());
        UssdConfigService cfg = new UssdConfigService();
        set(cfg, "bridgeEnabledProp", bridgeEnabled);
        set(cfg, "asyncGateTimeoutMsProp", 7000L);
        set(cfg, "asyncWaitMessageProp", "Please wait...");
        set(cfg, "asyncHardFailMessageProp", "unavailable");
        set(cfg, "dialogTimeoutMsProp", 60_000L);
        set(bridge, "config", cfg);
        set(bridge, "cdr", new CdrService() {
            @Override
            public void write(String correlationId, et.restlink.ussdgw.cdr.CdrPhase phase,
                              String msisdn, String shortCode, String status, String detail,
                              int networkId, String tenantId, String originationType) { }
        });
        set(bridge, "accessNi", ni);
        set(bridge, "niHttpPark", new et.restlink.ussdgw.api.classic.ClassicNiHttpPark());
        return bridge;
    }

    static final class CountingNi extends et.restlink.ussdgw.access.AccessNiDispatcher {
        volatile int pushes;

        @Override
        public void requestNiPush(VirtualSession session, String text) {
            pushes++;
        }
    }

    private static void set(Object target, String field, Object value) {
        try {
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
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static final class CapturingPort implements RaCommandPort {
        final List<OutboundCommand> cmds = new ArrayList<>();
        @Override public void sendCommand(OutboundCommand command) { cmds.add(command); }
    }
}
