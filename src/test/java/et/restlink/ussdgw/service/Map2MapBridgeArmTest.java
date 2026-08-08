package et.restlink.ussdgw.service;

import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionState;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.cdr.Map2MapCdr;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.events.Map2MapRequestEvent;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.telemetry.Map2MapTelemetry;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.jss7.command.Ss7Command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MAP2MAP bridge arm at hop ingress (not only after hop→AS): gate can fire during a slow
 * SRI/*8744# hop; hop completion after gate must not reset {@code S1_RELEASED}.
 */
class Map2MapBridgeArmTest {

    private MicroSleeContainer container;
    private VirtualSessionStore store;
    private VirtualSessionBridge bridge;
    private Map2MapCompletionService completion;
    private Map2MapTelemetry map2MapTelemetry;
    private RecordingCdr cdr;
    private AtomicReference<AsRequest> routed;
    private AtomicInteger startAwaitingCalls;
    private CapturingPort ss7;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        store = new VirtualSessionStore();
        set(store, "container", container);
        set(store, "config", new UssdConfigService());
        set(store, "profileTtlMs", 120_000L);
        store.ensureTable();

        startAwaitingCalls = new AtomicInteger();
        ss7 = new CapturingPort();

        UssdConfigService config = new UssdConfigService();
        set(config, "bridgeEnabledProp", true);
        set(config, "asyncGateTimeoutMsProp", 100L);
        set(config, "dialogTimeoutMsProp", 60_000L);
        set(config, "asyncWaitMessageProp", "Please wait...");
        set(config, "asyncHardFailMessageProp", "unavailable");

        AdaptiveTimeout adaptive = new AdaptiveTimeout();
        bridge = new VirtualSessionBridge() {
            @Override
            public void startAwaitingAs(VirtualSession session) {
                startAwaitingCalls.incrementAndGet();
                super.startAwaitingAs(session);
            }
        };
        set(bridge, "store", store);
        set(bridge, "adaptive", adaptive);
        set(bridge, "config", config);
        cdr = new RecordingCdr();
        set(bridge, "cdr", cdr);
        bridge.bindSs7(() -> ss7);

        routed = new AtomicReference<>();
        map2MapTelemetry = new Map2MapTelemetry();
        completion = new Map2MapCompletionService();
        set(completion, "store", store);
        set(completion, "bridge", bridge);
        set(completion, "cdr", cdr);
        set(completion, "map2MapTelemetry", map2MapTelemetry);
        set(completion, "asPullRouter", new AsPullRouter() {
            @Override
            public String route(ShortCodeRule rule, AsRequest asReq, String correlationId) {
                routed.set(asReq);
                return "routed-test";
            }
        });
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void ingressArmPutsSessionAwaitingAsWithGate() {
        VirtualSession session = ingressArmedSession("corr-arm", "dlg-arm");
        assertThat(session.adaptiveBridgeArm()).isTrue();
        assertThat(session.state()).isEqualTo(VirtualSessionState.AWAITING_AS);
        assertThat(session.gateDeadlineMs()).isGreaterThan(0L);
        assertThat(startAwaitingCalls.get()).isEqualTo(1);
        assertThat(store.get("corr-arm")).isPresent();
    }

    @Test
    void gateCanFireBeforeHopResponse() {
        VirtualSession session = ingressArmedSession("corr-gate", "dlg-gate");
        session.setGateDeadlineMs(System.currentTimeMillis() - 1);
        store.put(session);

        assertThat(bridge.onGateExpired(session)).isTrue();
        VirtualSession after = store.get("corr-gate").orElseThrow();
        assertThat(after.state()).isEqualTo(VirtualSessionState.S1_RELEASED);
        assertThat(after.dialogAlive()).isFalse();
        assertThat(ss7.cmds).hasSize(1);
        assertThat(ss7.cmds.get(0)).isInstanceOf(Ss7Command.MapProcessUnstructuredSsResponse.class);
        var cmd = (Ss7Command.MapProcessUnstructuredSsResponse) ss7.cmds.get(0);
        assertThat(cmd.text()).isEqualTo("Please wait...");
        assertThat(cmd.endDialog()).isTrue();
    }

