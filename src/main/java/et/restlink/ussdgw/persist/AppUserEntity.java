package et.restlink.ussdgw.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** API-only app user under a tenant (NI push key + routing ownership). Not a portal login. */
@Entity
@Table(name = "ussd_app_user")
public class AppUserEntity extends PanacheEntityBase {
    @Id
    @Column(nullable = false, length = 64)
    public String username;

    @Column(name = "tenant_id", nullable = false, length = 64)
    public String tenantId;

    @Column(name = "api_key_hash", nullable = false, length = 128)
    public String apiKeyHash;

    @Column(name = "api_key_fp", length = 8)
    public String apiKeyFp;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
}
