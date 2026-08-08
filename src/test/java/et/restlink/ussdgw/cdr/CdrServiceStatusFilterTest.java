package et.restlink.ussdgw.cdr;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CdrServiceStatusFilterTest {

    @Test
    void blankStatusIsNull() {
        assertThat(CdrService.normalizeStatusFilter(null)).isNull();
        assertThat(CdrService.normalizeStatusFilter("")).isNull();
        assertThat(CdrService.normalizeStatusFilter("   ")).isNull();
        assertThat(CdrService.normalizeStatusFilter("*")).isNull();
    }

    @Test
    void exactStatusUppercased() {
        var f = CdrService.normalizeStatusFilter("gated");
        assertThat(f).isNotNull();
        assertThat(f.prefix()).isFalse();
        assertThat(f.pattern()).isEqualTo("GATED");
    }

    @Test
    void prefixStarBecomesLikePattern() {
        var m2m = CdrService.normalizeStatusFilter("MAP2MAP_*");
        assertThat(m2m).isNotNull();
        assertThat(m2m.prefix()).isTrue();
        assertThat(m2m.pattern()).isEqualTo("MAP2MAP_%");

        var gated = CdrService.normalizeStatusFilter("GATED*");
        assertThat(gated.prefix()).isTrue();
        assertThat(gated.pattern()).isEqualTo("GATED%");

        var gatedAs = CdrService.normalizeStatusFilter("GATED_AS*");
        assertThat(gatedAs.pattern()).isEqualTo("GATED_AS%");
    }
}
