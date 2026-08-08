package et.restlink.ussdgw.events;

import com.microjainslee.api.SleeEvent;

/**
 * MO short-code armed for MAP2MAP re-route (Case 2): inbound processUnstructured is parked on
 * {@code inboundDialogId}; {@code Map2MapSbb} opens an outbound UnstructuredSS-Request
 * with {@code redirectUssd} text toward upper HLR / hop dest (no SRI), then Parent continues
 * to HTTP AS with the hop text. Stay-on-call = AdaptiveTimeout + Virtual Bridge at ingress.
 *
 * <p>When {@code hopDestGt} is set, the hop addresses that GT/SSN directly.
 * When blank, CalledParty = HLR Face {@code ussd.hlr.upper-gt} + SSN 6 (or {@code hopDestSsn}).
 */
public record Map2MapRequestEvent(
        String correlationId,
        String outboundCorr,
        String inboundDialogId,
        long inboundInvokeId,
        String msisdn,
        String shortCode,
        String dialedUssd,
        String redirectUssd,
        String asUrl,
        et.restlink.ussdgw.routing.RuleType ruleType,
        int networkId,
        String tenantId,
        String virtualSessionId,
        String requestId,
        boolean mark,
        String hlrMode,
        String hopDestGt,
        Integer hopDestSsn
) implements SleeEvent {
    /** Compat: older 14-field shape without mark/hlrMode/hop dest. */
    public Map2MapRequestEvent(
            String correlationId,
            String outboundCorr,
            String inboundDialogId,
            long inboundInvokeId,
            String msisdn,
            String shortCode,
            String dialedUssd,
            String redirectUssd,
            String asUrl,
            et.restlink.ussdgw.routing.RuleType ruleType,
            int networkId,
            String tenantId,
            String virtualSessionId,
            String requestId) {
        this(correlationId, outboundCorr, inboundDialogId, inboundInvokeId, msisdn, shortCode,
                dialedUssd, redirectUssd, asUrl, ruleType, networkId, tenantId,
                virtualSessionId, requestId, false, null, null, null);
    }

    /** Compat: 16-field shape with mark/hlrMode, no hop dest. */
    public Map2MapRequestEvent(
            String correlationId,
            String outboundCorr,
            String inboundDialogId,
            long inboundInvokeId,
            String msisdn,
            String shortCode,
            String dialedUssd,
            String redirectUssd,
            String asUrl,
            et.restlink.ussdgw.routing.RuleType ruleType,
            int networkId,
            String tenantId,
            String virtualSessionId,
            String requestId,
            boolean mark,
            String hlrMode) {
        this(correlationId, outboundCorr, inboundDialogId, inboundInvokeId, msisdn, shortCode,
                dialedUssd, redirectUssd, asUrl, ruleType, networkId, tenantId,
                virtualSessionId, requestId, mark, hlrMode, null, null);
    }

    /** Compat alias for redirect USSD string (DB column {@code map2map_gt}). */
    public String map2mapGt() {
        return redirectUssd;
    }

    /** Fixed hop when hopDestGt is non-blank — Case 2 explicit CalledParty (else upper-gt). */
    public boolean fixedHopArmed() {
        return hopDestGt != null && !hopDestGt.isBlank();
    }

    /** Digits-only CalledParty GT for fixed hop. */
    public String hopDestGtDigits() {
        return et.restlink.ussdgw.routing.ShortCodeRule.map2mapCalledGtDigits(hopDestGt);
    }

    /**
     * Effective CalledParty SSN for fixed hop (configured 1..255, else default 6).
     */
    public int effectiveHopDestSsn() {
        if (hopDestSsn != null && hopDestSsn >= 1 && hopDestSsn <= 255) {
            return hopDestSsn;
        }
        return 6;
    }
}
