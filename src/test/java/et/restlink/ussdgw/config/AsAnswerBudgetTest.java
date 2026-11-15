package et.restlink.ussdgw.config;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.httpclient.HttpCallbackClientRa;
import com.microjainslee.ra.httpclient.events.HttpCallbackCompletedEvent;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An AS gets at least 30 s to answer a pull: the gateway keeps the request open until the AS
 * answers or 30 s pass, never less — whatever the operator config says.
 */
class AsAnswerBudgetTest {

    @Test
    void configuredValuesBelowThirtySecondsAreRaised() {
        UssdConfigService cfg = new UssdConfigService();
        set(cfg, "httpRequestMsProp", 15_000);
        set(cfg, "grpcInvokeMsProp", 15_000L);
        assertThat(cfg.httpRequestTimeoutMs()).isEqualTo(30_000);
        assertThat(cfg.grpcInvokeTimeoutMs()).isEqualTo(30_000L);
    }

    @Test
    void longerValuesAreKept() {
        UssdConfigService cfg = new UssdConfigService();
        set(cfg, "httpRequestMsProp", 45_000);
        set(cfg, "grpcInvokeMsProp", 60_000L);
        assertThat(cfg.httpRequestTimeoutMs()).isEqualTo(45_000);
        assertThat(cfg.grpcInvokeTimeoutMs()).isEqualTo(60_000L);
    }

    @Test
    void defaultsAreThirtySeconds() {
        assertThat(UssdConfigService.AS_ANSWER_MIN_MS).isEqualTo(30_000);
    }

    /**
     * Behaviour, not just the number: an AS that holds the request for 20 s — past the old 15 s
     * cut-off — still gets its answer delivered, with the HTTP client RA configured exactly as
     * HttpApplyService configures it from a 15 s operator value.
     */
    @Test
    void anAsThatHoldsTwentySecondsIsStillAnswered() throws Exception {
        Vertx vertx = Vertx.vertx();
        HttpServer server = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b ->
                vertx.setTimer(20_000, id -> req.response().putHeader("Content-Type", "text/xml").end(
                        "<dialog mapMessagesSize=\"1\"><processUnstructuredSSRequest_Response "
                                + "dataCodingScheme=\"15\" string=\"late but fine\"/></dialog>"))));
        int port = server.listen(0).toCompletionStage().toCompletableFuture().get().actualPort();

        UssdConfigService cfg = new UssdConfigService();
        set(cfg, "httpRequestMsProp", 15_000);              // what the Digicom operator file says
        HttpCallbackClientRa ra = new HttpCallbackClientRa();
        ra.setRequestTimeoutMs(cfg.httpRequestTimeoutMs()); // as HttpApplyService does
        ra.setMaxRetries(0);
        CompletableFuture<HttpCallbackCompletedEvent> answer = new CompletableFuture<>();
        ra.setBootstrapPort(new RaBootstrapPort() {
            @Override public ActivityHandle createActivityHandle(String id) { return null; }
            @Override public void fireEvent(SleeEvent event, ActivityHandle handle, Address address) {
                if (event instanceof HttpCallbackCompletedEvent e) answer.complete(e);
            }
        });
        ra.raConfigure();
        ra.raActive();
        try {
            long t0 = System.nanoTime();
            ra.sendPost("corr-1", "http://127.0.0.1:" + port + "/ussd", "<dialog/>", "text/xml", Map.of());
            HttpCallbackCompletedEvent e = answer.get(40, TimeUnit.SECONDS);
            long waitedMs = (System.nanoTime() - t0) / 1_000_000;
            assertThat(e.getErrorMessage()).as("no timeout error").isNullOrEmpty();
            assertThat(e.getStatusCode()).isEqualTo(200);
            assertThat(e.getResponseBody()).contains("late but fine");
            assertThat(waitedMs).isGreaterThanOrEqualTo(19_000);
        } finally {
            ra.raStopping();
            ra.raUnconfigure();
            server.close();
            vertx.close();
        }
    }

    /** An AS that never answers: the pull ends at 30 s — not before, and not much after. */
    @Test
    void aSilentAsTimesOutAtThirtySecondsNotEarlier() throws Exception {
        Vertx vertx = Vertx.vertx();
        HttpServer server = vertx.createHttpServer().requestHandler(req -> { /* never answers */ });
        int port = server.listen(0).toCompletionStage().toCompletableFuture().get().actualPort();

        UssdConfigService cfg = new UssdConfigService();
        set(cfg, "httpRequestMsProp", 15_000);
        HttpCallbackClientRa ra = new HttpCallbackClientRa();
        ra.setRequestTimeoutMs(cfg.httpRequestTimeoutMs());
        ra.setMaxRetries(0);
        CompletableFuture<HttpCallbackCompletedEvent> answer = new CompletableFuture<>();
        ra.setBootstrapPort(new RaBootstrapPort() {
            @Override public ActivityHandle createActivityHandle(String id) { return null; }
            @Override public void fireEvent(SleeEvent event, ActivityHandle handle, Address address) {
                if (event instanceof HttpCallbackCompletedEvent e) answer.complete(e);
            }
        });
        ra.raConfigure();
        ra.raActive();
        try {
            long t0 = System.nanoTime();
            ra.sendPost("corr-2", "http://127.0.0.1:" + port + "/ussd", "<dialog/>", "text/xml", Map.of());
            HttpCallbackCompletedEvent e = answer.get(45, TimeUnit.SECONDS);
            long waitedMs = (System.nanoTime() - t0) / 1_000_000;
            assertThat(e.getErrorMessage()).as("a timeout, reported as an error").isNotBlank();
            assertThat(waitedMs).as("waited ms").isBetween(29_900L, 33_000L);
        } finally {
            ra.raStopping();
            ra.raUnconfigure();
            server.close();
            vertx.close();
        }
    }

    private static void set(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
