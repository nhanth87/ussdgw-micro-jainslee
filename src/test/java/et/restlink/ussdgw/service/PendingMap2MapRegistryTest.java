package et.restlink.ussdgw.service;

import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.events.Map2MapRequestEvent;
import et.restlink.ussdgw.routing.RuleType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PendingMap2MapRegistryTest {

    private PendingMap2MapRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PendingMap2MapRegistry();
        set(registry, "ttlMsProp", 30_000L);
    }

    @Test
    void takeIfPhaseOnlyMatches() {
        Map2MapRequestEvent req = sample("c1", "m2m-c1");
        registry.putSri("m2m-c1", req);
        assertThat(registry.takeIfPhase("m2m-c1", PendingMap2MapRegistry.Phase.AWAITING_USSD))
                .isEmpty();
        assertThat(registry.peek("m2m-c1")).isPresent();
        assertThat(registry.takeIfPhase("m2m-c1", PendingMap2MapRegistry.Phase.AWAITING_SRI))
                .isPresent()
                .get().extracting(PendingMap2MapRegistry.Pending::req).isEqualTo(req);
        assertThat(registry.take("m2m-c1")).isEmpty();
    }

    @Test
    void putUssdAdvancesPhase() {
        Map2MapRequestEvent req = sample("c2", "m2m-c2");
        registry.putUssd("m2m-c2", req, "251971200146", "63601");
        var p = registry.takeIfPhase("m2m-c2", PendingMap2MapRegistry.Phase.AWAITING_USSD);
        assertThat(p).isPresent();
        assertThat(p.get().mscGt()).isEqualTo("251971200146");
        assertThat(p.get().imsi()).isEqualTo("63601");
    }

    @Test
    void sweepExpiredRemovesDue() {
        Map2MapRequestEvent a = sample("a", "m2m-a");
        Map2MapRequestEvent b = sample("b", "m2m-b");
        registry.putSri("m2m-a", a, 1_000L);
        registry.putSri("m2m-b", b, 100_000L);
        List<Map2MapRequestEvent> expired = registry.sweepExpired(50_000L);
        assertThat(expired).containsExactly(a);
        assertThat(registry.peek("m2m-b")).isPresent();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void outboundCorrHelper() {
        assertThat(PendingMap2MapRegistry.outboundCorr("c1")).isEqualTo("m2m-c1");
        assertThat(PendingMap2MapRegistry.outboundCorr("m2m-c1")).isEqualTo("m2m-c1");
    }

    @Test
    void ttlAlignedWithDialogBudget() {
        set(registry, "ttlMsProp", 30_000L);
        UssdConfigService config = new UssdConfigService();
        set(config, "dialogTimeoutMsProp", 60_000L);
        set(registry, "config", config);
        assertThat(registry.ttlMs()).isEqualTo(60_000L);
    }

    private static Map2MapRequestEvent sample(String corr, String outbound) {
        return new Map2MapRequestEvent(
                corr, outbound, "dlg-" + corr, 1L, "251911", "*804#", "*804#",
                "*8744#", "http://as/userinfo", RuleType.HTTP, 0, null, "vs-" + corr, "req-" + corr);
    }

    private static void set(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
