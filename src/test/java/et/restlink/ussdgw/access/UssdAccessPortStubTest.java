package et.restlink.ussdgw.access;

import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;

import com.microjainslee.core.MicroSleeContainer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UssdAccessPortStubTest {
    private MicroSleeContainer container;
    private VirtualSessionStore store;
    private VirtualSessionBridge bridge;
    private DiameterUssdAccessAdapter diameter;
    private SmppUssdAccessAdapter smpp;
    private SipUssiAccessAdapter sip;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        store = new VirtualSessionStore();
        set(store, "container", container);
        set(store, "config", new UssdConfigService());
        set(store, "profileTtlMs", 120_000L);
        store.ensureTable();

        bridge = new VirtualSessionBridge();
        set(bridge, "store", store);
        set(bridge, "adaptive", new AdaptiveTimeout());
        UssdConfigService cfg = new UssdConfigService();
        set(cfg, "bridgeEnabledProp", true);
        set(cfg, "asyncGateTimeoutMsProp", 7000L);
        set(cfg, "dialogTimeoutMsProp", 60_000L);
        set(cfg, "diameterEnabledProp", true);
        set(cfg, "smppUssdEnabledProp", true);
        set(cfg, "sipEnabledProp", true);
        set(bridge, "config", cfg);
        set(bridge, "cdr", noopCdr());
        set(bridge, "accessNi", new AccessNiDispatcher() {
            @Override
            public void requestNiPush(VirtualSession session, String text) { }
        });

        diameter = new DiameterUssdAccessAdapter();
        set(diameter, "store", store);
        set(diameter, "bridge", bridge);
        set(diameter, "cdr", noopCdr());
        set(diameter, "config", cfg);

        smpp = new SmppUssdAccessAdapter();
        set(smpp, "store", store);
        set(smpp, "bridge", bridge);
        set(smpp, "cdr", noopCdr());
        set(smpp, "config", cfg);

        sip = new SipUssiAccessAdapter();
        set(sip, "store", store);
        set(sip, "bridge", bridge);
        set(sip, "cdr", noopCdr());
        set(sip, "config", cfg);
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void diameterMoPullCreatesSessionAwaitingAs() {
        VirtualSession s = diameter.acceptMoPull(new UssdAccessSession(
                "corr-d", "251911", "*100#", 7, "ethio-bank", OriginationType.DIAMETER, "dia-1"));
        assertThat(s).isNotNull();
        assertThat(s.originationType()).isEqualTo(OriginationType.DIAMETER);
        assertThat(s.tenantId()).isEqualTo("ethio-bank");
        assertThat(s.state().name()).isEqualTo("AWAITING_AS");
        assertThat(diameter.moCount()).isEqualTo(1);
    }

    @Test
    void smppAndSipMoPullSameBridgePath() {
        assertThat(smpp.acceptMoPull(new UssdAccessSession(
                "c-s", "2519", "*123#", 0, "lab-tenant", OriginationType.SMPP, ""))).isNotNull();
        assertThat(sip.acceptMoPull(new UssdAccessSession(
                "c-i", "2519", "*123#", 0, "lab-tenant", OriginationType.SIP, ""))).isNotNull();
        assertThat(smpp.moCount()).isEqualTo(1);
        assertThat(sip.moCount()).isEqualTo(1);
    }

    @Test
    void stubNiPushIncrementsCounter() {
        VirtualSession s = diameter.acceptMoPull(new UssdAccessSession(
                "corr-n", "2519", "*1#", 0, null, OriginationType.DIAMETER, "d"));
        diameter.requestNiPush(s, "hello");
        assertThat(diameter.niCount()).isEqualTo(1);
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
