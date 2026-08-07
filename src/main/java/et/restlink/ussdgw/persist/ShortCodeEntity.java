package et.restlink.ussdgw.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "ussd_short_code",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ussd_short_code_app",
                columnNames = {"short_code", "app_username"}))
public class ShortCodeEntity extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "short_code", nullable = false, length = 32)
    public String shortCode;

    @Column(name = "rule_type", nullable = false, length = 16)
    public String ruleType;

    @Column(name = "as_url", nullable = false, length = 512)
    public String asUrl;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "tenant_id", length = 128)
    public String tenantId;

    @Column(name = "network_id", nullable = false)
    public int networkId;

    /** Prefix / mark key — when true, dialed USSD that startsWith(shortCode) matches. */
    @Column(nullable = false)
    public boolean mark = false;

    /**
     * Optional API app-user that owns this rule (NI preference / CDR stamp).
     * Empty string = unbound / MO-default (composite unique with {@code short_code}).
     */
    @Column(name = "app_username", nullable = false, length = 64)
    public String appUsername = "";
}
