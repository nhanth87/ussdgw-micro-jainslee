package et.restlink.ussdgw.cdr;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CdrServiceStatusesTest {

    @Test
    void primaryOutcome_prefersEndOverGateArmed() {
        CdrRecord gate = row(CdrStatuses.GATE_ARMED,
                "service=VirtualSessionBridge|AdaptiveTimeout|gateMs=25000", 25000L, null);
        CdrRecord end = row("END",
                "service=VirtualSessionBridge|sync|asAction=END|asUssd=(xyz)|asLen=5|note=AS→UE",
                25000L, "(xyz)");
        end.phase = "COMPLETED";

        var outcome = CdrServiceStatuses.primaryOutcome(gate, List.of(gate, end));
        assertThat(outcome.status()).isEqualTo("END");
        assertThat(outcome.humanLabel()).isEqualTo("AS→UE end");
        assertThat(outcome.cssClass()).isEqualTo("cdr-status--ok");
        assertThat(CdrServiceStatuses.outcomeRank(CdrStatuses.GATE_ARMED))
                .isLessThan(CdrServiceStatuses.outcomeRank("END"));
    }

    @Test
    void timelineSummary_surfacesAsUssdNotBridgePipe() {
        CdrRecord end = row("END",
                "service=VirtualSessionBridge|sync|asAction=END|asUssd=Thank you for calling.|asLen=20|note=AS→UE",
                7000L, "Thank you for calling.");
        String sum = CdrServiceStatuses.timelineSummary(end);
        assertThat(sum).startsWith("AS: Thank you");
        assertThat(sum).doesNotContain("VirtualSessionBridge");
        assertThat(sum).doesNotContain("asAction=");
    }

    @Test
    void timelineSummary_gateArmedDoesNotInheritSessionAsUssd() {
        CdrRecord gate = row(CdrStatuses.GATE_ARMED,
                "service=VirtualSessionBridge|AdaptiveTimeout|gateMs=25000|note=armed-not-fired",
                25000L, "(xyz)"); // session column leaked onto event — must not become "AS: (xyz)"
        assertThat(CdrServiceStatuses.timelineSummary(gate))
                .isEqualTo("budget 25000 ms")
                .doesNotContain("AS:");
    }

    @Test
    void planes_listHumanServiceStatuses() {
        CdrRecord gate = row(CdrStatuses.GATE_ARMED,
                "service=VirtualSessionBridge|AdaptiveTimeout|gateMs=25000|note=armed-not-fired",
                25000L, null);
        CdrRecord hop = row(Map2MapCdr.HOP_CLOSE, "hopOutcome=text|asUssd=Balance is 12.50", 25000L, "Balance is 12.50");
        CdrRecord routed = row(Map2MapCdr.AS_ROUTED, "hopOutcome=text|asUssd=Balance is 12.50", 25000L, "Balance is 12.50");
        CdrRecord end = row("END",
                "service=VirtualSessionBridge|sync|asAction=END|asUssd=Balance is 12.50 ETB thank you|asLen=30|note=AS→UE",
                25000L, "Balance is 12.50 ETB thank you");
        end.phase = "COMPLETED";
        end.msisdn = "251911230398";
        end.shortCode = "*804#";

        var dig = CdrSessionDigest.from(end, List.of(end, routed, hop, gate));
        var planes = CdrServiceStatuses.planes(dig, end);

        assertThat(planes).extracting(CdrServiceStatuses.Plane::label)
                .contains("MAP MO", "HLR / hop", "Bridge / Adaptive", "AS HTTP", "MAP UE reply");
        assertThat(planes).noneMatch(p -> p.state().contains("VirtualSessionBridge"));
        String asHttp = planes.stream()
                .filter(p -> "AS HTTP".equals(p.label()))
                .map(CdrServiceStatuses.Plane::state)
                .findFirst()
                .orElse("");
        assertThat(asHttp).contains("Balance");
        String mapUe = planes.stream()
                .filter(p -> "MAP UE reply".equals(p.label()))
                .map(CdrServiceStatuses.Plane::state)
                .findFirst()
                .orElse("");
        assertThat(mapUe).isEqualTo("AS→UE end");
        assertThat(CdrServiceStatuses.primaryOutcome(end, dig.timelineOldestFirst()).status())
                .isEqualTo("END");
    }

    @Test
    void humanStatus_gateArmedIsNotHeroLabel() {
        assertThat(CdrServiceStatuses.humanStatus(CdrStatuses.GATE_ARMED)).isEqualTo("Gate armed");
        assertThat(CdrServiceStatuses.humanStatus(Map2MapCdr.HOP_CLOSE)).isEqualTo("Hop close (text)");
    }

    private static CdrRecord row(String status, String detail, Long gateMs, String asUssd) {
        CdrRecord r = new CdrRecord();
        r.correlationId = "corr-svc";
        r.status = status;
        r.detail = detail;
        r.gateMs = gateMs;
        r.asUssd = asUssd;
        r.shortCode = "*804#";
        r.msisdn = "251911230398";
        r.phase = "S1_ACTIVE";
        return r;
    }
}
