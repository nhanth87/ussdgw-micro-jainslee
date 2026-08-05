package et.restlink.ussdgw.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "ussd_admin_user")
public class AdminUserEntity extends PanacheEntityBase {
    @Id
    @Column(length = 64)
    public String username;

    @Column(name = "password_hash", nullable = false, length = 256)
    public String passwordHash;

    /** ADMIN | OPS | TENANT */
    @Column(nullable = false, length = 16)
    public String role;

    @Column(name = "tenant_id", length = 128)
    public String tenantId;

    @Column(name = "display_name", length = 256)
    public String displayName;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();
}
