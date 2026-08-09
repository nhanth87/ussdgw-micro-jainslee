package et.restlink.ussdgw.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShortCodeRuleMap2MapTest {

    @Test
    void defaultRerouteOffDisarmsMap2Map() {
        ShortCodeRule r = ShortCodeRule.ofReroute("*804#", RuleType.HTTP, "http://as/userinfo", true,
                null, 0, false, null, false, "*8744#", null);
        assertThat(r.map2mapArmed()).isFalse();
        assertThat(r.rerouteEnable()).isFalse();
        assertThat(r.bypass()).isTrue();
    }

    @Test
    void armedWhenRerouteAndRedirectSet() {
        ShortCodeRule r = ShortCodeRule.ofReroute("*804#", RuleType.HTTP, "http://as/userinfo", true,
                null, 0, false, null, true, "*8744#", "FAKE");
        assertThat(r.map2mapArmed()).isTrue();
        assertThat(r.redirectUssdString()).isEqualTo("*8744#");
        assertThat(r.hlrMode()).isEqualTo("FAKE");
        assertThat(r.bypass()).isFalse();
    }

    @Test
    void blankRedirectDisarmsEvenWhenRerouteTrue() {
        ShortCodeRule r = ShortCodeRule.ofReroute("*804#", RuleType.HTTP, "http://as/userinfo", true,
                null, 0, false, null, true, "  ", null);
        assertThat(r.map2mapArmed()).isFalse();
    }

    @Test
    void legacyBypassCtorStillMapsToReroute() {
        // 10-arg ctor: 9th boolean is legacy bypass (true = no hop)
        ShortCodeRule off = new ShortCodeRule("*804#", RuleType.HTTP, "http://as/userinfo", true,
                null, 0, false, null, true, "*8744#");
        assertThat(off.map2mapArmed()).isFalse();
        ShortCodeRule on = new ShortCodeRule("*804#", RuleType.HTTP, "http://as/userinfo", true,
                null, 0, false, null, false, "*8744#");
        assertThat(on.map2mapArmed()).isTrue();
    }

    @Test
    void calledGtDigitsStripStars() {
        assertThat(ShortCodeRule.map2mapCalledGtDigits("*8744#")).isEqualTo("8744");
        assertThat(ShortCodeRule.map2mapCalledGtDigits("8744")).isEqualTo("8744");
        assertThat(ShortCodeRule.map2mapCalledGtDigits(null)).isEmpty();
    }

    @Test
    void ussdStringKeepsOrWraps() {
        assertThat(ShortCodeRule.map2mapUssdString("*8744#")).isEqualTo("*8744#");
        assertThat(ShortCodeRule.map2mapUssdString("8744")).isEqualTo("*8744#");
    }

    @Test
    void legacyCtorDefaultsRerouteOff() {
        ShortCodeRule r = new ShortCodeRule("*123#", RuleType.HTTP, "http://x", true);
        assertThat(r.bypass()).isTrue();
        assertThat(r.rerouteEnable()).isFalse();
        assertThat(r.map2mapGt()).isNull();
        assertThat(r.map2mapArmed()).isFalse();
    }

    @Test
    void codeKindShortVsLongForMark() {
        assertThat(ShortCodeRule.codeKind("*101#", true, "*101")).isEqualTo("SHORT");
        assertThat(ShortCodeRule.codeKind("*101123456#", true, "*101")).isEqualTo("LONG");
        assertThat(ShortCodeRule.codeKind("*804#", false, "*804#")).isEqualTo("SHORT");
    }

    @Test
    void fixedHopDestSpDefaultsSsn6() {
        ShortCodeRule r = ShortCodeRule.ofReroute("*804#", RuleType.HTTP, "http://as/userinfo", true,
                null, 0, false, null, true, "*875#", null, "251971200201", null);
        assertThat(r.map2mapArmed()).isTrue();
        assertThat(r.fixedHopArmed()).isTrue();
        assertThat(r.redirectUssdString()).isEqualTo("*875#");
        assertThat(r.hopDestGtDigits()).isEqualTo("251971200201");
        assertThat(r.effectiveHopDestSsn()).isEqualTo(6);
        assertThat(ShortCodeRule.map2mapUssdString("*875#")).isEqualTo("*875#");
    }

    @Test
    void fixedHopDestExplicitSsn() {
        ShortCodeRule r = ShortCodeRule.ofReroute("*804#", RuleType.HTTP, "http://as/userinfo", true,
                null, 0, false, null, true, "*875#", "FAKE", "251971200201", 6);
        assertThat(r.hopDestSsn()).isEqualTo(6);
        assertThat(r.resolvedHopSsn()).isEqualTo(6);
        assertThat(r.hlrMode()).isEqualTo("FAKE");
        assertThat(r.fixedHopArmed()).isTrue();
    }

    @Test
    void resolveHopUssdExactShortLiteral() {
        assertThat(ShortCodeRule.resolveHopUssd("*804#", false, "*804#", "*875#"))
                .isEqualTo("*875#");
        assertThat(ShortCodeRule.resolveHopUssd("*804#", true, "*804", "*875#"))
                .isEqualTo("*875#");
        assertThat(ShortCodeRule.resolveHopUssd("*804#", true, "*804#", "*875#"))
                .isEqualTo("*875#");
    }

    @Test
    void resolveHopUssdLongPreservesSuffix() {
        assertThat(ShortCodeRule.resolveHopUssd("*804*1234#", true, "*804*", "*875*"))
                .isEqualTo("*875*1234#");
        assertThat(ShortCodeRule.resolveHopUssd("*804*1234#", true, "*804*", "*875#"))
                .isEqualTo("*8751234#");
        ShortCodeRule r = ShortCodeRule.ofReroute("*804*", RuleType.HTTP, "http://as/", true,
                null, 0, true, null, true, "*875*", null);
        assertThat(r.resolveHopUssd("*804*99#")).isEqualTo("*875*99#");
    }

    @Test
    void resolveHopUssdMarkWithoutMatchFallsBackToLiteral() {
        assertThat(ShortCodeRule.resolveHopUssd("*999*1#", true, "*804*", "*875*"))
                .isEqualTo("*875*");
    }

}
