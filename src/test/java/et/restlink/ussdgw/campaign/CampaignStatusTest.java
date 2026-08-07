package et.restlink.ussdgw.campaign;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignStatusTest {

    @Test
    void approvalStatesExist() {
        assertThat(CampaignStatus.PENDING_APPROVAL.name()).isEqualTo("PENDING_APPROVAL");
        assertThat(CampaignStatus.REJECTED.name()).isEqualTo("REJECTED");
        assertThat(CampaignStatus.values()).contains(
                CampaignStatus.DRAFT, CampaignStatus.PENDING_APPROVAL, CampaignStatus.REJECTED,
                CampaignStatus.RUNNING, CampaignStatus.PAUSED, CampaignStatus.COMPLETED,
                CampaignStatus.CANCELLED);
    }
}
