package et.restlink.ussdgw.cdr.clickhouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microjainslee.ra.clickhouse.ClickHouseHttp;
import et.restlink.ussdgw.cdr.CdrFileLedger;
import et.restlink.ussdgw.persist.CdrEntity;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Admin CDR reads from ClickHouse: newest sessions first, filters by tenant / MSISDN /
 * correlation in SQL, status (a rolled-up property) after the rollup.
 *
 * <p>"Newest N sessions" over months of events is expensive if scanned in one go, so the
 * look-back widens — 1 h, 24 h, 7 days, then the whole retention — and stops as soon as
 * enough sessions are found. An MSISDN or correlation filter goes straight to the whole
 * retention: the bloom-filter skip indexes make that cheap.</p>
 */
public final class CdrClickHouseReader {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter CH_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]");
    private static final long[] WINDOWS_HOURS = {1, 24, 24 * 7};

    private final ClickHouseHttp ch;
    private final String table;
    private final int retentionMonths;

    public CdrClickHouseReader(ClickHouseHttp ch, String qualifiedTable, int retentionMonths) {
        this.ch = ch;
        this.table = qualifiedTable;
        this.retentionMonths = retentionMonths;
    }

    public List<CdrEntity> sessions(int limit, String tenantId, String msisdn, String correlationId,
                                    Predicate<CdrEntity> statusFilter, boolean statusFiltered)
            throws IOException {
        int want = Math.max(1, limit);
        // Status is decided by the rollup, so over-fetch candidates when it filters.
        int candidates = statusFiltered ? Math.min(want * 4, 20_000) : want;
        boolean pointLookup = msisdn != null || correlationId != null;
        List<CdrEntity> sessions = List.of();
        if (!pointLookup) {
            for (long hours : WINDOWS_HOURS) {
                sessions = fetch("toIntervalHour(" + hours + ")", candidates, tenantId, msisdn,
                        correlationId, statusFilter);
                if (sessions.size() >= want) {
                    return sessions.subList(0, want);
                }
            }
        }
        sessions = fetch("toIntervalMonth(" + retentionMonths + ")", candidates, tenantId, msisdn,
                correlationId, statusFilter);
        return sessions.size() > want ? sessions.subList(0, want) : sessions;
    }

    private List<CdrEntity> fetch(String window, int candidates, String tenantId, String msisdn,
                                  String correlationId, Predicate<CdrEntity> statusFilter)
            throws IOException {
        StringBuilder where = new StringBuilder("recorded_at >= now64(3) - " + window);
        Map<String, String> params = new LinkedHashMap<>();
        if (tenantId != null) {
            where.append(" AND tenant_id = {tid:String}");
            params.put("tid", tenantId);
        }
        if (msisdn != null) {
            where.append(" AND msisdn = {msisdn:String}");
            params.put("msisdn", msisdn);
        }
        if (correlationId != null) {
            where.append(" AND correlation_id = {corr:String}");
            params.put("corr", correlationId);
        }
        params.put("n", Integer.toString(candidates));
        String sql = """
                SELECT event_id, recorded_at, correlation_id, phase, status, msisdn, short_code,
                       detail, network_id, tenant_id, origination_type, gate_ms, observed_ewma_ms,
                       hop_outcome, refuse_reason, as_ussd, csv_line
                FROM %1$s
                WHERE %2$s AND correlation_id IN (
                    SELECT correlation_id FROM %1$s WHERE %2$s
                    GROUP BY correlation_id ORDER BY max(recorded_at) DESC LIMIT {n:UInt32})
                ORDER BY correlation_id, recorded_at, event_id
                FORMAT JSONEachRow
                """.formatted(table, where);
        Map<String, List<CdrEntity>> byCorr = new LinkedHashMap<>();
        for (String line : ch.query(sql, params)) {
            CdrEntity e = parse(JSON.readTree(line));
            byCorr.computeIfAbsent(e.correlationId, k -> new ArrayList<>(8)).add(e);
        }
        List<CdrEntity> out = new ArrayList<>(byCorr.size());
        for (Map.Entry<String, List<CdrEntity>> e : byCorr.entrySet()) {
            CdrEntity s = CdrFileLedger.rollUpSession(e.getKey(), e.getValue());
            if (s != null && (statusFilter == null || statusFilter.test(s))) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparing(CdrFileLedger::sessionNewestAt).reversed());
        return out;
    }

    static CdrEntity parse(JsonNode n) {
        CdrEntity e = new CdrEntity();
        e.id = uuid(text(n, "event_id"));
        e.recordedAt = instant(text(n, "recorded_at"));
        e.startedAt = e.recordedAt;
        e.updatedAt = e.recordedAt;
        e.correlationId = text(n, "correlation_id");
        e.phase = text(n, "phase");
        e.status = text(n, "status");
        e.msisdn = blankToNull(text(n, "msisdn"));
        e.shortCode = blankToNull(text(n, "short_code"));
        e.detail = blankToNull(text(n, "detail"));
        e.networkId = n.path("network_id").asInt(0);
        e.tenantId = blankToNull(text(n, "tenant_id"));
        e.originationType = blankToNull(text(n, "origination_type"));
        e.gateMs = longOrNull(n.get("gate_ms"));
        e.observedEwmaMs = longOrNull(n.get("observed_ewma_ms"));
        e.hopOutcome = blankToNull(text(n, "hop_outcome"));
        e.refuseReason = blankToNull(text(n, "refuse_reason"));
        e.asUssd = blankToNull(text(n, "as_ussd"));
        e.csvLine = text(n, "csv_line");
        e.eventCount = 1;
        return e;
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? "" : v.asText();
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static Long longOrNull(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        // ClickHouse quotes 64-bit integers in JSON by default.
        return v.isNumber() ? v.asLong() : Long.parseLong(v.asText());
    }

    private static UUID uuid(String s) {
        try {
            return s.isEmpty() ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** DateTime64(3,'UTC') comes back as "2026-11-15 04:00:00.123". */
    static Instant instant(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        return LocalDateTime.parse(s, CH_TS).toInstant(ZoneOffset.UTC);
    }
}
