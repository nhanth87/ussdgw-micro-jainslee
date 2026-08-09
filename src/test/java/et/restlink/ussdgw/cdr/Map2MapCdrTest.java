package et.restlink.ussdgw.cdr;

import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.events.Map2MapRequestEvent;
import et.restlink.ussdgw.routing.RuleType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Map2MapCdrTest {

    @Test
    void detailIncludesShortCodeRedirectAndFixedHop() {
        Map2MapRequestEvent req = new Map2MapRequestEvent(
                "corr1", "m2m-corr1", "dlg1", 1L, "251911000001", "*804#", "*804#",
                "*875#", "http://as/", RuleType.HTTP, 0, "t1", "vs1", "req1",
                false, null, "251971200201", 6);
        String d = Map2MapCdr.detail(req, "path=fixed");
        assertThat(d)
                .contains("sc=*804#")
                .contains("redirect=*875#")
                .contains("dialed=*804#")
                .contains("hopGt=251971200201")
                .contains("hopSsn=6")
                .contains("path=fixed");
    }

    @Test
    void detailArmedCarriesGateAndVirtualBridge() {
        Map2MapRequestEvent req = new Map2MapRequestEvent(
                "c", "m2m-c", "d", 1L, "2519", "*804#", "*804#",
                "*8744#", "http://as/", RuleType.HTTP, 0, null, "vs-c", "r");
        VirtualSession s = new VirtualSession("vs-c", "c", "r", "2519", 0, "d", "*804#");
        s.setGateMs(1200L);
        String d = Map2MapCdr.detailArmed(req, s);
        assertThat(d)
                .contains("phase=hop-ingress")
                .contains("gateMs=1200")
                .contains("virtualBridgeId=vs-c")
                .contains("redirect=*8744#");
        assertThat(Map2MapCdr.gateMs(s)).isEqualTo(1200L);
    }

    @Test
    void statusConstantsMatchTelemetryLanguage() {
        assertThat(Map2MapCdr.ARMED).isEqualTo("MAP2MAP_ARMED");
        assertThat(Map2MapCdr.HOP_START).isEqualTo("MAP2MAP_HOP_START");
        assertThat(Map2MapCdr.GATED_HOP).isEqualTo("MAP2MAP_GATED_HOP");
        assertThat(Map2MapCdr.COMPLETE_AFTER_GATE).isEqualTo("MAP2MAP_COMPLETE_AFTER_GATE");
        assertThat(Map2MapCdr.TIMEOUT_AFTER_BRIDGE).isEqualTo("MAP2MAP_TIMEOUT_AFTER_BRIDGE");
        assertThat(Map2MapCdr.TIMEOUT).isEqualTo("MAP2MAP_TIMEOUT");
        assertThat(Map2MapCdr.OK).isEqualTo("MAP2MAP_OK");
        assertThat(Map2MapCdr.HLR_REJECT).isEqualTo("HLR_REJECT");
        assertThat(Map2MapCdr.HOP_CLOSE).isEqualTo("MAP2MAP_HOP_CLOSE");
        assertThat(Map2MapCdr.AS_ROUTED).isEqualTo("MAP2MAP_AS_ROUTED");
        assertThat(Map2MapCdr.AS_EARLY).isEqualTo("MAP2MAP_AS_EARLY");
        assertThat(Map2MapCdr.AS_USSD_HLR_PENDING).isEqualTo("hlr pending");
    }

    @Test
    void reRouteAsUssd_rejectAndNone() {
        assertThat(Map2MapCdr.statusForDialogLost("REJECT", false)).isEqualTo(Map2MapCdr.HLR_REJECT);
        assertThat(Map2MapCdr.statusForDialogLost("CLOSE", false)).isEqualTo(Map2MapCdr.HOP_CLOSE);
        assertThat(Map2MapCdr.statusForDialogLost("RELEASE", true)).isEqualTo(Map2MapCdr.HOP_CLOSE);
        assertThat(Map2MapCdr.statusForDialogLost("TIMEOUT", false)).isEqualTo(Map2MapCdr.TIMEOUT);
        assertThat(Map2MapCdr.statusForDialogLost("TIMEOUT", true))
                .isEqualTo(Map2MapCdr.TIMEOUT_AFTER_BRIDGE);
        assertThat(Map2MapCdr.statusForDialogLost("USER_ABORT", false)).isEqualTo(Map2MapCdr.HOP_ABORT);
        assertThat(Map2MapCdr.isTimerDialogLost("TIMEOUT")).isTrue();
        assertThat(Map2MapCdr.isTimerDialogLost("CLOSE")).isFalse();
        assertThat(Map2MapCdr.asUssdForReRouteHop("", Map2MapCdr.OUTCOME_REJECT))
                .isEqualTo("hlr reject");
        assertThat(Map2MapCdr.asUssdForReRouteHop("", Map2MapCdr.OUTCOME_CLOSE))
                .isEmpty();
        assertThat(Map2MapCdr.asUssdForReRouteHop("", Map2MapCdr.OUTCOME_EMPTY))
                .isEmpty();
        assertThat(Map2MapCdr.asUssdForReRouteHop("hi", Map2MapCdr.OUTCOME_TEXT)).isEqualTo("hi");
        assertThat(Map2MapCdr.isTerminalHopOutcome(Map2MapCdr.OUTCOME_REJECT)).isTrue();
        assertThat(Map2MapCdr.isTerminalHopOutcome(Map2MapCdr.OUTCOME_CLOSE)).isTrue();
        assertThat(Map2MapCdr.isTerminalHopOutcome(Map2MapCdr.OUTCOME_TEXT)).isFalse();
    }
}
