package et.restlink.ussdgw.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShortCodeRoutingServiceTest {

    @Test
    void extractShortCode() {
        assertThat(ShortCodeRoutingService.extractShortCode("*123#")).isEqualTo("*123#");
        assertThat(ShortCodeRoutingService.extractShortCode("*123*1#")).isEqualTo("*123*1#");
        assertThat(ShortCodeRoutingService.extractShortCode("  *456# ")).isEqualTo("*456#");
    }

    @Test
    void putAndFindExact() {
        ShortCodeRoutingService svc = new ShortCodeRoutingService();
        svc.put(new ShortCodeRule("*999#", RuleType.HTTP, "http://x/y", true));
        assertThat(svc.find("*999#")).isPresent();
        assertThat(svc.find("*000#")).isEmpty();
        assertThat(svc.find("*999*1#")).isEmpty();
    }

    @Test
    void markPrefixRoutesLongerDial() {
        ShortCodeRoutingService svc = new ShortCodeRoutingService();
        svc.put(new ShortCodeRule("*100*", RuleType.HTTP, "http://as/mark", true, null, 0, true));

        assertThat(svc.find("*100*")).isPresent()
                .get().extracting(ShortCodeRule::asUrl).isEqualTo("http://as/mark");
        assertThat(svc.find("*100*123456#")).isPresent()
                .get().extracting(ShortCodeRule::asUrl).isEqualTo("http://as/mark");
        assertThat(svc.find("*100*9*1#")).isPresent()
                .get().extracting(ShortCodeRule::shortCode).isEqualTo("*100*");
        assertThat(svc.find("*101*123#")).isEmpty();
        assertThat(svc.find("*10#")).isEmpty();
    }

    /**
     * Ethiopia MO lab: dial {@code *101xxxxxx#} (digits after 101, no second '*').
     * Mark prefix must be {@code *101} — {@code *101*} would not match.
     */
    @Test
    void ethiopiaStar101MarkPrefixWithoutSecondAsterisk() {
        ShortCodeRoutingService svc = new ShortCodeRoutingService();
        svc.put(new ShortCodeRule("*101", RuleType.HTTP, "http://127.0.0.1:8090/ussd/pull",
                true, null, 0, true));

        String dialed = ShortCodeRoutingService.extractShortCode("*101123456#");
        assertThat(dialed).isEqualTo("*101123456#");
        assertThat(svc.find(dialed)).isPresent()
                .get().extracting(ShortCodeRule::shortCode).isEqualTo("*101");
        assertThat(svc.find("*101#")).isPresent();
        assertThat(svc.find("*101*99#")).isPresent(); // still starts with *101
        assertThat(svc.find("*102123#")).isEmpty();

        ShortCodeRoutingService wrongStar = new ShortCodeRoutingService();
        wrongStar.put(new ShortCodeRule("*101*", RuleType.HTTP, "http://as/wrong", true, null, 0, true));
        assertThat(wrongStar.find("*101123456#")).isEmpty();
    }

    @Test
    void exactBeatsShorterMarkPrefix() {
        ShortCodeRoutingService svc = new ShortCodeRoutingService();
        svc.put(new ShortCodeRule("*100*", RuleType.HTTP, "http://as/prefix", true, null, 0, true));
        svc.put(new ShortCodeRule("*100*99#", RuleType.HTTP, "http://as/exact", true, null, 0, false));

        assertThat(svc.find("*100*99#")).get().extracting(ShortCodeRule::asUrl)
                .isEqualTo("http://as/exact");
        assertThat(svc.find("*100*88#")).get().extracting(ShortCodeRule::asUrl)
                .isEqualTo("http://as/prefix");
    }

    @Test
    void longestMarkPrefixWins() {
        ShortCodeRoutingService svc = new ShortCodeRoutingService();
        svc.put(new ShortCodeRule("*1*", RuleType.HTTP, "http://as/short", true, null, 0, true));
        svc.put(new ShortCodeRule("*100*", RuleType.HTTP, "http://as/long", true, null, 0, true));

        assertThat(svc.find("*100*123#")).get().extracting(ShortCodeRule::asUrl)
                .isEqualTo("http://as/long");
        assertThat(svc.find("*1*9#")).get().extracting(ShortCodeRule::asUrl)
                .isEqualTo("http://as/short");
    }

    @Test
    void disabledMarkIgnored() {
        ShortCodeRoutingService svc = new ShortCodeRoutingService();
        svc.put(new ShortCodeRule("*100*", RuleType.HTTP, "http://as/off", false, null, 0, true));
        assertThat(svc.find("*100*1#")).isEmpty();
    }

    @Test
    void prefersMatchingAppUsernameAmongEqualMarks() {
        ShortCodeRoutingService svc = new ShortCodeRoutingService();
        svc.put(new ShortCodeRule("*100*", RuleType.HTTP, "http://as/a", true, "t1", 0, true, "app-a"));
        svc.put(new ShortCodeRule("*100*", RuleType.HTTP, "http://as/b", true, "t1", 0, true, "app-b"));

        assertThat(svc.find("*100*1#", "app-b")).get().extracting(ShortCodeRule::asUrl)
                .isEqualTo("http://as/b");
        assertThat(svc.find("*100*1#", "app-a")).get().extracting(ShortCodeRule::asUrl)
                .isEqualTo("http://as/a");
    }

    @Test
    void moPrefersUnboundAppUsername() {
        ShortCodeRoutingService svc = new ShortCodeRoutingService();
        svc.put(new ShortCodeRule("*200#", RuleType.HTTP, "http://as/shared", true, null, 0, false, null));
        svc.put(new ShortCodeRule("*200#", RuleType.HTTP, "http://as/owned", true, "t1", 0, false, "app-a"));

        assertThat(svc.find("*200#")).get().extracting(ShortCodeRule::asUrl)
                .isEqualTo("http://as/shared");
        assertThat(svc.find("*200#", "app-a")).get().extracting(ShortCodeRule::asUrl)
                .isEqualTo("http://as/owned");
    }

    @Test
    void twoExactRulesSameShortCodeDifferentAppUsernameCoexist() {
        ShortCodeRoutingService svc = new ShortCodeRoutingService();
        svc.put(new ShortCodeRule("*300#", RuleType.HTTP, "http://as/a", true, "t1", 1, false, "app-a"));
        svc.put(new ShortCodeRule("*300#", RuleType.HTTP, "http://as/b", true, "t1", 1, false, "app-b"));

        assertThat(svc.list()).hasSize(2);
        assertThat(svc.find("*300#", "app-a")).get().extracting(ShortCodeRule::asUrl)
                .isEqualTo("http://as/a");
        assertThat(svc.find("*300#", "app-b")).get().extracting(ShortCodeRule::asUrl)
                .isEqualTo("http://as/b");
    }
}
