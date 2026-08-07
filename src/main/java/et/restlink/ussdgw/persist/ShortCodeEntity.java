package et.restlink.ussdgw.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ussd_short_code")
public class ShortCodeEntity extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "short_code", nullable = false, unique = true, length = 32)
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
}
