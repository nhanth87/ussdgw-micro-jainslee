package et.restlink.ussdgw.bridge;

public enum VirtualSessionState {
    ACTIVE,
    AWAITING_AS,
    /**
     * Exclusive claim held by the thread that is turning an AS content response into a
     * MAP reply or an NI push. Classic parity: {@code BRIDGED -> PUSH_PENDING} in
     * {@code BridgeReconciler} is the single idempotency point; {@code RESPONDING} is the
     * same CAS applied one step earlier so the pull (sync) and callback (push) channels
     * cannot both act on one correlation.
     */
    RESPONDING,
    S1_RELEASED,
    PUSH_PENDING,
    COMPLETED,
    ABORTED,
    FAILED,
    ZOMBIE;

    /** Terminal states: the ussdTx Profile row is dropped once reached. */
    public boolean terminal() {
        return this == COMPLETED || this == ABORTED || this == FAILED || this == ZOMBIE;
    }
}
