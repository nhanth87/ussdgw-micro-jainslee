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
    void putAndFind() {
        ShortCodeRoutingService svc = new ShortCodeRoutingService();
        svc.put(new ShortCodeRule("*999#", RuleType.HTTP, "http://x/y", true));
        assertThat(svc.find("*999#")).isPresent();
        assertThat(svc.find("*000#")).isEmpty();
    }
}
