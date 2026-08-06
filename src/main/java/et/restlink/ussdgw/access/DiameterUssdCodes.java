package et.restlink.ussdgw.access;

/** Shared Diameter USSD AVP / command constants (lab + 3GPP-style payload map). */
public final class DiameterUssdCodes {
    /** Lab application id for USSD-over-Diameter (camel-generic RA). */
    public static final long USSD_APP_ID = 16777358L;
    /** Lab USSD-Request command code. */
    public static final int USSD_REQUEST = 8388729;
    /** User-Name / MSISDN carrier. */
    public static final int AVP_USER_NAME = 1;
    /** USSD string payload. */
    public static final int AVP_USSD_STRING = 1470;
    /** Service code / short code. */
    public static final int AVP_SERVICE_CODE = 1471;
    /** Correlation id for NI/MO pairing. */
    public static final int AVP_CORRELATION = 1472;

    private DiameterUssdCodes() {}
}
