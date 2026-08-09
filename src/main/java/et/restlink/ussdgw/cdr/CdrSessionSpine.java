package et.restlink.ussdgw.cdr;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fixed 6-hop CDR expand spine — dense ops fold from {@code events_json} / {@code as_ussd}
 * only. No new persist write path. Missing hops stay visible as {@link Result#SKIPPED}.
 *
 * <ol>
 *   <li>Receive USSD (UE→GW)</li>
 *   <li>Re-route upper HLR/MSC (or SRI dest when NI)</li>
 *   <li>HLR/MSC response (~50 hop text)</li>
 *   <li>Send AS</li>
 *   <li>AS response (~50)</li>
 *   <li>Gate → UE (FAIL/red when AS had text but MAP not sent)</li>
 * </ol>
 */
public final class CdrSessionSpine {
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    private CdrSessionSpine() {}

    public enum Result {
        OK, FAIL, SKIPPED
    }

    /**
     * @param slot 1..6
     * @param detail dense operator line (msisdn/sc/GT/~50 text/URL/reason)
     * @param whenUtc short UTC time from evidence event, or empty
     * @param chipClass existing ledger chip class ({@code cdr-status--ok|fail|gated})
     */
    public record Step(int slot, String id, String label, Result result,
                       String detail, String whenUtc, String cssClass, String chipClass) {
        public String resultLabel() {
            return result == null ? "—" : result.name();
        }
    }

    /** Always exactly 6 steps, oldest-first evidence from digest timeline + focus row. */
    public static List<Step> derive(CdrSessionDigest.Digest dig, CdrRecord focus) {
        List<CdrRecord> tl = dig == null || dig.timelineOldestFirst() == null
                ? List.of() : dig.timelineOldestFirst();
        Map<String, String> fields = dig == null || dig.detailFields() == null
                ? Map.of() : dig.detailFields();

        String asSnip = CdrUssdSnippet.resolveForDisplay(
                focus == null ? null : focus.asUssd,
                fields.get("asUssd"));
        boolean asHasOperatorText = hasOperatorAsText(asSnip, fields);

        List<Step> out = new ArrayList<>(6);
        out.add(stepReceive(focus, dig, tl, fields));
        out.add(stepReroute(dig, tl, fields));
        out.add(stepHopResponse(dig, tl, fields, asSnip));
        out.add(stepSendAs(dig, tl, fields));
        out.add(stepAsResponse(dig, tl, fields, asSnip, asHasOperatorText));
        out.add(stepGateToUe(dig, tl, asHasOperatorText, asSnip));
        return List.copyOf(out);
    }

    private static Step stepReceive(CdrRecord focus, CdrSessionDigest.Digest dig,
                                    List<CdrRecord> tl, Map<String, String> fields) {
        String dial = firstNonBlank(
                dig == null ? null : dig.dialed(),
                fields.get("dialed"));
        String sc = firstNonBlank(
                dig == null ? null : dig.shortCode(),
                focus == null ? null : focus.shortCode,
                fields.get("sc"));
        String msisdn = focus == null ? null : focus.msisdn;
        if ((msisdn == null || msisdn.isBlank()) && !tl.isEmpty()) {
            for (CdrRecord r : tl) {
                if (r != null && r.msisdn != null && !r.msisdn.isBlank()) {
                    msisdn = r.msisdn;
                    break;
                }
            }
        }
        Instant when = focus != null && focus.startedAt != null ? focus.startedAt
                : (focus != null ? focus.createdAt : firstWhen(tl));
        final String dialF = dial;
        final String scF = sc;
        final String msisdnF = msisdn;
        boolean emptyMo = dialF == null && scF == null
                && (msisdnF == null || msisdnF.isBlank());
        if (!emptyMo || !tl.isEmpty()) {
            String detail = joinParts(
                    msisdnF == null ? null : "msisdn=" + msisdnF,
                    dialF == null ? null : "dialed=" + dialF,
                    scF == null ? null : "sc=" + scF,
                    (dialF == null && scF == null && msisdnF != null) ? "MO received" : null);
            return step(1, "receive", "Receive USSD", Result.OK, detail, when);
        }
        if (hasStatus(tl, u -> u.equals("ABORT")) && !hasUeMapSent(tl)) {
            return step(1, "receive", "Receive USSD", Result.FAIL,
                    "reason=no MO digits/msisdn", when);
        }
        return step(1, "receive", "Receive USSD", Result.FAIL, "reason=no MO evidence", when);
    }

    private static Step stepReroute(CdrSessionDigest.Digest dig, List<CdrRecord> tl,
                                    Map<String, String> fields) {
        Instant when = whenForStatuses(tl, Map2MapCdr.HOP_START, Map2MapCdr.SRI_SENT,
                Map2MapCdr.USSD_SENT, Map2MapCdr.ARMED, "HLR_PROXY_OK");
        if (!hasHopOrSri(tl, dig, fields)) {
            return step(2, "reroute", "Re-route HLR/MSC", Result.SKIPPED,
                    "reason=no re-route/SRI on this session", when);
        }
        String dest = firstNonBlank(
                fields.get("hopGt"), fields.get("mscGt"), fields.get("msc"), fields.get("destGt"));
        String ssn = fields.get("hopSsn");
        String path = fields.get("path");
        String detail = joinParts(
                dest == null ? null : "gt=" + dest,
                ssn == null || ssn.isBlank() ? null : "ssn=" + ssn,
                path == null ? null : "path=" + path,
                hasStatus(tl, u -> u.equals(Map2MapCdr.SRI_SENT) || u.contains("SRI")) ? "SRI" : null,
                dest == null ? "hop armed" : null);
        if (hasStatus(tl, u -> u.equals(Map2MapCdr.HLR_REJECT))
                && !hasStatus(tl, u -> u.equals(Map2MapCdr.USSD_SENT)
                || u.equals(Map2MapCdr.HOP_START) || u.equals(Map2MapCdr.HOP_CLOSE)
                || u.equals(Map2MapCdr.OK) || u.equals(Map2MapCdr.AS_ROUTED))) {
            return step(2, "reroute", "Re-route HLR/MSC", Result.FAIL,
                    detail + " · reason=reject", whenForStatuses(tl, Map2MapCdr.HLR_REJECT));
        }
        CdrSessionDigest.Answer sent = dig == null ? null : dig.upperHlrSent();
        if (sent != null && "unknown".equals(sent.value()) && dest == null) {
            return step(2, "reroute", "Re-route HLR/MSC", Result.SKIPPED,
                    "reason=configured only · not sent", when);
        }
        return step(2, "reroute", "Re-route HLR/MSC", Result.OK, detail, when);
    }

    private static Step stepHopResponse(CdrSessionDigest.Digest dig, List<CdrRecord> tl,
                                        Map<String, String> fields, String asSnip) {
        Instant when = whenForStatuses(tl, Map2MapCdr.HOP_CLOSE, Map2MapCdr.HOP_FAIL,
                Map2MapCdr.HOP_ABORT, Map2MapCdr.HLR_REJECT, Map2MapCdr.TIMEOUT,
                Map2MapCdr.OK, "HLR_PROXY_OK", "HLR_DIAM_OK");
        if (!hasHopOrSri(tl, dig, fields)) {
            return step(3, "hop_resp", "HLR/MSC response", Result.SKIPPED,
                    "reason=no hop", when);
        }
        String hopOutcome = fields.get("hopOutcome");
        String hopText = hopTextSnippet(fields, asSnip, tl);
        String msc = firstNonBlank(fields.get("mscGt"), fields.get("msc"));
        String imsi = fields.get("imsi");
        if (Map2MapCdr.OUTCOME_TEXT.equals(hopOutcome)
                || hasStatus(tl, u -> u.equals(Map2MapCdr.HOP_CLOSE))) {
            String detail = joinParts(
                    hopText.isEmpty() ? null : ("hopText=" + hopText),
                    hopOutcome == null ? null : ("hopOutcome=" + hopOutcome),
                    msc == null ? null : ("msc=" + msc));
            return step(3, "hop_resp", "HLR/MSC response", Result.OK,
                    detail.isEmpty() ? "text hop" : detail, when);
        }
        if (Map2MapCdr.OUTCOME_REJECT.equals(hopOutcome)
                || hasStatus(tl, u -> u.equals(Map2MapCdr.HLR_REJECT))) {
            return step(3, "hop_resp", "HLR/MSC response", Result.FAIL,
                    "reason=reject · hopOutcome=" + nullTo(hopOutcome, "reject"), when);
        }
        if (Map2MapCdr.OUTCOME_ABORT.equals(hopOutcome)
                || hasStatus(tl, u -> u.equals(Map2MapCdr.HOP_ABORT))) {
            return step(3, "hop_resp", "HLR/MSC response", Result.FAIL, "reason=abort", when);
        }
        if (Map2MapCdr.OUTCOME_TIMEOUT.equals(hopOutcome)
                || hasStatus(tl, u -> u.equals(Map2MapCdr.TIMEOUT)
                || u.equals(Map2MapCdr.TIMEOUT_AFTER_BRIDGE)
                || u.contains("SRI_TIMEOUT") || u.equals("SRI_NO_MSC") || u.equals("NI_NO_MSC"))) {
            return step(3, "hop_resp", "HLR/MSC response", Result.FAIL,
                    joinParts("reason=timeout/no MSC", msc == null ? null : "msc=" + msc), when);
        }
        if (Map2MapCdr.OUTCOME_EMPTY.equals(hopOutcome)
                || Map2MapCdr.OUTCOME_CLOSE.equals(hopOutcome)
                || hasStatus(tl, u -> u.equals(Map2MapCdr.HOP_FAIL))) {
            return step(3, "hop_resp", "HLR/MSC response", Result.FAIL,
                    "reason=no hop text · hopOutcome=" + nullTo(hopOutcome, "empty"), when);
        }
        CdrSessionDigest.Answer resp = dig == null ? null : dig.hlrResponse();
        if (resp != null && resp.value() != null && !"unknown".equals(resp.value())) {
            String v = resp.value().toLowerCase(Locale.ROOT);
            if (v.contains("fail") || v.contains("reject") || v.contains("none")
                    || v.contains("abort") || v.startsWith("no")) {
                return step(3, "hop_resp", "HLR/MSC response", Result.FAIL,
                        "reason=" + resp.value(), when);
            }
            return step(3, "hop_resp", "HLR/MSC response", Result.OK,
                    joinParts(hopText.isEmpty() ? null : ("hopText=" + hopText),
                            "hlr=" + resp.value(),
                            msc == null ? null : ("msc=" + msc),
                            imsi == null ? null : ("imsi=" + imsi)),
                    when);
        }
        if (msc != null || imsi != null) {
            return step(3, "hop_resp", "HLR/MSC response", Result.OK,
                    joinParts(msc == null ? null : ("msc=" + msc),
                            imsi == null ? null : ("imsi=" + imsi), "SRI ok"),
                    when);
        }
        return step(3, "hop_resp", "HLR/MSC response", Result.SKIPPED,
                "reason=awaiting hop", when);
    }

    private static Step stepSendAs(CdrSessionDigest.Digest dig, List<CdrRecord> tl,
                                   Map<String, String> fields) {
        Instant when = whenForStatuses(tl, CdrStatuses.GATED_AS_NOTIFY, CdrStatuses.GATED_AS_ACK,
                Map2MapCdr.AS_ROUTED, Map2MapCdr.OK, Map2MapCdr.HOP_CLOSE, Map2MapCdr.AS_EARLY);
        String url = firstNonBlank(fields.get("asUrl"), fields.get("url"));
        String urlShort = shortenUrl(url);
        String sc = firstNonBlank(fields.get("sc"), dig == null ? null : dig.shortCode());
        CdrSessionDigest.Answer sent = dig == null ? null : dig.asNotifySent();
        if (hasStatus(tl, u -> u.equals(CdrStatuses.GATED_AS_FAIL)
                || u.equals(CdrStatuses.AS_PULL_FAIL)
                || u.equals(Map2MapCdr.AS_ROUTE_FAIL))) {
            return step(4, "send_as", "Send AS", Result.FAIL,
                    joinParts(urlShort == null ? null : ("asUrl=" + urlShort),
                            sc == null ? null : ("sc=" + sc), "reason=AS send fail"),
                    whenForStatuses(tl, CdrStatuses.GATED_AS_FAIL, CdrStatuses.AS_PULL_FAIL,
                            Map2MapCdr.AS_ROUTE_FAIL));
        }
        if (hasStatus(tl, u -> u.equals(CdrStatuses.GATED_AS_SKIP))) {
            return step(4, "send_as", "Send AS", Result.SKIPPED,
                    "reason=notify skipped", when);
        }
        if (sent != null && sent.value() != null && sent.value().startsWith("no")) {
            return step(4, "send_as", "Send AS", Result.FAIL,
                    "reason=" + sent.value(), when);
        }
        if (sent != null && sent.value() != null && !"unknown".equals(sent.value())) {
            return step(4, "send_as", "Send AS", Result.OK,
                    joinParts(urlShort == null ? null : ("asUrl=" + urlShort),
                            sc == null ? null : ("sc=" + sc),
                            "sent=" + sent.value()),
                    when);
        }
        if (url != null || fields.containsKey("asUssd")
                || hasStatus(tl, u -> u.equals("END") || u.equals("CONTINUE")
                || u.equals(Map2MapCdr.AS_ROUTED) || u.equals(Map2MapCdr.OK)
                || u.equals(Map2MapCdr.HOP_CLOSE))) {
            return step(4, "send_as", "Send AS", Result.OK,
                    joinParts(urlShort == null ? null : ("asUrl=" + urlShort),
                            sc == null ? null : ("sc=" + sc), "AS path"),
                    when);
        }
        return step(4, "send_as", "Send AS", Result.FAIL, "reason=AS not sent", when);
    }

    private static Step stepAsResponse(CdrSessionDigest.Digest dig, List<CdrRecord> tl,
                                       Map<String, String> fields, String asSnip,
                                       boolean asHasOperatorText) {
        Instant when = whenForStatuses(tl, "END", "CONTINUE", CdrStatuses.GATED_AS_ACK,
                CdrStatuses.AS_EMPTY_BODY, CdrStatuses.AS_PULL_FAIL, CdrStatuses.GATED_AS_FAIL);
        if (hasStatus(tl, u -> u.equals(CdrStatuses.AS_EMPTY_BODY)
                || u.equals(CdrStatuses.AS_PULL_FAIL)
                || u.equals(CdrStatuses.GATED_AS_FAIL)
                || u.equals(Map2MapCdr.AS_ROUTE_FAIL))) {
            return step(5, "as_resp", "AS response", Result.FAIL,
                    joinParts(asSnip.isEmpty() ? null : ("asUssd=" + asSnip),
                            "reason=empty/fail"),
                    when);
        }
        if (asHasOperatorText) {
            return step(5, "as_resp", "AS response", Result.OK, "asUssd=" + asSnip, when);
        }
        CdrSessionDigest.Answer resp = dig == null ? null : dig.asResponse();
        if (resp != null && resp.value() != null) {
            String v = resp.value().toLowerCase(Locale.ROOT);
            if (v.contains("fail") || v.contains("empty")) {
                return step(5, "as_resp", "AS response", Result.FAIL,
                        "reason=" + resp.value(), when);
            }
            if (v.contains("as→ue") || v.contains("as->ue") || v.contains("ack")
                    || v.contains("yes")) {
                return step(5, "as_resp", "AS response", Result.OK,
                        asSnip.isEmpty() ? ("as=" + resp.value()) : ("asUssd=" + asSnip),
                        when);
            }
            if (v.contains("routed")) {
                return step(5, "as_resp", "AS response", Result.SKIPPED,
                        "reason=pull routed · no AS body yet", when);
            }
        }
        if (hasStatus(tl, u -> u.equals("END") || u.equals("CONTINUE"))) {
            return step(5, "as_resp", "AS response", Result.OK,
                    asSnip.isEmpty() ? "AS→UE" : ("asUssd=" + asSnip), when);
        }
        if (hasStatus(tl, u -> u.equals(Map2MapCdr.AS_ROUTED) || u.equals(Map2MapCdr.OK)
                || u.equals(Map2MapCdr.HOP_CLOSE))) {
            return step(5, "as_resp", "AS response", Result.SKIPPED,
                    "reason=awaiting AS body", when);
        }
        return step(5, "as_resp", "AS response", Result.FAIL, "reason=no AS text", when);
    }

    private static Step stepGateToUe(CdrSessionDigest.Digest dig, List<CdrRecord> tl,
                                     boolean asHasOperatorText, String asSnip) {
        Instant when = whenForStatuses(tl, "END", "CONTINUE", "ABORT", CdrStatuses.BRIDGED_DONE,
                CdrStatuses.GATE_EXPIRED, CdrStatuses.BRIDGED, CdrStatuses.GATE_ARMED);
        Long gateMs = dig == null ? null : dig.gateMs();
        String gatePart = gateMs != null && gateMs > 0 ? ("gateMs=" + gateMs) : null;
        if (hasUeMapSent(tl)) {
            String kind = lastUeMapStatus(tl);
            String map = "CONTINUE".equals(kind) ? "MAP CONTINUE"
                    : "ABORT".equals(kind) ? "MAP ABORT" : "MAP END";
            Result r = "ABORT".equals(kind) ? Result.FAIL : Result.OK;
            return step(6, "gate_ue", "Gate → UE", r,
                    joinParts(map, gatePart, asSnip.isEmpty() ? null : ("asUssd=" + asSnip)),
                    when);
        }
        if (asHasOperatorText) {
            return step(6, "gate_ue", "Gate → UE", Result.FAIL,
                    joinParts("reason=AS text not sent to UE",
                            "asUssd=" + asSnip, gatePart),
                    when);
        }
        if (hasStatus(tl, u -> u.equals(CdrStatuses.BRIDGED) || u.equals(CdrStatuses.GATE_ARMED)
                || u.equals(CdrStatuses.GATED))) {
            return step(6, "gate_ue", "Gate → UE", Result.SKIPPED,
                    joinParts("reason=gate armed · awaiting AS", gatePart), when);
        }
        if (hasStatus(tl, u -> u.equals(CdrStatuses.GATE_EXPIRED)
                || u.equals(CdrStatuses.GATE_NO_BRIDGE))) {
            return step(6, "gate_ue", "Gate → UE", Result.FAIL,
                    joinParts("reason=gate expired", gatePart), when);
        }
        return step(6, "gate_ue", "Gate → UE", Result.FAIL,
                joinParts("reason=MAP not sent to UE", gatePart), when);
    }

    private static Step step(int slot, String id, String label, Result result,
                             String detail, Instant when) {
        String d = detail == null || detail.isBlank() ? "—" : clip(detail.trim(), 96);
        String css = switch (result) {
            case OK -> "cdr-hop--ok";
            case FAIL -> "cdr-hop--fail";
            case SKIPPED -> "cdr-hop--skip";
        };
        String chip = switch (result) {
            case OK -> "cdr-status--ok";
            case FAIL -> "cdr-status--fail";
            case SKIPPED -> "cdr-status--gated";
        };
        return new Step(slot, id, label, result, d, formatWhen(when), css, chip);
    }

    static boolean hasOperatorAsText(String asSnip, Map<String, String> fields) {
        if (asSnip == null || asSnip.isBlank()) {
            return false;
        }
        String t = asSnip.trim();
        if (t.equals("—") || t.equals("-")) {
            return false;
        }
        if (Map2MapCdr.AS_USSD_HLR_NONE.equals(t)
                || Map2MapCdr.AS_USSD_HLR_REJECT.equals(t)
                || Map2MapCdr.AS_USSD_HLR_PENDING.equals(t)) {
            return false;
        }
        String raw = fields == null ? null : fields.get("asUssd");
        if (raw != null) {
            String r = raw.trim();
            if (Map2MapCdr.AS_USSD_HLR_NONE.equals(r)
                    || Map2MapCdr.AS_USSD_HLR_REJECT.equals(r)
                    || Map2MapCdr.AS_USSD_HLR_PENDING.equals(r)) {
                return false;
            }
        }
        return true;
    }

    private static String hopTextSnippet(Map<String, String> fields, String asSnip,
                                         List<CdrRecord> tl) {
        if (hasStatus(tl, u -> u.equals(Map2MapCdr.HOP_CLOSE))) {
            String as = fields.get("asUssd");
            if (as != null && !as.isBlank()
                    && !Map2MapCdr.AS_USSD_HLR_NONE.equals(as.trim())
                    && !Map2MapCdr.AS_USSD_HLR_REJECT.equals(as.trim())
                    && !Map2MapCdr.AS_USSD_HLR_PENDING.equals(as.trim())) {
                return CdrUssdSnippet.of(as);
            }
        }
        String hopUssd = firstNonBlank(fields.get("hopUssd"), fields.get("hopText"));
        if (hopUssd != null) {
            return CdrUssdSnippet.of(hopUssd);
        }
        if (Map2MapCdr.OUTCOME_TEXT.equals(fields.get("hopOutcome")) && asSnip != null
                && !asSnip.isBlank()) {
            return asSnip;
        }
        return "";
    }

    private static boolean hasHopOrSri(List<CdrRecord> tl, CdrSessionDigest.Digest dig,
                                       Map<String, String> fields) {
        if (dig != null) {
            if (dig.upperHlrSent() != null && !"unknown".equals(dig.upperHlrSent().value())) {
                return true;
            }
            if (dig.hlrResponse() != null && !"unknown".equals(dig.hlrResponse().value())) {
                return true;
            }
        }
        if (fields.containsKey("hopGt") || fields.containsKey("hopOutcome")
                || fields.containsKey("msc") || fields.containsKey("mscGt")
                || "hlr".equalsIgnoreCase(fields.get("path"))) {
            return true;
        }
        for (CdrRecord r : tl) {
            if (r == null || r.status == null) {
                continue;
            }
            if (CdrStatuses.isMap2MapFamily(r.status)) {
                return true;
            }
            String u = r.status.toUpperCase(Locale.ROOT);
            if (u.startsWith("HLR_") || u.contains("SRI") || u.startsWith("NI_")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUeMapSent(List<CdrRecord> tl) {
        return hasStatus(tl, u -> u.equals("END") || u.equals("CONTINUE")
                || u.equals(CdrStatuses.BRIDGED_DONE));
    }

    private static String lastUeMapStatus(List<CdrRecord> tl) {
        String last = "END";
        for (CdrRecord r : tl) {
            if (r == null || r.status == null) {
                continue;
            }
            String u = r.status.toUpperCase(Locale.ROOT);
            if (u.equals("END") || u.equals("CONTINUE") || u.equals("ABORT")
                    || u.equals(CdrStatuses.BRIDGED_DONE)) {
                last = u.equals(CdrStatuses.BRIDGED_DONE) ? "END" : u;
            }
        }
        return last;
    }

    private static Instant whenForStatuses(List<CdrRecord> tl, String... statuses) {
        Instant found = null;
        if (tl == null || statuses == null) {
            return null;
        }
        for (CdrRecord r : tl) {
            if (r == null || r.status == null || r.createdAt == null) {
                continue;
            }
            String u = r.status.trim().toUpperCase(Locale.ROOT);
            for (String s : statuses) {
                if (s != null && u.equals(s.toUpperCase(Locale.ROOT))) {
                    found = r.createdAt;
                }
            }
        }
        return found;
    }

    private static Instant firstWhen(List<CdrRecord> tl) {
        if (tl == null) {
            return null;
        }
        for (CdrRecord r : tl) {
            if (r != null && r.createdAt != null) {
                return r.createdAt;
            }
        }
        return null;
    }

    private static String formatWhen(Instant when) {
        return when == null ? "" : WHEN.format(when);
    }

    private static boolean hasStatus(List<CdrRecord> tl, java.util.function.Predicate<String> pred) {
        for (CdrRecord r : tl) {
            if (r == null || r.status == null) {
                continue;
            }
            if (pred.test(r.status.trim().toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String shortenUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.trim();
        int scheme = u.indexOf("://");
        String rest = scheme >= 0 ? u.substring(scheme + 3) : u;
        if (rest.length() <= 42) {
            return rest;
        }
        int slash = rest.indexOf('/');
        if (slash > 0 && slash < rest.length() - 1) {
            String host = rest.substring(0, Math.min(slash, 18));
            String path = rest.substring(slash);
            if (path.length() > 22) {
                path = "…" + path.substring(path.length() - 20);
            }
            return host + path;
        }
        return rest.substring(0, 41) + "…";
    }

    private static String joinParts(String... parts) {
        StringBuilder sb = new StringBuilder();
        if (parts == null) {
            return "";
        }
        for (String p : parts) {
            if (p == null || p.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(" · ");
            }
            sb.append(p.trim());
        }
        return sb.toString();
    }

    private static String nullTo(String v, String d) {
        return v == null || v.isBlank() ? d : v.trim();
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "—";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
