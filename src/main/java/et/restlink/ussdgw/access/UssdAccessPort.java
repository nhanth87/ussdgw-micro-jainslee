package et.restlink.ussdgw.access;

import et.restlink.ussdgw.bridge.VirtualSession;

/**
 * Per-bearer adapter: MO pull ingress and NI push egress.
 * MAP / Diameter / SIP / SMPP — Diameter & SIP NI live when RA peer/RA ready; else STUB_QUEUED.
 */
public interface UssdAccessPort {
    OriginationType type();

    /** Network-initiated push (UnstructuredSS-Request / stub equivalent). */
    void requestNiPush(VirtualSession session, String text);

    /**
     * Network-initiated push with one-shot hint. Default ignores the flag (bearer
     * keeps its 2-arg behavior); MAP honors it (Notify vs Request, P1-3).
     */
    default void requestNiPush(VirtualSession session, String text, boolean notifyOnly) {
        requestNiPush(session, text);
    }

    /**
     * Lab / stub MO pull: create session + startAwaitingAs. MAP uses the live SBB path instead.
     * @return session stored and awaiting AS, or null if rejected
     */
    VirtualSession acceptMoPull(UssdAccessSession access);
}
