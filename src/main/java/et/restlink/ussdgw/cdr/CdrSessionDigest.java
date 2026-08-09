package et.restlink.ussdgw.cdr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parse pipe {@code k=v} CDR detail + derive a gated / MAP2MAP session digest for admin expand.
 * Honest unknowns stay {@code unknown} — never invent HLR/AS outcomes not present in the ledger.
 */
public final class CdrSessionDigest {
    private CdrSessionDigest() {}

    public record Answer(String value, String evidence) {
        public static Answer of(String value, String evidence) {
            return new Answer(value == null || value.isBlank() ? "unknown" : value, evidence);
        }

        public static Answer unknown() {
            return new Answer("unknown", null);
        }
    }

    public record Digest(
            Long gateMs,
            Long observedEwmaMs,
            String shortCode,
            String longOrRedirect,
            String dialed,
            Answer upperHlrSent,
            Answer hlrResponse,
            Answer asNotifySent,
            Answer asResponse,
            Map<String, String> detailFields,
            List<CdrRecord> timelineOldestFirst) {}

    /** Split {@code a=b|c=d|flag} detail into ordered map (last win on duplicate keys). */
    public static Map<String, String> parseDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : detail.split("\\|")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int eq = p.indexOf('=');
            if (eq <= 0) {
                out.put(p, "true");
                continue;
            }
            out.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
        }
        return Collections.unmodifiableMap(out);
    }

    public static Digest from(CdrRecord focus, List<CdrRecord> sameCorrNewestFirst) {
        List<CdrRecord> rows = sameCorrNewestFirst == null ? List.of() : sameCorrNewestFirst;
        List<CdrRecord> oldestFirst = new ArrayList<>(rows);
        Collections.reverse(oldestFirst);

        Map<String, String> fields = new LinkedHashMap<>();
        if (focus != null && focus.detail != null) {
            fields.putAll(parseDetail(focus.detail));
        }
        for (CdrRecord r : oldestFirst) {
            if (r != null && r.detail != null) {
                fields.putAll(parseDetail(r.detail));
            }
        }

        Long gateMs = focus != null ? focus.gateMs : null;
        Long ewma = focus != null ? focus.observedEwmaMs : null;
        String sc = focus != null ? focus.shortCode : null;
        for (CdrRecord r : oldestFirst) {
            if (r == null) {
                continue;
            }
            if (gateMs == null && r.gateMs != null && r.gateMs > 0) {
                gateMs = r.gateMs;
            }
            if (ewma == null && r.observedEwmaMs != null && r.observedEwmaMs > 0) {
                ewma = r.observedEwmaMs;
            }
            if ((sc == null || sc.isBlank()) && r.shortCode != null && !r.shortCode.isBlank()) {
                sc = r.shortCode;
            }
        }
        if (gateMs == null) {
            gateMs = parseLong(fields.get("gateMs"));
        }

        String redirect = firstNonBlank(fields.get("redirect"), fields.get("longCode"), fields.get("longcode"));
        String dialed = fields.get("dialed");
        if ((sc == null || sc.isBlank()) && fields.get("sc") != null) {
            sc = fields.get("sc");
        }

        return new Digest(
                gateMs,
                ewma,
                sc,
                redirect,
                dialed,
                upperHlrSent(oldestFirst, fields),
                hlrResponse(oldestFirst, fields),
                asNotifySent(oldestFirst, fields),
                asResponse(oldestFirst, fields),
                Collections.unmodifiableMap(fields),
                List.copyOf(oldestFirst));
    }

    private static Answer upperHlrSent(List<CdrRecord> oldestFirst, Map<String, String> fields) {
        for (CdrRecord r : oldestFirst) {
            if (r == null || r.status == null) {
                continue;
            }
            String u = r.status.toUpperCase(Locale.ROOT);
            if (u.equals(Map2MapCdr.SRI_SENT) || u.equals("HLR_PROXY_OK")) {
                return Answer.of("yes", r.status);
            }
            if (u.equals(Map2MapCdr.HLR_REJECT) || u.equals(Map2MapCdr.USSD_SENT)
                    || u.equals(Map2MapCdr.HOP_START)) {
                return Answer.of("yes", r.status);
            }
            if (u.equals(Map2MapCdr.OK) || u.equals(Map2MapCdr.COMPLETE_AFTER_GATE)
                    || u.equals(Map2MapCdr.HOP_CLOSE)) {
                return Answer.of("yes", r.status);
            }
        }
        if (fields.containsKey("hopGt") || "hlr".equalsIgnoreCase(fields.get("path"))
                || fields.containsKey("hlrMode")) {
            String ev = fields.containsKey("hopGt") ? ("hopGt=" + fields.get("hopGt"))
                    : fields.containsKey("hlrMode") ? ("hlrMode=" + fields.get("hlrMode"))
                    : ("path=" + fields.get("path"));
            return Answer.of("armed/configured", ev);
        }
        return Answer.unknown();
    }

    private static Answer hlrResponse(List<CdrRecord> oldestFirst, Map<String, String> fields) {
        String hopOutcome = fields.get("hopOutcome");
        if (Map2MapCdr.OUTCOME_REJECT.equals(hopOutcome)) {
            return Answer.of("reject", "hopOutcome=reject");
        }
        if (Map2MapCdr.OUTCOME_EMPTY.equals(hopOutcome) || Map2MapCdr.OUTCOME_TIMEOUT.equals(hopOutcome)
                || Map2MapCdr.OUTCOME_ABORT.equals(hopOutcome) || Map2MapCdr.OUTCOME_CLOSE.equals(hopOutcome)) {
            return Answer.of("none / empty", "hopOutcome=" + hopOutcome);
        }
        if (Map2MapCdr.OUTCOME_TEXT.equals(hopOutcome)) {
            return Answer.of("yes (text)", "hopOutcome=text");
        }
        for (CdrRecord r : oldestFirst) {
            if (r == null || r.status == null) {
                continue;
            }
            String u = r.status.toUpperCase(Locale.ROOT);
            if (u.equals(Map2MapCdr.HLR_REJECT)) {
                return Answer.of("reject", r.status);
            }
            if (u.equals(Map2MapCdr.HOP_ABORT)) {
                return Answer.of("abort", r.status);
            }
            if (u.equals(Map2MapCdr.HOP_CLOSE)) {
                return Answer.of("close (text)", r.status);
            }
            if (u.equals(Map2MapCdr.HOP_FAIL)) {
                return Answer.of("none / empty", r.status);
            }
            // USSD_SENT = outbound only — not a hop response.
            if (u.equals(Map2MapCdr.OK) || u.equals(Map2MapCdr.COMPLETE_AFTER_GATE)
                    || u.equals(Map2MapCdr.HOP_CLOSE)) {
                String asUssd = fields.get("asUssd");
                if (Map2MapCdr.AS_USSD_HLR_REJECT.equals(asUssd)
                        || Map2MapCdr.AS_USSD_HLR_NONE.equals(asUssd)) {
                    continue;
                }
                return Answer.of("yes", r.status);
            }
            if (u.contains("SRI_TIMEOUT") || u.equals(Map2MapCdr.TIMEOUT)
                    || u.equals(Map2MapCdr.TIMEOUT_AFTER_BRIDGE)
                    || u.equals("HLR_PROXY_TIMEOUT") || u.equals("HLR_PROXY_FAIL")
                    || u.equals("HLR_DIAM_FAIL") || u.equals("SRI_NO_MSC") || u.equals("NI_NO_MSC")) {
                return Answer.of("no / fail", r.status);
            }
            if (u.equals("HLR_PROXY_OK") || u.equals("HLR_DIAM_OK") || u.equals("HLR_FAKE_OK")) {
                return Answer.of("yes", r.status);
            }
        }
        if (fields.containsKey("msc") || fields.containsKey("mscGt") || fields.containsKey("imsi")) {
            return Answer.of("yes", "detail msc/imsi present");
        }
        return Answer.unknown();
    }

    private static Answer asNotifySent(List<CdrRecord> oldestFirst, Map<String, String> fields) {
        for (CdrRecord r : oldestFirst) {
            if (r == null || r.status == null) {
                continue;
            }
            String u = r.status.toUpperCase(Locale.ROOT);
            if (u.equals(CdrStatuses.GATED_AS_NOTIFY) || u.equals(CdrStatuses.GATED_AS_ACK)
                    || u.equals(CdrStatuses.GATED_AS_FAIL)) {
                return Answer.of("yes", r.status);
            }
            if (u.equals(CdrStatuses.GATED_AS_SKIP)) {
                return Answer.of("no (skipped)", r.status);
            }
            if (u.equals(CdrStatuses.BRIDGED) || u.equals(CdrStatuses.QUEUED)
                    || u.equals(CdrStatuses.BRIDGED_DONE) || u.equals(CdrStatuses.AS_EMPTY_BODY)
                    || u.equals(CdrStatuses.AS_PULL_FAIL)) {
                return Answer.of("yes (AS path)", r.status);
            }
        }
        // After gated-AS rows: RE_ROUTE pull-routed statuses still count as AS path.
        for (CdrRecord r : oldestFirst) {
            if (r == null || r.status == null) {
                continue;
            }
            String u = r.status.toUpperCase(Locale.ROOT);
            if (u.equals(Map2MapCdr.AS_ROUTED) || u.equals(Map2MapCdr.OK)
                    || u.equals(Map2MapCdr.COMPLETE_AFTER_GATE)
                    || u.equals(Map2MapCdr.HOP_CLOSE)) {
                return Answer.of("yes (AS path)", r.status);
            }
        }
        if (fields.containsKey("asUssd") || fields.containsKey("asUrl")) {
            String ev = fields.containsKey("asUssd")
                    ? ("asUssd=" + fields.get("asUssd")) : ("asUrl=" + fields.get("asUrl"));
            return Answer.of("yes / queued", ev);
        }
        if ("no-http-asUrl".equals(fields.get("skip")) || "dispatch".equals(fields.get("skip"))) {
            return Answer.of("no", "skip=" + fields.get("skip"));
        }
        return Answer.unknown();
    }

    private static Answer asResponse(List<CdrRecord> oldestFirst, Map<String, String> fields) {
        // Prefer definitive AS body outcomes over earlier MAP2MAP_AS_ROUTED / OK rows.
        // END/CONTINUE = VirtualSessionBridge applied AS→UE (not hop-close).
        for (CdrRecord r : oldestFirst) {
            if (r == null || r.status == null) {
                continue;
            }
            String u = r.status.toUpperCase(Locale.ROOT);
            if (u.equals("END") || u.equals("CONTINUE")) {
                String snip = fields.get("asUssd");
                String ev = snip != null && !snip.isBlank()
                        ? (r.status + "|asUssd=" + snip) : r.status;
                return Answer.of("yes / AS→UE", ev);
            }
            if (u.equals(CdrStatuses.GATED_AS_ACK) || u.equals(CdrStatuses.BRIDGED_DONE)) {
                return Answer.of("yes / ack", r.status);
            }
            if (u.equals(CdrStatuses.GATED_AS_FAIL) || u.equals(CdrStatuses.AS_EMPTY_BODY)
                    || u.equals(CdrStatuses.AS_PULL_FAIL) || u.equals(Map2MapCdr.AS_ROUTE_FAIL)) {
                return Answer.of("fail", r.status);
            }
        }
        for (CdrRecord r : oldestFirst) {
            if (r == null || r.status == null) {
                continue;
            }
            String u = r.status.toUpperCase(Locale.ROOT);
            // MAP2MAP_OK / AS_ROUTED = AS pull queued, not AS body ack.
            if (u.equals(Map2MapCdr.AS_ROUTED) || u.equals(Map2MapCdr.OK)
                    || u.equals(Map2MapCdr.COMPLETE_AFTER_GATE)
                    || u.equals(Map2MapCdr.HOP_CLOSE)) {
                return Answer.of("AS pull routed", r.status);
            }
        }
        String http = fields.get("http");
        if (http != null) {
            try {
                int code = Integer.parseInt(http.trim());
                if (code >= 200 && code < 400) {
                    return Answer.of("yes / http " + code, "http=" + http);
                }
                return Answer.of("fail / http " + code, "http=" + http);
            } catch (NumberFormatException ignored) {
                return Answer.of("http=" + http, "http");
            }
        }
        if (fields.containsKey("gated-ack")) {
            return Answer.of("yes / ack", "gated-ack");
        }
        if (fields.containsKey("gated-fail")) {
            return Answer.of("fail", "gated-fail");
        }
        return Answer.unknown();
    }

    private static Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
