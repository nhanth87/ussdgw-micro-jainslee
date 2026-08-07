package et.restlink.ussdgw.service;

import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;

import com.microjainslee.core.MicroSleeContainer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsPullRouterTest {
    private MicroSleeContainer container;
    private AsPullRouter router;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        router = new AsPullRouter();
        set(router, "container", container);
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void httpRuleReturnsRoutedHttpDetail() {
        AsRequest req = new AsRequest("vs", "c1", "r1", 0, "2519", "*123#", "*123#", 1);
        String detail = router.route(
                new ShortCodeRule("*123#", RuleType.HTTP, "http://as/pull", true),
                req, "c1");
        assertThat(detail).isEqualTo("routed HTTP sc=*123#");
    }

    @Test
    void grpcRuleReturnsRoutedGrpcDetail() {
        AsRequest req = new AsRequest("vs", "c2", "r2", 0, "2519", "*456#", "*456#", 1);
        String detail = router.route(
                new ShortCodeRule("*456#", RuleType.GRPC, "localhost:50051|et.as/Pull", true),
                req, "c2");
        assertThat(detail).isEqualTo("routed GRPC sc=*456#");
    }

    /**
     * Both client RAs fire their completion on {@code createActivityHandle(correlationId)}, and
     * the container derives the SBB entity id from the activity context name. A decorated name
     * here would put submit and completion on two different entities.
     */
    @Test
    void pullActivityIsNamedAfterTheBareCorrelationId() {
        AsRequest req = new AsRequest("vs", "c-name", "r1", 0, "2519", "*123#", "*123#", 1);
        router.route(new ShortCodeRule("*123#", RuleType.HTTP, "http://as/pull", true),
                req, "c-name");
        assertThat(container.getActivityContextNamingFacility().lookup("c-name")).isNotNull();
        assertThat(container.getActivityContextNamingFacility().lookup("pull-http-c-name"))
                .isNull();

        AsRequest grpcReq = new AsRequest("vs", "g-name", "r1", 0, "2519", "*456#", "*456#", 1);
        router.route(new ShortCodeRule("*456#", RuleType.GRPC, "localhost:50051|et.as/Pull", true),
                grpcReq, "g-name");
        assertThat(container.getActivityContextNamingFacility().lookup("g-name")).isNotNull();
        assertThat(container.getActivityContextNamingFacility().lookup("pull-grpc-g-name"))
                .isNull();
    }

    @Test
    void emptyUrlFailsClosed() {
        AsRequest req = new AsRequest("vs", "c3", "r3", 0, "2519", "*1#", "*1#", 0);
        assertThatThrownBy(() -> router.route(
                new ShortCodeRule("*1#", RuleType.HTTP, "  ", true), req, "c3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AS URL empty");
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
