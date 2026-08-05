package et.restlink.ussdgw.access;

/**
 * Immutable view of an access-plane USSD dialog (MO pull or NI push handle).
 */
public record UssdAccessSession(
        String correlationId,
        String msisdn,
        String shortCode,
        int networkId,
        String tenantId,
        OriginationType originationType,
        String dialogHandle
) {
    public UssdAccessSession {
        if (originationType == null) originationType = OriginationType.MAP;
        if (dialogHandle == null) dialogHandle = "";
        if (correlationId == null) correlationId = "";
    }
}
