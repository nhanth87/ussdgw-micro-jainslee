package et.restlink.ussdgw.hlr;

import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.RuntimeConfigStore;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.events.InboundSriSmEvent;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.jss7.command.Ss7Command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class HlrFaceServiceTest {

    private CapturingPort port;
    private HlrFaceService face;
    private PendingHlrProxyRegistry pending;
    private DiameterLocationClient diameter;
    private UssdConfigService config;
    private RuntimeConfigStore store;
    private final List<String> cdrCodes = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        port = new CapturingPort();
        store = new RuntimeConfigStore();
        // empty Panache load — fine for unit tests without DB if PostConstruct skipped
        clearStore(store);

        config = new UssdConfigService();
        set(config, "store", store);
        set(config, "ussdGtProp", "251900000100");
        set(config, "ussdSsnProp", 8);
        set(config, "hlrSsnProp", 6);
        set(config, "hlrModeProp", "PROXY_MAP");
        set(config, "hlrFakeImsiProp", "636010000000001");
        set(config, "hlrFakeMscGtProp", "251911000099");
        set(config, "hlrUpperGtProp", "251900000006");
        set(config, "diameterEnabledProp", false);

        HlrResolvePolicy policy = new HlrResolvePolicy();
        set(policy, "config", config);

        pending = new PendingHlrProxyRegistry();
        diameter = new DiameterLocationClient();
        set(diameter, "config", config);
        diameter.forceStub(true);

        CdrService cdr = new CdrService() {
            @Override
            public void write(String correlationId, CdrPhase phase, String msisdn,
                              String shortCode, String status, String detail) {
                cdrCodes.add(status);
            }
        };

        face = new HlrFaceService();
        set(face, "policy", policy);
        set(face, "pendingProxy", pending);
        set(face, "diameter", diameter);
        set(face, "config", config);
        set(face, "cdr", cdr);
        cdrCodes.clear();
    }

    @Test
    void defaultModeIsProxyMap() {
        assertThat(HlrResolveMode.parse(null)).isEqualTo(HlrResolveMode.PROXY_MAP);
        assertThat(HlrResolveMode.parse("FAKE")).isEqualTo(HlrResolveMode.FAKE);
        assertThat(HlrResolveMode.parse("PROXY_DIAMETER")).isEqualTo(HlrResolveMode.PROXY_DIAMETER);
        assertThat(HlrResolveMode.parse("FAKE_THEN_RESOLVE")).isEqualTo(HlrResolveMode.FAKE_THEN_RESOLVE);
    }

    @Test
    void fakeSendsSriResponse() throws Exception {
        injectCache(store, RuntimeConfigStore.Keys.HLR_MODE, "FAKE");

        InboundSriSmEvent ev = new InboundSriSmEvent("42", 7L, "251911000001", "251900000100", 0);
        String r = face.handle(ev, port);
        assertThat(r).isEqualTo("HLR_FAKE");
        assertThat(port.cmds).hasSize(1);
        assertThat(port.cmds.get(0)).isInstanceOf(Ss7Command.MapSendRoutingInfoForSmResponse.class);
        var rsp = (Ss7Command.MapSendRoutingInfoForSmResponse) port.cmds.get(0);
        assertThat(rsp.dialogId()).isEqualTo("42");
        assertThat(rsp.invokeId()).isEqualTo(7L);
        assertThat(rsp.imsi()).isEqualTo("636010000000001");
        assertThat(rsp.mscGt()).isEqualTo("251911000099");
        assertThat(cdrCodes).contains("HLR_FAKE");
    }

    @Test
    void proxyMapSendsOutboundSriAndRegistersPending() throws Exception {
        injectCache(store, RuntimeConfigStore.Keys.HLR_MODE, "PROXY_MAP");
        injectCache(store, RuntimeConfigStore.Keys.HLR_UPPER_GT, "251900000006");

        InboundSriSmEvent ev = new InboundSriSmEvent("99", 1L, "251911000001", "251900000100", 1);
        String r = face.handle(ev, port);
        assertThat(r).isEqualTo("HLR_PROXY_MAP_SENT");
        assertThat(port.cmds.get(0)).isInstanceOf(Ss7Command.MapSendRoutingInfoForSm.class);
        var sri = (Ss7Command.MapSendRoutingInfoForSm) port.cmds.get(0);
        assertThat(sri.targetAddress().globalTitle()).isEqualTo("251900000006");
        assertThat(pending.size()).isEqualTo(1);

        port.cmds.clear();
        String relay = face.relayUpperResponse(sri.dialogId(), "63601999", "251988800001", null, port);
        assertThat(relay).isEqualTo("HLR_PROXY_OK");
        assertThat(port.cmds.get(0)).isInstanceOf(Ss7Command.MapSendRoutingInfoForSmResponse.class);
        var rsp = (Ss7Command.MapSendRoutingInfoForSmResponse) port.cmds.get(0);
        assertThat(rsp.dialogId()).isEqualTo("99");
        assertThat(rsp.imsi()).isEqualTo("63601999");
    }

    @Test
    void proxyMapFailClosedWhenUpperLoops() throws Exception {
        injectCache(store, RuntimeConfigStore.Keys.HLR_MODE, "PROXY_MAP");
        injectCache(store, RuntimeConfigStore.Keys.HLR_UPPER_GT, "251900000100"); // == ussdGt

        String r = face.handle(new InboundSriSmEvent("1", 1L, "2519", "100", 0), port);
        assertThat(r).isEqualTo("HLR_PROXY_FAIL");
        assertThat(port.cmds.get(0)).isInstanceOf(Ss7Command.MapDialogAbort.class);
    }

    @Test
    void proxyDiameterUsesStubLocation() throws Exception {
        injectCache(store, RuntimeConfigStore.Keys.HLR_MODE, "PROXY_DIAMETER");
        diameter.putStub("251911000001",
                new DiameterLocationClient.Location("636010000000077", "251977700001", null));

        String r = face.handle(new InboundSriSmEvent("5", 3L, "251911000001", "100", 0), port);
        assertThat(r).isEqualTo("HLR_DIAM_OK");
        var rsp = (Ss7Command.MapSendRoutingInfoForSmResponse) port.cmds.get(0);
        assertThat(rsp.imsi()).isEqualTo("636010000000077");
        assertThat(rsp.mscGt()).isEqualTo("251977700001");
    }

    @Test
    void fakeThenResolveSendsFakeThenOutboundSri() throws Exception {
        injectCache(store, RuntimeConfigStore.Keys.HLR_MODE, "FAKE_THEN_RESOLVE");
        injectCache(store, RuntimeConfigStore.Keys.HLR_UPPER_GT, "251900000006");

        String r = face.handle(new InboundSriSmEvent("8", 2L, "251911000001", "100", 0), port);
        assertThat(r).isEqualTo("HLR_FAKE_THEN_RESOLVE_MAP");
        assertThat(port.cmds).hasSize(2);
        assertThat(port.cmds.get(0)).isInstanceOf(Ss7Command.MapSendRoutingInfoForSmResponse.class);
        assertThat(port.cmds.get(1)).isInstanceOf(Ss7Command.MapSendRoutingInfoForSm.class);
    }

    @SuppressWarnings("unchecked")
    private static void injectCache(RuntimeConfigStore store, String key, String value) throws Exception {
        Field f = RuntimeConfigStore.class.getDeclaredField("cache");
        f.setAccessible(true);
        ((ConcurrentHashMap<String, String>) f.get(store)).put(key, value);
    }

    private static void clearStore(RuntimeConfigStore store) throws Exception {
        Field f = RuntimeConfigStore.class.getDeclaredField("cache");
        f.setAccessible(true);
        ((ConcurrentHashMap<?, ?>) f.get(store)).clear();
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(field);
    }

    static final class CapturingPort implements RaCommandPort {
        final List<OutboundCommand> cmds = new ArrayList<>();
        @Override public void sendCommand(OutboundCommand command) { cmds.add(command); }
    }
}
