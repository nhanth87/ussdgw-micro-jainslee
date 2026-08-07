package et.restlink.ussdgw.api;

/**
 * HTTP/gRPC AS interaction mode hint on pull (GW→AS) and admin panels.
 * AS may still return {@code async=true} for ASYNC_ACK regardless of hint.
 */
public enum AsMode {
    /** Immediate CONTINUE/END/ABORT on the pull response. */
    SYNC,
    /** Pull returns {@code async=true}; content later via /as/callback or gRPC Callback. */
    ASYNC_ACK,
    /** Bridge armed — late content after adaptive gate may S1-release then NI reconcile. */
    BRIDGE;

    public String wire() {
        return name();
    }

    public static AsMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        for (AsMode m : values()) {
            if (m.name().equalsIgnoreCase(s) || m.name().replace('_', '-').equalsIgnoreCase(s)) {
                return m;
            }
        }
        return null;
    }
}
