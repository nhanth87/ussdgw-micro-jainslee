package et.restlink.ussdgw.bench;

import com.microjainslee.core.EventDeliveryMode;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.NullActivities;
import com.microjainslee.ra.httpclient.HttpCallbackClientRa;
import com.microjainslee.ra.httpclient.HttpCallbackRaEndpoint;
import com.microjainslee.ra.httpclient.events.HttpCallbackCompletedEvent;
import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.api.WireFormatResolver;
import et.restlink.ussdgw.bridge.UssdSagaCoordinator;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.events.PullHttpEvent;
import et.restlink.ussdgw.sbbs.HttpClientSbb;
import et.restlink.ussdgw.service.AsPullClient;
import et.restlink.ussdgw.service.AsPullRouter;
import et.restlink.ussdgw.service.AsPullStateRegistry;
import et.restlink.ussdgw.service.SbbServices;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proof that the gateway's AS plane is asynchronous and runs on virtual threads.
 *
 * <p>Real pieces, configured like the deployed gateway (values baked from
 * {@code build/application.properties}): micro-jainslee container with
 * {@code event-delivery=async-commit}, virtual threads, SBB per virtual thread; the real
 * {@link HttpClientSbb}; four real {@code ra-http-client} entities in an RA entity group. The AS
 * is a local Vert.x server that holds <b>every request for 1 second</b> before answering — a slow
 * application server.
 *
 * <p>{@value #SESSIONS} USSD sessions are started at once. If the gateway handled them one after
 * another, the run would take {@value #SESSIONS} × 1 s. Asynchronous handling finishes in about
 * one AS delay, with all sessions open at the AS at the same time, and the SBB work done on
 * virtual threads. The test asserts exactly that and prints the numbers.
 */
class AsyncVirtualThreadProofTest {

    static final int SESSIONS = 1000;
    static final long AS_DELAY_MS = 1000;

    @Test
    void oneThousandSessionsAgainstASlowAsFinishInAboutOneAsDelay() throws Exception {
        // ---- the slow AS: answers every request after AS_DELAY_MS; counts concurrent requests
        Vertx vertx = Vertx.vertx();
        AtomicInteger openAtAs = new AtomicInteger();
        AtomicInteger peakOpenAtAs = new AtomicInteger();
        LongAdder asHits = new LongAdder();
        String menu = "<dialog mapMessagesSize=\"1\"><unstructuredSSRequest_Request dataCodingScheme=\"15\" "
                + "string=\"1. Balance 2. Bundles\"/></dialog>";
        HttpServer server = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b -> {
            asHits.increment();
            peakOpenAtAs.accumulateAndGet(openAtAs.incrementAndGet(), Math::max);
            vertx.setTimer(AS_DELAY_MS, id -> {
                openAtAs.decrementAndGet();
                req.response().putHeader("Content-Type", "text/xml;charset=UTF-8")
                        .putHeader("Set-Cookie", "JSESSIONID=" + Long.toHexString(System.nanoTime()) + "; Path=/")
                        .end(menu);
            });
        }));
        int port = server.listen(0).toCompletionStage().toCompletableFuture().get().actualPort();
        String asUrl = "http://127.0.0.1:" + port + "/ussd/pull";

        // ---- the container, as the deployed gateway bakes it
        MicroSleeContainer container = new MicroSleeContainer(MicroSleeConfiguration.builder()
                .eventRouterBufferSize(16_384)
                .preferVirtualThreads(true)
                .sbbPoolMax(40_960)
                .sbbPerVirtualThread(true)
                .eventDeliveryMode(EventDeliveryMode.parse("async-commit"))
                .build());
        container.start();

        CountDownLatch done = new CountDownLatch(SESSIONS);
        LongAdder failures = new LongAdder();
        Map<String, Long> startedAt = new ConcurrentHashMap<>();
        List<Long> latenciesMs = java.util.Collections.synchronizedList(new ArrayList<>());
        // Threads that ran HttpClientSbb: the send (asks the store for the AS cookie) and the
        // answer (hands the AS response to the bridge).
        Set<Thread> sendThreads = ConcurrentHashMap.newKeySet();
        Set<Thread> answerThreads = ConcurrentHashMap.newKeySet();

        SbbServices services = new SbbServices();
        AsPullStateRegistry registry = new AsPullStateRegistry();
        set(registry, "ttlMsProp", 120_000L);
        AsPullClient asPull = new AsPullClient();
        set(asPull, "failThreshold", 1_000_000);
        set(asPull, "openMs", 30_000L);
        set(asPull, "maxRetries", 0);
        set(services, "container", container);
        set(services, "asPull", asPull);
        set(services, "asPullState", registry);
        set(services, "saga", new UssdSagaCoordinator() {
            @Override public void onAsPullFailed(String correlationId, String reason) {
                failures.increment();
                done.countDown();
            }
        });
        set(services, "store", new VirtualSessionStore() {
            private final Map<String, String> cookies = new ConcurrentHashMap<>();
            @Override public Optional<VirtualSession> get(String correlationId) { return Optional.empty(); }
            @Override public String asCookie(String correlationId) {
                sendThreads.add(Thread.currentThread());
                return cookies.get(correlationId);
            }
            @Override public void setAsCookie(String correlationId, String v) {
                if (v != null) cookies.put(correlationId, v);
            }
        });
        set(services, "bridge", new VirtualSessionBridge() {
            @Override public void onAsResponse(AsResponse response, long latencyMs) {
                answerThreads.add(Thread.currentThread());
                Long t0 = startedAt.remove(response.correlationId());
                if (t0 != null) {
                    latenciesMs.add((System.nanoTime() - t0) / 1_000_000);
                }
                done.countDown();
            }
        });
        set(services, "wireFacade", new AsWireFacade());
        set(services, "wireFormatResolver", new WireFormatResolver() {
            @Override public AsHttpWireFormat resolve(String tenantId) { return AsHttpWireFormat.XML; }
        });
        container.registerSbbType(HttpClientSbb.class, () -> new HttpClientSbb(services));
        container.mapEventToSbb(PullHttpEvent.class, "HttpClientSbb");
        container.mapEventToSbb(HttpCallbackCompletedEvent.class, "HttpClientSbb");

        List<HttpCallbackRaEndpoint> entities = new ArrayList<>();
        for (int e = 0; e < 4; e++) {
            HttpCallbackClientRa ra = new HttpCallbackClientRa();
            ra.setMaxPoolSize(512);        // 4 × 512 connections ≥ SESSIONS
            ra.setMaxRetries(0);
            ra.setRequestTimeoutMs(15_000);
            entities.add(new HttpCallbackRaEndpoint(HttpCallbackRaEndpoint.NAME + "#" + e, ra));
        }
        container.registerRaEntityGroup(HttpCallbackRaEndpoint.NAME, entities);

        try {
            // ---- start every session at once, exactly as AsPullRouter does for an MO dial
            long t0 = System.nanoTime();
            for (int i = 0; i < SESSIONS; i++) {
                String corr = "proof-" + i;
                startedAt.put(corr, System.nanoTime());
                NullActivities.fire(container, AsPullRouter.pullActivityName(corr),
                        new PullHttpEvent(asUrl, new AsRequest("vs", corr, corr, 0,
                                "2519" + (11_000_000 + i), "*804#", "*804#", 0)));
            }
            long submitMs = (System.nanoTime() - t0) / 1_000_000;
            boolean all = done.await(60, TimeUnit.SECONDS);
            long wallMs = (System.nanoTime() - t0) / 1_000_000;

            long virtualSend = sendThreads.stream().filter(Thread::isVirtual).count();
            long virtualAnswer = answerThreads.stream().filter(Thread::isVirtual).count();
            List<Long> sorted = new ArrayList<>(latenciesMs);
            sorted.sort(null);
            long p50 = sorted.isEmpty() ? -1 : sorted.get(sorted.size() / 2);
            long p99 = sorted.isEmpty() ? -1 : sorted.get((int) (sorted.size() * 0.99) - 1);

            System.out.printf("%n=== async / virtual-thread proof ===%n");
            System.out.printf("sessions            %d, AS holds each request %d ms%n", SESSIONS, AS_DELAY_MS);
            System.out.printf("serial would take   %,d ms%n", SESSIONS * AS_DELAY_MS);
            System.out.printf("all submitted in    %d ms (the caller never waits for the AS)%n", submitMs);
            System.out.printf("all answered in     %,d ms  (completed=%s, failures=%d, AS hits=%d)%n",
                    wallMs, all, failures.sum(), asHits.sum());
            System.out.printf("per-session latency p50 %d ms, p99 %d ms%n", p50, p99);
            System.out.printf("peak open at the AS %d requests at the same time%n", peakOpenAtAs.get());
            System.out.printf("SBB send threads    %d distinct, %d virtual%n", sendThreads.size(), virtualSend);
            System.out.printf("SBB answer threads  %d distinct, %d virtual%n", answerThreads.size(), virtualAnswer);

            assertThat(all).as("every session answered").isTrue();
            assertThat(failures.sum()).isZero();
            assertThat(asHits.sum()).isEqualTo(SESSIONS);
            // Serial handling: SESSIONS × 1 s = 1000 s. Asynchronous: about one AS delay.
            assertThat(wallMs).as("wall time, ms").isLessThan(10 * AS_DELAY_MS);
            assertThat(peakOpenAtAs.get()).as("sessions open at the AS at the same time")
                    .isGreaterThanOrEqualTo(SESSIONS / 2);
            assertThat(virtualSend).as("SBB send work on virtual threads").isEqualTo(sendThreads.size());
            assertThat(virtualAnswer).as("SBB answer work on virtual threads").isEqualTo(answerThreads.size());
        } finally {
            container.stop();
            server.close();
            vertx.close();
        }
    }

    static void set(Object target, String field, Object value) {
        HttpPullThroughputBench.set(target, field, value);
    }
}
