package et.restlink.ussdgw.cdr;

import et.restlink.ussdgw.persist.CdrEntity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * USSD CDR: file log ({@code USSD_CDR}, append-only) + session ledger upsert via
 * {@link CdrDbFlusher} ({@code ussd_cdr_session}, 1 corr → 1 row).
 * Hot path never blocks on DB when {@code ussd.cdr.db.async=true}.
 */
@ApplicationScoped
public class CdrService {
    private static final Logger CDR = LogManager.getLogger("USSD_CDR");
    private static final Logger LOG = LogManager.getLogger(CdrService.class);
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;
    /** Extra legacy event rows scanned when dual-reading pre-session tape. */
    static final int LEGACY_SCAN_MULTIPLIER = 8;

    @Inject CdrDbFlusher flusher;
    @Inject EntityManager em;
    @Inject DataSource dataSource;

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
        String csv = formatCsv(correlationId, phaseName, msisdn, shortCode, st, d,
                networkId, tenantId, gateMs, observedEwmaMs);
        CDR.info(csv);

        Instant now = Instant.now();
        CdrEntity row = new CdrEntity();
        row.id = UUID.randomUUID();
        row.recordedAt = now;
        row.startedAt = now;
        row.updatedAt = now;
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
        var kv = CdrSessionDigest.parseDetail(d);
        row.hopOutcome = clip(kv.get("hopOutcome"), 32);
        row.refuseReason = clip(kv.get("refuseReason"), 128);
        row.asUssd = clip(kv.get("asUssd"), 256);
        row.csvLine = csv.length() > 4000 ? csv.substring(0, 4000) : csv;
        row.eventCount = 1;

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

    private static String clip(String v, int max) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String t = v.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    /** Admin list — newest first (session ledger, 1 row/corr). */
    @Transactional
    public List<CdrEntity> list(int limit) {
        return list(limit, null, null);
    }

    @Transactional
    public List<CdrEntity> list(int limit, String tenantId) {
        return list(limit, tenantId, null);
    }

    @Transactional
    public List<CdrEntity> list(int limit, String tenantId, String msisdn) {
        return list(limit, tenantId, msisdn, null);
    }

    @Transactional
    public List<CdrEntity> list(int limit, String tenantId, String msisdn, String correlationId) {
        return list(limit, tenantId, msisdn, correlationId, null);
    }

    /**
     * Admin list + optional status filter. Status: exact (case-insensitive) or trailing
     * {@code *} prefix ({@code MAP2MAP_*}, {@code GATED*}) — matches <em>rolled-up</em>
     * session status (v1; event-status filter later).
     * Dual-reads legacy {@code ussd_cdr} (DISTINCT newest per corr) when session row missing.
     */
    @Transactional
    public List<CdrEntity> list(int limit, String tenantId, String msisdn, String correlationId,
                                String status) {
        int lim = Math.min(Math.max(limit, 1), MAX_LIMIT);
        String tid = tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
        String msisdnFilter = normalizeMsisdnFilter(msisdn);
        String corrFilter = normalizeCorrFilter(correlationId);
        StatusFilter stFilter = normalizeStatusFilter(status);

        List<CdrEntity> sessions = querySessionLedger(lim, tid, msisdnFilter, corrFilter, stFilter);
        List<CdrEntity> legacy = queryLegacyDistinct(lim * LEGACY_SCAN_MULTIPLIER,
                tid, msisdnFilter, corrFilter, stFilter);
        return mergeSessionPreferring(sessions, legacy, lim);
    }

    private List<CdrEntity> querySessionLedger(int lim, String tid, String msisdnFilter,
                                               String corrFilter, StatusFilter stFilter) {
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
        if (stFilter != null) {
            if (stFilter.prefix()) {
                jpql.append(" AND UPPER(c.status) LIKE :st");
            } else {
                jpql.append(" AND UPPER(c.status) = :st");
            }
        }
        jpql.append(" ORDER BY c.updatedAt DESC");
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
        if (stFilter != null) {
            q.setParameter("st", stFilter.pattern());
        }
        q.setMaxResults(lim);
        return q.getResultList();
    }

