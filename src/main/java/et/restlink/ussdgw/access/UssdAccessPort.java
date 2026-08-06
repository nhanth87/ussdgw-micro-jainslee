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
     * Lab / stub MO pull: create session + startAwaitingAs. MAP uses the live SBB path instead.
     * @return session stored and awaiting AS, or null if rejected
     */
    VirtualSession acceptMoPull(UssdAccessSession access);
}