    @Test
    void hopCompletionAfterGateKeepsS1ReleasedAndStillPullsAs() {
        VirtualSession session = ingressArmedSession("corr-late", "dlg-late");
        session.setGateDeadlineMs(System.currentTimeMillis() - 1);
        store.put(session);
        assertThat(bridge.onGateExpired(session)).isTrue();
        assertThat(store.get("corr-late").orElseThrow().state())
                .isEqualTo(VirtualSessionState.S1_RELEASED);

        int armedBefore = startAwaitingCalls.get();
        Map2MapRequestEvent req = sample("corr-late", PendingMap2MapRegistry.outboundCorr("corr-late"));
        String detail = completion.onMap2MapResponse(req, "Hop user info");

        assertThat(detail).startsWith("map2map-ok");
        assertThat(startAwaitingCalls.get()).isEqualTo(armedBefore); // no re-arm
        assertThat(store.get("corr-late").orElseThrow().state())
                .isEqualTo(VirtualSessionState.S1_RELEASED);
        assertThat(routed.get()).isNotNull();
        assertThat(routed.get().ussdString()).isEqualTo("Hop user info");
        assertThat(routed.get().originatedUssd()).isEqualTo("*804#");
        assertThat(map2MapTelemetry.completionAfterGateCount()).isEqualTo(1);
        assertThat(map2MapTelemetry.hopOkCount()).isEqualTo(1);
        assertThat(map2MapTelemetry.asRoutedCount()).isEqualTo(1);
        assertThat(cdr.statuses()).contains(Map2MapCdr.COMPLETE_AFTER_GATE);
        assertThat(cdr.lastDetail()).contains("phase=after-gate").contains("sc=*804#");
    }

    @Test
    void fastHopStillRearmsAwaitingAsAndPulls() {
        VirtualSession session = ingressArmedSession("corr-fast", "dlg-fast");
        long gateBefore = session.gateDeadlineMs();

        Map2MapRequestEvent req = sample("corr-fast", PendingMap2MapRegistry.outboundCorr("corr-fast"));
        String detail = completion.onMap2MapResponse(req, "Fast hop");

        assertThat(detail).startsWith("map2map-ok");
        assertThat(startAwaitingCalls.get()).isEqualTo(2); // ingress + AS re-arm
        VirtualSession after = store.get("corr-fast").orElseThrow();
        assertThat(after.state()).isEqualTo(VirtualSessionState.AWAITING_AS);
        assertThat(after.gateDeadlineMs()).isGreaterThanOrEqualTo(gateBefore);
        assertThat(routed.get().ussdString()).isEqualTo("Fast hop");
        assertThat(ss7.cmds).isEmpty(); // UE still waiting on AS, not gated
        assertThat(map2MapTelemetry.completionAfterGateCount()).isZero();
        assertThat(map2MapTelemetry.hopOkCount()).isEqualTo(1);
        assertThat(map2MapTelemetry.asRoutedCount()).isEqualTo(1);
        assertThat(cdr.statuses()).contains(Map2MapCdr.OK).doesNotContain(Map2MapCdr.COMPLETE_AFTER_GATE);
    }

    @Test
    void outboundCorrHelperStable() {
        assertThat(PendingMap2MapRegistry.outboundCorr("abc")).isEqualTo("m2m-abc");
        assertThat(PendingMap2MapRegistry.outboundCorr("m2m-abc")).isEqualTo("m2m-abc");
    }

    @Test
    void hopTtlAlignsWithDialogBudget() {
        PendingMap2MapRegistry pending = new PendingMap2MapRegistry();
        set(pending, "ttlMsProp", 30_000L);
        UssdConfigService config = new UssdConfigService();
        set(config, "dialogTimeoutMsProp", 60_000L);
        set(pending, "config", config);
        assertThat(pending.ttlMs()).isEqualTo(60_000L);
    }

    /** Same contract as MapUssdParentSbb map2map branch: arm before hop is queued. */
    private VirtualSession ingressArmedSession(String corr, String dialogId) {
        VirtualSession session = new VirtualSession(
                "vs-" + corr, corr, "req-" + corr, "251911000001", 0, dialogId, "*804#");
        session.setInvokeId(7L);
        session.setDialogAlive(true);
        session.setOriginationType(OriginationType.MAP);
        session.setAdaptiveBridgeArm(true);
        bridge.startAwaitingAs(session);
        return store.get(corr).orElseThrow();
    }

    private static Map2MapRequestEvent sample(String corr, String outbound) {
        return new Map2MapRequestEvent(
                corr, outbound, "dlg-" + corr, 7L, "251911000001", "*804#", "*804#",
                "*8744#", "http://as/userinfo", RuleType.HTTP, 0, null,
                "vs-" + corr, "req-" + corr);
    }

    private static final class RecordingCdr extends CdrService {
        private final List<String> statuses = new ArrayList<>();
        private volatile String lastDetail;

        @Override
        public void write(String correlationId, CdrPhase phase, String msisdn,
                          String shortCode, String status, String detail) {
            write(correlationId, phase, msisdn, shortCode, status, detail, 0, null, "MAP", null, null);
        }

        @Override
        public void write(String correlationId, CdrPhase phase, String msisdn,
                          String shortCode, String status, String detail,
                          int networkId, String tenantId, String originationType,
                          Long gateMs, Long observedEwmaMs) {
            if (status != null) {
                statuses.add(status);
            }
            lastDetail = detail;
        }

        List<String> statuses() { return statuses; }
        String lastDetail() { return lastDetail; }
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

    private static final class CapturingPort implements RaCommandPort {
        final List<OutboundCommand> cmds = new ArrayList<>();
        @Override public void sendCommand(OutboundCommand command) { cmds.add(command); }
    }
}
