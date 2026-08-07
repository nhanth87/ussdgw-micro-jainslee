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
}
