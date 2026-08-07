package et.restlink.ussdgw.api;

/**
 * Dual-mode AS HTTP body format: classic {@code <dialog>} XML or greenfield JSON.
 */
public enum AsHttpWireFormat {
    XML,
    JSON;

    /**
     * Case-insensitive parse; blank / null → {@link #XML} (classic default).
     */
    public static AsHttpWireFormat parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return XML;
        }
        return switch (raw.trim().toUpperCase()) {
            case "JSON" -> JSON;
            default -> XML;
        };
    }

    public String contentType() {
        return this == JSON
                ? "application/json; charset=utf-8"
                : "text/xml; charset=utf-8";
    }
}
