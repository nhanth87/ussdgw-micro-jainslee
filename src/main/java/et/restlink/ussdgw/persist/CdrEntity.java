package et.restlink.ussdgw.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * USSD CDR row — OTA-shaped (UUID PK, async JDBC flusher, PostgreSQL-ready).
 */
@Entity
@Table(name = "ussd_cdr")
public class CdrEntity {
    @Id
    public UUID id;

    @Column(name = "recorded_at", nullable = false)
    public Instant recordedAt;

    @Column(name = "correlation_id", nullable = false, length = 128)
    public String correlationId;

    @Column(nullable = false, length = 32)
    public String phase;

    @Column(nullable = false, length = 64)
    public String status;

    @Column(length = 32)
    public String msisdn;

    @Column(name = "short_code", length = 32)
    public String shortCode;

    @Column(length = 1024)
    public String detail;

    @Column(name = "network_id")
    public Integer networkId;

    @Column(name = "tenant_id", length = 128)
    public String tenantId;

    @Column(name = "origination_type", length = 32)
    public String originationType;

    /** Adaptive gate applied to this leg (ms), when the row belongs to a gated phase. */
    @Column(name = "gate_ms")
    public Long gateMs;

    /** EWMA of AS latency for {@link #networkId} at the time of the row (ms). */
    @Column(name = "observed_ewma_ms")
    public Long observedEwmaMs;

    @Column(name = "csv_line", nullable = false, length = 4000)
    public String csvLine;
}
