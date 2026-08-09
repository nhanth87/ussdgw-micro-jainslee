package et.restlink.ussdgw.bridge;

import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.service.Map2MapCompletionService;
import et.restlink.ussdgw.profile.UssdUserProfileStore;
import et.restlink.ussdgw.events.Map2MapRequestEvent;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.cdr.Map2MapCdr;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.service.AsPullRouter;
import et.restlink.ussdgw.telemetry.Map2MapTelemetry;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.core.MicroSleeContainer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MAP2MAP hop-outstanding MO hold + durable ussdUser profile persist.
 */
class Map2MapHopHoldAndUserProfileTest {
    private MicroSleeContainer container;
    private VirtualSessionStore store;
    private UssdUserProfileStore userProfiles;
    private VirtualSessionBridge bridge;
    private UssdSagaCoordinator saga;
    private Map2MapCompletionService completion;
    private CapturingPort ss7;
    private List<String> cdrStatuses;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        store = new VirtualSessionStore();
        set(store, "container", container);
        set(store, "config", new UssdConfigService());
        set(store, "profileTtlMs", 120_000L);
        store.ensureTable();

        userProfiles = new UssdUserProfileStore();
        set(userProfiles, "container", container);
        userProfiles.ensureTable();

        ss7 = new CapturingPort();
        cdrStatuses = new ArrayList<>();

        UssdConfigService config = new UssdConfigService();
        set(config, "bridgeEnabledProp", true);
        set(config, "asyncGateTimeoutMsProp", 7000L);
        set(config, "dialogTimeoutMsProp", 60_000L);
        set(config, "asyncWaitMessageProp", "Please wait...");
        set(config, "asyncHardFailMessageProp", "unavailable");

        AdaptiveTimeout adaptive = new AdaptiveTimeout();
        bridge = new VirtualSessionBridge();
        set(bridge, "store", store);
        set(bridge, "adaptive", adaptive);
        set(bridge, "config", config);
        set(bridge, "cdr", recordingCdr());
        bridge.bindSs7(() -> ss7);

        saga = new UssdSagaCoordinator();
        set(saga, "store", store);
        set(saga, "bridge", bridge);
        set(saga, "adaptive", adaptive);
        set(saga, "config", config);
        set(saga, "cdr", recordingCdr());
        saga.bindSs7(() -> ss7);

