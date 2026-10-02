package et.restlink.ussdgw.bridge;

/**
 * Retry policy for late (bridged) NI pushes (P1-5, classic {@code PushRetryQueue}
 * parity). Only transient network refusals are retried — a subscriber verdict
 * ({@code absentSubscriber}, {@code unknownSubscriber}, …) is terminal on first sight.
 */
public final class NiPushRetryPolicy {
    private NiPushRetryPolicy() {}

    /** Push attempts after the initial send before the fallback runs. */
    public static final int MAX_ATTEMPTS = 3;

    /** Classic backoff: 3s / 8s / 15s. */
    public static long delayMs(int attempt) {
        return switch (Math.max(1, attempt)) {
            case 1 -> 3_000L;
            case 2 -> 8_000L;
            default -> 15_000L;
        };
    }

    /**
     * Retryable MAP error names (TS 29.002, case-insensitive substring): handset or
     * network busy, transient failure, timeout. Unknown/absent subscriber verdicts
     * and anything unrecognized are terminal (fail-closed — never silent-retry).
     */
    public static boolean isRetryable(String errorName) {
        if (errorName == null || errorName.isBlank()) {
            return false;
        }
        String n = errorName.trim().toLowerCase();
        return n.contains("busy")
                || n.contains("systemfailure")
                || n.contains("timeout")
                || n.contains("congestion");
    }
}
