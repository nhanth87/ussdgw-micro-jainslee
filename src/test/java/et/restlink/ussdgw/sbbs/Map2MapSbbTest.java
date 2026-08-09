package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.admin.LinkStatusService;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.cdr.Map2MapCdr;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.config.RuntimeConfigStore;
import et.restlink.ussdgw.events.Map2MapRequestEvent;
import et.restlink.ussdgw.hlr.HlrFaceService;
import et.restlink.ussdgw.hlr.HlrLocationCache;
import et.restlink.ussdgw.hlr.HlrResolvePolicy;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.service.AsPullRouter;
import et.restlink.ussdgw.service.Map2MapCompletionService;
import et.restlink.ussdgw.service.PendingMap2MapRegistry;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Map2MapSbb Case 2: upper-gt / fixed hop (no SRI); lab skip continues to AS. */
class Map2MapSbbTest {
    private MicroSleeContainer container;
    private SbbServices services;
    private CapturingSs7 ss7;
    private Map2MapSbb sbb;
    private PendingMap2MapRegistry pending;
    private RecordingStore store;
    private AtomicReference<AsRequest> routed;
    private UssdConfigService config;
    private RuntimeConfigStore runtimeKv;
    private HlrResolvePolicy hlrPolicy;
    private HlrFaceService hlrFace;
    private RecordingCdr cdr;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        ss7 = new CapturingSs7();
        store = new RecordingStore();
        pending = new PendingMap2MapRegistry();
        set(pending, "ttlMsProp", 30_000L);
        routed = new AtomicReference<>();

        Map2MapCompletionService completion = new Map2MapCompletionService();
        set(completion, "store", store);
        set(completion, "bridge", new VirtualSessionBridge() {
            @Override
            public void startAwaitingAs(VirtualSession s) {
            }
        });
        set(completion, "asPullRouter", new AsPullRouter() {
            @Override
            public String route(ShortCodeRule rule, AsRequest asReq, String correlationId) {
                routed.set(asReq);
                return "routed-test";
            }
        });
        cdr = new RecordingCdr();
        set(completion, "cdr", cdr);
        set(completion, "map2MapTelemetry", new et.restlink.ussdgw.telemetry.Map2MapTelemetry());

        runtimeKv = new RuntimeConfigStore();
        config = new UssdConfigService();
        set(config, "store", runtimeKv);
        set(config, "ussdGtProp", "251971200100");
        set(config, "hlrSsnProp", 6);
        set(config, "ussdSsnProp", 8);
        set(config, "liveNetworkIdProp", 0);
        set(config, "hlrUpperGtProp", java.util.Optional.of("251971200200"));
        set(config, "hlrModeProp", "PROXY_MAP");
        set(config, "hlrFakeImsiProp", java.util.Optional.of("636010000000001"));
        set(config, "hlrFakeMscGtProp", java.util.Optional.of("251911000099"));

        hlrPolicy = new HlrResolvePolicy();
        set(hlrPolicy, "config", config);

        hlrFace = new HlrFaceService();
        set(hlrFace, "policy", hlrPolicy);
        set(hlrFace, "locationCache", new HlrLocationCache());

        services = new SbbServices();
        set(services, "config", config);
        set(services, "store", store);
        set(services, "container", container);
        set(services, "pendingMap2Map", pending);
        set(services, "map2MapCompletion", completion);
        set(services, "hlrPolicy", hlrPolicy);
        set(services, "hlrFace", hlrFace);
        set(services, "cdr", cdr);
        set(services, "map2MapTelemetry", new et.restlink.ussdgw.telemetry.Map2MapTelemetry());
        set(services, "linkStatus", new LinkStatusService() {
            @Override
            public boolean ss7Live() {
                return false;
            }
        });
        setStatic(SbbServices.class, "INSTANCE", services);

