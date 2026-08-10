package et.restlink.ussdgw.cdr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Operator-facing CDR service plane statuses + human labels.
 * Presentation only — does not change emit / AdaptiveTimeout / rollup law.
 *
 * <p>Primary operator signals: AS USSD (~50) + outcome ({@code END}/{@code CONTINUE}/fail),
 * not {@code GATE_ARMED} or {@code service=VirtualSessionBridge/…} pipe dumps.
 */
public final class CdrServiceStatuses {
    private CdrServiceStatuses() {}

    public record Plane(String id, String label, String state, String cssClass) {}

    public record Outcome(String status, String humanLabel, String cssClass) {}

    /**
     * Prefer terminal AS→UE / fail / hop outcome over in-flight gate arming.
     * {@code GATE_ARMED} is never the hero when a stronger status exists in the timeline.
     */
    public static Outcome primaryOutcome(CdrRecord focus, List<CdrRecord> timelineOldestFirst) {
        CdrRecord best = focus;
        int bestRank = outcomeRank(focus == null ? null : focus.status);
        if (timelineOldestFirst != null) {
            for (CdrRecord r : timelineOldestFirst) {
                if (r == null || r.status == null) {
                    continue;
                }
                int rank = outcomeRank(r.status);
                if (rank > bestRank) {
                    best = r;
                    bestRank = rank;
                }
            }
        }
        String st = best == null || best.status == null || best.status.isBlank() ? "—" : best.status.trim();
        String phase = best == null ? null : best.phase;
        return new Outcome(st, humanStatus(st), CdrStatuses.ledgerChipClass(phase, st));
    }

    /**
     * Rank for display hero. Higher wins.
     * GATE_ARMED / GATED sit near the bottom so END / fail / hop outcomes surface.
     */
    static int outcomeRank(String status) {
        if (status == null || status.isBlank()) {
            return 0;
        }
        String u = status.trim().toUpperCase(Locale.ROOT);
        if (u.equals("END") || u.equals(CdrStatuses.BRIDGED_DONE) || u.equals("SUCCESS")) {
            return 100;
        }
        if (u.equals("CONTINUE")) {
            return 90;
        }
        if (CdrSessionRollup.isTerminalFail(u) || u.equals("ABORT")) {
            return 95;
        }
        if (u.equals(Map2MapCdr.HOP_CLOSE)) {
            return 70;
        }
        if (u.equals(Map2MapCdr.HOP_FAIL) || u.equals(Map2MapCdr.HOP_ABORT)
                || u.equals(Map2MapCdr.HLR_REJECT)) {
            return 85;
        }
        if (u.equals(Map2MapCdr.AS_ROUTED) || u.equals(Map2MapCdr.OK)
                || u.equals(Map2MapCdr.COMPLETE_AFTER_GATE)
                || u.equals(CdrStatuses.GATED_AS_ACK)) {
            return 60;
        }
        if (u.equals(CdrStatuses.BRIDGED) || u.equals(CdrStatuses.QUEUED)
                || u.equals(CdrStatuses.GATE_EXPIRED)) {
            return 40;
        }
        if (u.equals(CdrStatuses.GATE_ARMED) || u.equals(CdrStatuses.GATED)) {
            return 10;
        }
        if (CdrStatuses.isMap2MapFamily(u)) {
            return 30;
        }
        if (CdrStatuses.isGateFamily(u)) {
            return 20;
        }
        return 15;
    }