    /**
     * Newest event row per correlation_id from legacy append-only {@code ussd_cdr}.
     * Portable (H2 + PG): scan newest-first and keep first sighting of each corr.
     */
    List<CdrEntity> queryLegacyDistinct(int scanLimit, String tid, String msisdnFilter,
                                        String corrFilter, StatusFilter stFilter) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, recorded_at, correlation_id, phase, status, msisdn, short_code, detail, "
                        + "network_id, tenant_id, origination_type, gate_ms, observed_ewma_ms, "
                        + "hop_outcome, refuse_reason, as_ussd, csv_line "
                        + "FROM ussd_cdr WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (tid != null) {
            sql.append(" AND tenant_id = ?");
            params.add(tid);
        }
        if (msisdnFilter != null) {
            sql.append(" AND msisdn = ?");
            params.add(msisdnFilter);
        }
        if (corrFilter != null) {
            sql.append(" AND correlation_id = ?");
            params.add(corrFilter);
        }
        if (stFilter != null) {
            if (stFilter.prefix()) {
                sql.append(" AND UPPER(status) LIKE ?");
            } else {
                sql.append(" AND UPPER(status) = ?");
            }
            params.add(stFilter.pattern());
        }
        sql.append(" ORDER BY recorded_at DESC");

        Map<String, CdrEntity> byCorr = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ps.setMaxRows(Math.max(scanLimit, 1));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && byCorr.size() < scanLimit) {
                    String corr = rs.getString("correlation_id");
                    if (corr == null) {
                        corr = "";
                    }
                    if (byCorr.containsKey(corr)) {
                        continue;
                    }
                    byCorr.put(corr, readLegacyRow(rs));
                }
            }
        } catch (SQLException e) {
            // Table missing on brand-new lab before any legacy writes — session ledger only.
            LOG.debug("legacy ussd_cdr dual-read skipped: {}", e.toString());
        }
        return new ArrayList<>(byCorr.values());
    }

    static List<CdrEntity> mergeSessionPreferring(List<CdrEntity> sessions,
                                                  List<CdrEntity> legacy,
                                                  int lim) {
        Map<String, CdrEntity> byCorr = new LinkedHashMap<>();
        if (sessions != null) {
            for (CdrEntity s : sessions) {
                if (s == null) {
                    continue;
                }
                String corr = s.correlationId == null ? "" : s.correlationId;
                byCorr.put(corr, s);
            }
        }
        if (legacy != null) {
            for (CdrEntity leg : legacy) {
                if (leg == null) {
                    continue;
                }
                String corr = leg.correlationId == null ? "" : leg.correlationId;
                byCorr.putIfAbsent(corr, leg);
            }
        }
        List<CdrEntity> all = new ArrayList<>(byCorr.values());
        all.sort(Comparator.comparing(
                (CdrEntity e) -> e.updatedAt != null ? e.updatedAt
                        : (e.recordedAt != null ? e.recordedAt : Instant.EPOCH),
                Comparator.reverseOrder()));
        if (all.size() <= lim) {
            return all;
        }
        return all.subList(0, lim);
    }

    private static CdrEntity readLegacyRow(ResultSet rs) throws SQLException {
        CdrEntity e = new CdrEntity();
        Object id = rs.getObject("id");
        if (id instanceof UUID u) {
            e.id = u;
        } else if (id != null) {
            e.id = UUID.fromString(id.toString());
        }
        Timestamp rec = rs.getTimestamp("recorded_at");
        Instant at = rec == null ? Instant.now() : rec.toInstant();
        e.recordedAt = at;
        e.startedAt = at;
        e.updatedAt = at;
        e.correlationId = rs.getString("correlation_id");
        e.phase = rs.getString("phase");
        e.status = rs.getString("status");
        e.msisdn = rs.getString("msisdn");
        e.shortCode = rs.getString("short_code");
        e.detail = rs.getString("detail");
        int net = rs.getInt("network_id");
        e.networkId = rs.wasNull() ? null : net;
        e.tenantId = rs.getString("tenant_id");
        e.originationType = rs.getString("origination_type");
        long gate = rs.getLong("gate_ms");
        e.gateMs = rs.wasNull() ? null : gate;
        long ewma = rs.getLong("observed_ewma_ms");
        e.observedEwmaMs = rs.wasNull() ? null : ewma;
        try {
            e.hopOutcome = rs.getString("hop_outcome");
            e.refuseReason = rs.getString("refuse_reason");
            e.asUssd = rs.getString("as_ussd");
        } catch (SQLException ignored) {
            // pre-V12 local
        }
        e.csvLine = rs.getString("csv_line");
        e.eventCount = null;
        e.eventsJson = null;
        return e;
    }

    /** Backward-compatible view for admin HTML (phase/status fields). */
    public List<CdrRecord> listRecords(int limit) {
        return listRecords(limit, null, null, null, null);
    }

    public List<CdrRecord> listRecords(int limit, String tenantId) {
        return listRecords(limit, tenantId, null, null, null);
    }

    public List<CdrRecord> listRecords(int limit, String tenantId, String msisdn) {
        return listRecords(limit, tenantId, msisdn, null, null);
    }

    public List<CdrRecord> listRecords(int limit, String tenantId, String msisdn, String correlationId) {
        return listRecords(limit, tenantId, msisdn, correlationId, null);
    }

    public List<CdrRecord> listRecords(int limit, String tenantId, String msisdn,
                                       String correlationId, String status) {
        return list(limit, tenantId, msisdn, correlationId, status).stream()
                .map(e -> {
                    CdrRecord r = CdrRecord.fromEntity(e);
                    r.legacyEventTape = e.eventsJson == null && e.eventCount == null;
                    return r;
                }).toList();
    }

    /**
     * Expand timeline for a ledger row: {@code events_json} when present; else legacy
     * sibling rows from {@code ussd_cdr} (historical multi-row corrs).
     */
    public List<CdrRecord> timelineFor(CdrRecord focus, String tenantScope, int legacyLimit) {
        if (focus == null) {
            return List.of();
        }
        if (focus.eventsJson != null && !focus.eventsJson.isBlank()) {
            CdrEntity fake = new CdrEntity();
            fake.id = focus.id;
            fake.correlationId = focus.correlationId;
            fake.eventsJson = focus.eventsJson;
            fake.eventCount = focus.eventCount;
            fake.msisdn = focus.msisdn;
            fake.shortCode = focus.shortCode;
            fake.gateMs = focus.gateMs;
            fake.observedEwmaMs = focus.observedEwmaMs;
            fake.hopOutcome = focus.hopOutcome;
            fake.refuseReason = focus.refuseReason;
            fake.asUssd = focus.asUssd;
            fake.networkId = focus.networkId;
            fake.tenantId = focus.tenantId;
            fake.originationType = focus.originationType;
            return CdrSessionRollup.timelineFromEvents(fake);
        }
        // Legacy tape: all rows for corr (newest-first query → reverse in digest)
        return listLegacyTimeline(focus.correlationId, tenantScope, legacyLimit);
    }

    List<CdrRecord> listLegacyTimeline(String correlationId, String tenantScope, int limit) {
        String corr = normalizeCorrFilter(correlationId);
        if (corr == null) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT id, recorded_at, correlation_id, phase, status, msisdn, short_code, detail, "
                        + "network_id, tenant_id, origination_type, gate_ms, observed_ewma_ms, "
                        + "hop_outcome, refuse_reason, as_ussd, csv_line "
                        + "FROM ussd_cdr WHERE correlation_id = ?");
        if (tenantScope != null && !tenantScope.isBlank()) {
            sql.append(" AND tenant_id = ?");
        }
        sql.append(" ORDER BY recorded_at DESC");
        List<CdrRecord> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            ps.setString(1, corr);
            if (tenantScope != null && !tenantScope.isBlank()) {
                ps.setString(2, tenantScope.trim());
            }
            ps.setMaxRows(Math.max(limit, 1));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CdrRecord r = CdrRecord.fromEntity(readLegacyRow(rs));
                    r.legacyEventTape = true;
                    out.add(r);
                }
            }
        } catch (SQLException e) {
            LOG.debug("legacy timeline skipped corr={}: {}", corr, e.toString());
        }
        return out;
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

    /**
     * Parse admin status filter. Trailing {@code *} → SQL {@code LIKE} prefix (uppercased).
     * Catalog statuses use {@code _} as a literal separator; LIKE {@code _} is one-char
     * wildcard but still matches our {@code MAP2MAP_*} / {@code GATE_*} rows in practice.
     */
    static StatusFilter normalizeStatusFilter(String status) {
        if (status == null) {
            return null;
        }
        String t = status.trim();
        if (t.isEmpty()) {
            return null;
        }
        boolean prefix = t.endsWith("*");
        String body = prefix ? t.substring(0, t.length() - 1) : t;
        if (body.isEmpty()) {
            return null;
        }
        String upper = body.toUpperCase(Locale.ROOT);
        if (prefix) {
            return new StatusFilter(upper + "%", true);
        }
        return new StatusFilter(upper, false);
    }

    record StatusFilter(String pattern, boolean prefix) {}

    static String formatCsv(String corr, String phase, String msisdn, String sc,
                            String status, String detail, int networkId, String tenantId) {
        return formatCsv(corr, phase, msisdn, sc, status, detail, networkId, tenantId, null, null);
    }

    static String formatCsv(String corr, String phase, String msisdn, String sc,
                            String status, String detail, int networkId, String tenantId,
                            Long gateMs, Long observedEwmaMs) {
        return String.join("|",
                corr == null ? "" : corr,
                phase == null ? "" : phase,
                msisdn == null ? "" : msisdn,
                sc == null ? "" : sc,
                status == null ? "" : status,
                detail == null ? "" : detail.replace('|', '/'),
                Integer.toString(networkId),
                tenantId == null ? "" : tenantId,
                gateMs == null ? "" : Long.toString(gateMs),
                observedEwmaMs == null ? "" : Long.toString(observedEwmaMs));
    }
}
