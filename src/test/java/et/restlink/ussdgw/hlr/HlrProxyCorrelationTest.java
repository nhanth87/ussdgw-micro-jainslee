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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B3 + H8 — one inbound SRI-SM dialog gets exactly one response, and an upper answer only ever
 * reaches the dialog that asked for it.
 */
class HlrProxyCorrelationTest {

    private CapturingPort port;
    private HlrFaceService face;
    private PendingHlrProxyRegistry pending;
    private HlrLocationCache cache;
    private RuntimeConfigStore store;
    private final List<String> cdrCodes = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        port = new CapturingPort();
        store = new RuntimeConfigStore();
        clearStore(store);

        UssdConfigService config = new UssdConfigService();
        set(config, "store", store);
        set(config, "ussdGtProp", "251900000100");
        set(config, "ussdSsnProp", 8);
        set(config, "hlrSsnProp", 6);
        set(config, "hlrModeProp", "PROXY_MAP");
        set(config, "hlrFakeImsiProp", Optional.of("636010000000001"));
        set(config, "hlrFakeMscGtProp", Optional.of("251911000099"));
        set(config, "hlrUpperGtProp", Optional.of("251900000006"));
        set(config, "diameterEnabledProp", false);

        HlrResolvePolicy policy = new HlrResolvePolicy();
        set(policy, "config", config);

        pending = new PendingHlrProxyRegistry();
        pending.bindSs7(() -> port);
        cache = new HlrLocationCache();

        DiameterLocationClient diameter = new DiameterLocationClient();
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
        set(face, "locationCache", cache);
        set(face, "diameter", diameter);
        set(face, "config", config);
        set(face, "cdr", cdr);
        cdrCodes.clear();
    }

    @Test
    void fakeThenResolveAnswersTheInboundDialogExactlyOnce() throws Exception {
        injectCache(store, RuntimeConfigStore.Keys.HLR_MODE, "FAKE_THEN_RESOLVE");
        injectCache(store, RuntimeConfigStore.Keys.HLR_UPPER_GT, "251900000006");

        String r = face.handle(new InboundSriSmEvent("dlg-1", 2L, "251911000001", "100", 0), port);
        assertThat(r).isEqualTo("HLR_FAKE_THEN_RESOLVE_MAP");
        assertThat(responsesOn(port, "dlg-1")).hasSize(1);
        var outbound = (Ss7Command.MapSendRoutingInfoForSm) port.cmds.get(1);

        port.cmds.clear();
        String enrich = face.relayUpperResponse(
                outbound.dialogId(), "636019999999999", "251988800001", null, port);

        // A second MapSendRoutingInfoForSmResponse on a closed dialog would break the MAP FSM.
        assertThat(enrich).isEqualTo("HLR_ENRICH_OK");
        assertThat(port.cmds).isEmpty();
        assertThat(cache.get("251911000001"))
                .get()
                .extracting(HlrLocationCache.Location::imsi)
                .isEqualTo("636019999999999");
    }

    @Test
    void fakeThenResolveServesTheLearnedLocationOnTheNextQuery() throws Exception {
        injectCache(store, RuntimeConfigStore.Keys.HLR_MODE, "FAKE_THEN_RESOLVE");
        injectCache(store, RuntimeConfigStore.Keys.HLR_UPPER_GT, "251900000006");
        cache.put("251911000001", "636019999999999", "251988800001", null);

        face.handle(new InboundSriSmEvent("dlg-2", 3L, "251911000001", "100", 0), port);

        var rsp = responsesOn(port, "dlg-2").get(0);
        assertThat(rsp.imsi()).isEqualTo("636019999999999");
        assertThat(rsp.mscGt()).isEqualTo("251988800001");
    }

    @Test
    void unknownUpperCorrelationIsDroppedAndLeavesOtherDialogsAlone() throws Exception {
        injectCache(store, RuntimeConfigStore.Keys.HLR_MODE, "PROXY_MAP");
        injectCache(store, RuntimeConfigStore.Keys.HLR_UPPER_GT, "251900000006");

        face.handle(new InboundSriSmEvent("dlg-victim", 1L, "251911000001", "100", 0), port);
        port.cmds.clear();

        String r = face.relayUpperResponse("hlr-proxy-never-sent", "63601", "2519888", null, port);
        assertThat(r).isEqualTo("hlr-proxy-no-pending");
        assertThat(port.cmds).isEmpty();
        assertThat(pending.size()).isEqualTo(1);
    }

    @Test
    void expiredProxyQueryAbortsItsInboundDialog() {
        pending.put("out-relay", new PendingHlrProxyRegistry.Pending(
                "dlg-stuck", 4L, "251911000001", 0, "100", HlrResolveMode.PROXY_MAP), 0L);

        int reclaimed = face.expirePending(pending.ttlMs());

        assertThat(reclaimed).isEqualTo(1);
        assertThat(port.cmds).hasSize(1);
        var abort = (Ss7Command.MapDialogAbort) port.cmds.get(0);
        assertThat(abort.dialogId()).isEqualTo("dlg-stuck");
        assertThat(cdrCodes).contains("HLR_PROXY_TIMEOUT");
        assertThat(pending.size()).isZero();
    }

    @Test
    void expiredEnrichQueryDoesNotTouchTheAlreadyAnsweredDialog() {
        pending.put("out-enrich", new PendingHlrProxyRegistry.Pending(
                "dlg-done", 5L, "251911000001", 0, "100",
                HlrResolveMode.FAKE_THEN_RESOLVE, true), 0L);

        int reclaimed = face.expirePending(pending.ttlMs());

        assertThat(reclaimed).isEqualTo(1);
        assertThat(port.cmds).isEmpty();
        assertThat(cdrCodes).doesNotContain("HLR_PROXY_TIMEOUT");
    }

    private static List<Ss7Command.MapSendRoutingInfoForSmResponse> responsesOn(
            CapturingPort port, String dialogId) {
        return port.cmds.stream()
                .filter(Ss7Command.MapSendRoutingInfoForSmResponse.class::isInstance)
                .map(Ss7Command.MapSendRoutingInfoForSmResponse.class::cast)
                .filter(c -> dialogId.equals(c.dialogId()))
                .toList();
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
