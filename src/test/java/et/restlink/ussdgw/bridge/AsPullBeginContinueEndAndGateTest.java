package et.restlink.ussdgw.bridge;

import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.cdr.CdrUssdSnippet;
import et.restlink.ussdgw.config.UssdConfigService;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.jss7.command.Ss7Command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour seam B: BEGIN arm → CONTINUE multimenu → END MAP commands, plus AdaptiveTimeout
 * gate fire / late NI. Assertions fail on wrong Ss7Command type, endDialog flag, or state.
 */
class AsPullBeginContinueEndAndGateTest {

    private MicroSleeContainer container;
    private VirtualSessionStore store;
    private CapturingPort ss7;
    private CountingNi ni;
    private VirtualSessionBridge bridge;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        store = new VirtualSessionStore();
        set(store, "container", container);
        set(store, "config", new UssdConfigService());
        set(store, "profileTtlMs", 120_000L);
        store.ensureTable();

        ss7 = new CapturingPort();
        ni = new CountingNi();
        bridge = newBridge(true, 7_000L);
        bridge.bindSs7(() -> ss7);
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    @DisplayName("BEGIN: startAwaitingAs arms GATE with config ceiling (not EWMA×1.5)")
    void beginArmsGateWithConfigCeilingNotEwma() {
        AdaptiveTimeout adaptive = new AdaptiveTimeout();
        adaptive.recordLatency(1, 1000); // would suggest 1500 if used for live gate
        assertThat(adaptive.suggestGateMs(1, 25_000)).isEqualTo(1500L);
        set(bridge, "adaptive", adaptive);
        UssdConfigService cfg = config(true, 25_000L);
        set(bridge, "config", cfg);

        VirtualSession s = awaitingSession("corr-begin", 1);
        bridge.startAwaitingAs(s, "hop");

        VirtualSession armed = store.get("corr-begin").orElseThrow();
        assertThat(armed.state()).isEqualTo(VirtualSessionState.AWAITING_AS);
        assertThat(armed.gateMs()).isEqualTo(25_000L);
        assertThat(armed.gateDeadlineMs() - armed.pullStartedAtMs()).isEqualTo(25_000L);
        assertThat(armed.gateMs()).isNotEqualTo(1500L);
    }

    @Test
    @DisplayName("CONTINUE: MapProcessUnstructuredSsResponse end=false; generation not bumped")
    void continueSendsNonEndMapResponseWithoutBumpingGeneration() {
        VirtualSession s = awaitingSession("corr-cont", 0);
        bridge.startAwaitingAs(s);
        assertThat(store.get("corr-cont").orElseThrow().generation()).isEqualTo(1);

        bridge.onAsResponse(new AsResponse("corr-cont", "r1", 1,
                "1. Balance\n2. Topup\n0. Exit", AsAction.CONTINUE, false), 40);

        VirtualSession after = store.get("corr-cont").orElseThrow();
        assertThat(after.state()).isEqualTo(VirtualSessionState.ACTIVE);
        assertThat(after.generation()).isEqualTo(1);
        assertThat(ss7.cmds).hasSize(1);
        assertThat(ss7.cmds.get(0)).isInstanceOf(Ss7Command.MapProcessUnstructuredSsResponse.class);
        var cmd = (Ss7Command.MapProcessUnstructuredSsResponse) ss7.cmds.get(0);
        assertThat(cmd.endDialog()).isFalse();
        assertThat(cmd.text()).isEqualTo("1. Balance\n2. Topup\n0. Exit");
    }