        completion = new Map2MapCompletionService();
        set(completion, "store", store);
        set(completion, "bridge", bridge);
        set(completion, "cdr", recordingCdr());
        set(completion, "map2MapTelemetry", new Map2MapTelemetry());
        set(completion, "userProfiles", userProfiles);
        set(completion, "adaptive", adaptive);
        set(completion, "asPullRouter", new AsPullRouter() {
            @Override
            public String route(ShortCodeRule rule, AsRequest asReq, String correlationId) {
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
    void hopOutstandingRoundTripsInUssdTx() {
        VirtualSession s = newSession("corr-hop", "251911230398");
        s.setMap2mapHopOutstanding(true);
        store.put(s);
        assertThat(store.get("corr-hop").orElseThrow().map2mapHopOutstanding()).isTrue();
        s.setMap2mapHopOutstanding(false);
        store.put(s);
        assertThat(store.get("corr-hop").orElseThrow().map2mapHopOutstanding()).isFalse();
    }

    @Test
    void sagaDefersHardFailWhileHopOutstanding() {
        VirtualSession s = newSession("corr-hold", "251911230398");
        s.setMap2mapHopOutstanding(true);
        s.setState(VirtualSessionState.AWAITING_AS);
        store.put(s);

        saga.onAsPullFailed("corr-hold", "AS_EMPTY_BODY");

        assertThat(store.get("corr-hold")).isPresent();
        assertThat(store.get("corr-hold").orElseThrow().dialogAlive()).isTrue();
        assertThat(ss7.cmds).isEmpty();
        assertThat(cdrStatuses).contains("MAP2MAP_MO_HOLD");
    }

    @Test
    void sagaHardFailsAfterHopCleared() {
        VirtualSession s = newSession("corr-clear", "251911230398");
        s.setMap2mapHopOutstanding(false);
        s.setState(VirtualSessionState.AWAITING_AS);
        store.put(s);

        saga.onAsPullFailed("corr-clear", "AS_EMPTY_BODY");

        assertThat(store.get("corr-clear")).isEmpty();
        assertThat(ss7.cmds).isNotEmpty();
        assertThat(cdrStatuses).contains("AS_EMPTY_BODY");
    }

    @Test
    void bridgeHardGateDeferredWhileHopOutstanding() {
        UssdConfigService config = new UssdConfigService();
        set(config, "bridgeEnabledProp", false);
        set(config, "asyncHardFailMessageProp", "unavailable");
        set(config, "dialogTimeoutMsProp", 60_000L);
        set(bridge, "config", config);

        VirtualSession s = newSession("corr-gate", "251911230398");
        s.setMap2mapHopOutstanding(true);
        s.setAdaptiveBridgeArm(true);
        s.setState(VirtualSessionState.AWAITING_AS);
        store.put(s);

        assertThat(bridge.onGateExpired(s)).isFalse();
        assertThat(store.get("corr-gate")).isPresent();
        assertThat(store.get("corr-gate").orElseThrow().dialogAlive()).isTrue();
        assertThat(ss7.cmds).isEmpty();
        assertThat(cdrStatuses).contains("MAP2MAP_MO_HOLD");
    }

    @Test
    void asEndDeferredWhileHopOutstanding() {
        VirtualSession s = newSession("corr-as", "251911230398");
        s.setMap2mapHopOutstanding(true);
        s.setState(VirtualSessionState.AWAITING_AS);
        store.put(s);

        bridge.onAsResponse(new AsResponse("corr-as", "r1", 1, "bye", AsAction.END, false), 50);

        assertThat(store.get("corr-as")).isPresent();
        assertThat(store.get("corr-as").orElseThrow().dialogAlive()).isTrue();
        assertThat(store.get("corr-as").orElseThrow().state())
                .isEqualTo(VirtualSessionState.AWAITING_AS);
        assertThat(ss7.cmds).isEmpty();
        assertThat(cdrStatuses).contains("MAP2MAP_MO_HOLD");
    }

    @Test
    void completionPersistsUserProfile() {
        VirtualSession s = newSession("corr-prof", "251911230398");
        s.setGateMs(3200L);
        s.setState(VirtualSessionState.AWAITING_AS);
        store.put(s);

        Map2MapRequestEvent req = new Map2MapRequestEvent(
                "corr-prof", "m2m-corr-prof", "dlg-in", 1L,
                "251911230398", "*804#", "*804#", "*875#",
                "http://as/pull", RuleType.HTTP, 0, "lab",
                "vs-prof", "req-prof", false, null, "251971200201", 6);

        String out = completion.onMap2MapResponse(req, "", Map2MapCdr.OUTCOME_REJECT);
        assertThat(out).startsWith("map2map-ok");

        var p = userProfiles.get("251911230398").orElseThrow();
        assertThat(p.getLastCorrId()).isEqualTo("corr-prof");
        assertThat(p.getLastShortCode()).isEqualTo("*804#");
        assertThat(p.getLastRedirectUssd()).isEqualTo("*875#");
        assertThat(p.getLastHopDestGt()).isEqualTo("251971200201");
        assertThat(p.getLastHopOutcome()).isEqualTo(Map2MapCdr.OUTCOME_REJECT);
        assertThat(p.getLastGateMs()).isEqualTo(3200L);
        assertThat(p.getMap2mapTxCount()).isEqualTo(1);
    }

    private VirtualSession newSession(String corr, String msisdn) {
        VirtualSession s = new VirtualSession("vs-" + corr, corr, "req-" + corr,
                msisdn, 0, "dlg-" + corr, "*804#");
        s.setInvokeId(1L);
        s.setDialogAlive(true);
        s.setOriginationType(OriginationType.MAP);
        s.setAdaptiveBridgeArm(true);
        return s;
    }

    private CdrService recordingCdr() {
        return new CdrService() {
            @Override
            public void write(String correlationId, CdrPhase phase, String msisdn,
                              String shortCode, String status, String detail,
                              int networkId, String tenantId, String originationType) {
                cdrStatuses.add(status);
            }

            @Override
            public void write(String correlationId, CdrPhase phase, String msisdn,
                              String shortCode, String status, String detail,
                              int networkId, String tenantId, String originationType,
                              Long gateMs, Long observedEwmaMs) {
                cdrStatuses.add(status);
            }
        };
    }

    private static final class CapturingPort implements RaCommandPort {
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
