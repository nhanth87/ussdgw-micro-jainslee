package et.restlink.ussdgw.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ussd_campaign_target",
        uniqueConstraints = @UniqueConstraint(name = "uk_ussd_camp_msisdn",
                columnNames = {"campaign_id", "msisdn"}))
public class CampaignTargetEntity extends PanacheEntityBase {
    @Id
    public UUID id;

    @Column(name = "campaign_id", nullable = false)
    public UUID campaignId;

    @Column(nullable = false, length = 32)
    public String msisdn;

    @Column(nullable = false, length = 16)
    public String status = "PENDING";

    @Column(name = "correlation_id", length = 128)
    public String correlationId;

    @Column(length = 512)
    public String error;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();
}