    @Test
    @DisplayName("Multimenu: MS digit bumps generation; second CONTINUE stays interactive")
    void multimenuDigitBumpThenSecondContinue() {
        VirtualSession s = awaitingSession("corr-mm", 0);
        bridge.startAwaitingAs(s);
        bridge.onAsResponse(new AsResponse("corr-mm", "r1", 1, "Menu 1", AsAction.CONTINUE, false), 20);
        assertThat(store.get("corr-mm").orElseThrow().state()).isEqualTo(VirtualSessionState.ACTIVE);

        // MapUssdParentSbb.onUserContinue: bump generation then re-arm AS wait
        VirtualSession live = store.get("corr-mm").orElseThrow();
        live.nextGeneration();
        assertThat(live.generation()).isEqualTo(2);
        bridge.startAwaitingAs(live);
        assertThat(store.get("corr-mm").orElseThrow().state())
                .isEqualTo(VirtualSessionState.AWAITING_AS);

        ss7.cmds.clear();
        bridge.onAsResponse(new AsResponse("corr-mm", "r1", 2,
                "Balance menu\n1. Main\n0. Back", AsAction.CONTINUE, false), 30);

        VirtualSession after = store.get("corr-mm").orElseThrow();
        assertThat(after.state()).isEqualTo(VirtualSessionState.ACTIVE);
        assertThat(after.generation()).isEqualTo(2);
        assertThat(ss7.cmds).hasSize(1);
        var cmd = (Ss7Command.MapProcessUnstructuredSsResponse) ss7.cmds.get(0);
        assertThat(cmd.endDialog()).isFalse();
        assertThat(cmd.text()).contains("Balance menu");
    }

    @Test
    @DisplayName("N-step: three CONTINUEs then END; endDialog false mid-path; gen bumps only on digit")
    void multimenuThreeContinuesThenEnd() {
        RecordingCdr cdr = new RecordingCdr();
        set(bridge, "cdr", cdr);
        VirtualSession s = awaitingSession("corr-3c", 0);
        bridge.startAwaitingAs(s);
        int gen = store.get("corr-3c").orElseThrow().generation();

        String[] menus = {"abc", "2-dce", "more-menu"};
        for (int i = 0; i < menus.length; i++) {
            ss7.cmds.clear();
            bridge.onAsResponse(new AsResponse("corr-3c", "r1", gen, menus[i], AsAction.CONTINUE, false), 10 + i);
            VirtualSession after = store.get("corr-3c").orElseThrow();
            assertThat(after.state()).isEqualTo(VirtualSessionState.ACTIVE);
            assertThat(after.generation()).isEqualTo(gen); // AS CONTINUE must not bump
            var cmd = (Ss7Command.MapProcessUnstructuredSsResponse) ss7.cmds.get(0);
            assertThat(cmd.endDialog()).isFalse();
            assertThat(cmd.text()).isEqualTo(menus[i]);

            if (i < menus.length - 1) {
                // MS digit bump only
                after.nextGeneration();
                gen = after.generation();
                bridge.startAwaitingAs(after);
            }
        }

        // Final digit → END
        VirtualSession live = store.get("corr-3c").orElseThrow();
        live.nextGeneration();
        gen = live.generation();
        bridge.startAwaitingAs(live);
        ss7.cmds.clear();
        bridge.onAsResponse(new AsResponse("corr-3c", "r1", gen, "(xyz)", AsAction.END, false), 40);

        assertThat(store.get("corr-3c")).isEmpty();
        var endCmd = (Ss7Command.MapProcessUnstructuredSsResponse) ss7.cmds.get(0);
        assertThat(endCmd.endDialog()).isTrue();
        assertThat(endCmd.text()).isEqualTo("(xyz)");
        assertThat(cdr.statuses.stream().filter("CONTINUE"::equals).count()).isEqualTo(3);
        assertThat(cdr.statuses).contains("END");
    }

