package et.restlink.ussdgw.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublicPushUrlsTest {

    @Test
    void rejectsWildcardAndUsesPublicBase() {
        assertThat(PublicPushUrls.normalizePublicBase("http://0.0.0.0:8088")).isEmpty();
        assertThat(PublicPushUrls.publicNiPushUrl(
                "http://100.110.205.176:8088", "0.0.0.0", 8088, "/ussd"))
                .isEqualTo("http://100.110.205.176:8088/ussd");
        assertThat(PublicPushUrls.publicNiPushUrl(
                "http://100.110.205.176", "0.0.0.0", 8088, "/ussd"))
                .isEqualTo("http://100.110.205.176/ussd");
    }

    @Test
    void emptyWhenOnlyWildcardListen() {
        assertThat(PublicPushUrls.publicNiPushUrl("", "0.0.0.0", 8088, "/ussd")).isEmpty();
        assertThat(PublicPushUrls.publicHttpBase("", "0.0.0.0", 8088)).isEmpty();
    }

    @Test
    void grpcEndpointFromPublicBase() {
        // public-base-url may carry the HTTP port — gRPC must still advertise grpcPort.
        assertThat(PublicPushUrls.publicGrpcPushEndpoint("http://100.110.205.176:8088", 9099))
                .isEqualTo("100.110.205.176:9099");
        assertThat(PublicPushUrls.publicGrpcPushEndpoint("http://100.110.205.176", 9099))
                .isEqualTo("100.110.205.176:9099");
        assertThat(PublicPushUrls.publicGrpcPushEndpoint("http://0.0.0.0:8088", 9099)).isEmpty();
    }

    @Test
    void stripsPathFromPublicBase() {
        assertThat(PublicPushUrls.normalizePublicBase("http://host:8088/admin/"))
                .isEqualTo("http://host:8088");
    }
}
