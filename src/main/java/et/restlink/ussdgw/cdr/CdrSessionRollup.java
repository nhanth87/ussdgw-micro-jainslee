package et.restlink.ussdgw.cdr;

import et.restlink.ussdgw.persist.CdrEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Session-ledger rollup: N hot-path milestones → one {@code ussd_cdr_session} snapshot.
 * Precedence (locked): terminal fail &gt; terminal success &gt; latest in-flight.
 * {@link Map2MapCdr#HOP_CLOSE} is <em>not</em> terminal fail (AS may still route → END).
 */
public final class CdrSessionRollup {
    public static final int MAX_EVENTS = 32;
    /** Soft cap on serialized events_json (~8 KiB column). */
    public static final int MAX_EVENTS_JSON_CHARS = 8000;
    public static final int MAX_DETAIL_CHARS = 1024;

    private CdrSessionRollup() {}

    public enum Rank {
        IN_FLIGHT(0),
        TERMINAL_SUCCESS(1),
        TERMINAL_FAIL(2);

        private final int weight;

        Rank(int weight) {
            this.weight = weight;
        }

        public int weight() {
            return weight;
        }
    }

    public record Event(Instant t, String phase, String status, String detail) {}

    public static Rank rank(String status, String phase) {
        if (isTerminalFail(status)) {
            return Rank.TERMINAL_FAIL;
        }
        if (isTerminalSuccess(status, phase)) {
            return Rank.TERMINAL_SUCCESS;
        }
        return Rank.IN_FLIGHT;
    }

    /**
     * Terminal fail family. Explicitly excludes {@link Map2MapCdr#HOP_CLOSE}
     * (amber hop-text; session continues to AS_ROUTED / END).
     */
    public static boolean isTerminalFail(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String u = status.trim().toUpperCase(Locale.ROOT);
        if (u.equals(Map2MapCdr.HOP_CLOSE)) {
            return false;
        }
        if (u.equals(Map2MapCdr.HOP_FAIL)
                || u.equals(Map2MapCdr.HOP_ABORT)
                || u.equals(Map2MapCdr.TIMEOUT)
                || u.equals(Map2MapCdr.TIMEOUT_AFTER_BRIDGE)
                || u.equals(Map2MapCdr.AS_ROUTE_FAIL)
                || u.equals(Map2MapCdr.HLR_REJECT)
                || u.equals(CdrStatuses.AS_EMPTY_BODY)
                || u.equals(CdrStatuses.AS_PULL_FAIL)
                || u.equals(CdrStatuses.GATE_NO_BRIDGE)
                || u.equals("ABORT")
                || u.equals("ZOMBIE")
                || u.equals("SRI_TIMEOUT")
                || u.equals("SRI_NO_MSC")
                || u.equals("NI_NO_MSC")
                || u.equals("HLR_PROXY_TIMEOUT")
                || u.equals("HLR_PROXY_FAIL")
                || u.equals("HLR_DIAM_FAIL")
                || u.equals("HLR_FAKE_MISCONFIG")) {
            return true;
        }
        return u.contains("TIMEOUT")
                || u.contains("FAIL")
                || u.endsWith("_ABORT")
                || u.equals("ABORT");
    }

    public static boolean isTerminalSuccess(String status, String phase) {
        if (status == null || status.isBlank()) {
            return "COMPLETED".equals(phase);
        }
        String u = status.trim().toUpperCase(Locale.ROOT);
        if (u.equals("END")
                || u.equals(CdrStatuses.BRIDGED_DONE)
                || u.equals("SUCCESS")
                || u.equals("HLR_PROXY_OK")
                || u.equals("HLR_DIAM_OK")
                || u.equals("HLR_FAKE_OK")) {
            return true;
        }
        return "COMPLETED".equals(phase)
                && !isTerminalFail(status)
                && !u.equals(Map2MapCdr.HOP_CLOSE);
    }

    /** Merge a hot-path delta into an in-memory session snapshot (same corr). */
    public static CdrEntity merge(CdrEntity into, CdrEntity delta) {
        if (delta == null) {
            return into;
        }
        if (into == null) {
            return seed(delta);
        }
        Instant deltaAt = delta.recordedAt != null ? delta.recordedAt : Instant.now();
        if (into.startedAt == null
                || (deltaAt != null && into.startedAt != null && deltaAt.isBefore(into.startedAt))) {
            into.startedAt = deltaAt;
        }
        Instant updated = deltaAt;
        if (into.updatedAt == null || (updated != null && updated.isAfter(into.updatedAt))) {
            into.updatedAt = updated;
            into.recordedAt = updated;
        }

        List<Event> events = parseEvents(into.eventsJson);
        events.add(new Event(deltaAt, delta.phase, delta.status, clipDetail(delta.detail)));
        events = capEvents(events);
        into.eventCount = (into.eventCount == null ? 0 : into.eventCount) + 1;
        into.eventsJson = serializeEvents(events);

        Rank intoRank = rank(into.status, into.phase);
        Rank deltaRank = rank(delta.status, delta.phase);
        boolean takeStatus = deltaRank.weight() > intoRank.weight()
                || (deltaRank == Rank.IN_FLIGHT && intoRank == Rank.IN_FLIGHT);
        if (takeStatus) {
            into.phase = delta.phase;
            into.status = delta.status;
        }

        into.detail = mergeDetail(into.detail, delta.detail);
        if (delta.msisdn != null && !delta.msisdn.isBlank()) {
            into.msisdn = delta.msisdn;
        }
        if (delta.shortCode != null && !delta.shortCode.isBlank()) {
            into.shortCode = delta.shortCode;
        }
        if (delta.tenantId != null && !delta.tenantId.isBlank()) {
            into.tenantId = delta.tenantId;
        }
        if (delta.originationType != null && !delta.originationType.isBlank()) {
            into.originationType = delta.originationType;
        }
        if (delta.networkId != null) {
            into.networkId = delta.networkId;
        }
        if (delta.gateMs != null && delta.gateMs > 0) {
            into.gateMs = delta.gateMs;
        }
        if (delta.observedEwmaMs != null && delta.observedEwmaMs > 0) {
            into.observedEwmaMs = delta.observedEwmaMs;
        }
        if (delta.hopOutcome != null && !delta.hopOutcome.isBlank()) {
            into.hopOutcome = delta.hopOutcome;
        }
        if (delta.refuseReason != null && !delta.refuseReason.isBlank()) {
            into.refuseReason = delta.refuseReason;
        }
        if (delta.asUssd != null && !delta.asUssd.isBlank()) {
            into.asUssd = delta.asUssd;
        }
        if (delta.csvLine != null && !delta.csvLine.isBlank()) {
            into.csvLine = delta.csvLine;
        }
        return into;
    }

    public static CdrEntity seed(CdrEntity delta) {
        CdrEntity row = new CdrEntity();
        row.id = delta.id != null ? delta.id : UUID.randomUUID();
        Instant at = delta.recordedAt != null ? delta.recordedAt : Instant.now();
        row.recordedAt = at;
        row.startedAt = at;
        row.updatedAt = at;
        row.correlationId = delta.correlationId == null ? "" : delta.correlationId;
        row.phase = delta.phase;
        row.status = delta.status;
        row.msisdn = delta.msisdn;
        row.shortCode = delta.shortCode;
        row.detail = clipDetail(delta.detail);
        row.networkId = delta.networkId;
        row.tenantId = delta.tenantId;
        row.originationType = delta.originationType;
        row.gateMs = delta.gateMs;
        row.observedEwmaMs = delta.observedEwmaMs;
        row.hopOutcome = delta.hopOutcome;
        row.refuseReason = delta.refuseReason;
        row.asUssd = delta.asUssd;
        row.csvLine = delta.csvLine == null ? "" : delta.csvLine;
        row.eventCount = 1;
        row.eventsJson = serializeEvents(List.of(
                new Event(at, delta.phase, delta.status, clipDetail(delta.detail))));
        return row;
    }

    /** Coalesce a drain batch keyed by correlationId (order preserved within each corr). */
    public static List<CdrEntity> coalesceByCorrelation(List<CdrEntity> batch) {
        if (batch == null || batch.isEmpty()) {
            return List.of();
        }
        Map<String, CdrEntity> byCorr = new LinkedHashMap<>();
        for (CdrEntity row : batch) {
            if (row == null) {
                continue;
            }
            String corr = row.correlationId == null ? "" : row.correlationId;
            byCorr.put(corr, merge(byCorr.get(corr), row));
        }
        return new ArrayList<>(byCorr.values());
    }

    /**
     * Fold a coalesced flush snapshot onto a persisted session row (across flush windows).
     * Keeps {@code id}/{@code started_at}; appends events; adds {@code event_count}.
     */
    public static CdrEntity foldIncomingSession(CdrEntity existing, CdrEntity incoming) {
        if (existing == null) {
            return incoming == null ? null : seed(incoming);
        }
        if (incoming == null) {
            return existing;
        }
        CdrEntity out = new CdrEntity();
        out.id = existing.id;
        out.correlationId = existing.correlationId;
        out.startedAt = existing.startedAt != null ? existing.startedAt : incoming.startedAt;
        Instant upd = incoming.updatedAt != null ? incoming.updatedAt : incoming.recordedAt;
        if (upd == null) {
            upd = Instant.now();
        }
        if (existing.updatedAt != null && existing.updatedAt.isAfter(upd)) {
            upd = existing.updatedAt;
        }
        out.updatedAt = upd;
        out.recordedAt = upd;

        List<Event> events = parseEvents(existing.eventsJson);
        List<Event> more = parseEvents(incoming.eventsJson);
        if (more.isEmpty() && incoming.status != null) {
            more = List.of(new Event(
                    incoming.recordedAt != null ? incoming.recordedAt : upd,
                    incoming.phase, incoming.status, clipDetail(incoming.detail)));
        }
        events.addAll(more);
        events = capEvents(events);
        out.eventsJson = serializeEvents(events);
        int base = existing.eventCount == null ? 0 : existing.eventCount;
        int add = incoming.eventCount == null ? more.size() : incoming.eventCount;
        out.eventCount = base + add;

        Rank exRank = rank(existing.status, existing.phase);
        Rank inRank = rank(incoming.status, incoming.phase);
        if (inRank.weight() > exRank.weight()
                || (inRank == Rank.IN_FLIGHT && exRank == Rank.IN_FLIGHT)) {
            out.phase = incoming.phase;
            out.status = incoming.status;
        } else {
            out.phase = existing.phase;
            out.status = existing.status;
        }

        out.detail = mergeDetail(existing.detail, incoming.detail);
        out.msisdn = firstNonBlank(incoming.msisdn, existing.msisdn);
        out.shortCode = firstNonBlank(incoming.shortCode, existing.shortCode);
        out.tenantId = firstNonBlank(incoming.tenantId, existing.tenantId);
        out.originationType = firstNonBlank(incoming.originationType, existing.originationType);
        out.networkId = incoming.networkId != null ? incoming.networkId : existing.networkId;
        out.gateMs = pickPositive(incoming.gateMs, existing.gateMs);
        out.observedEwmaMs = pickPositive(incoming.observedEwmaMs, existing.observedEwmaMs);
        out.hopOutcome = firstNonBlank(incoming.hopOutcome, existing.hopOutcome);
        out.refuseReason = firstNonBlank(incoming.refuseReason, existing.refuseReason);
        out.asUssd = firstNonBlank(incoming.asUssd, existing.asUssd);
        out.csvLine = firstNonBlank(incoming.csvLine, existing.csvLine);
        if (out.csvLine == null) {
            out.csvLine = "";
        }
        return out;
    }

    private static Long pickPositive(Long prefer, Long fallback) {
        if (prefer != null && prefer > 0) {
            return prefer;
        }
        return fallback;
    }

    private static String firstNonBlank(String prefer, String fallback) {
        if (prefer != null && !prefer.isBlank()) {
            return prefer;
        }
        return fallback;
    }

    public static List<Event> parseEvents(String eventsJson) {
        if (eventsJson == null || eventsJson.isBlank()) {
            return new ArrayList<>();
        }
        String s = eventsJson.trim();
        if (!s.startsWith("[") || !s.endsWith("]")) {
            return new ArrayList<>();
        }
        List<Event> out = new ArrayList<>();
        // Minimal array-of-objects parser (avoids ObjectMapper on hot coalesce path).
        int i = 1;
        while (i < s.length()) {
            int objStart = s.indexOf('{', i);
            if (objStart < 0) {
                break;
            }
            int objEnd = s.indexOf('}', objStart);
            if (objEnd < 0) {
                break;
            }
            String obj = s.substring(objStart + 1, objEnd);
            Instant t = parseInstant(jsonField(obj, "t"));
            String phase = jsonField(obj, "phase");
            String status = jsonField(obj, "status");
            String detail = jsonField(obj, "detail");
            out.add(new Event(t, phase, status, detail));
            i = objEnd + 1;
        }
        return out;
    }

    public static String serializeEvents(List<Event> events) {
        if (events == null || events.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(Math.min(MAX_EVENTS_JSON_CHARS, events.size() * 96));
        sb.append('[');
        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{');
            sb.append("\"t\":\"").append(esc(e.t() == null ? "" : e.t().toString())).append('"');
            sb.append(",\"phase\":\"").append(esc(nullToEmpty(e.phase()))).append('"');
            sb.append(",\"status\":\"").append(esc(nullToEmpty(e.status()))).append('"');
            if (e.detail() != null && !e.detail().isBlank()) {
                sb.append(",\"detail\":\"").append(esc(clipDetail(e.detail()))).append('"');
            }
            sb.append('}');
            if (sb.length() > MAX_EVENTS_JSON_CHARS) {
                // Truncate to what fits; prefer keeping prefix + re-cap.
                List<Event> capped = capEvents(events.subList(0, i + 1));
                return serializeEventsBounded(capped);
            }
        }
        sb.append(']');
        return sb.toString();
    }

    private static String serializeEventsBounded(List<Event> events) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"t\":\"").append(esc(e.t() == null ? "" : e.t().toString())).append('"');
            sb.append(",\"phase\":\"").append(esc(nullToEmpty(e.phase()))).append('"');
            sb.append(",\"status\":\"").append(esc(nullToEmpty(e.status()))).append('"');
            if (e.detail() != null && !e.detail().isBlank()) {
                String d = clipDetail(e.detail());
                if (d != null && d.length() > 120) {
                    d = d.substring(0, 120);
                }
                sb.append(",\"detail\":\"").append(esc(d)).append('"');
            }
            sb.append('}');
        }
        sb.append(']');
        if (sb.length() > MAX_EVENTS_JSON_CHARS) {
            return sb.substring(0, MAX_EVENTS_JSON_CHARS - 1) + "]";
        }
        return sb.toString();
    }

    /** Keep first event + latest events when over cap. */
    public static List<Event> capEvents(List<Event> events) {
        if (events == null || events.size() <= MAX_EVENTS) {
            return events == null ? new ArrayList<>() : new ArrayList<>(events);
        }
        List<Event> out = new ArrayList<>(MAX_EVENTS);
        out.add(events.getFirst());
        int keepTail = MAX_EVENTS - 1;
        out.addAll(events.subList(events.size() - keepTail, events.size()));
        return out;
    }

    /** Last-win pipe {@code k=v} merge, truncated. */
    public static String mergeDetail(String existing, String incoming) {
        if (incoming == null || incoming.isBlank()) {
            return clipDetail(existing);
        }
        if (existing == null || existing.isBlank()) {
            return clipDetail(incoming);
        }
        Map<String, String> fields = new LinkedHashMap<>(CdrSessionDigest.parseDetail(existing));
        fields.putAll(CdrSessionDigest.parseDetail(incoming));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('|');
            }
            if ("true".equals(e.getValue()) && !e.getKey().contains("=")) {
                sb.append(e.getKey());
            } else {
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
        }
        return clipDetail(sb.toString());
    }

    public static String clipDetail(String detail) {
        if (detail == null) {
            return null;
        }
        if (detail.length() <= MAX_DETAIL_CHARS) {
            return detail;
        }
        return detail.substring(0, MAX_DETAIL_CHARS);
    }

    /** Build timeline records (oldest-first) from {@code events_json} for admin expand. */
    public static List<CdrRecord> timelineFromEvents(CdrEntity session) {
        if (session == null) {
            return List.of();
        }
        List<Event> events = parseEvents(session.eventsJson);
        if (events.isEmpty()) {
            return List.of(CdrRecord.fromEntity(session));
        }
        List<CdrRecord> out = new ArrayList<>(events.size());
        for (Event e : events) {
            CdrRecord r = new CdrRecord();
            r.id = session.id;
            r.correlationId = session.correlationId;
            r.createdAt = e.t();
            r.phase = e.phase();
            r.status = e.status();
            r.detail = e.detail();
            r.msisdn = session.msisdn;
            r.shortCode = session.shortCode;
            r.gateMs = session.gateMs;
            r.observedEwmaMs = session.observedEwmaMs;
            r.hopOutcome = session.hopOutcome;
            r.refuseReason = session.refuseReason;
            r.asUssd = session.asUssd;
            r.networkId = session.networkId;
            r.tenantId = session.tenantId;
            r.originationType = session.originationType;
            r.eventCount = session.eventCount;
            out.add(r);
        }
        return out;
    }

    private static String jsonField(String obj, String key) {
        String needle = "\"" + key + "\":\"";
        int at = obj.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int start = at + needle.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < obj.length(); i++) {
            char c = obj.charAt(i);
            if (c == '\\' && i + 1 < obj.length()) {
                sb.append(obj.charAt(i + 1));
                i++;
                continue;
            }
            if (c == '"') {
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("|", "/");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
