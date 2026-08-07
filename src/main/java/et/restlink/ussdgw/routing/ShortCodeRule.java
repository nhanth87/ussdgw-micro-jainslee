package et.restlink.ussdgw.routing;

/**
 * Short-code → AS routing rule, optionally scoped to tenant / networkId.
 * {@code mark=true} = prefix key (classic {@code exactMatch=false}): dialed strings that
 * {@code startsWith(shortCode)} route here (e.g. {@code *100*} matches {@code *100*123456#}).
 */
public record ShortCodeRule(
        String shortCode,
        RuleType ruleType,
        String asUrl,
        boolean enabled,
        String tenantId,
        int networkId,
        boolean mark
) {
    public ShortCodeRule(String shortCode, RuleType ruleType, String asUrl, boolean enabled) {
        this(shortCode, ruleType, asUrl, enabled, null, 0, false);
    }

    public ShortCodeRule(String shortCode, RuleType ruleType, String asUrl, boolean enabled,
                         String tenantId, int networkId) {
        this(shortCode, ruleType, asUrl, enabled, tenantId, networkId, false);
    }
}
