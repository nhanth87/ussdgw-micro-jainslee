package et.restlink.ussdgw.service;

import et.restlink.ussdgw.bridge.GatedSessionMeta;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.cdr.CdrStatuses;
import et.restlink.ussdgw.events.GatedAsNotifyEvent;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.routing.ShortCodeRoutingService;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GatedAsNotifyServiceTest {

    @Test
    void pushToAsRoutesXmlEventToHttpAsUrl() {
        ShortCodeRoutingService routing = new ShortCodeRoutingService() {
            @Override
            public Optional<ShortCodeRule> find(String shortCode) {
                return Optional.of(new ShortCodeRule(
                        "*123#", RuleType.HTTP, "http://as.example/ussd/pull", true));
            }
        };
        List<GatedAsNotifyEvent> captured = new ArrayList<>();
        List<String> cdrStatuses = new ArrayList<>();
        GatedAsNotifyService svc = new GatedAsNotifyService();
        set(svc, "routing", routing);
        set(svc, "cdr", new et.restlink.ussdgw.cdr.CdrService() {
            @Override
            public void write(String correlationId, et.restlink.ussdgw.cdr.CdrPhase phase,
                              String msisdn, String shortCode, String status, String detail,
                              int networkId, String tenantId, String originationType,
                              Long gateMs, Long observedEwmaMs) {
                cdrStatuses.add(status);
            }
        });
        svc.bindTestDispatch(captured::add);

        VirtualSession s = new VirtualSession("vs", "corr-1", "r", "2519", 0, "dlg", "*123#");
        GatedSessionMeta meta = GatedSessionMeta.of(
                s, "js-1", GatedSessionMeta.REASON_BRIDGED, 2500L);

        assertThat(svc.pushToAs(meta, s)).isTrue();
        assertThat(svc.pushed()).isEqualTo(1);
        assertThat(captured).hasSize(1);
        GatedAsNotifyEvent ev = captured.get(0);
        assertThat(ev.asUrl()).isEqualTo("http://as.example/ussd/pull");
        assertThat(ev.xmlBody())
                .contains("virtualBridgeId=\"corr-1\"")
                .contains("jsessionId=\"js-1\"")
                .contains("gateReason=\"BRIDGED\"")
                .contains("unstructuredSSNotify_Request");
        assertThat(ev.meta().observedEwmaMs()).isEqualTo(2500L);
        assertThat(cdrStatuses).containsExactly(CdrStatuses.GATED_AS_NOTIFY);
    }

    @Test
    void pushSkippedWhenNoHttpRule() {
        ShortCodeRoutingService routing = new ShortCodeRoutingService() {
            @Override
            public Optional<ShortCodeRule> find(String shortCode) {
                return Optional.empty();
            }
        };
        List<String> cdrStatuses = new ArrayList<>();
        GatedAsNotifyService svc = new GatedAsNotifyService();
        set(svc, "routing", routing);
        set(svc, "cdr", new et.restlink.ussdgw.cdr.CdrService() {
            @Override
            public void write(String correlationId, et.restlink.ussdgw.cdr.CdrPhase phase,
                              String msisdn, String shortCode, String status, String detail,
                              int networkId, String tenantId, String originationType,
                              Long gateMs, Long observedEwmaMs) {
                cdrStatuses.add(status);
            }
        });
        svc.bindTestDispatch(ev -> {
            throw new AssertionError("must not dispatch");
        });

        GatedSessionMeta meta = GatedSessionMeta.niPark(
                "c", "js", 1000L, null, 0, "1", "*999#", null);
        assertThat(svc.pushToAs(meta, null)).isFalse();
        assertThat(svc.skipped()).isEqualTo(1);
        assertThat(cdrStatuses).containsExactly(CdrStatuses.GATED_AS_SKIP);
    }

    @Test
    void encodeXmlIsClassicNotifyShape() {
        String xml = GatedAsNotifyService.encodeXml(GatedSessionMeta.niPark(
                "c", "js", 3000L, 1500L, 1, "2519", "*1#", "vs"));
        assertThat(xml).contains("adaptiveTimeoutMs=\"3000\"")
                .contains("observedEwmaMs=\"1500\"")
                .contains("jsessionId=\"js\"");
    }

    private static void set(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