        sbb = new Map2MapSbb(services);
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
    void labSkipContinuesToAsWithEmptyHop() {
        VirtualSession session = new VirtualSession("vs1", "corr1", "req1", "911230398", 0,
                "dlg1", "*804#");
        store.put(session);

        Map2MapRequestEvent req = new Map2MapRequestEvent(
                "corr1", "m2m-corr1", "dlg1", 7L, "911230398", "*804#", "*804#",
                "*8744#", "http://as/userinfo", RuleType.HTTP, 0, null, "vs1", "req1");

        sbb.onEvent(req, container.createActivityContext("t"));

        assertThat(ss7.cmds).isEmpty();
        assertThat(pending.size()).isZero();
        assertThat(routed.get()).isNotNull();
        assertThat(routed.get().ussdString()).isEqualTo(Map2MapCdr.AS_USSD_HLR_NONE);
        assertThat(routed.get().originatedUssd()).isEqualTo("*804#");
        assertThat(routed.get().codeKind()).isEqualTo("SHORT");
        assertThat(routed.get().msisdn()).isEqualTo("911230398");
        assertThat(routed.get().shortCode()).isEqualTo("*804#");
        assertThat(cdr.statuses()).contains(Map2MapCdr.SKIP_LAB, Map2MapCdr.AS_ROUTED);
        assertThat(cdr.details().stream().anyMatch(d -> d != null && d.contains("redirect=*8744#")))
                .isTrue();
    }

    @Test
    void liveSs7BlankHopUsesUpperGtNoSri() {
        set(services, "linkStatus", new LinkStatusService() {
            @Override
            public boolean ss7Live() {
                return true;
            }
        });
        VirtualSession session = new VirtualSession("vs2", "corr2", "req2", "911230398", 0,
                "dlg2", "*804#");
        store.put(session);

        Map2MapRequestEvent req = new Map2MapRequestEvent(
                "corr2", "m2m-corr2", "dlg2", 3L, "911230398", "*804#", "*804#",
                "*8744#", "http://as/userinfo", RuleType.HTTP, 0, null, "vs2", "req2");

        sbb.onEvent(req, container.createActivityContext("t2"));

        assertThat(pending.peek("m2m-corr2")).isPresent()
                .get().extracting(PendingMap2MapRegistry.Pending::phase)
                .isEqualTo(PendingMap2MapRegistry.Phase.AWAITING_USSD);
        assertThat(ss7.cmds).hasSize(1);
        assertThat(ss7.cmds.get(0)).isInstanceOf(Ss7Command.MapUnstructuredSsRequest.class);
        var ussd = (Ss7Command.MapUnstructuredSsRequest) ss7.cmds.get(0);
        assertThat(ussd.processUnstructured()).isTrue();
        assertThat(ussd.dialogId()).isEqualTo("m2m-corr2");
        assertThat(ussd.targetAddress().globalTitle()).isEqualTo("251971200200");
        assertThat(ussd.targetAddress().subSystemNumber()).isEqualTo(6);
        assertThat(ussd.msisdn()).isEqualTo("911230398");
        assertThat(ussd.text()).isEqualTo("*8744#");
        assertThat(routed.get()).isNull();
        assertThat(cdr.statuses()).contains(Map2MapCdr.HOP_START, Map2MapCdr.USSD_SENT);
        assertThat(cdr.details().stream().anyMatch(d -> d != null && d.contains("path=upper-gt"))).isTrue();
        assertThat(ss7.cmds.stream().noneMatch(c -> c instanceof Ss7Command.MapSendRoutingInfoForSm)).isTrue();
    }

    @Test
    void blankHopIgnoresFakeModeStillUsesUpperGt() throws Exception {
        set(services, "linkStatus", new LinkStatusService() {
            @Override
            public boolean ss7Live() {
                return true;
            }
        });
        injectKv(runtimeKv, RuntimeConfigStore.Keys.HLR_MODE, "FAKE");
        injectKv(runtimeKv, RuntimeConfigStore.Keys.HLR_FAKE_IMSI, "636019999000001");
        injectKv(runtimeKv, RuntimeConfigStore.Keys.HLR_FAKE_MSC_GT, "251977700042");

        VirtualSession session = new VirtualSession("vs3", "corr3", "req3", "911230398", 0,
                "dlg3", "*804#");
        store.put(session);

        Map2MapRequestEvent req = new Map2MapRequestEvent(
                "corr3", "m2m-corr3", "dlg3", 5L, "911230398", "*804#", "*804#",
                "*8744#", "http://as/userinfo", RuleType.HTTP, 0, null, "vs3", "req3");

        sbb.onEvent(req, container.createActivityContext("t3"));

        assertThat(ss7.cmds).hasSize(1);
        assertThat(ss7.cmds.get(0)).isInstanceOf(Ss7Command.MapUnstructuredSsRequest.class);
        var ussd = (Ss7Command.MapUnstructuredSsRequest) ss7.cmds.get(0);
        assertThat(ussd.processUnstructured()).isTrue();
        assertThat(ussd.targetAddress().globalTitle()).isEqualTo("251971200200");
        assertThat(ussd.msisdn()).isEqualTo("911230398");
        assertThat(ussd.text()).isEqualTo("*8744#");
        assertThat(pending.peek("m2m-corr3")).isPresent()
                .get().extracting(PendingMap2MapRegistry.Pending::phase)
                .isEqualTo(PendingMap2MapRegistry.Phase.AWAITING_USSD);
    }

