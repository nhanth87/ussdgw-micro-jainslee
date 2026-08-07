package et.restlink.ussdgw.logging;

/**
 * Masking for subscriber identifiers that would otherwise land in {@code dist/logs/} — trace
 * files are rotated but retained for days, and a full MSISDN there is subscriber PII.
 *
 * <p>CDR rows keep the full MSISDN on purpose: that table is the billing record and is access
 * controlled. Only free-text log lines are masked.
 */
public final class Pii {

    private static final int VISIBLE_SUFFIX = 4;

    private Pii() {
    }

    /** {@code 251911223344} → {@code ********3344}. Short values are masked entirely. */
    public static String maskMsisdn(String msisdn) {
        if (msisdn == null) {
            return "";
        }
        String s = msisdn.trim();
        if (s.isEmpty()) {
            return "";
        }
        if (s.length() <= VISIBLE_SUFFIX) {
            return "*".repeat(s.length());
        }
        return "*".repeat(s.length() - VISIBLE_SUFFIX) + s.substring(s.length() - VISIBLE_SUFFIX);
    }

    /** Convenience for trace details: {@code msisdn=********3344}. */
    public static String msisdnDetail(String msisdn) {
        return "msisdn=" + maskMsisdn(msisdn);
    }
}
