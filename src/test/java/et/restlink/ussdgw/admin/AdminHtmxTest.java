package et.restlink.ussdgw.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminHtmxTest {

    @Test
    void headerSafeReplacesEmDashWithAsciiHyphen() {
        assertThat(AdminHtmx.headerSafe("saved *875# — live"))
                .isEqualTo("saved *875# - live");
        assertThat(AdminHtmx.headerSafe("a\u2013b\u2026c")).isEqualTo("a-b...c");
    }

    @Test
    void triggerToastIsAsciiOnlyAndIncludesCatalogRefresh() {
        String hx = AdminHtmx.triggerToast(
                "saved *875# — live", "ok", "/admin/routing/partial", "#rule-rows");
        assertThat(hx).doesNotContain("\u2014");
        assertThat(hx).contains("saved *875# - live");
        assertThat(hx).contains("\"ussdToast\"");
        assertThat(hx).contains("\"ussdCatalogChanged\"");
        assertThat(hx).contains("/admin/routing/partial");
        assertThat(hx).contains("#rule-rows");
        for (int i = 0; i < hx.length(); i++) {
            char c = hx.charAt(i);
            assertThat(c).isBetween((char) 0x20, (char) 0x7E);
        }
    }

    @Test
    void triggerToastWithoutPartialOmitsCatalogChanged() {
        String hx = AdminHtmx.triggerToast("ok", "info");
        assertThat(hx).contains("ussdToast");
        assertThat(hx).doesNotContain("ussdCatalogChanged");
    }
}
