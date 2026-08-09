package et.restlink.ussdgw.cdr;

/**
 * Truncate USSD strings for CDR pipe {@code detail} (~50 chars).
 * Used when AS→UE text is applied ({@code END}/{@code CONTINUE}) and on MAP2MAP hop→AS rows.
 */
public final class CdrUssdSnippet {
    /** Default ledger preview length (operator asked ~50). */
    public static final int MAX_CHARS = 50;

    private CdrUssdSnippet() {}

    /**
     * Sanitize for pipe detail (no {@code |}/newlines) and truncate with ellipsis when longer.
     */
    public static String of(String text) {
        return of(text, MAX_CHARS);
    }

    public static String of(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String t = text.replace('|', '/').replace('\r', ' ').replace('\n', ' ').trim();
        int m = Math.max(1, max);
        if (t.length() <= m) {
            return t;
        }
        return t.substring(0, m) + "…";
    }

    /**
     * Pipe fragment: {@code asUssd=&lt;snippet&gt;|asLen=N} or {@code asUssd-empty}.
     * Key {@code asUssd=} is mirrored into the CDR {@code as_ussd} column by {@link CdrService}.
     */
    public static String asUssdDetail(String text) {
        if (text == null || text.isBlank()) {
            return "asUssd-empty";
        }
        String raw = text.trim();
        return "asUssd=" + of(raw) + "|asLen=" + raw.length();
    }
}
