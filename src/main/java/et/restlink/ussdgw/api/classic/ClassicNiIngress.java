package et.restlink.ussdgw.api.classic;

import et.restlink.ussdgw.api.AsHttpWireFormat;

/**
 * Parsed AS→GW network-initiated (NI) request body fields.
 */
public record ClassicNiIngress(
        String msisdn,
        String text,
        String correlationId,
        boolean emptyDialogHandshake,
        AsHttpWireFormat rawFormat
) {}
