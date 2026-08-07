package et.restlink.ussdgw.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ussd_campaign")
public class CampaignEntity extends PanacheEntityBase {
    @Id
    public UUID id;

    @Column(name = "tenant_id", length = 128)
    public String tenantId;

    @Column(nullable = false, length = 256)
    public String name;

    @Column(name = "ussd_text", nullable = false, length = 182)
    public String text;

    @Column(nullable = false, length = 16)
    public String alphabet = "AUTO";

    @Column(name = "network_id", nullable = false)
    public int networkId;

    @Column(nullable = false, length = 16)
    public String status = "DRAFT";

    @Column(name = "max_tps", nullable = false)
    public int maxTps = 5;

    @Column(name = "sent_count", nullable = false)
    public int sentCount;

    @Column(name = "fail_count", nullable = false)
    public int failCount;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    @Column(name = "created_by", length = 64)
    public String createdBy;

    @Column(name = "submitted_at")
    public Instant submittedAt;

    @Column(name = "reviewed_by", length = 64)
    public String reviewedBy;

    @Column(name = "review_note", length = 512)
    public String reviewNote;
}
