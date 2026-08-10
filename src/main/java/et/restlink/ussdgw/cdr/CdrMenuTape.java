package et.restlink.ussdgw.cdr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fold multimenu MS digit / AS CONTINUE milestones from {@code events_json} for admin prove.
 * No new persist path — read-only over the existing CDR tape.
 */
public final class CdrMenuTape {
    private CdrMenuTape() {}

    public record Step(String kind, int gen, String text) {}

    public record Summary(int digitCount, int continueCount, int maxGen, String path, List<Step> steps) {
        public boolean multimenu() {
            return digitCount > 0 || continueCount > 1;
        }
    }

    /** Oldest-first timeline → menu steps (MS_DIGIT / CONTINUE / END with gen). */
    public static Summary fromTimeline(List<CdrRecord> oldestFirst) {
        List<Step> steps = new ArrayList<>();
        int digits = 0;
        int continues = 0;
        int maxGen = 0;
        if (oldestFirst != null) {
            for (CdrRecord r : oldestFirst) {
                if (r == null || r.status == null) {
                    continue;
                }
                String st = r.status.trim().toUpperCase(Locale.ROOT);
                Map<String, String> kv = r.detail == null || r.detail.isBlank()
                        ? Map.of() : CdrSessionDigest.parseDetail(r.detail);
                int gen = parseInt(kv.get("gen"), 0);
                if (gen > maxGen) {
                    maxGen = gen;
                }
                if (st.equals(CdrStatuses.MS_DIGIT)) {
                    digits++;
                    String dig = firstNonBlank(kv.get("digit"), CdrUssdSnippet.of(r.asUssd));
                    steps.add(new Step("DIGIT", gen, dig.isEmpty() ? "?" : dig));
                } else if (st.equals("CONTINUE") || st.equals("END")) {
                    if (st.equals("CONTINUE")) {
                        continues++;
                    }
                    String as = firstNonBlank(kv.get("asUssd"), CdrUssdSnippet.of(r.asUssd));
                    steps.add(new Step(st, gen, as));
                }
            }
        }
        String path = formatPath(steps);
        return new Summary(digits, continues, maxGen, path, List.copyOf(steps));
    }

    static String formatPath(List<Step> steps) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Step s : steps) {
            if (s == null) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(" → ");
            }
            if ("DIGIT".equals(s.kind())) {
                sb.append("dig");
                if (s.gen() > 0) {
                    sb.append('[').append(s.gen()).append(']');
                }
                sb.append('=').append(s.text() == null || s.text().isBlank() ? "?" : s.text());
            } else {
                sb.append(s.kind().toLowerCase(Locale.ROOT));
                if (s.gen() > 0) {
                    sb.append('[').append(s.gen()).append(']');
                }
                if (s.text() != null && !s.text().isBlank()) {
                    sb.append('=').append(CdrUssdSnippet.of(s.text(), 24));
                }
            }
        }
        return sb.toString();
    }

    private static int parseInt(String raw, int dflt) {
        if (raw == null || raw.isBlank()) {
            return dflt;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return b == null ? "" : b.trim();
    }
}