    /** Human label for a machine CDR status (filter still uses machine tokens). */
    public static String humanStatus(String status) {
        if (status == null || status.isBlank()) {
            return "—";
        }
        String u = status.trim().toUpperCase(Locale.ROOT);
        return switch (u) {
            case "GATE_ARMED", "GATED" -> "Gate armed";
            case "BRIDGED" -> "Gate fired → UE wait";
            case "GATE_EXPIRED" -> "Gate expired";
            case "GATE_NO_BRIDGE" -> "Gate / no bridge";
            case "QUEUED" -> "NI queued after bridge";
            case "BRIDGED_DONE" -> "NI done after bridge";
            case "GATED_AS_NOTIFY" -> "AS notify queued";
            case "GATED_AS_ACK" -> "AS HTTP ack";
            case "GATED_AS_FAIL" -> "AS HTTP fail";
            case "GATED_AS_SKIP" -> "AS notify skipped";
            case "AS_EMPTY_BODY" -> "AS empty body";
            case "AS_PULL_FAIL" -> "AS pull fail";
            case "AS_DROP" -> "AS drop (gen/state)";
            case "MS_DIGIT" -> "MS digit (menu)";
            case "END" -> "AS→UE end";
            case "CONTINUE" -> "AS→UE continue";
            case "ABORT" -> "AS→UE abort";
            case "MAP2MAP_ARMED" -> "MAP2MAP armed";
            case "MAP2MAP_HOP_START" -> "Hop start";
            case "MAP2MAP_USSD_SENT" -> "Hop USSD sent";
            case "MAP2MAP_SRI_SENT" -> "SRI-SM sent";
            case "MAP2MAP_HOP_CLOSE" -> "Hop close (text)";
            case "MAP2MAP_HOP_FAIL" -> "Hop fail (no text)";
            case "MAP2MAP_HOP_ABORT" -> "Hop abort";
            case "MAP2MAP_AS_ROUTED" -> "AS pull routed";
            case "MAP2MAP_OK" -> "Hop OK + AS routed";
            case "MAP2MAP_TIMEOUT" -> "MAP2MAP timeout";
            case "HLR_REJECT" -> "HLR hop reject";
            case "ZOMBIE" -> "Zombie / dead MAP";
            default -> status.trim();
        };
    }

    /**
     * Compact timeline line — never dump {@code service=VirtualSessionBridge|…} pipe soup.
     * Prefer AS USSD snippet, hop outcome, or human status.
     */
    public static String timelineSummary(CdrRecord r) {
        if (r == null) {
            return "";
        }
        String st = r.status == null ? "" : r.status.trim().toUpperCase(Locale.ROOT);
        Map<String, String> kv = r.detail == null || r.detail.isBlank()
                ? Map.of() : CdrSessionDigest.parseDetail(r.detail);
        // Gate arming — budget only; never inherit rolled session as_ussd as "AS: …".
        if (st.equals(CdrStatuses.GATE_ARMED) || st.equals(CdrStatuses.GATED)) {
            String gateMs = kv.get("gateMs");
            if (gateMs == null || gateMs.isBlank()) {
                gateMs = r.gateMs != null && r.gateMs > 0 ? Long.toString(r.gateMs) : null;
            }
            return gateMs != null ? ("budget " + gateMs + " ms") : "budget armed";
        }
        if (st.equals(CdrStatuses.MS_DIGIT)) {
            String dig = kv.get("digit");
            String gen = kv.get("gen");
            StringBuilder ms = new StringBuilder("digit=");
            ms.append(dig == null || dig.isBlank() ? "?" : dig);
            if (gen != null && !gen.isBlank()) {
                ms.append(" gen=").append(gen);
            }
            return ms.toString();
        }
        if (st.equals(CdrStatuses.AS_DROP)) {
            String reason = kv.get("reason");
            String wire = kv.get("wireGen");
            String sess = kv.get("sessionGen");
            StringBuilder drop = new StringBuilder("drop");
            if (reason != null && !reason.isBlank()) {
                drop.append('=').append(reason);
            }
            if (wire != null || sess != null) {
                drop.append(" wireGen=").append(wire == null ? "?" : wire)
                        .append(" sessionGen=").append(sess == null ? "?" : sess);
            }
            return drop.toString();
        }
        // AS text only when this milestone's detail carries asUssd= (or AS→UE statuses).
        String detailAs = kv.get("asUssd");
        boolean asRow = st.equals("END") || st.equals("CONTINUE") || st.equals("ABORT")
                || st.equals(Map2MapCdr.AS_ROUTED) || st.equals(Map2MapCdr.OK)
                || st.equals(CdrStatuses.GATED_AS_ACK) || st.equals(CdrStatuses.GATED_AS_NOTIFY)
                || st.equals(Map2MapCdr.HOP_CLOSE);
        String as = detailAs != null && !detailAs.isBlank() ? detailAs
                : (asRow ? r.asUssd : null);
        String snip = CdrUssdSnippet.of(as);
        if (!snip.isEmpty()) {
            String gen = kv.get("gen");
            return gen != null && !gen.isBlank() ? ("AS: " + snip + " gen=" + gen) : ("AS: " + snip);
        }
        String hop = kv.get("hopOutcome");
        if (hop != null && !hop.isBlank()) {
            return "hop=" + hop;
        }
        String http = kv.get("http");
        if (http != null && !http.isBlank()) {
            return "http=" + http;
        }
        String note = kv.get("note");
        if (note != null && !note.isBlank() && !note.contains("VirtualSession")
                && !note.equals("armed-not-fired") && !note.equals("AS→UE")) {
            return note.length() > 40 ? note.substring(0, 39) + "…" : note;
        }
        return humanStatus(r.status);
    }

