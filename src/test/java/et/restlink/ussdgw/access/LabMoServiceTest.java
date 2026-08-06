package et.restlink.ussdgw.access;

import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.routing.ShortCodeRoutingService;
import et.restlink.ussdgw.service.AsPullRouter;
import et.restlink.ussdgw.tenant.TenantGuard;

import com.microjainslee.core.MicroSleeContainer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabMoServiceTest {
    private MicroSleeContainer container;
    private VirtualSessionStore store;
    private LabMoService lab;
    private final List<String> routes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        store = new VirtualSessionStore();
        set(store, "container", container);
        set(store, "config", new UssdConfigService());
        set(store, "profileTtlMs", 120_000L);
        store.ensureTable();

        VirtualSessionBridge bridge = new VirtualSessionBridge();
        set(bridge, "store", store);
        set(bridge, "adaptive", new AdaptiveTimeout());
        UssdConfigService cfg = new UssdConfigService();
        set(cfg, "bridgeEnabledProp", true);
        set(cfg, "asyncGateTimeoutMsProp", 7000L);
        set(cfg, "dialogTimeoutMsProp", 60_000L);
        set(cfg, "httpClientBridgeEnabledProp", true);
        set(cfg, "smppUssdEnabledProp", true);
        set(cfg, "diameterEnabledProp", true);
        set(cfg, "sipEnabledProp", true);
        set(bridge, "config", cfg);
        set(bridge, "cdr", noopCdr());
        set(bridge, "accessNi", new AccessNiDispatcher() {
            @Override
            public void requestNiPush(VirtualSession session, String text) { }
        });

        ShortCodeRoutingService routing = new ShortCodeRoutingService() {
            @Override
            public Optional<ShortCodeRule> find(String shortCode) {
                if ("*123#".equals(shortCode)) {
                    return Optional.of(new ShortCodeRule(
                            "*123#", RuleType.HTTP, "http://as/lab", true, null, 0));
                }
                return Optional.empty();
            }
        };

        AsPullRouter asPull = new AsPullRouter() {
            @Override
            public String route(ShortCodeRule rule, AsRequest asReq, String correlationId) {
                routes.add(rule.ruleType() + ":" + rule.asUrl());
                return "routed HTTP sc=" + asReq.shortCode();
            }
        };

        SmppUssdAccessAdapter smpp = new SmppUssdAccessAdapter();
        DiameterUssdAccessAdapter diameter = new DiameterUssdAccessAdapter();
        SipUssiAccessAdapter sip = new SipUssiAccessAdapter();

        lab = new LabMoService();
        set(lab, "routing", routing);
        set(lab, "asPullRouter", asPull);
        set(lab, "store", store);
        set(lab, "bridge", bridge);
        set(lab, "config", cfg);
        set(lab, "tenantGuard", new TenantGuard());
        set(lab, "smpp", smpp);
        set(lab, "diameter", diameter);
        set(lab, "sip", sip);
        routes.clear();
    }

    @AfterEach
    void tearDown() {
        if (container != null) container.stop();
    }

    @Test
    void smppLabMoRoutesHttpPull() {
        LabMoService.Result r = lab.start(
                OriginationType.SMPP, "251911", "*123#", "*123#", null, 0);
        assertThat(r.session().state().name()).isEqualTo("AWAITING_AS");
        assertThat(r.session().originationType()).isEqualTo(OriginationType.SMPP);
        assertThat(r.session().adaptiveBridgeArm()).isTrue();
        assertThat(routes).containsExactly("HTTP:http://as/lab");
        assertThat(r.routeDetail()).contains("routed HTTP");
    }

    @Test
    void unknownShortCodeRejected() {
        assertThatThrownBy(() -> lab.start(
                OriginationType.DIAMETER, "251911", "*999#", "*999#", null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no route");
        assertThat(routes).isEmpty();
    }

    private static CdrService noopCdr() {
        return new CdrService() {
            @Override
            public void write(String correlationId, et.restlink.ussdgw.cdr.CdrPhase phase,
                              String msisdn, String shortCode, String status, String detail,
                              int networkId, String tenantId, String originationType) { }
        };
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
