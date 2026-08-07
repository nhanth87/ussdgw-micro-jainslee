package et.restlink.ussdgw.routing;

/**
 * Short-code → AS routing rule, optionally scoped to tenant / networkId / app user.
 * {@code mark=true} = prefix key (classic {@code exactMatch=false}): dialed strings that
 * {@code startsWith(shortCode)} route here (e.g. {@code *100*} matches {@code *100*123456#}).
 * {@code appUsername} binds ownership for NI app-user preference (null = shared / MO-default).
 */
public record ShortCodeRule(
        String shortCode,
        RuleType ruleType,
        String asUrl,
        boolean enabled,
        String tenantId,
        int networkId,
        boolean mark,
        String appUsername
) {
    public ShortCodeRule(String shortCode, RuleType ruleType, String asUrl, boolean enabled) {
        this(shortCode, ruleType, asUrl, enabled, null, 0, false, null);
    }

    public ShortCodeRule(String shortCode, RuleType ruleType, String asUrl, boolean enabled,
                         String tenantId, int networkId) {
        this(shortCode, ruleType, asUrl, enabled, tenantId, networkId, false, null);
    }

    public ShortCodeRule(String shortCode, RuleType ruleType, String asUrl, boolean enabled,
                         String tenantId, int networkId, boolean mark) {
        this(shortCode, ruleType, asUrl, enabled, tenantId, networkId, mark, null);
    }
}
