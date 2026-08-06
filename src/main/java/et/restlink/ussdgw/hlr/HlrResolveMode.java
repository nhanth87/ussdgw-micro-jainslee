package et.restlink.ussdgw.hlr;

/**
 * Per-network HLR face policy for inbound SRI-SM.
 * Default is {@link #PROXY_MAP} fail-closed (no silent FAKE).
 */
public enum HlrResolveMode {
    /** Answer immediately with configured fake IMSI/MSC (ops must set). */
    FAKE,
    /** Forward SRI-SM to upper HLR GT; relay response. Fail-closed if no upper GT / timeout. */
    PROXY_MAP,
    /** Diameter ULR/ULA stub toward HSS; map serving node → SRI-SM Response. Fail-closed. */
    PROXY_DIAMETER,
    /** Answer FAKE immediately, then async enrich via MAP or Diameter (non-blocking face). */
    FAKE_THEN_RESOLVE;

    public static HlrResolveMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PROXY_MAP;
        }
        String n = raw.trim().toUpperCase().replace('-', '_');
        return switch (n) {
            case "FAKE" -> FAKE;
            case "PROXY_MAP", "PROXY", "MAP" -> PROXY_MAP;
            case "PROXY_DIAMETER", "DIAMETER", "DIAM" -> PROXY_DIAMETER;
            case "FAKE_THEN_RESOLVE", "FAKE_THEN", "FAKE_RESOLVE" -> FAKE_THEN_RESOLVE;
            default -> PROXY_MAP;
        };
    }
}
