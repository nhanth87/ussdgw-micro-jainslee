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

    /**
     * Transition mirror of {@code !rerouteEnable}. Prefer {@link #rerouteEnable}.
     * Kept so older readers / V9 rows remain consistent after V10.
     */
    @Column(nullable = false)
    public boolean bypass = true;

    /**
     * When {@code true} and {@link #map2mapGt} is set, {@code Map2MapSbb} hops first
     * (redirect USSD string on UnstructuredSS-Request). Default {@code false} = direct to
     * {@link #asUrl}.
     */
    @Column(name = "reroute_enable", nullable = false)
    public boolean rerouteEnable = false;

    /**
     * Redirect USSD string for MAP2MAP hop (e.g. {@code *8744#} or {@code 8744}).
     * Column name {@code map2map_gt} kept for compat — value is USSD text, not SCCP GT.
     * Null/blank = no hop even when {@link #rerouteEnable} is true.
     */
    @Column(name = "map2map_gt", length = 64)
    public String map2mapGt;

    /**
     * Optional per-rule HLR face override: {@code FAKE}, {@code PROXY_MAP}, …
     * Null / blank / {@code INHERIT} → use HLR Face global.
     */
    @Column(name = "hlr_mode", length = 32)
    public String hlrMode;

    /**
     * Optional MAP2MAP fixed hop SCCP CalledParty GT. When set, skip SRI/FAKE and
     * address this GT directly (USSD string still from {@link #map2mapGt}).
     */
    @Column(name = "hop_dest_gt", length = 32)
    public String hopDestGt;

    /**
     * Optional MAP2MAP fixed hop CalledParty SSN. Null with {@link #hopDestGt} set →
     * runtime default 6 (HLR face peer style). Ignored when hop dest GT blank.
     */
    @Column(name = "hop_dest_ssn")
    public Integer hopDestSsn;
}
