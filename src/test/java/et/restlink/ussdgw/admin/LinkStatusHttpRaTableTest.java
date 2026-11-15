package et.restlink.ussdgw.admin;

import com.microjainslee.ra.httpclient.HttpCallbackClientRa;
import com.microjainslee.ra.httpclient.HttpCallbackRaEndpoint;
import com.microjainslee.ra.httpserver.HttpServerRaEndpoint;
import com.microjainslee.ra.httpserver.HttpServerResourceAdaptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The HTTP tab shows every HTTP client RA entity and the server RA's deadline counters. */
class LinkStatusHttpRaTableTest {

    @Test
    void httpTabListsEveryClientEntityAndTheServerDeadline() {
        LinkStatusService links = new LinkStatusService();
        HttpCallbackClientRa a = new HttpCallbackClientRa();
        a.setMaxPoolSize(512);
        HttpCallbackClientRa b = new HttpCallbackClientRa();
        b.setMaxPoolSize(512);
        HttpServerResourceAdaptor server = new HttpServerResourceAdaptor();
        server.setResponseTimeoutMs(300_000);
        links.bindHttpPlane(
                () -> List.of(new HttpCallbackRaEndpoint("http-callback-ra#0", a),
                              new HttpCallbackRaEndpoint("http-callback-ra#1", b)),
                () -> new HttpServerRaEndpoint(server));

        String html = links.htmlPartial("http");

        assertThat(html).contains("HTTP RA entities")
                .contains("http-callback-ra#0").contains("http-callback-ra#1")
                .contains("total (2)").contains(">512<")
                .contains("answered 504 by the RA 0").contains("deadline 300000 ms");
    }

    @Test
    void dashboardStaysCompact() {
        LinkStatusService links = new LinkStatusService();
        assertThat(links.htmlPartial("all")).doesNotContain("HTTP RA entities");
    }

    @Test
    void clientOffIsSaidPlainly() {
        assertThat(new LinkStatusService().htmlPartial("http"))
                .contains("HTTP client off").contains("http-server-ra off");
    }
}