    @Test
    void blankHopIgnoresProxyNeverSendsSri() throws Exception {
        set(services, "linkStatus", new LinkStatusService() {
            @Override
            public boolean ss7Live() {
                return true;
            }
        });
        injectKv(runtimeKv, RuntimeConfigStore.Keys.HLR_MODE, "PROXY_MAP");

        Map2MapRequestEvent req = new Map2MapRequestEvent(
                "corr4", "m2m-corr4", "dlg4", 1L, "911230398", "*804#", "*804#",
                "*8744#", "http://as/userinfo", RuleType.HTTP, 0, null, "vs4", "req4");

        sbb.onEvent(req, container.createActivityContext("t4"));

        assertThat(ss7.cmds).hasSize(1);
        assertThat(ss7.cmds.get(0)).isInstanceOf(Ss7Command.MapUnstructuredSsRequest.class);
        assertThat(ss7.cmds.stream().noneMatch(c -> c instanceof Ss7Command.MapSendRoutingInfoForSm)).isTrue();
    }

    @Test
    void perRuleFakeIgnoredOnCase2UsesUpperGt() throws Exception {
        set(services, "linkStatus", new LinkStatusService() {
            @Override
            public boolean ss7Live() {
                return true;
            }
        });
        injectKv(runtimeKv, RuntimeConfigStore.Keys.HLR_MODE, "PROXY_MAP");
        injectKv(runtimeKv, RuntimeConfigStore.Keys.HLR_FAKE_IMSI, "636019999000001");
        injectKv(runtimeKv, RuntimeConfigStore.Keys.HLR_FAKE_MSC_GT, "251977700042");

        VirtualSession session = new VirtualSession("vs5", "corr5", "req5", "911230398", 0,
                "dlg5", "*804#");
        store.put(session);

        Map2MapRequestEvent req = new Map2MapRequestEvent(
                "corr5", "m2m-corr5", "dlg5", 2L, "911230398", "*804#", "*804#",
                "*8744#", "http://as/userinfo", RuleType.HTTP, 0, null, "vs5", "req5",
                false, "FAKE");

        sbb.onEvent(req, container.createActivityContext("t5"));

        var ussd = (Ss7Command.MapUnstructuredSsRequest) ss7.cmds.get(0);
        assertThat(ussd.processUnstructured()).isTrue();
        assertThat(ussd.targetAddress().globalTitle()).isEqualTo("251971200200");
        assertThat(ussd.msisdn()).isEqualTo("911230398");
    }

    @Test
    void upperGtUnusableFailsClosedNoSri() throws Exception {
        set(services, "linkStatus", new LinkStatusService() {
            @Override
            public boolean ss7Live() {
                return true;
            }
        });
        // Self-loop: upper GT == local USSD GT
        set(config, "hlrUpperGtProp", java.util.Optional.of("251971200100"));

        Map2MapRequestEvent req = new Map2MapRequestEvent(
                "corr-fail", "m2m-corr-fail", "dlg-fail", 1L, "911230398", "*804#", "*804#",
                "*8744#", "http://as/userinfo", RuleType.HTTP, 0, null, "vs-fail", "req-fail");

        sbb.onEvent(req, container.createActivityContext("t-fail"));

        assertThat(ss7.cmds).isNotEmpty();
        assertThat(ss7.cmds.stream().noneMatch(c -> c instanceof Ss7Command.MapSendRoutingInfoForSm)).isTrue();
        assertThat(ss7.cmds.stream().noneMatch(c -> c instanceof Ss7Command.MapUnstructuredSsRequest)).isTrue();
        assertThat(cdr.statuses()).contains("MAP2MAP_UPPER_GT_FAIL");
        assertThat(routed.get()).isNull();
    }

