package et.restlink.ussdgw.cdr;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CdrSessionDigestTest {

    @Test
    void parseDetail_splitsPipeKvAndFlags() {
        var m = CdrSessionDigest.parseDetail("sc=*101|asUrl=http://x/ussd|gated-ack|http=200");
        assertThat(m)
                .containsEntry("sc", "*101")
                .containsEntry("asUrl", "http://x/ussd")
                .containsEntry("gated-ack", "true")
                .containsEntry("http", "200");
    }

    @Test
    void digest_derivesGateHlrAsFromTimeline() {
        CdrRecord gated = row(CdrStatuses.GATE_ARMED,
                "service=VirtualSessionBridge|AdaptiveTimeout|gateMs=1200|note=armed-not-fired", 1200L);
        CdrRecord sri = row(Map2MapCdr.SRI_SENT, "sc=*101|redirect=*101*9#|hlrMode=PROXY_MAP|path=hlr", null);
        CdrRecord ussd = row(Map2MapCdr.USSD_SENT, "mscGt=251911|imsi=63601|hopOutcome=text", null);
        CdrRecord ok = row(Map2MapCdr.OK, "hopOutcome=text|asUssd=Balance|hopLen=7", null);
        CdrRecord notify = row(CdrStatuses.GATED_AS_NOTIFY, "service=GatedAsNotifyService|asUrl=http://as/ussd", 1200L);
        CdrRecord ack = row(CdrStatuses.GATED_AS_ACK, "service=HttpClientSbb|gated-ack|http=200", 1200L);

        // list API returns newest-first
        var dig = CdrSessionDigest.from(ack, List.of(ack, notify, ok, ussd, sri, gated));
        assertThat(dig.gateMs()).isEqualTo(1200L);
        assertThat(dig.shortCode()).isEqualTo("*101");
        assertThat(dig.longOrRedirect()).isEqualTo("*101*9#");
        assertThat(dig.upperHlrSent().value()).isEqualTo("yes");
        assertThat(dig.hlrResponse().value()).startsWith("yes");
        assertThat(dig.asNotifySent().value()).isEqualTo("yes");
        assertThat(dig.asResponse().value()).startsWith("yes");
        assertThat(dig.timelineOldestFirst()).hasSize(6);
        assertThat(dig.timelineOldestFirst().getFirst().status).isEqualTo(CdrStatuses.GATE_ARMED);
    }

    @Test
    void digest_rejectHopIsHonest() {
        CdrRecord rej = row(Map2MapCdr.HLR_REJECT,
                "sc=*804#|redirect=*875#|hopOutcome=reject|kind=REJECT", 7000L);
        CdrRecord as = row(Map2MapCdr.AS_ROUTED,
                "hopOutcome=reject|asUssd=hlr reject|phase=as-no-rearm", 7000L);
        var dig = CdrSessionDigest.from(as, List.of(as, rej));
        assertThat(dig.hlrResponse().value()).isEqualTo("reject");
        assertThat(dig.asResponse().value()).contains("AS pull routed");
        assertThat(dig.detailFields().get("asUssd")).isEqualTo("hlr reject");
    }

    @Test
    void digest_asSkipIsHonestNo() {
        CdrRecord skip = row(CdrStatuses.GATED_AS_SKIP,
                "service=GatedAsNotifyService|skip=no-http-asUrl", 800L);
        var dig = CdrSessionDigest.from(skip, List.of(skip));
        assertThat(dig.asNotifySent().value()).contains("no");
        assertThat(dig.asResponse().value()).isEqualTo("unknown");
    }

    @Test
    void digest_endMeansAsToUeNotHopClose() {
        CdrRecord hop = row(Map2MapCdr.AS_ROUTED,
                "hopOutcome=close|asUssd-empty|phase=as-no-rearm|asRouted=true", 7000L);
        CdrRecord end = row("END",
                "service=VirtualSessionBridge|sync|asAction=END|asUssd=Thank you.|asLen=10|note=AS→UE",
                7000L);
        end.phase = "COMPLETED";
        var dig = CdrSessionDigest.from(end, List.of(end, hop));
        assertThat(dig.asResponse().value()).isEqualTo("yes / AS→UE");
        assertThat(dig.asResponse().evidence()).contains("asUssd=Thank you.");
        assertThat(dig.detailFields().get("asUssd")).isEqualTo("Thank you.");
    }

    @Test
    void digest_asUssdFromSessionColumnWhenDetailLacksKey() {
        CdrRecord end = row("END", "service=VirtualSessionBridge|sync|asAction=END|note=AS→UE", 7000L);
        end.phase = "COMPLETED";
        end.asUssd = "Balance is 12.50 ETB. Thank you for using Digicom.";
        var dig = CdrSessionDigest.from(end, List.of(end));
        assertThat(dig.detailFields().get("asUssd"))
                .isEqualTo("Balance is 12.50 ETB. Thank you for using Digicom.");
        assertThat(CdrUssdSnippet.resolveForDisplay(end.asUssd, dig.detailFields().get("asUssd")))
                .startsWith("Balance is 12.50 ETB.")
                .hasSizeLessThanOrEqualTo(CdrUssdSnippet.MAX_CHARS + 1);
    }

    @Test
    void digest_recoversAsUssdFromMangledSlashDetailInTimeline() {
        // Pre-fix events_json replaced '|' with '/' — column still has the text.
        CdrRecord focus = row("END", "service=VirtualSessionBridge|note=rolled", 25000L);
        focus.asUssd = "(xyz)";
        CdrRecord mangled = row("END",
                "service=VirtualSessionBridge/sync/asAction=END/asUssd=(xyz)/asLen=5/note=AS→UE",
                25000L);
        mangled.asUssd = "(xyz)";
        String restored = CdrSessionRollup.normalizeEventDetail(mangled.detail);
        mangled.detail = restored;
        var dig = CdrSessionDigest.from(focus, List.of(mangled, focus));
        assertThat(dig.detailFields().get("asUssd")).isEqualTo("(xyz)");
        assertThat(restored).contains("|asUssd=(xyz)|");
    }

    private static CdrRecord row(String status, String detail, Long gateMs) {
        CdrRecord r = new CdrRecord();
        r.correlationId = "corr-1";
        r.status = status;
        r.detail = detail;
        r.gateMs = gateMs;
        r.shortCode = status.startsWith("MAP2MAP") ? null : "*101";
        return r;
    }
}
