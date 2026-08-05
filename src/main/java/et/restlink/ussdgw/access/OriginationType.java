package et.restlink.ussdgw.access;

/** Access-plane bearer for USSD PULL/PUSH (aligns CDR {@code origination_type}). */
public enum OriginationType {
    MAP,
    DIAMETER,
    SMPP,
    SIP;

    public static OriginationType parse(String raw) {
        if (raw == null || raw.isBlank()) return MAP;
        try {
            return OriginationType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MAP;
        }
    }
}