    @Test
    void hopDestSsnAloneOverridesUpperGtSsn() {
        set(services, "linkStatus", new LinkStatusService() {
            @Override
            public boolean ss7Live() {
                return true;
            }
        });
        VirtualSession session = new VirtualSession("vs-ssn", "corr-ssn", "req-ssn", "911230398", 0,
                "dlg-ssn", "*804#");
        store.put(session);

        Map2MapRequestEvent req = new Map2MapRequestEvent(
                "corr-ssn", "m2m-corr-ssn", "dlg-ssn", 1L, "911230398", "*804#", "*804#",
                "*875#", "http://as/userinfo", RuleType.HTTP, 0, null, "vs-ssn", "req-ssn",
                false, null, null, 8);

        sbb.onEvent(req, container.createActivityContext("t-ssn"));

        var ussd = (Ss7Command.MapUnstructuredSsRequest) ss7.cmds.get(0);
        assertThat(ussd.processUnstructured()).isTrue();
        assertThat(ussd.targetAddress().globalTitle()).isEqualTo("251971200200");
        assertThat(ussd.targetAddress().subSystemNumber()).isEqualTo(8);
    }

    @Test
    void labSkipForcesBridgeArmOnSession() {
        VirtualSession session = new VirtualSession("vs6", "corr6", "req6", "911230398", 0,
                "dlg6", "*101");
        store.put(session);

        Map2MapRequestEvent req = new Map2MapRequestEvent(
                "corr6", "m2m-corr6", "dlg6", 1L, "911230398", "*101", "*101123456#",
                "*8744#", "http://as/userinfo", RuleType.HTTP, 0, null, "vs6", "req6",
                true, null);

        sbb.onEvent(req, container.createActivityContext("t6"));

        assertThat(session.adaptiveBridgeArm()).isTrue();
        assertThat(routed.get().originatedUssd()).isEqualTo("*101123456#");
        assertThat(routed.get().codeKind()).isEqualTo("LONG");
        assertThat(routed.get().ussdString()).isEqualTo(Map2MapCdr.AS_USSD_HLR_NONE);
    }


    @Test
    void fixedHopDestAddressesSpGtSsnWithoutSri() {
        set(services, "linkStatus", new LinkStatusService() {
            @Override
            public boolean ss7Live() {
                return true;
            }
        });
        VirtualSession session = new VirtualSession("vs-sp", "corr-sp", "req-sp", "251911000001", 0,
                "dlg-sp", "*804#");
        store.put(session);

        // SP prove: redirect *875# → GT 251971200201 / SSN 6 (skip SRI/FAKE)
        Map2MapRequestEvent req = new Map2MapRequestEvent(
                "corr-sp", "m2m-corr-sp", "dlg-sp", 9L, "251911000001", "*804#", "*804#",
                "*875#", "http://127.0.0.1:8090/ussd/pull", RuleType.HTTP, 0, null, "vs-sp", "req-sp",
                false, null, "251971200201", 6);

        sbb.onEvent(req, container.createActivityContext("t-sp"));

        assertThat(ss7.cmds).hasSize(1);
        assertThat(ss7.cmds.get(0)).isInstanceOf(Ss7Command.MapUnstructuredSsRequest.class);
        var ussd = (Ss7Command.MapUnstructuredSsRequest) ss7.cmds.get(0);
        assertThat(ussd.processUnstructured()).isTrue();
        assertThat(ussd.dialogId()).isEqualTo("m2m-corr-sp");
        assertThat(ussd.targetAddress().globalTitle()).isEqualTo("251971200201");
        assertThat(ussd.targetAddress().subSystemNumber()).isEqualTo(6);
        assertThat(ussd.localAddress().subSystemNumber()).isEqualTo(6);
        assertThat(ussd.text()).isEqualTo("*875#");
        assertThat(ussd.msisdn()).isEqualTo("251911000001");
        assertThat(pending.peek("m2m-corr-sp")).isPresent()
                .get().extracting(PendingMap2MapRegistry.Pending::phase)
                .isEqualTo(PendingMap2MapRegistry.Phase.AWAITING_USSD);
        assertThat(routed.get()).isNull();
    }

