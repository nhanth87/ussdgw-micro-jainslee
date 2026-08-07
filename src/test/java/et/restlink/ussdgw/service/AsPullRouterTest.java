package et.restlink.ussdgw.service;

import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionState;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.persist.SipTrunkEntity;
import et.restlink.ussdgw.persist.TenantEntity;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.sip.SipTrunkService;
import et.restlink.ussdgw.tenant.TenantService;

import com.microjainslee.core.MicroSleeContainer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsPullRouterTest {
    private MicroSleeContainer container;
    private AsPullRouter router;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        router = new AsPullRouter();
        set(router, "container", container);
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void httpRuleReturnsRoutedHttpDetail() {
        AsRequest req = new AsRequest("vs", "c1", "r1", 0, "2519", "*123#", "*123#", 1);
        String detail = router.route(
                new ShortCodeRule("*123#", RuleType.HTTP, "http://as/pull", true),
                req, "c1");
        assertThat(detail).isEqualTo("routed HTTP sc=*123#");
    }

    @Test
    void grpcRuleReturnsRoutedGrpcDetail() {
        AsRequest req = new AsRequest("vs", "c2", "r2", 0, "2519", "*456#", "*456#", 1);
        String detail = router.route(
                new ShortCodeRule("*456#", RuleType.GRPC, "localhost:50051|et.as/Pull", true),
                req, "c2");
        assertThat(detail).isEqualTo("routed GRPC sc=*456#");
    }

    /**
     * Both client RAs fire their completion on {@code createActivityHandle(correlationId)}, and
     * the container derives the SBB entity id from the activity context name. A decorated name
     * here would put submit and completion on two different entities.
     */
    @Test
    void pullActivityIsNamedAfterTheBareCorrelationId() {
        AsRequest req = new AsRequest("vs", "c-name", "r1", 0, "2519", "*123#", "*123#", 1);
        router.route(new ShortCodeRule("*123#", RuleType.HTTP, "http://as/pull", true),
                req, "c-name");
        assertThat(container.getActivityContextNamingFacility().lookup("c-name")).isNotNull();
        assertThat(container.getActivityContextNamingFacility().lookup("pull-http-c-name"))
                .isNull();

        AsRequest grpcReq = new AsRequest("vs", "g-name", "r1", 0, "2519", "*456#", "*456#", 1);
        router.route(new ShortCodeRule("*456#", RuleType.GRPC, "localhost:50051|et.as/Pull", true),
                grpcReq, "g-name");
        assertThat(container.getActivityContextNamingFacility().lookup("g-name")).isNotNull();
        assertThat(container.getActivityContextNamingFacility().lookup("pull-grpc-g-name"))
                .isNull();
    }

    @Test
    void emptyUrlFailsClosed() {
        AsRequest req = new AsRequest("vs", "c3", "r3", 0, "2519", "*1#", "*1#", 0);
        assertThatThrownBy(() -> router.route(
                new ShortCodeRule("*1#", RuleType.HTTP, "  ", true), req, "c3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AS URL empty");
    }

    @Test
    void sipRuleWithoutRaFailsClosed() {
        AsRequest req = new AsRequest("vs", "c-sip", "r1", 0, "2519", "*9#", "*9#", 1);
        assertThatThrownBy(() -> router.route(
                new ShortCodeRule("*9#", RuleType.SIP, "trunk-a", true), req, "c-sip"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SIP RA not active");
    }

    @Test
    void resolveTrunkUsesAsUrlTrunkId() {
        SipTrunkEntity trunk = trunk("trunk-a");
        set(router, "sipTrunks", new SipTrunkService() {
            @Override
            public Optional<SipTrunkEntity> byId(String trunkId) {
                return "trunk-a".equals(trunkId) ? Optional.of(trunk) : Optional.empty();
            }
        });
        AsRequest req = new AsRequest("vs", "c4", "r4", 0, "2519", "*9#", "*9#", 1);
        SipTrunkEntity got = router.resolveTrunk(
                new ShortCodeRule("*9#", RuleType.SIP, "trunk-a", true), req, "c4");
        assertThat(got).isSameAs(trunk);
    }

    @Test
    void resolveTrunkFallsBackToTenantPreferredTrunk() {
        SipTrunkEntity preferred = trunk("tenant-trunk");
        set(router, "sipTrunks", new SipTrunkService() {
            @Override
            public Optional<SipTrunkEntity> byId(String trunkId) {
                return Optional.empty();
            }

            @Override
            public Optional<SipTrunkEntity> resolveForTenant(String tenantId) {
                return "bank1".equals(tenantId) ? Optional.of(preferred) : Optional.empty();
            }
        });
        AsRequest req = new AsRequest("vs", "c5", "r5", 0, "2519", "*9#", "*9#", 1);
        SipTrunkEntity got = router.resolveTrunk(
                new ShortCodeRule("*9#", RuleType.SIP, "missing-trunk", true, "bank1", 1),
                req, "c5");
        assertThat(got).isSameAs(preferred);
    }

    @Test
    void resolveTrunkRejectsTenantMismatch() {
        SipTrunkEntity trunk = trunk("trunk-a");
        trunk.tenantId = "bank-a";
        set(router, "sipTrunks", new SipTrunkService() {
            @Override
            public Optional<SipTrunkEntity> byId(String trunkId) {
                return "trunk-a".equals(trunkId) ? Optional.of(trunk) : Optional.empty();
            }
        });
        AsRequest req = new AsRequest("vs", "c-own", "r1", 0, "2519", "*9#", "*9#", 1);
        assertThatThrownBy(() -> router.resolveTrunk(
                new ShortCodeRule("*9#", RuleType.SIP, "trunk-a", true, "bank-b", 1),
                req, "c-own"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant mismatch");
    }

    @Test
    void resolveTrunkAllowsSharedTrunkForAnyTenant() {
        SipTrunkEntity shared = trunk("shared");
        shared.tenantId = null;
        set(router, "sipTrunks", new SipTrunkService() {
            @Override
            public Optional<SipTrunkEntity> byId(String trunkId) {
                return Optional.of(shared);
            }
        });
        AsRequest req = new AsRequest("vs", "c-sh", "r1", 0, "2519", "*9#", "*9#", 1);
        assertThat(router.resolveTrunk(
                new ShortCodeRule("*9#", RuleType.SIP, "shared", true, "bank-x", 1),
                req, "c-sh")).isSameAs(shared);
    }

    @Test
    void encodeSipPullUsesWireFacadeXmlByDefault() {
        set(router, "wireFacade", new AsWireFacade());
        AsRequest req = new AsRequest("vs", "c6", "r6", 0, "2519", "*123#", "*123#", 1);
        AsPullRouter.SipPullBody body = router.encodeSipPull(req, "c6", null);
        assertThat(body.contentType()).isEqualTo(AsPullRouter.SIP_CT_XML);
        assertThat(body.body()).contains("<dialog").contains("*123#");
    }

    @Test
    void encodeSipPullUsesJsonWhenTenantWireFormatJson() {
        set(router, "wireFacade", new AsWireFacade());
        set(router, "tenants", new TenantService() {
            @Override
            public Optional<TenantEntity> byId(String tenantId) {
                TenantEntity e = new TenantEntity();
                e.tenantId = tenantId;
                e.httpAsWireFormat = "JSON";
                return Optional.of(e);
            }
        });
        AsRequest req = new AsRequest("vs", "c7", "r7", 0, "2519", "*123#", "*123#", 1);
        AsPullRouter.SipPullBody body = router.encodeSipPull(req, "c7", "bank1");
        assertThat(body.contentType()).isEqualTo(AsPullRouter.SIP_CT_JSON);
        assertThat(body.body()).contains("\"correlationId\"").contains("c7");
        assertThat(router.resolveSipWireFormat("bank1")).isEqualTo(AsHttpWireFormat.JSON);
    }

    @Test
    void encodeSipPullFallsBackToPlainWhenFacadeMissing() {
        set(router, "wireFacade", null);
        AsRequest req = new AsRequest("vs", "c8", "r8", 0, "2519", "*123#", "*123#", 1);
        AsPullRouter.SipPullBody body = router.encodeSipPull(req, "c8", null);
        assertThat(body.contentType()).isEqualTo(AsPullRouter.SIP_CT_PLAIN);
        assertThat(body.body()).contains("correlationId=c8").contains("msisdn=2519");
    }

    @Test
    void armSipPullBridgeSetsAwaitingAs() {
        VirtualSessionStore store = new VirtualSessionStore();
        set(store, "container", container);
        set(store, "config", new UssdConfigService());
        set(store, "profileTtlMs", 120_000L);
        store.ensureTable();

        VirtualSessionBridge bridge = new VirtualSessionBridge();
        AdaptiveTimeout adaptive = new AdaptiveTimeout();
        UssdConfigService config = new UssdConfigService();
        set(bridge, "store", store);
        set(bridge, "adaptive", adaptive);
        set(bridge, "config", config);
        set(bridge, "cdr", new et.restlink.ussdgw.cdr.CdrService() {
            @Override
            public void write(String correlationId, et.restlink.ussdgw.cdr.CdrPhase phase,
                              String msisdn, String shortCode, String status, String detail,
                              int networkId, String tenantId, String originationType,
                              Long gateMs, Long observedEwmaMs) {
                // no-op for unit test
            }
        });
        set(bridge, "accessNi", null);
        set(bridge, "niHttpPark", null);

        set(router, "store", store);
        set(router, "bridge", bridge);
        set(router, "config", config);

        VirtualSession session = new VirtualSession(
                "vs", "c-arm", "r1", "2519", 1, "dlg", "*9#");
        session.setState(VirtualSessionState.ACTIVE);
        store.put(session);

        router.armSipPullBridge(session, true);

        VirtualSession got = store.get("c-arm").orElseThrow();
        assertThat(got.state()).isEqualTo(VirtualSessionState.AWAITING_AS);
        assertThat(got.gateDeadlineMs()).isPositive();
        assertThat(got.adaptiveBridgeArm()).isTrue();
    }

    @Test
    void resolveTrunkRejectsCrossTenantTrunk() {
        SipTrunkEntity foreign = trunk("other-trunk");
        foreign.tenantId = "bank2";
        set(router, "sipTrunks", new SipTrunkService() {
            @Override
            public Optional<SipTrunkEntity> byId(String trunkId) {
                return "other-trunk".equals(trunkId) ? Optional.of(foreign) : Optional.empty();
            }
        });
        AsRequest req = new AsRequest("vs", "c-x", "r-x", 0, "2519", "*9#", "*9#", 1);
        assertThatThrownBy(() -> router.resolveTrunk(
                new ShortCodeRule("*9#", RuleType.SIP, "other-trunk", true, "bank1", 1),
                req, "c-x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant mismatch");
    }

    private static SipTrunkEntity trunk(String id) {
        SipTrunkEntity e = new SipTrunkEntity();
        e.trunkId = id;
        e.enabled = true;
        e.peerHost = "as.example";
        e.peerPort = 5060;
        return e;
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
