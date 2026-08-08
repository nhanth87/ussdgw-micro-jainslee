package et.restlink.ussdgw.cdr;

import et.restlink.ussdgw.persist.CdrEntity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * USSD CDR: file log (USSD_CDR) + PostgreSQL/H2 table via {@link CdrDbFlusher} (OTA pattern).
 * Hot path never blocks on DB when {@code ussd.cdr.db.async=true}.
 */
@ApplicationScoped
public class CdrService {
    private static final Logger CDR = LogManager.getLogger("USSD_CDR");
    private static final Logger LOG = LogManager.getLogger(CdrService.class);
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    @Inject CdrDbFlusher flusher;
    @Inject EntityManager em;

    @ConfigProperty(name = "ussd.cdr.enabled", defaultValue = "true")
    boolean enabled;
    @ConfigProperty(name = "ussd.cdr.db.async", defaultValue = "true")
    boolean asyncDb;
    @ConfigProperty(name = "ussd.cdr.network-id", defaultValue = "0")
    int defaultNetworkId;

    public void write(String correlationId, CdrPhase phase, String msisdn,
                      String shortCode, String status, String detail) {
        write(correlationId, phase, msisdn, shortCode, status, detail,
                defaultNetworkId, null, "MAP");
    }

    public void write(String correlationId, CdrPhase phase, String msisdn,
                      String shortCode, String status, String detail,
                      int networkId, String tenantId, String originationType) {
        write(correlationId, phase, msisdn, shortCode, status, detail,
                networkId, tenantId, originationType, null, null);
    }

    /**
     * Bridge-aware variant: stamps the adaptive gate that was applied and the EWMA that
     * produced it as real columns, so gate behaviour is queryable instead of only readable
     * in the free-text {@code detail}.
     */
    public void write(String correlationId, CdrPhase phase, String msisdn,
                      String shortCode, String status, String detail,
                      int networkId, String tenantId, String originationType,
                      Long gateMs, Long observedEwmaMs) {
        if (!enabled) return;
        String d = detail == null ? null : (detail.length() > 1000 ? detail.substring(0, 1000) : detail);
        String phaseName = phase == null ? "UNKNOWN" : phase.name();
        String st = status == null ? "UNKNOWN" : status;
        String csv = formatCsv(correlationId, phaseName, msisdn, shortCode, st, d, networkId, tenantId);
        CDR.info(csv);

        CdrEntity row = new CdrEntity();
        row.id = UUID.randomUUID();
        row.recordedAt = Instant.now();
        row.correlationId = correlationId == null ? "" : correlationId;
        row.phase = phaseName;
        row.status = st.length() > 64 ? st.substring(0, 64) : st;
        row.msisdn = msisdn;
        row.shortCode = shortCode;
        row.detail = d;
        row.networkId = networkId;
        row.tenantId = tenantId;
        row.originationType = originationType == null ? "MAP" : originationType;
        row.gateMs = gateMs;
        row.observedEwmaMs = observedEwmaMs;
        row.csvLine = csv.length() > 4000 ? csv.substring(0, 4000) : csv;

        try {
            if (asyncDb) {
                flusher.enqueue(row);
            } else {
                flusher.persistSync(row);
            }
        } catch (RuntimeException e) {
            LOG.warn("CDR persist failed corr={}: {}", correlationId, e.toString());
        }
    }

    /** Admin list — newest first. */
    @Transactional
    public List<CdrEntity> list(int limit) {
        return list(limit, null, null);
    }

    @Transactional
    public List<CdrEntity> list(int limit, String tenantId) {
        return list(limit, tenantId, null);
    }

    /**
     * Admin list with optional tenant scope and MSISDN filter (exact match after trim, OTA-shaped).
     */
    @Transactional
    public List<CdrEntity> list(int limit, String tenantId, String msisdn) {
        return list(limit, tenantId, msisdn, null);
    }

    /**
     * Admin list with optional tenant, MSISDN, and correlationId filters (exact match after trim).
     * Correlation filter matches classic bridge CDR workflow (S1/S2 legs share one id).
     */
    @Transactional
    public List<CdrEntity> list(int limit, String tenantId, String msisdn, String correlationId) {
        int lim = Math.min(Math.max(limit, 1), MAX_LIMIT);
        String tid = tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
        String msisdnFilter = normalizeMsisdnFilter(msisdn);
        String corrFilter = normalizeCorrFilter(correlationId);
        StringBuilder jpql = new StringBuilder("SELECT c FROM CdrEntity c WHERE 1=1");
        if (tid != null) {
            jpql.append(" AND c.tenantId = :tid");
        }
        if (msisdnFilter != null) {
            jpql.append(" AND c.msisdn = :m");
        }
        if (corrFilter != null) {
            jpql.append(" AND c.correlationId = :corr");
        }
        jpql.append(" ORDER BY c.recordedAt DESC");
        TypedQuery<CdrEntity> q = em.createQuery(jpql.toString(), CdrEntity.class);
        if (tid != null) {
            q.setParameter("tid", tid);
        }
        if (msisdnFilter != null) {
            q.setParameter("m", msisdnFilter);
        }
        if (corrFilter != null) {
            q.setParameter("corr", corrFilter);
        }
        q.setMaxResults(lim);
        return q.getResultList();
    }

    /** Backward-compatible view for admin HTML (phase/status fields). */
    public List<CdrRecord> listRecords(int limit) {
        return listRecords(limit, null, null, null);
    }

    public List<CdrRecord> listRecords(int limit, String tenantId) {
        return listRecords(limit, tenantId, null, null);
    }

    public List<CdrRecord> listRecords(int limit, String tenantId, String msisdn) {
        return listRecords(limit, tenantId, msisdn, null);
    }

    public List<CdrRecord> listRecords(int limit, String tenantId, String msisdn, String correlationId) {
        return list(limit, tenantId, msisdn, correlationId).stream().map(CdrRecord::fromEntity).toList();
    }

    /** Clamp limit for admin UI; blank/invalid → default. */
    public static int clampLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            int n = Integer.parseInt(raw.trim());
            if (n < 1) {
                return DEFAULT_LIMIT;
            }
            return Math.min(n, MAX_LIMIT);
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }

    static String normalizeMsisdnFilter(String msisdn) {
        if (msisdn == null) {
            return null;
        }
        String t = msisdn.trim();
        return t.isEmpty() ? null : t;
    }

    static String normalizeCorrFilter(String correlationId) {
        if (correlationId == null) {
            return null;
        }
        String t = correlationId.trim();
        return t.isEmpty() ? null : t;
    }

    static String formatCsv(String corr, String phase, String msisdn, String sc,
                            String status, String detail, int networkId, String tenantId) {
        return String.join("|",
                corr == null ? "" : corr,
                phase == null ? "" : phase,
                msisdn == null ? "" : msisdn,
                sc == null ? "" : sc,
                status == null ? "" : status,
                detail == null ? "" : detail.replace('|', '/'),
                Integer.toString(networkId),
                tenantId == null ? "" : tenantId);
    }
}