    @Test
    void fixedHopLongMarkPreservesSuffixOnWire() {
        set(services, "linkStatus", new LinkStatusService() {
            @Override
            public boolean ss7Live() {
                return true;
            }
        });
        et.restlink.ussdgw.routing.ShortCodeRoutingService routing =
                new et.restlink.ussdgw.routing.ShortCodeRoutingService();
        routing.put(ShortCodeRule.ofReroute("*804*", RuleType.HTTP, "http://as/", true,
                null, 0, true, null, true, "*875*", null, "251971200201", 6));
        set(services, "routing", routing);

        VirtualSession session = new VirtualSession("vs-long", "corr-long", "req-long", "251911000001", 0,
                "dlg-long", "*804*");
        store.put(session);

        Map2MapRequestEvent req = new Map2MapRequestEvent(
                "corr-long", "m2m-corr-long", "dlg-long", 9L, "251911000001", "*804*",
                "*804*1234#", "*875*", "http://127.0.0.1:8090/ussd/pull", RuleType.HTTP, 0, null,
                "vs-long", "req-long", true, null, "251971200201", 6);

        sbb.onEvent(req, container.createActivityContext("t-long"));

        assertThat(ss7.cmds).hasSize(1);
        var ussd = (Ss7Command.MapUnstructuredSsRequest) ss7.cmds.get(0);
        assertThat(ussd.processUnstructured()).isTrue();
        assertThat(ussd.text()).isEqualTo("*875*1234#");
        assertThat(cdr.statuses()).contains(Map2MapCdr.USSD_SENT);
        assertThat(cdr.details().stream().anyMatch(d -> d != null && d.contains("hopUssd=*875*1234#")))
                .isTrue();
    }

    @Test
    void fixedHopLongMarkChainsSecondReroute() {
        set(services, "linkStatus", new LinkStatusService() {
            @Override
            public boolean ss7Live() {
                return true;
            }
        });
        et.restlink.ussdgw.routing.ShortCodeRoutingService routing =
                new et.restlink.ussdgw.routing.ShortCodeRoutingService();
        routing.put(ShortCodeRule.ofReroute("*804*", RuleType.HTTP, "http://as/", true,
                null, 0, true, null, true, "*875*", null, "251971200201", 6));
        routing.put(ShortCodeRule.ofReroute("*875*", RuleType.HTTP, "http://as/", true,
                null, 0, true, null, true, "*8775*", null));
        set(services, "routing", routing);

        VirtualSession session = new VirtualSession("vs-ch", "corr-ch", "req-ch", "251911000001", 0,
                "dlg-ch", "*804*");
        store.put(session);

        Map2MapRequestEvent req = new Map2MapRequestEvent(
                "corr-ch", "m2m-corr-ch", "dlg-ch", 9L, "251911000001", "*804*",
                "*804*1234#", "*875*", "http://127.0.0.1:8090/ussd/pull", RuleType.HTTP, 0, null,
                "vs-ch", "req-ch", true, null, "251971200201", 6);

        sbb.onEvent(req, container.createActivityContext("t-ch"));

        var ussd = (Ss7Command.MapUnstructuredSsRequest) ss7.cmds.get(0);
        assertThat(ussd.text()).isEqualTo("*8775*1234#");
    }

    @Test
    void fixedHopDestSkipsSriAndFakeSendsToConfiguredGtSsn() {
        set(services, "linkStatus", new LinkStatusService() {
            @Override
            public boolean ss7Live() {
                return true;
            }
        });
        // PROXY_MAP + fake fields present — fixed hop must still skip SRI/FAKE.
        injectKvQuiet(runtimeKv, RuntimeConfigStore.Keys.HLR_MODE, "PROXY_MAP");
        injectKvQuiet(runtimeKv, RuntimeConfigStore.Keys.HLR_FAKE_IMSI, "636019999000001");
        injectKvQuiet(runtimeKv, RuntimeConfigStore.Keys.HLR_FAKE_MSC_GT, "251977700042");

        VirtualSession session = new VirtualSession("vs7", "corr7", "req7", "911230398", 0,
                "dlg7", "*804#");
        store.put(session);

        Map2MapRequestEvent req = new Map2MapRequestEvent(
                "corr7", "m2m-corr7", "dlg7", 9L, "911230398", "*804#", "*804#",
                "*875#", "http://as/userinfo", RuleType.HTTP, 0, null, "vs7", "req7",
                false, "PROXY_MAP", "251971200201", 6);

        sbb.onEvent(req, container.createActivityContext("t7"));

        assertThat(ss7.cmds).hasSize(1);
        assertThat(ss7.cmds.get(0)).isInstanceOf(Ss7Command.MapUnstructuredSsRequest.class);
        var ussd = (Ss7Command.MapUnstructuredSsRequest) ss7.cmds.get(0);
        assertThat(ussd.processUnstructured()).isTrue();
        assertThat(ussd.dialogId()).isEqualTo("m2m-corr7");
        assertThat(ussd.targetAddress().globalTitle()).isEqualTo("251971200201");
        assertThat(ussd.targetAddress().subSystemNumber()).isEqualTo(6);
        assertThat(ussd.msisdn()).isEqualTo("911230398");
        assertThat(ussd.text()).isEqualTo("*875#");
        assertThat(pending.peek("m2m-corr7")).isPresent()
                .get().extracting(PendingMap2MapRegistry.Pending::phase)
                .isEqualTo(PendingMap2MapRegistry.Phase.AWAITING_USSD);
        assertThat(routed.get()).isNull();
    }

