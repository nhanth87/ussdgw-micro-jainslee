package et.restlink.ussdgw.routing;

/** Short-code → AS routing rule, optionally scoped to tenant / networkId. */
public record ShortCodeRule(
        String shortCode,
        RuleType ruleType,
        String asUrl,
        boolean enabled,
        String tenantId,
        int networkId
) {
    public ShortCodeRule(String shortCode, RuleType ruleType, String asUrl, boolean enabled) {
        this(shortCode, ruleType, asUrl, enabled, null, 0);
    }
}