    @Test
    @DisplayName("CONTINUE+END CDR: asUssd snippet on every menu step and final")
    void continueAndEndCdrAsUssdSnippet() {
        RecordingCdr cdr = new RecordingCdr();
        set(bridge, "cdr", cdr);
        VirtualSession s = awaitingSession("corr-cdr-n", 0);
        bridge.startAwaitingAs(s);

        String m1 = "abc";
        String m2 = "2-dce — padded so snippet truncation path is exercised pad pad pad pad pad";
        String end = "(xyz) Thank you Digicom VAS final text pad pad";
        bridge.onAsResponse(new AsResponse("corr-cdr-n", "r1", 1, m1, AsAction.CONTINUE, false), 10);
        VirtualSession live = store.get("corr-cdr-n").orElseThrow();
        live.nextGeneration();
        bridge.startAwaitingAs(live);
        bridge.onAsResponse(new AsResponse("corr-cdr-n", "r1", 2, m2, AsAction.CONTINUE, false), 20);
        live = store.get("corr-cdr-n").orElseThrow();
        live.nextGeneration();
        bridge.startAwaitingAs(live);
        bridge.onAsResponse(new AsResponse("corr-cdr-n", "r1", 3, end, AsAction.END, false), 30);

        assertThat(cdr.statuses.stream().filter("CONTINUE"::equals).count()).isEqualTo(2);
        for (int i = 0; i < cdr.statuses.size(); i++) {
            String st = cdr.statuses.get(i);
            if (!"CONTINUE".equals(st) && !"END".equals(st)) {
                continue;
            }
            String d = cdr.details.get(i);
            assertThat(d)
                    .contains("asUssd=")
                    .contains("asLen=")
                    .contains("note=AS→UE")
                    .contains("asAction=" + st);
        }
        assertThat(cdr.details.get(cdr.statuses.indexOf("CONTINUE"))).contains(CdrUssdSnippet.of(m1));
        assertThat(cdr.details.get(cdr.statuses.lastIndexOf("CONTINUE")))
                .contains(CdrUssdSnippet.of(m2));
        assertThat(cdr.details.get(cdr.statuses.lastIndexOf("END"))).contains(CdrUssdSnippet.of(end));
    }

    @Test
    @DisplayName("Empty AS body END path: empty text END still records asUssd-empty")
    void emptyAsBodyEndsSession() {
        RecordingCdr cdr = new RecordingCdr();
        set(bridge, "cdr", cdr);
        VirtualSession s = awaitingSession("corr-empty", 0);
        bridge.startAwaitingAs(s);

        // Wire decode of empty dialog → END with empty text (HttpClient maps AS_EMPTY_BODY separately;
        // bridge apply of empty END must still terminate MAP dialog).
        bridge.onAsResponse(new AsResponse("corr-empty", "r1", 1, "", AsAction.END, false), 5);

        assertThat(store.get("corr-empty")).isEmpty();
        assertThat(ss7.cmds).hasSize(1);
        var cmd = (Ss7Command.MapProcessUnstructuredSsResponse) ss7.cmds.get(0);
        assertThat(cmd.endDialog()).isTrue();
        assertThat(cdr.statuses).contains("END");
        assertThat(cdr.details.get(cdr.statuses.lastIndexOf("END")))
                .contains("asUssd-empty")
                .contains("note=AS→UE");
    }

    @Test
    @DisplayName("Notify decoded as CONTINUE must not be applied as menu — wire seam only here")
    void notifyMustNotDecodeAsContinue_wireGuard() {
        // Bridge trusts AsAction; wire codec must refuse Notify→CONTINUE (A seam).
        // Keep B regression: CONTINUE with menu text still endDialog=false.
        VirtualSession s = awaitingSession("corr-notify-guard", 0);
        bridge.startAwaitingAs(s);
        bridge.onAsResponse(new AsResponse("corr-notify-guard", "r1", 1, "menu", AsAction.CONTINUE, false), 10);
        var cmd = (Ss7Command.MapProcessUnstructuredSsResponse) ss7.cmds.get(0);
        assertThat(cmd.endDialog()).isFalse();
        assertThat(store.get("corr-notify-guard").orElseThrow().generation()).isEqualTo(1);
    }

    @Test
    @DisplayName("END: MapProcessUnstructuredSsResponse end=true; profile removed")
    void endSendsFinalMapResponseAndRemovesProfile() {
        RecordingCdr cdr = new RecordingCdr();
        set(bridge, "cdr", cdr);
        VirtualSession s = awaitingSession("corr-end", 0);
        bridge.startAwaitingAs(s);

        String asText = "Thank you. Your request was accepted by Digicom VAS.";
        bridge.onAsResponse(new AsResponse("corr-end", "r1", 1, asText, AsAction.END, false), 50);

        assertThat(store.get("corr-end")).isEmpty();
        assertThat(ss7.cmds).hasSize(1);
        assertThat(ss7.cmds.get(0)).isInstanceOf(Ss7Command.MapProcessUnstructuredSsResponse.class);
        var cmd = (Ss7Command.MapProcessUnstructuredSsResponse) ss7.cmds.get(0);
        assertThat(cmd.endDialog()).isTrue();
        assertThat(cmd.text()).isEqualTo(asText);

        // END = AS→UE applied (not hop-close); detail carries ~50-char asUssd snippet.
        assertThat(cdr.statuses).contains("END");
        int endIdx = cdr.statuses.lastIndexOf("END");
        assertThat(cdr.phases.get(endIdx)).isEqualTo(CdrPhase.COMPLETED);
        String endDetail = cdr.details.get(endIdx);
        assertThat(endDetail)
                .contains("asAction=END")
                .contains("note=AS→UE")
                .contains("asUssd=")
                .contains("asLen=" + asText.length());
        assertThat(endDetail).contains(CdrUssdSnippet.of(asText));
    }

