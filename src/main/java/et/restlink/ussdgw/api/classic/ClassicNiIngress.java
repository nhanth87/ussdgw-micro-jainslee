package et.restlink.ussdgw.api.classic;

import et.restlink.ussdgw.api.AsHttpWireFormat;

/**
 * Parsed AS→GW network-initiated (NI) request body fields.
 *
 * @param networkId classic {@code <dialog networkId="...">}; null when the AS did not send one,
 *                  so the caller can fall back to the tenant or configured default rather than
 *                  silently assuming network 0
 */
public record ClassicNiIngress(
        String msisdn,
        String text,
        String correlationId,
        boolean emptyDialogHandshake,
        AsHttpWireFormat rawFormat,
        Integer networkId
) {
    public ClassicNiIngress(String msisdn, String text, String correlationId,
                            boolean emptyDialogHandshake, AsHttpWireFormat rawFormat) {
        this(msisdn, text, correlationId, emptyDialogHandshake, rawFormat, null);
    }
}
