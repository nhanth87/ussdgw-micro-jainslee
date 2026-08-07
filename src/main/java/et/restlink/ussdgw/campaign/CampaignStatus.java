package et.restlink.ussdgw.campaign;

/** Campaign lifecycle status. */
public enum CampaignStatus {
    DRAFT,
    PENDING_APPROVAL,
    REJECTED,
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELLED
}