    @Test
    @DisplayName("CONTINUE: CDR carries AS menu snippet (AS→UE, not hop-close)")
    void continueCdrIncludesAsUssdSnippet() {
        RecordingCdr cdr = new RecordingCdr();
        set(bridge, "cdr", cdr);
        VirtualSession s = awaitingSession("corr-cont-cdr", 0);
        bridge.startAwaitingAs(s);

        String menu = "1. Balance\n2. Topup\n0. Exit — pad pad pad pad pad pad pad pad pad pad";
        bridge.onAsResponse(new AsResponse("corr-cont-cdr", "r1", 1, menu, AsAction.CONTINUE, false), 40);

        assertThat(cdr.statuses).contains("CONTINUE");
        String d = cdr.details.get(cdr.statuses.lastIndexOf("CONTINUE"));
        assertThat(d)
                .contains("asAction=CONTINUE")
                .contains("note=AS→UE")
                .contains("asUssd=")
                .contains("asLen=");
        assertThat(d).doesNotContain("\n");
    }

    @Test
    @DisplayName("Full BEGIN→CONTINUE→digit→END sequence keeps corr and MAP end flags correct")
    void fullBeginContinueDigitEndSequence() {
        VirtualSession s = awaitingSession("corr-flow", 0);
        bridge.startAwaitingAs(s);
        assertThat(store.get("corr-flow").orElseThrow().state())
                .isEqualTo(VirtualSessionState.AWAITING_AS);

        bridge.onAsResponse(new AsResponse("corr-flow", "r1", 1, "Menu A", AsAction.CONTINUE, false), 10);
        VirtualSession afterMenu = store.get("corr-flow").orElseThrow();
        assertThat(afterMenu.state()).isEqualTo(VirtualSessionState.ACTIVE);
        assertThat(((Ss7Command.MapProcessUnstructuredSsResponse) ss7.cmds.get(0)).endDialog()).isFalse();

        afterMenu.nextGeneration();
        bridge.startAwaitingAs(afterMenu);
        ss7.cmds.clear();
        bridge.onAsResponse(new AsResponse("corr-flow", "r1", 2, "Thank you.", AsAction.END, false), 15);

        assertThat(store.get("corr-flow")).isEmpty();
        assertThat(((Ss7Command.MapProcessUnstructuredSsResponse) ss7.cmds.get(0)).endDialog()).isTrue();
    }

    @Test
    @DisplayName("Gate fire: wait text + S1_RELEASED + endDialog; late AS NI once")
    void gateFireThenLateAsPushesNiOnce() {
        VirtualSession s = awaitingSession("corr-gate", 0);
        bridge.startAwaitingAs(s);
        s = store.get("corr-gate").orElseThrow();
        s.setGateDeadlineMs(System.currentTimeMillis() - 1);
        store.put(s);

        assertThat(bridge.onGateExpired(s)).isTrue();
        VirtualSession gated = store.get("corr-gate").orElseThrow();
        assertThat(gated.state()).isEqualTo(VirtualSessionState.S1_RELEASED);
        assertThat(gated.dialogAlive()).isFalse();
        assertThat(ss7.cmds).hasSize(1);
        var wait = (Ss7Command.MapProcessUnstructuredSsResponse) ss7.cmds.get(0);
        assertThat(wait.text()).isEqualTo("Please wait...");
        assertThat(wait.endDialog()).isTrue();

        bridge.onAsResponse(new AsResponse("corr-gate", "r1", 1, "Late menu", AsAction.CONTINUE, false), 80);
        assertThat(ni.pushes).isEqualTo(1);
        assertThat(store.get("corr-gate").orElseThrow().state())
                .isEqualTo(VirtualSessionState.PUSH_PENDING);

        bridge.onAsResponse(new AsResponse("corr-gate", "r1", 1, "Again", AsAction.CONTINUE, false), 10);
        assertThat(ni.pushes).isEqualTo(1);
    }