    /**
     * STATUS OF ALL SERVICES — fixed planes + conditional SRI/NI.
     * States derived from digest answers + timeline; honest {@code —} when unknown.
     */
    public static List<Plane> planes(CdrSessionDigest.Digest dig, CdrRecord focus) {
        List<CdrRecord> tl = dig == null ? List.of()
                : (dig.timelineOldestFirst() == null ? List.of() : dig.timelineOldestFirst());
        List<Plane> out = new ArrayList<>(6);

        out.add(plane("map_mo", "MAP MO", mapMoState(focus, tl), cssForMo(tl)));
        if (hasHopOrSri(tl, dig)) {
            out.add(plane("hlr_hop", "HLR / hop",
                    answerState(dig == null ? null : dig.hlrResponse(),
                            dig == null ? null : dig.upperHlrSent()),
                    cssForAnswer(dig == null ? null : dig.hlrResponse())));
        }
        out.add(plane("bridge", "Bridge / Adaptive", bridgeState(dig, tl), cssForBridge(tl)));
        out.add(plane("as_http", "AS HTTP", asHttpState(dig, focus), cssForAs(dig)));
        out.add(plane("map_ue", "MAP UE reply", mapUeState(tl), cssForUe(tl)));
        if (hasNi(tl)) {
            out.add(plane("ni", "NI / SRI", niState(tl), cssForNi(tl)));
        }
        return List.copyOf(out);
    }

    private static Plane plane(String id, String label, String state, String css) {
        return new Plane(id, label, state == null || state.isBlank() ? "—" : state, css);
    }

    private static String mapMoState(CdrRecord focus, List<CdrRecord> tl) {
        String sc = focus != null ? focus.shortCode : null;
        String msisdn = focus != null ? focus.msisdn : null;
        for (CdrRecord r : tl) {
            if (r == null) {
                continue;
            }
            if ((sc == null || sc.isBlank()) && r.shortCode != null && !r.shortCode.isBlank()) {
                sc = r.shortCode;
            }
            if ((msisdn == null || msisdn.isBlank()) && r.msisdn != null && !r.msisdn.isBlank()) {
                msisdn = r.msisdn;
            }
        }
        if (sc != null && !sc.isBlank()) {
            return "MO " + sc.trim();
        }
        if (msisdn != null && !msisdn.isBlank()) {
            return "MO received";
        }
        return tl.isEmpty() ? "—" : "session";
    }

    private static String answerState(CdrSessionDigest.Answer primary, CdrSessionDigest.Answer sent) {
        if (primary != null && primary.value() != null && !"unknown".equals(primary.value())) {
            return primary.value();
        }
        if (sent != null && sent.value() != null && !"unknown".equals(sent.value())) {
            return "sent · " + sent.value();
        }
        return "—";
    }

