package et.restlink.ussdgw.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleTypeTest {

    @Test
    void parseReRouteAliases() {
        assertThat(RuleType.parse("RE_ROUTE")).isEqualTo(RuleType.RE_ROUTE);
        assertThat(RuleType.parse("re-route")).isEqualTo(RuleType.RE_ROUTE);
        assertThat(RuleType.parse("REROUTE")).isEqualTo(RuleType.RE_ROUTE);
        assertThat(RuleType.parse("map2map")).isEqualTo(RuleType.RE_ROUTE);
    }

    @Test
    void reRouteImpliesRerouteAndHttpPullPlane() {
        assertThat(RuleType.RE_ROUTE.impliesReroute()).isTrue();
        assertThat(RuleType.RE_ROUTE.asPullPlane()).isEqualTo(RuleType.HTTP);
        assertThat(RuleType.RE_ROUTE.usesHttpAsPull()).isTrue();
        assertThat(RuleType.HTTP.impliesReroute()).isFalse();
        assertThat(RuleType.HTTP.usesHttpAsPull()).isTrue();
        assertThat(RuleType.GRPC.asPullPlane()).isEqualTo(RuleType.GRPC);
        assertThat(RuleType.GRPC.usesHttpAsPull()).isFalse();
        assertThat(RuleType.SIP.asPullPlane()).isEqualTo(RuleType.SIP);
    }

    @Test
    void parseUnknownThrows() {
        assertThatThrownBy(() -> RuleType.parse("FTP"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