    @Test
    @DisplayName("Hard-fail when bridge disarmed: unavailable, no late NI")
    void hardFailWhenBridgeDisarmedNoLateNi() {
        bridge = newBridge(false, 7_000L);
        bridge.bindSs7(() -> ss7);
        ni = new CountingNi();
        set(bridge, "accessNi", ni);

        VirtualSession s = awaitingSession("corr-hard", 0);
        s.setAdaptiveBridgeArm(false);
        store.put(s);
        bridge.startAwaitingAs(s);

        assertThat(bridge.onGateExpired(store.get("corr-hard").orElseThrow())).isTrue();
        assertThat(store.get("corr-hard")).isEmpty();
        var cmd = (Ss7Command.MapProcessUnstructuredSsResponse) ss7.cmds.get(0);
        assertThat(cmd.text()).isEqualTo("unavailable");
        assertThat(cmd.endDialog()).isTrue();

        bridge.onAsResponse(new AsResponse("corr-hard", "r1", 1, "Too late", AsAction.END, false), 20);
        assertThat(ni.pushes).isZero();
    }

    private VirtualSession awaitingSession(String corr, int networkId) {
        VirtualSession s = new VirtualSession("vs-" + corr, corr, "req-" + corr,
                "251911000001", networkId, "dlg-" + corr, "*100#");
        s.setInvokeId(7L);
        s.setDialogAlive(true);
        s.setOriginationType(OriginationType.MAP);
        s.setAdaptiveBridgeArm(true);
        s.setState(VirtualSessionState.AWAITING_AS);
        store.put(s);
        return store.get(corr).orElseThrow();
    }

    private VirtualSessionBridge newBridge(boolean bridgeEnabled, long asyncGateMs) {
        VirtualSessionBridge b = new VirtualSessionBridge();
        set(b, "store", store);
        set(b, "adaptive", new AdaptiveTimeout());
        set(b, "config", config(bridgeEnabled, asyncGateMs));
        set(b, "cdr", new RecordingCdr());
        set(b, "accessNi", ni);
        set(b, "niHttpPark", new et.restlink.ussdgw.api.classic.ClassicNiHttpPark());
        return b;
    }

    /** Captures bridge CDR writes (11-arg path used by VirtualSessionBridge.cdrWrite). */
    static final class RecordingCdr extends CdrService {
        final List<String> statuses = new ArrayList<>();
        final List<String> details = new ArrayList<>();
        final List<CdrPhase> phases = new ArrayList<>();

        @Override
        public void write(String correlationId, CdrPhase phase, String msisdn, String shortCode,
                          String status, String detail, int networkId, String tenantId,
                          String originationType, Long gateMs, Long observedEwmaMs) {
            phases.add(phase);
            statuses.add(status);
            details.add(detail);
        }
    }

    private static UssdConfigService config(boolean bridgeEnabled, long asyncGateMs) {
        UssdConfigService cfg = new UssdConfigService();
        set(cfg, "bridgeEnabledProp", bridgeEnabled);
        set(cfg, "asyncGateTimeoutMsProp", asyncGateMs);
        set(cfg, "dialogTimeoutMsProp", 60_000L);
        set(cfg, "asyncWaitMessageProp", "Please wait...");
        set(cfg, "asyncHardFailMessageProp", "unavailable");
        return cfg;
    }

    static final class CountingNi extends et.restlink.ussdgw.access.AccessNiDispatcher {
        volatile int pushes;

        @Override
        public void requestNiPush(VirtualSession session, String text) {
            pushes++;
        }
    }

    static final class CapturingPort implements RaCommandPort {
        final List<OutboundCommand> cmds = new ArrayList<>();

        @Override
        public void sendCommand(OutboundCommand command) {
            cmds.add(command);
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
}
