package et.restlink.ussdgw.hlr;

import et.restlink.ussdgw.config.RuntimeConfigStore;
import et.restlink.ussdgw.config.UssdConfigService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HlrResolvePolicyRuleOverrideTest {
    private HlrResolvePolicy policy;
    private RuntimeConfigStore kv;

    @BeforeEach
    void setUp() throws Exception {
        kv = new RuntimeConfigStore();
        UssdConfigService config = new UssdConfigService();
        set(config, "store", kv);
        set(config, "hlrModeProp", "PROXY_MAP");
        set(config, "hlrFakeImsiProp", java.util.Optional.of("636010000000001"));
        set(config, "hlrFakeMscGtProp", java.util.Optional.of("251911000099"));
        set(config, "ussdGtProp", "251971200100");
        set(config, "hlrUpperGtProp", java.util.Optional.of("251971200200"));
        policy = new HlrResolvePolicy();
        set(policy, "config", config);
    }

    @Test
    void inheritUsesGlobalProxy() {
        assertThat(policy.resolveMode(0, "911", null)).isEqualTo(HlrResolveMode.PROXY_MAP);
        assertThat(policy.resolveMode(0, "911", "INHERIT")).isEqualTo(HlrResolveMode.PROXY_MAP);
        assertThat(policy.usesFakeForOutbound(0, "911", "INHERIT")).isFalse();
    }

    @Test
    void ruleFakeOverridesGlobalProxy() {
        assertThat(policy.resolveMode(0, "911", "FAKE")).isEqualTo(HlrResolveMode.FAKE);
        assertThat(policy.usesFakeForOutbound(0, "911", "FAKE")).isTrue();
    }

    @Test
    void ruleProxyOverridesGlobalFake() throws Exception {
        injectKv(kv, RuntimeConfigStore.Keys.HLR_MODE, "FAKE");
        assertThat(policy.usesFakeForOutbound(0, "911", null)).isTrue();
        assertThat(policy.usesFakeForOutbound(0, "911", "PROXY_MAP")).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static void injectKv(RuntimeConfigStore store, String key, String value) throws Exception {
        var f = RuntimeConfigStore.class.getDeclaredField("cache");
        f.setAccessible(true);
        ((java.util.concurrent.ConcurrentHashMap<String, String>) f.get(store)).put(key, value);
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                var f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new IllegalStateException("No field " + field);
    }
}
