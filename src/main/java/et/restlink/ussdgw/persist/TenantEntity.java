package et.restlink.ussdgw.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "ussd_tenant")
public class TenantEntity extends PanacheEntityBase {
    @Id
    @Column(name = "tenant_id", length = 128)
    public String tenantId;

    @Column(name = "display_name", length = 256)
    public String displayName;

    @Column(name = "network_id", nullable = false)
    public int networkId;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "http_api_key", length = 256)
    public String httpApiKey;

    @Column(name = "smpp_system_id", length = 128)
    public String smppSystemId;

    /** Write-only from admin; never shown in list. */
    @Column(name = "smpp_password", length = 256)
    public String smppPassword;

    @Column(name = "as_callback_base", length = 1024)
    public String asCallbackBase;

    @Column(name = "max_tps", nullable = false)
    public int maxTps = 50;

    /** HTTP AS pull/push body format: {@code XML} (default) or {@code JSON}. */
    @Column(name = "http_as_wire_format", length = 8, nullable = false)
    public String httpAsWireFormat = "XML";

    /** Preferred AS-facing SIP trunk for MO pull / NI push over SIP. */
    @Column(name = "sip_trunk_id", length = 64)
    public String sipTrunkId;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();
}
