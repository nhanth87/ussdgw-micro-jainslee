package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionState;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.persist.SipTrunkEntity;
import et.restlink.ussdgw.service.SbbServices;
import et.restlink.ussdgw.sip.SipTrunkService;
import et.restlink.ussdgw.sip.SipUssdBodyCodec;
import et.restlink.ussdgw.tenant.TenantGuard;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.sipservlet.events.SipMessageEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SIP inbound: pull reply → {@code onAsResponse} (not NI); NI requires trunk; route-fail cleans session.
 */
class SipUssiSbbTest {
    private MicroSleeContainer container;
    private VirtualSessionStore store;
    private RecordingBridge bridge;
    private SbbServices services;
    private SipUssiSbb sbb;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        store = new VirtualSessionStore();
        set(store, "container", container);
        set(store, "config", new UssdConfigService());
        set(store, "profileTtlMs", 120_000L);
        store.ensureTable();

        bridge = new RecordingBridge();
        UssdConfigService config = new UssdConfigService();
        set(config, "sipEnabledProp", true);
        set(config, "httpNiDefaultNetworkIdProp", 1);

        services = new SbbServices();
        set(services, "config", config);
        set(services, "bridge", bridge);
        set(services, "store", store);
        set(services, "wireFacade", new AsWireFacade());
        set(services, "container", container);
        set(services, "tenantGuard", new TenantGuard() {
            @Override
            public Decision admit(String tenantId) {
                return new Decision(Reason.OK, null);
            }
        });
        set(services, "sipTrunks", new SipTrunkService() {
            @Override
            public Optional<SipTrunkEntity> matchPeer(String peerHost) {
                if ("as.example".equalsIgnoreCase(peerHost)) {
                    return Optional.of(trunk("trunk-a", "as.example", "bank1"));
                }
                return Optional.empty();
            }
        });
        set(services, "adaptive", new AdaptiveTimeout());
        setStatic(SbbServices.class, "INSTANCE", services);

        sbb = new SipUssiSbb(services, null);
        bridge.last = null;
    }

    @AfterEach
    void tearDown() {
        setStatic(SbbServices.class, "INSTANCE", null);
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void pullReplyViaCallIdGoesToOnAsResponseNotNi() {
        String corr = "corr-pull-1";
        VirtualSession parked = new VirtualSession(
                "vs", corr, "r1", "251911000111", 1, "dlg", "*123#");
        parked.setState(VirtualSessionState.AWAITING_AS);
        parked.setTenantId("bank1");
        parked.setGateDeadlineMs(System.currentTimeMillis() + 60_000);
        store.put(parked);
        int before = store.size();

        SipMessageEvent msg = new SipMessageEvent(
                "pull-" + corr,
                "sip:as@as.example",
                "sip:ussdgw@gw.local",
                "text/plain",
                "Main menu");
        sbb.onEvent(msg, null);

        assertThat(bridge.last).isNotNull();
        assertThat(bridge.last.correlationId()).isEqualTo(corr);
        assertThat(bridge.last.text()).isEqualTo("Main menu");
        // No additional NI session row
        assertThat(store.size()).isEqualTo(before);
    }

    @Test
    void softNiWithoutTrunkIsRejected() {
        SipMessageEvent msg = new SipMessageEvent(
                "cid-1",
                "sip:as@unknown.peer",
                "sip:251911000111@gw.local",
                "text/plain",
                "Push this");
        sbb.onEvent(msg, null);
        assertThat(bridge.last).isNull();
        assertThat(store.size()).isZero();
    }

    @Test
    void explicitNiWithTrunkCreatesSession() {
        SipMessageEvent msg = new SipMessageEvent(
                "cid-ni",
                "sip:as@as.example",
                "sip:251911000222@gw.local",
                "application/sdp",
                """
                        v=0
                        a=msisdn:251911000222
                        a=ussd-string:Hello NI
                        """);
        sbb.onEvent(msg, null);
        assertThat(bridge.last).isNull();
        assertThat(store.size()).isEqualTo(1);
        VirtualSession ni = store.findAwaitingAsByMsisdn("251911000222", "bank1").orElse(null);
        // NI session is ACTIVE (not AWAITING_AS) — look via size + pending
        assertThat(ni).isNull();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void niRouteFailureRemovesSession() {
        // Null container → NPE on routeEvent path; catch Throwable must remove the row.
        set(services, "container", null);
        SipMessageEvent msg = new SipMessageEvent(
                "cid-fail",
                "sip:as@as.example",
                "sip:251911000333@gw.local",
                "application/sdp",
                """
                        v=0
                        a=msisdn:251911000333
                        a=ussd-string:Fail me
                        """);
        int before = store.size();
        sbb.onEvent(msg, null);
        assertThat(store.size()).isEqualTo(before);
    }

    @Test
    void corrFromPullCallId() {
        assertThat(SipUssiSbb.corrFromPullCallId("pull-abc")).isEqualTo("abc");
        assertThat(SipUssiSbb.corrFromPullCallId("other")).isNull();
    }

    @Test
    void freeTextMarkedSoftNi() {
        var d = SipUssdBodyCodec.decode("text/plain", "Menu", false, "BODY");
        assertThat(d.explicitNi()).isFalse();
    }

    private static SipTrunkEntity trunk(String id, String peer, String tenant) {
        SipTrunkEntity e = new SipTrunkEntity();
        e.trunkId = id;
        e.peerHost = peer;
        e.enabled = true;
        e.tenantId = tenant;
        e.inboundBody = "BODY";
        return e;
    }

    private static final class RecordingBridge extends VirtualSessionBridge {
        volatile AsResponse last;
        @Override
        public void onAsResponse(AsResponse response, long latencyMs) {
            last = response;
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

    private static void setStatic(Class<?> type, String field, Object value) {
        try {
            var f = type.getDeclaredField(field);
            f.setAccessible(true);
            f.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
