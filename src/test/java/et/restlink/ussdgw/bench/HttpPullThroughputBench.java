package et.restlink.ussdgw.bench;

import com.microjainslee.core.EventDeliveryMode;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
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
import et.restlink.ussdgw.service.AsPullStateRegistry;
import et.restlink.ussdgw.service.SbbServices;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * HTTP AS plane throughput with the REAL pieces: micro-jainslee container + EventRouter,
 * HttpClientSbb routed by event mapping, ra-http-client (Vert.x WebClient), and a local Vert.x
 * "AS" that answers classic XML (Set-Cookie on every response, optional think time).
 *
 * <pre>
 * mvn test -Dtest=HttpPullThroughputBench -Dbench.http=true \
 *   [-Dbench.delivery=sync|async-commit] [-Dbench.pulls=200000] [-Dbench.rate=10000] [-Dbench.asDelayMs=0]
 * </pre>
 *
 * One pull = PullHttpEvent → POST → HttpCallbackCompletedEvent → decode → bridge.onAsResponse,
 * i.e. the HTTP half of every USSD turn. MAP events are not in this loop.
 */
@EnabledIfSystemProperty(named = "bench.http", matches = "true")
class HttpPullThroughputBench {

    @Test
    void measure() throws Exception {
        String delivery = System.getProperty("bench.delivery", "sync");
        int pulls = Integer.getInteger("bench.pulls", 200_000);
        int rate = Integer.getInteger("bench.rate", 10_000);
        long asDelayMs = Long.getLong("bench.asDelayMs", 0L);

        // ---- the AS
        Vertx vertx = Vertx.vertx();
        String xml = "<dialog mapMessagesSize=\"1\"><unstructuredSSRequest_Request dataCodingScheme=\"15\" "
                + "string=\"1. Balance 2. Bundles\"/></dialog>";
        LongAdder asHits = new LongAdder();
        HttpServer server = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b -> {
            asHits.increment();
            Runnable answer = () -> req.response()
                    .putHeader("Content-Type", "text/xml;charset=UTF-8")
                    .putHeader("Set-Cookie", "JSESSIONID=" + Long.toHexString(System.nanoTime()) + "; Path=/")
                    .end(xml);
            if (asDelayMs > 0) {
                vertx.setTimer(asDelayMs, id -> answer.run());
            } else {
                answer.run();
            }
        }));
        int port = server.listen(0).toCompletionStage().toCompletableFuture().get().actualPort();
        String url = "http://127.0.0.1:" + port + "/ussd";

        // ---- the container, as SbbRegistrationSupport wires it
        MicroSleeContainer container = new MicroSleeContainer(MicroSleeConfiguration.builder()
                .eventRouterBufferSize(16_384)
                .preferVirtualThreads(true)
                .sbbPoolMax(40_960)
                .sbbPerVirtualThread(true)
                .eventDeliveryMode(EventDeliveryMode.parse(delivery))
                .build());
        container.start();

        CountDownLatch done = new CountDownLatch(pulls);
        LongAdder failures = new LongAdder();
        Map<String, Long> startedAt = new ConcurrentHashMap<>();
        AtomicLong latencySum = new AtomicLong();
        AtomicLong latencyMax = new AtomicLong();

        AsPullStateRegistry registry = new AsPullStateRegistry();
        set(registry, "ttlMsProp", 120_000L);
        AsPullClient asPull = new AsPullClient();
        set(asPull, "failThreshold", 1_000_000);
        set(asPull, "openMs", 30_000L);
        set(asPull, "maxRetries", 0);
        SbbServices services = new SbbServices();
        set(services, "container", container);
        set(services, "asPull", asPull);
        set(services, "asPullState", registry);
        set(services, "saga", new UssdSagaCoordinator() {
            @Override public void onAsPullFailed(String correlationId, String reason) {
                failures.increment();
                done.countDown();
            }
        });
        set(services, "store", new CookieOnlyStore());
        set(services, "bridge", new VirtualSessionBridge() {
            @Override public void onAsResponse(AsResponse response, long latencyMs) {
                Long t0 = startedAt.remove(response.correlationId());
                if (t0 != null) {
                    long l = System.nanoTime() - t0;
                    latencySum.addAndGet(l);
                    latencyMax.accumulateAndGet(l, Math::max);
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

        int entities = Integer.getInteger("bench.entities", Runtime.getRuntime().availableProcessors());
        java.util.List<HttpCallbackRaEndpoint> eps = new java.util.ArrayList<>();
        for (int e = 0; e < entities; e++) {
            HttpCallbackClientRa ra = new HttpCallbackClientRa();
            ra.setMaxPoolSize(Math.max(1, Integer.getInteger("bench.pool", 8192) / entities));
            ra.setMaxRetries(0);
            ra.setRequestTimeoutMs(15_000);
            eps.add(new HttpCallbackRaEndpoint(HttpCallbackRaEndpoint.NAME + "#" + e, ra));
        }
        container.registerRaEntityGroup(HttpCallbackRaEndpoint.NAME, eps);

        // ---- drive at `rate` pulls/s from several producers (the MAP SBBs in production)
        int producers = Integer.getInteger("bench.producers", 4);
        long t0 = System.nanoTime();
        long intervalNs = 1_000_000_000L / Math.max(1, rate);
        Thread[] drivers = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            final int first = p;
            drivers[p] = Thread.ofPlatform().name("bench-producer-" + p).start(() -> {
                for (int i = first; i < pulls; i += producers) {
                    long due = t0 + i * intervalNs;
                    while (System.nanoTime() < due) {
                        Thread.onSpinWait();
                    }
                    String corr = "c" + i;
                    startedAt.put(corr, System.nanoTime());
                    // Same as AsPullRouter: a JSR 240 Null Activity per pull, ended after the fire.
                    com.microjainslee.core.NullActivities.fire(container,
                            et.restlink.ussdgw.service.AsPullRouter.pullActivityName(corr),
                            new PullHttpEvent(url, new AsRequest("vs", corr, corr, 0,
                                    "2519" + (10_000_000 + i % 1_000_000), "*123#", "*123#", 0)));
                }
            });
        }
        for (Thread d : drivers) {
            d.join();
        }
        long sentNs = System.nanoTime() - t0;
        boolean all = done.await(120, TimeUnit.SECONDS);
        long totalNs = System.nanoTime() - t0;

        long completed = pulls - done.getCount();
        double offered = pulls / (sentNs / 1e9);
        double achieved = completed / (totalNs / 1e9);
        System.out.printf("%n=== HTTP pull bench: delivery=%s pulls=%d targetRate=%d asDelayMs=%d raEntities=%d producers=%d ===%n",
                delivery, pulls, rate, asDelayMs, entities, producers);
        System.out.printf("offered      %,.0f pulls/s (submit loop)%n", offered);
        System.out.printf("completed    %,d / %,d  (all=%s, failures=%d, AS hits=%,d)%n",
                completed, pulls, all, failures.sum(), asHits.sum());
        System.out.printf("throughput   %,.0f pulls/s end-to-end%n", achieved);
        System.out.printf("latency      avg %.1f ms, max %.1f ms%n",
                completed == 0 ? 0 : latencySum.get() / 1e6 / Math.max(1, completed - failures.sum()),
                latencyMax.get() / 1e6);

        Thread.sleep(2_000);  // let trailing ActivityEnded / removals drain
        System.out.printf("leftover     sbb entities %,d   bound activity names %,d   entities removed %,d%n",
                ((Map<?, ?>) get(container, "sbbs")).size(), boundNames(container),
                container.getEntityRemovalCount());

        container.stop();
        server.close();
        vertx.close();
    }

    /** ussdTx stand-in: cookie field only; no rows (the bench measures the HTTP plane). */
    static final class CookieOnlyStore extends VirtualSessionStore {
        private final Map<String, String> cookies = new ConcurrentHashMap<>();
        @Override public Optional<VirtualSession> get(String correlationId) { return Optional.empty(); }
        @Override public String asCookie(String correlationId) { return cookies.get(correlationId); }
        @Override public void setAsCookie(String correlationId, String v) {
            if (v == null) cookies.remove(correlationId); else cookies.put(correlationId, v);
        }
    }

    static Object get(Object target, String field) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                var f = c.getDeclaredField(field);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("No field " + field + " on " + target.getClass());
    }

    /** Names still bound in the activity-context naming facility (leaked ACIs show up here). */
    static int boundNames(MicroSleeContainer container) {
        Object backend = get(container, "activityContextNamingFacility");
        for (var f : backend.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            try {
                Object inner = f.get(backend);
                if (inner != null && inner.getClass().getSimpleName().equals("InMemoryActivityContextNamingFacility")) {
                    return ((Map<?, ?>) get(inner, "contexts")).size();
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return -1;
    }

    static void set(Object target, String field, Object value) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                var f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("No field " + field + " on " + target.getClass());
    }
}