    private static String bridgeState(CdrSessionDigest.Digest dig, List<CdrRecord> tl) {
        String latestGate = null;
        for (CdrRecord r : tl) {
            if (r == null || r.status == null) {
                continue;
            }
            String u = r.status.toUpperCase(Locale.ROOT);
            if (u.equals(CdrStatuses.GATE_EXPIRED) || u.equals(CdrStatuses.GATE_NO_BRIDGE)
                    || u.equals(CdrStatuses.BRIDGED) || u.equals(CdrStatuses.GATE_ARMED)
                    || u.equals(CdrStatuses.GATED) || u.equals(CdrStatuses.QUEUED)
                    || u.equals(CdrStatuses.BRIDGED_DONE)) {
                latestGate = u;
            }
        }
        Long gateMs = dig == null ? null : dig.gateMs();
        String budget = gateMs != null && gateMs > 0 ? (gateMs + " ms") : null;
        if (latestGate == null) {
            return budget == null ? "—" : ("budget " + budget);
        }
        String human = humanStatus(latestGate);
        return budget == null ? human : (human + " · " + budget);
    }

    private static String asHttpState(CdrSessionDigest.Digest dig, CdrRecord focus) {
        String snip = CdrUssdSnippet.resolveForDisplay(
                focus == null ? null : focus.asUssd,
                dig == null ? null : dig.detailFields().get("asUssd"));
        CdrSessionDigest.Answer resp = dig == null ? null : dig.asResponse();
        if (resp != null && resp.value() != null && !"unknown".equals(resp.value())) {
            if (!snip.isEmpty()) {
                return resp.value() + " · " + snip;
            }
            return resp.value();
        }
        CdrSessionDigest.Answer sent = dig == null ? null : dig.asNotifySent();
        if (sent != null && sent.value() != null && !"unknown".equals(sent.value())) {
            return !snip.isEmpty() ? (sent.value() + " · " + snip) : sent.value();
        }
        return snip.isEmpty() ? "—" : snip;
    }

    private static String mapUeState(List<CdrRecord> tl) {
        String last = null;
        for (CdrRecord r : tl) {
            if (r == null || r.status == null) {
                continue;
            }
            String u = r.status.toUpperCase(Locale.ROOT);
            if (u.equals("END") || u.equals("CONTINUE") || u.equals("ABORT")
                    || u.equals(CdrStatuses.BRIDGED_DONE)) {
                last = u;
            }
        }
        return last == null ? "—" : humanStatus(last);
    }

    private static String niState(List<CdrRecord> tl) {
        for (int i = tl.size() - 1; i >= 0; i--) {
            CdrRecord r = tl.get(i);
            if (r == null || r.status == null) {
                continue;
            }
            String u = r.status.toUpperCase(Locale.ROOT);
            if (u.equals(CdrStatuses.BRIDGED_DONE) || u.equals(CdrStatuses.QUEUED)
                    || u.contains("SRI") || u.equals("NI_NO_MSC") || u.startsWith("NI_")) {
                return humanStatus(u);
            }
        }
        return "seen";
    }

