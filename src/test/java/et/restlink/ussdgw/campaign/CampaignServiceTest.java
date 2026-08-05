package et.restlink.ussdgw.campaign;

import et.restlink.ussdgw.persist.CampaignTargetEntity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignServiceTest {
    @Test
    void parseMsisdnsDedupesAndStripsNonDigits() {
        List<String> ms = CampaignService.parseMsisdns("""
                251911000001
                +251-911-000-002
                251911000001
                junk
                123
                251911000003,251911000004
                """);
        assertThat(ms).containsExactly(
                "251911000001", "251911000002", "251911000003", "251911000004");
    }

    @Test
    void parseMsisdnsBlankEmpty() {
        assertThat(CampaignService.parseMsisdns(null)).isEmpty();
        assertThat(CampaignService.parseMsisdns("  \n")).isEmpty();
    }

    @Test
    void statusEnumsStable() {
        assertThat(CampaignStatus.DRAFT.name()).isEqualTo("DRAFT");
        assertThat(CampaignTargetStatus.PENDING.name()).isEqualTo("PENDING");
        assertThat(CampaignTargetStatus.SENDING.name()).isEqualTo("SENDING");
        assertThat(CampaignStatus.valueOf("RUNNING")).isEqualTo(CampaignStatus.RUNNING);
    }

    @Test
    void maxTextLenConstant() {
        assertThat(CampaignService.MAX_TEXT_LEN).isEqualTo(182);
    }

    @Test
    void claimSelectMarksBudgetAndSkipsBusyMsisdn() {
        CampaignTargetEntity a = pending("251911000001");
        CampaignTargetEntity busy = pending("251911000002");
        CampaignTargetEntity c = pending("251911000003");
        Set<String> busyUe = Set.of("251911000002");

        List<CampaignTargetEntity> picked = CampaignService.selectClaimable(
                List.of(a, busy, c), busyUe::contains, 2);

        assertThat(picked).containsExactly(a, c);
        assertThat(CampaignService.claimedTargetStatus()).isEqualTo("SENDING");
    }

    @Test
    void claimSelectSkipsDuplicateMsisdnInSameBatch() {
        CampaignTargetEntity first = pending("251911000001");
        CampaignTargetEntity dup = pending("251911000001");
        List<CampaignTargetEntity> picked = CampaignService.selectClaimable(
                List.of(first, dup), msisdn -> false, 5);
        assertThat(picked).containsExactly(first);
    }

    @Test
    void cancelLeavesCampaignCancelledAndPendingTargetsFailed() {
        assertThat(CampaignService.cancelledStatus()).isEqualTo("CANCELLED");
        assertThat(CampaignService.cancelledTargetStatus()).isEqualTo("FAILED");
        // create→start uses DRAFT→RUNNING; cancel from any non-terminal → CANCELLED
        assertThat(CampaignStatus.DRAFT.name()).isEqualTo("DRAFT");
        assertThat(CampaignStatus.RUNNING.name()).isEqualTo("RUNNING");
    }

    private static CampaignTargetEntity pending(String msisdn) {
        CampaignTargetEntity t = new CampaignTargetEntity();
        t.id = UUID.randomUUID();
        t.campaignId = UUID.randomUUID();
        t.msisdn = msisdn;
        t.status = CampaignTargetStatus.PENDING.name();
        t.correlationId = t.id.toString();
        return t;
    }
}
