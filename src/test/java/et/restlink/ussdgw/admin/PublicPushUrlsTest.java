package et.restlink.ussdgw.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublicPushUrlsTest {

    @Test
    void rejectsWildcardAndUsesPublicBase() {
        assertThat(PublicPushUrls.normalizePublicBase("http://0.0.0.0:8088")).isEmpty();
        assertThat(PublicPushUrls.publicNiPushUrl(
                "http://192.0.2.50:8088", "0.0.0.0", 8088, "/ussd"))
                .isEqualTo("http://192.0.2.50:8088/ussd");
        assertThat(PublicPushUrls.publicNiPushUrl(
                "http://192.0.2.50", "0.0.0.0", 8088, "/ussd"))
                .isEqualTo("http://192.0.2.50/ussd");
    }

    @Test
    void emptyWhenOnlyWildcardListen() {
        assertThat(PublicPushUrls.publicNiPushUrl("", "0.0.0.0", 8088, "/ussd")).isEmpty();
        assertThat(PublicPushUrls.publicHttpBase("", "0.0.0.0", 8088)).isEmpty();
    }

    @Test
    void grpcEndpointFromPublicBase() {
        // public-base-url may carry the HTTP port — gRPC must still advertise grpcPort.
        assertThat(PublicPushUrls.publicGrpcPushEndpoint("http://192.0.2.50:8088", 9099))
                .isEqualTo("192.0.2.50:9099");
        assertThat(PublicPushUrls.publicGrpcPushEndpoint("http://192.0.2.50", 9099))
                .isEqualTo("192.0.2.50:9099");
        assertThat(PublicPushUrls.publicGrpcPushEndpoint("http://0.0.0.0:8088", 9099)).isEmpty();
    }

    @Test
    void stripsPathFromPublicBase() {
        assertThat(PublicPushUrls.normalizePublicBase("http://host:8088/admin/"))
                .isEqualTo("http://host:8088");
    }
}