    private static boolean hasHopOrSri(List<CdrRecord> tl, CdrSessionDigest.Digest dig) {
        if (dig != null) {
            if (dig.upperHlrSent() != null && !"unknown".equals(dig.upperHlrSent().value())) {
                return true;
            }
            if (dig.hlrResponse() != null && !"unknown".equals(dig.hlrResponse().value())) {
                return true;
            }
        }
        for (CdrRecord r : tl) {
            if (r != null && r.status != null && CdrStatuses.isMap2MapFamily(r.status)) {
                return true;
            }
            if (r != null && r.status != null) {
                String u = r.status.toUpperCase(Locale.ROOT);
                if (u.startsWith("HLR_") || u.contains("SRI")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasNi(List<CdrRecord> tl) {
        for (CdrRecord r : tl) {
            if (r == null || r.status == null) {
                continue;
            }
            String u = r.status.toUpperCase(Locale.ROOT);
            if (u.equals(CdrStatuses.QUEUED) || u.equals(CdrStatuses.BRIDGED_DONE)
                    || u.contains("SRI") || u.startsWith("NI_") || u.equals("NI_NO_MSC")) {
                return true;
            }
        }
        return false;
    }

    private static String cssForMo(List<CdrRecord> tl) {
        return tl.isEmpty() ? "cdr-svc--mute" : "cdr-svc--ok";
    }

    private static String cssForAnswer(CdrSessionDigest.Answer a) {
        if (a == null || a.value() == null || "unknown".equals(a.value())) {
            return "cdr-svc--mute";
        }
        String v = a.value().toLowerCase(Locale.ROOT);
        if (v.contains("fail") || v.contains("reject") || v.contains("abort")
                || v.contains("none") || v.contains("empty") || v.startsWith("no")) {
            return "cdr-svc--fail";
        }
        if (v.contains("yes") || v.contains("text") || v.contains("close")) {
            return "cdr-svc--ok";
        }
        return "cdr-svc--live";
    }

    private static String cssForBridge(List<CdrRecord> tl) {
        for (int i = tl.size() - 1; i >= 0; i--) {
            CdrRecord r = tl.get(i);
            if (r == null || r.status == null) {
                continue;
            }
            String u = r.status.toUpperCase(Locale.ROOT);
            if (u.equals(CdrStatuses.GATE_EXPIRED) || u.equals(CdrStatuses.GATE_NO_BRIDGE)) {
                return "cdr-svc--fail";
            }
            if (u.equals(CdrStatuses.BRIDGED) || u.equals(CdrStatuses.BRIDGED_DONE)) {
                return "cdr-svc--live";
            }
            if (u.equals(CdrStatuses.GATE_ARMED) || u.equals(CdrStatuses.GATED)) {
                return "cdr-svc--gated";
            }
        }
        return "cdr-svc--mute";
    }

    private static String cssForAs(CdrSessionDigest.Digest dig) {
        if (dig == null || dig.asResponse() == null) {
            return "cdr-svc--mute";
        }
        String v = dig.asResponse().value() == null ? ""
                : dig.asResponse().value().toLowerCase(Locale.ROOT);
        if (v.contains("fail") || v.contains("empty")) {
            return "cdr-svc--fail";
        }
        if (v.contains("yes") || v.contains("ack") || v.contains("as→ue") || v.contains("as->ue")) {
            return "cdr-svc--ok";
        }
        if (v.contains("routed") || v.contains("queued")) {
            return "cdr-svc--live";
        }
        return "cdr-svc--mute";
    }

    private static String cssForUe(List<CdrRecord> tl) {
        for (int i = tl.size() - 1; i >= 0; i--) {
            CdrRecord r = tl.get(i);
            if (r == null || r.status == null) {
                continue;
            }
            String u = r.status.toUpperCase(Locale.ROOT);
            if (u.equals("END") || u.equals(CdrStatuses.BRIDGED_DONE)) {
                return "cdr-svc--ok";
            }
            if (u.equals("CONTINUE")) {
                return "cdr-svc--live";
            }
            if (u.equals("ABORT")) {
                return "cdr-svc--fail";
            }
        }
        return "cdr-svc--mute";
    }

    private static String cssForNi(List<CdrRecord> tl) {
        for (int i = tl.size() - 1; i >= 0; i--) {
            CdrRecord r = tl.get(i);
            if (r == null || r.status == null) {
                continue;
            }
            String u = r.status.toUpperCase(Locale.ROOT);
            if (u.contains("FAIL") || u.contains("TIMEOUT") || u.equals("NI_NO_MSC")
                    || u.equals("SRI_NO_MSC")) {
                return "cdr-svc--fail";
            }
            if (u.equals(CdrStatuses.BRIDGED_DONE) || u.contains("OK")) {
                return "cdr-svc--ok";
            }
        }
        return "cdr-svc--live";
    }
}
