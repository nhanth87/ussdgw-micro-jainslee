package et.restlink.ussdgw.cdr;

import et.restlink.ussdgw.persist.CdrEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin/UI view of a CDR row (maps from {@link CdrEntity}).
 */
public final class CdrRecord {
    public UUID id;
    public Instant createdAt;
    public String correlationId;
    public String phase;
    public String msisdn;
    public String shortCode;
    public String status;
    public String detail;
    public Integer networkId;
    public String tenantId;
    public String originationType;
    public Long gateMs;
    public Long observedEwmaMs;

    public static CdrRecord fromEntity(CdrEntity e) {
        CdrRecord r = new CdrRecord();
        if (e == null) return r;
        r.id = e.id;
        r.createdAt = e.recordedAt;
        r.correlationId = e.correlationId;
        r.phase = e.phase;
        r.msisdn = e.msisdn;
        r.shortCode = e.shortCode;
        r.status = e.status;
        r.detail = e.detail;
        r.networkId = e.networkId;
        r.tenantId = e.tenantId;
        r.originationType = e.originationType;
        r.gateMs = e.gateMs;
        r.observedEwmaMs = e.observedEwmaMs;
        return r;
    }
}