    @Test
    void fixedHopDestDefaultsSsn6WhenUnset() {
        set(services, "linkStatus", new LinkStatusService() {
            @Override
            public boolean ss7Live() {
                return true;
            }
        });
        VirtualSession session = new VirtualSession("vs8", "corr8", "req8", "911230398", 0,
                "dlg8", "*804#");
        store.put(session);

        Map2MapRequestEvent req = new Map2MapRequestEvent(
                "corr8", "m2m-corr8", "dlg8", 1L, "911230398", "*804#", "*804#",
                "*875#", "http://as/userinfo", RuleType.HTTP, 0, null, "vs8", "req8",
                false, null, "251971200201", null);

        sbb.onEvent(req, container.createActivityContext("t8"));

        var ussd = (Ss7Command.MapUnstructuredSsRequest) ss7.cmds.get(0);
        assertThat(ussd.processUnstructured()).isTrue();
        assertThat(ussd.targetAddress().subSystemNumber()).isEqualTo(6);
        assertThat(ussd.text()).isEqualTo("*875#");
    }

    @Test
    void labMoNetworkId1HopUsesLiveNetworkId0() {
        set(services, "linkStatus", new LinkStatusService() {
            @Override
            public boolean ss7Live() {
                return true;
            }
        });
        // MO arrived on lab SCCP plane (networkId=1); hop must still use live plane 0.
        Map2MapRequestEvent req = new Map2MapRequestEvent(
                "corr-lab", "m2m-corr-lab", "dlg-lab", 1L, "911230398", "*804#", "*804#",
                "*875#", "http://as/userinfo", RuleType.HTTP, 1, null, "vs-lab", "req-lab",
                false, null, "251971200201", null);

        sbb.onEvent(req, container.createActivityContext("t-lab"));

        assertThat(ss7.cmds).hasSize(1);
        var ussd = (Ss7Command.MapUnstructuredSsRequest) ss7.cmds.get(0);
        assertThat(ussd.processUnstructured()).isTrue();
        assertThat(ussd.networkId()).isEqualTo(0);
        assertThat(ussd.targetAddress().globalTitle()).isEqualTo("251971200201");
        assertThat(cdr.details().stream().anyMatch(d -> d != null && d.contains("hopNet=0")
                && d.contains("moNet=1"))).isTrue();
    }

    @Test
    void withoutHopDestUsesUpperGtNoSri() {
        set(services, "linkStatus", new LinkStatusService() {
            @Override
            public boolean ss7Live() {
                return true;
            }
        });
        Map2MapRequestEvent req = new Map2MapRequestEvent(
                "corr9", "m2m-corr9", "dlg9", 1L, "911230398", "*804#", "*804#",
                "*8744#", "http://as/userinfo", RuleType.HTTP, 0, null, "vs9", "req9",
                false, null, null, null);

        sbb.onEvent(req, container.createActivityContext("t9"));

        assertThat(ss7.cmds.get(0)).isInstanceOf(Ss7Command.MapUnstructuredSsRequest.class);
        var ussd = (Ss7Command.MapUnstructuredSsRequest) ss7.cmds.get(0);
        assertThat(ussd.processUnstructured()).isTrue();
        assertThat(ussd.targetAddress().globalTitle()).isEqualTo("251971200200");
        assertThat(ss7.cmds.stream().noneMatch(c -> c instanceof Ss7Command.MapSendRoutingInfoForSm)).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static void injectKvQuiet(RuntimeConfigStore store, String key, String value) {
        try {
            injectKv(store, key, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void injectKv(RuntimeConfigStore store, String key, String value) throws Exception {
        var f = RuntimeConfigStore.class.getDeclaredField("cache");
        f.setAccessible(true);
        ((java.util.concurrent.ConcurrentHashMap<String, String>) f.get(store)).put(key, value);
    }

    private static CdrService noopCdr() {
        return new RecordingCdr();
    }

    private static final class RecordingCdr extends CdrService {
        private final List<String> statuses = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<String> details = new java.util.concurrent.CopyOnWriteArrayList<>();

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
            details.add(detail);
        }

        List<String> statuses() { return statuses; }
        List<String> details() { return details; }
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
