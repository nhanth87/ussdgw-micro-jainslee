package et.restlink.ussdgw.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same short code, different companies: each company has its own SS7 link (networkId) and its
 * own AS. A dial is answered by the AS of the company whose link it came in on.
 */
class ShortCodeRoutingPerTenantTest {

    private static final int NET_A = 1;
    private static final int NET_B = 2;
    private static final int NET_C = 3;

    private ShortCodeRoutingService routing;

    @BeforeEach
    void setUp() {
        routing = new ShortCodeRoutingService();
        routing.put(new ShortCodeRule("*123#", RuleType.HTTP, "http://asp1.compA/ussd", true, "compA", NET_A));
        routing.put(new ShortCodeRule("*123#", RuleType.HTTP, "http://asp2.compB/ussd", true, "compB", NET_B));
    }

    @Test
    void companyALinkGoesToAsp1AndCompanyBLinkGoesToAsp2() {
        assertThat(routing.find("*123#", null, NET_A, null)).get()
                .extracting(ShortCodeRule::asUrl).isEqualTo("http://asp1.compA/ussd");
        assertThat(routing.find("*123#", null, NET_B, null)).get()
                .extracting(ShortCodeRule::asUrl).isEqualTo("http://asp2.compB/ussd");
        assertThat(routing.list()).hasSize(2);   // both rules live side by side
    }

    @Test
    void aLinkWithoutItsOwnRuleNeverBorrowsAnotherCompanysRule() {
        assertThat(routing.find("*123#", null, NET_C, null)).isEmpty();
    }

    @Test
    void sharedRuleServesLinksWithoutTheirOwnRule() {
        routing.put(new ShortCodeRule("*123#", RuleType.HTTP, "http://shared/ussd", true));
        assertThat(routing.find("*123#", null, NET_C, null)).get()
                .extracting(ShortCodeRule::asUrl).isEqualTo("http://shared/ussd");
        // ...but a company's own rule still wins on its link.
        assertThat(routing.find("*123#", null, NET_A, null)).get()
                .extracting(ShortCodeRule::asUrl).isEqualTo("http://asp1.compA/ussd");
    }

    @Test
    void ownExactRuleBeatsSharedLongerPrefix() {
        routing.put(new ShortCodeRule("*123", RuleType.HTTP, "http://shared/prefix", true, null, 0, true));
        assertThat(routing.find("*123#", null, NET_A, null)).get()
                .extracting(ShortCodeRule::asUrl).isEqualTo("http://asp1.compA/ussd");
    }

    @Test
    void continueTurnWithTheSessionsTenantStaysWithThatTenant() {
        // A shared rule of another tenant on network 0 must not catch company A's later turns.
        routing.put(new ShortCodeRule("*123#", RuleType.HTTP, "http://compB-shared/ussd", true, "compB", 0));
        assertThat(routing.find("*123#", null, NET_A, "compA")).get()
                .extracting(ShortCodeRule::asUrl).isEqualTo("http://asp1.compA/ussd");
        assertThat(routing.find("*123#", null, NET_C, "compA")).isEmpty();
    }

    @Test
    void noLinkKnownKeepsThePreTenantBehaviour() {
        // Diameter / SIP / single-link installs pass networkId 0: any rule still matches.
        assertThat(routing.find("*123#")).isPresent();
        routing.put(new ShortCodeRule("*123#", RuleType.HTTP, "http://shared/ussd", true));
        assertThat(routing.find("*123#")).get()
                .extracting(ShortCodeRule::asUrl).isEqualTo("http://shared/ussd");
    }

    @Test
    void ruleKeyIncludesTheNetwork() {
        assertThat(routing.byKey("*123#", NET_A, null)).get()
                .extracting(ShortCodeRule::tenantId).isEqualTo("compA");
        assertThat(routing.byKey("*123#", NET_B, null)).get()
                .extracting(ShortCodeRule::tenantId).isEqualTo("compB");
        assertThat(routing.byKey("*123#", NET_C, null)).isEmpty();
    }
}
