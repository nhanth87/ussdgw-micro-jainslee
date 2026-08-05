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
        return list(limit, null);
    }

    @Transactional
    public List<CdrEntity> list(int limit, String tenantId) {
        int lim = Math.min(Math.max(limit, 1), MAX_LIMIT);
        TypedQuery<CdrEntity> q;
        if (tenantId != null && !tenantId.isBlank()) {
            q = em.createQuery(
                    "SELECT c FROM CdrEntity c WHERE c.tenantId = :tid ORDER BY c.recordedAt DESC",
                    CdrEntity.class);
            q.setParameter("tid", tenantId.trim());
        } else {
            q = em.createQuery(
                    "SELECT c FROM CdrEntity c ORDER BY c.recordedAt DESC", CdrEntity.class);
        }
        q.setMaxResults(lim);
        return q.getResultList();
    }

    /** Backward-compatible view for admin HTML (phase/status fields). */
    public List<CdrRecord> listRecords(int limit) {
        return listRecords(limit, null);
    }

    public List<CdrRecord> listRecords(int limit, String tenantId) {
        return list(limit, tenantId).stream().map(CdrRecord::fromEntity).toList();
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
