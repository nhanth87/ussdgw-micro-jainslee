package et.restlink.ussdgw.cdr;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CdrSessionSpineTest {

    @Test
    void derive_alwaysSixSlots_denseHappyPath() {
        CdrRecord gate = row(CdrStatuses.GATE_ARMED,
                "service=VirtualSessionBridge|AdaptiveTimeout|gateMs=25000|note=armed-not-fired",
                25000L, null);
        CdrRecord hop = row(Map2MapCdr.HOP_CLOSE,
                "hopOutcome=text|hopGt=251911000001|hopSsn=6|asUssd=Balance is 12.50",
                25000L, "Balance is 12.50");
        CdrRecord routed = row(Map2MapCdr.AS_ROUTED,
                "hopOutcome=text|asUrl=http://127.0.0.1:8090/ussd/pull|asUssd=Balance is 12.50",
                25000L, "Balance is 12.50");
        CdrRecord end = row("END",
                "service=VirtualSessionBridge|sync|asAction=END|asUssd=Balance is 12.50 ETB thank you|asLen=30|note=AS→UE|dialed=*804*123#|asUrl=http://127.0.0.1:8090/ussd/pull",
                25000L, "Balance is 12.50 ETB thank you");
        end.phase = "COMPLETED";
        end.msisdn = "251911230398";
        end.shortCode = "*804#";
        end.startedAt = Instant.parse("2026-08-10T01:00:00Z");

        var dig = CdrSessionDigest.from(end, List.of(end, routed, hop, gate));
        var steps = CdrSessionSpine.derive(dig, end);

        assertThat(steps).hasSize(6);
        assertThat(steps).extracting(CdrSessionSpine.Step::slot)
                .containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(steps.get(0).result()).isEqualTo(CdrSessionSpine.Result.OK);
        assertThat(steps.get(0).detail()).contains("msisdn=251911230398");
        assertThat(steps.get(0).detail()).contains("sc=*804#");
        assertThat(steps.get(0).detail()).contains("dialed=");
        assertThat(steps.get(1).result()).isEqualTo(CdrSessionSpine.Result.OK);
        assertThat(steps.get(1).detail()).contains("gt=251911000001");
        assertThat(steps.get(2).result()).isEqualTo(CdrSessionSpine.Result.OK);
        assertThat(steps.get(2).detail()).contains("hopText=");
        assertThat(steps.get(3).result()).isEqualTo(CdrSessionSpine.Result.OK);
        assertThat(steps.get(3).detail()).contains("asUrl=");
        assertThat(steps.get(4).result()).isEqualTo(CdrSessionSpine.Result.OK);
        assertThat(steps.get(4).detail()).contains("asUssd=");
        assertThat(steps.get(4).detail()).contains("Balance");
        assertThat(steps.get(5).result()).isEqualTo(CdrSessionSpine.Result.OK);
        assertThat(steps.get(5).detail()).contains("MAP END");
        assertThat(steps.get(5).detail()).contains("gateMs=25000");
        assertThat(steps.get(5).chipClass()).isEqualTo("cdr-status--ok");
        assertThat(steps).noneMatch(s -> s.detail().contains("VirtualSessionBridge"));
    }

    @Test
    void derive_missingHop_slots2and3SkippedWithReason() {
        CdrRecord end = row("END",
                "asAction=END|asUssd=Thank you|asLen=10|note=AS→UE|asUrl=http://as/pull",
                7000L, "Thank you");
        end.shortCode = "*101#";
        end.msisdn = "251911230398";
        var dig = CdrSessionDigest.from(end, List.of(end));
        var steps = CdrSessionSpine.derive(dig, end);

        assertThat(steps).hasSize(6);
        assertThat(steps.get(1).result()).isEqualTo(CdrSessionSpine.Result.SKIPPED);
        assertThat(steps.get(1).detail()).contains("reason=");
        assertThat(steps.get(1).chipClass()).isEqualTo("cdr-status--gated");
        assertThat(steps.get(2).result()).isEqualTo(CdrSessionSpine.Result.SKIPPED);
        assertThat(steps.get(5).result()).isEqualTo(CdrSessionSpine.Result.OK);
    }

    @Test
    void derive_asTextButNoMapToUe_slot6FailRed() {
        CdrRecord routed = row(Map2MapCdr.AS_ROUTED,
                "asUrl=http://127.0.0.1:8090/ussd/pull|asUssd=Your balance is 99 ETB",
                7000L, "Your balance is 99 ETB");
        routed.shortCode = "*804#";
        routed.msisdn = "251911230398";
        var dig = CdrSessionDigest.from(routed, List.of(routed));
        var steps = CdrSessionSpine.derive(dig, routed);

        assertThat(steps.get(4).result()).isEqualTo(CdrSessionSpine.Result.OK);
        assertThat(steps.get(4).detail()).contains("asUssd=");
        assertThat(steps.get(5).result()).isEqualTo(CdrSessionSpine.Result.FAIL);
        assertThat(steps.get(5).chipClass()).isEqualTo("cdr-status--fail");
        assertThat(steps.get(5).detail()).containsIgnoringCase("not sent to UE");
    }

    @Test
    void derive_sriNiFillsSlots2and3() {
        CdrRecord sri = row(Map2MapCdr.SRI_SENT, "mscGt=251911999000|imsi=63601", null, null);
        CdrRecord done = row(CdrStatuses.BRIDGED_DONE,
                "mscGt=251911999000|imsi=63601|asUssd=Push hello", 5000L, "Push hello");
        done.msisdn = "251911230398";
        done.shortCode = "NI";
        var dig = CdrSessionDigest.from(done, List.of(done, sri));
        var steps = CdrSessionSpine.derive(dig, done);

        assertThat(steps.get(1).result()).isEqualTo(CdrSessionSpine.Result.OK);
        assertThat(steps.get(2).result()).isEqualTo(CdrSessionSpine.Result.OK);
        assertThat(steps.get(2).detail()).contains("msc=");
        assertThat(steps.get(5).result()).isEqualTo(CdrSessionSpine.Result.OK);
        assertThat(steps.get(5).detail()).contains("MAP END");
    }

    @Test
    void hasOperatorAsText_rejectsHlrSentinels() {
        assertThat(CdrSessionSpine.hasOperatorAsText("hlr none",
                java.util.Map.of("asUssd", "hlr none"))).isFalse();
        assertThat(CdrSessionSpine.hasOperatorAsText("Balance 1",
                java.util.Map.of("asUssd", "Balance 1"))).isTrue();
    }

    private static CdrRecord row(String status, String detail, Long gateMs, String asUssd) {
        CdrRecord r = new CdrRecord();
        r.correlationId = "corr-spine";
        r.status = status;
        r.detail = detail;
        r.gateMs = gateMs;
        r.asUssd = asUssd;
        r.shortCode = "*804#";
        r.msisdn = "251911230398";
        r.phase = "S1_ACTIVE";
        r.createdAt = Instant.parse("2026-08-10T01:00:05Z");
        return r;
    }
}
