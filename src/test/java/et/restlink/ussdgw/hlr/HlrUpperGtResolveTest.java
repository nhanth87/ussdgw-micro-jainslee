package et.restlink.ussdgw.hlr;

import et.restlink.ussdgw.config.RuntimeConfigStore;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.events.InboundSriSmEvent;
import et.restlink.ussdgw.sbbs.SriSbb;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.jss7.command.Ss7Command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Destination GT resolution for outbound SRI-SM (NI SriSbb + PROXY_MAP face). */
class HlrUpperGtResolveTest {

    private UssdConfigService config;
    private RuntimeConfigStore store;
    private HlrResolvePolicy policy;

    @BeforeEach
    void setUp() throws Exception {
        store = new RuntimeConfigStore();
        clearStore(store);
        config = new UssdConfigService();
        set(config, "store", store);
        set(config, "ussdGtProp", "251900000100");
        set(config, "hlrUpperGtProp", Optional.of("251900000006"));
        policy = new HlrResolvePolicy();
        set(policy, "config", config);
    }

    @Test
    void overlayWinsOverPropsForCalledParty() throws Exception {
        injectCache(store, RuntimeConfigStore.Keys.HLR_UPPER_GT, "251922222222");
        assertThat(policy.upperHlrGt()).isEqualTo("251922222222");
        assertThat(SriSbb.resolveUpperHlrGt(config, policy)).isEqualTo("251922222222");
    }

    @Test
    void blankOverlayFallsBackToProps() throws Exception {
        injectCache(store, RuntimeConfigStore.Keys.HLR_UPPER_GT, "   ");
        assertThat(policy.upperHlrGt()).isEqualTo("251900000006");
        assertThat(SriSbb.isUnusableUpperHlrGt(config, policy, policy.upperHlrGt())).isFalse();
    }

    @Test
    void missingOverlayUsesProps() {
        assertThat(policy.upperHlrGt()).isEqualTo("251900000006");
    }

    @Test
    void proxyMapOutboundUsesResolvedUpperNotMsisdn() throws Exception {
        injectCache(store, RuntimeConfigStore.Keys.HLR_MODE, "PROXY_MAP");
        injectCache(store, RuntimeConfigStore.Keys.HLR_UPPER_GT, "");
        set(config, "hlrModeProp", "PROXY_MAP");
        set(config, "hlrSsnProp", 6);
        set(config, "ussdSsnProp", 8);
        set(config, "hlrFakeImsiProp", Optional.of("63601"));
        set(config, "hlrFakeMscGtProp", Optional.of("251911000099"));

        HlrFaceService face = new HlrFaceService();
        set(face, "policy", policy);
        set(face, "pendingProxy", new PendingHlrProxyRegistry());
        set(face, "diameter", new DiameterLocationClient());
        set(face, "config", config);
        set(face, "cdr", new et.restlink.ussdgw.cdr.CdrService() {
            @Override
            public void write(String correlationId, et.restlink.ussdgw.cdr.CdrPhase phase,
                              String msisdn, String shortCode, String status, String detail) {}
        });

        CapturingPort port = new CapturingPort();
        String msisdn = "251911000001";
        String r = face.handle(new InboundSriSmEvent("1", 1L, msisdn, "251900000100", 0), port);
        assertThat(r).isEqualTo("HLR_PROXY_MAP_SENT");
        var sri = (Ss7Command.MapSendRoutingInfoForSm) port.cmds.get(0);
        assertThat(sri.targetAddress().globalTitle()).isEqualTo("251900000006");
        assertThat(sri.targetAddress().globalTitle()).isNotEqualTo(msisdn);
        assertThat(sri.msisdn()).isEqualTo(msisdn);
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
