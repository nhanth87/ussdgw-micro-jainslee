package et.restlink.ussdgw.api.classic;

import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.httpserver.command.HttpServerCommand;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ClassicNiHttpParkTest {
    private static final int RACE_ROUNDS = 200;

    private ClassicNiHttpPark park;
    private CapturingHttp http;

    @BeforeEach
    void setUp() {
        park = new ClassicNiHttpPark();
        set(park, "adaptive", new AdaptiveTimeout());
        set(park, "config", new UssdConfigService());
        set(park, "wireFacade", new AsWireFacade());
        http = new CapturingHttp();
        park.bindHttp(() -> http);
    }

    @AfterEach
    void tearDown() {
        park.shutdown();
    }

    @Test
    void completeParkedEncodedSettlesAndKeepsJsession() {
        park.park("http-n", "js-n", "corr-n", AsHttpWireFormat.XML, 0, false);
        String body = ClassicDialogXmlCodec.encodeNiNotifyResponse("corr-n");
        assertThat(park.completeParkedEncoded("corr-n", body)).isTrue();
        assertThat(http.last).isInstanceOf(HttpServerCommand.HttpResponseExCommand.class);
        var ex = (HttpServerCommand.HttpResponseExCommand) http.last;
        assertThat(ex.textBody()).contains("unstructuredSSNotify_Response");
        assertThat(park.findByJsession("js-n")).isPresent();
        assertThat(park.findByCorr("corr-n").orElseThrow().httpSessionId()).isNull();
        assertThat(park.completeParkedEncoded("corr-n", body)).isFalse();
    }

    @Test
    void parkFindUnparkByCorrAndJsession() {
        ClassicNiHttpPark.ParkRecord rec = park.park(
                "http-1", "js-1", "corr-1", AsHttpWireFormat.XML, 0, false);
        assertThat(rec.correlationId()).isEqualTo("corr-1");
        assertThat(park.findByJsession("js-1")).contains(rec);
        assertThat(park.findByCorr("corr-1")).contains(rec);
        assertThat(park.size()).isEqualTo(1);

        assertThat(park.unpark("corr-1")).contains(rec);
        assertThat(park.findByJsession("js-1")).isEmpty();
        assertThat(park.findByCorr("corr-1")).isEmpty();
        assertThat(park.size()).isZero();
    }

    @Test
    void completeParkedRepliesAndKeepsJsessionMapping() {
        park.park("http-1", "js-keep", "corr-keep", AsHttpWireFormat.JSON, 0, false);
        assertThat(park.completeParked("corr-keep", "Menu", AsAction.CONTINUE)).isTrue();
        assertThat(http.last).isNotNull();
        assertThat(http.last).isInstanceOf(HttpServerCommand.HttpResponseExCommand.class);
        var ex = (HttpServerCommand.HttpResponseExCommand) http.last;
        assertThat(ex.sessionId()).isEqualTo("http-1");
        assertThat(ex.headers()).containsEntry("Set-Cookie", "JSESSIONID=js-keep; Path=/; HttpOnly");
        assertThat(ex.contentType()).contains("json");

        // Mapping retained for classic continue; no pending HTTP until re-park.
        assertThat(park.findByJsession("js-keep")).isPresent();
        assertThat(park.findByCorr("corr-keep").orElseThrow().httpSessionId()).isNull();
        assertThat(park.completeParked("corr-keep", "x", AsAction.CONTINUE)).isFalse();
    }

    @Test
    void reparkUpdatesHttpSessionId() {
        park.park("http-old", "js-2", "corr-2", AsHttpWireFormat.XML, 1, false);
        park.completeParked("corr-2", "ok", AsAction.CONTINUE);
        ClassicNiHttpPark.ParkRecord again = park.park(
                "http-new", "js-2", "corr-2", AsHttpWireFormat.XML, 1, false);
        assertThat(again.httpSessionId()).isEqualTo("http-new");
        assertThat(park.findByJsession("js-2").orElseThrow().httpSessionId()).isEqualTo("http-new");
    }

    @Test
    void scheduleAdaptiveGateWritesGatedCdrWithGateMs() {
        CapturingCdr cdr = new CapturingCdr();
        set(park, "cdr", cdr);

        ClassicNiHttpPark.ParkRecord rec = park.park(
                "http-g", "js-g", "corr-g", AsHttpWireFormat.XML, 0, false);
        park.scheduleAdaptiveGate(rec);

        assertThat(rec.appliedGateMs()).isGreaterThanOrEqualTo(AdaptiveTimeout.FLOOR_MS);
        assertThat(cdr.gateMs).isEqualTo(rec.appliedGateMs());
        assertThat(cdr.status).isEqualTo("GATED");
        assertThat(cdr.phase.name()).isEqualTo("S1_ACTIVE");
        assertThat(cdr.detail).contains("AdaptiveTimeout");
    }

    @Test
    void adaptiveGateExpiresRepliesAbort() throws Exception {
        ClassicNiHttpPark.ParkRecord rec = park.park(
                "http-gate", "js-gate", "corr-gate", AsHttpWireFormat.XML, 0, false);
        park.scheduleGate(rec, 40L);

        waitUntil(() -> http.all.size() == 1 && park.findByCorr("corr-gate").isEmpty(), 2_000);

        var ex = (HttpServerCommand.HttpResponseExCommand) http.all.get(0);
        assertThat(ex.statusCode()).isEqualTo(200);
        assertThat(ex.sessionId()).isEqualTo("http-gate");
        assertThat(ex.headers()).containsEntry("Set-Cookie", "JSESSIONID=js-gate; Path=/; HttpOnly");
        assertThat(ex.textBody().toLowerCase()).contains("abort");
    }

    @Test
    void completeParkedCancelsGateNoAbort() throws Exception {
        ClassicNiHttpPark.ParkRecord rec = park.park(
                "http-ok", "js-ok", "corr-ok", AsHttpWireFormat.XML, 0, false);
        park.scheduleGate(rec, 250L);
        assertThat(park.completeParked("corr-ok", "Menu", AsAction.CONTINUE)).isTrue();

        Thread.sleep(400);
        assertThat(http.all).hasSize(1);
        var ex = (HttpServerCommand.HttpResponseExCommand) http.all.get(0);
        assertThat(ex.textBody().toLowerCase()).doesNotContain("abort");
        assertThat(ex.textBody()).contains("Menu");
    }

    @Test
    void scheduleLabEchoCompletesWithoutSbbSleep() throws Exception {
        park.park("http-echo", "js-echo", "corr-echo", AsHttpWireFormat.XML, 0, false);
        park.scheduleLabEcho("corr-echo", "echo-text", 30L);

        waitUntil(() -> http.all.size() == 1, 2_000);
        var ex = (HttpServerCommand.HttpResponseExCommand) http.all.get(0);
        assertThat(ex.textBody()).contains("echo-text");
        assertThat(park.findByCorr("corr-echo").orElseThrow().httpSessionId()).isNull();
    }

    @Test
    void completeParkedVsGateRaceSingleReply() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < RACE_ROUNDS; i++) {
                String corr = "race-" + i;
                String httpId = "http-" + i;
                int before = http.all.size();
                ClassicNiHttpPark.ParkRecord rec = park.park(
                        httpId, "js-" + i, corr, AsHttpWireFormat.XML, 0, false);
                // Arm a near-immediate gate so both paths contend.
                park.scheduleGate(rec, 1L);

                CyclicBarrier gun = new CyclicBarrier(2);
                Future<?> complete = pool.submit(() -> {
                    sync(gun);
                    park.completeParked(corr, "won", AsAction.CONTINUE);
                });
                Future<?> waitGate = pool.submit(() -> {
                    sync(gun);
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                complete.get(5, TimeUnit.SECONDS);
                waitGate.get(5, TimeUnit.SECONDS);

                waitUntil(() -> repliesFor(httpId) >= 1 || park.findByCorr(corr).isEmpty(), 1_000);
                assertThat(repliesFor(httpId))
                        .as("corr=%s / %s must emit exactly one HTTP reply", corr, httpId)
                        .isEqualTo(1);
                assertThat(http.all.size() - before)
                        .as("no extra replies beyond this round")
                        .isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private long repliesFor(String httpSessionId) {
        return http.all.stream()
                .filter(HttpServerCommand.HttpResponseExCommand.class::isInstance)
                .map(HttpServerCommand.HttpResponseExCommand.class::cast)
                .filter(c -> httpSessionId.equals(c.sessionId()))
                .count();
    }

    private static void waitUntil(java.util.concurrent.Callable<Boolean> cond, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(cond.call())) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(cond.call()).as("condition within %dms", timeoutMs).isTrue();
    }

    private static void sync(CyclicBarrier gun) {
        try {
            gun.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
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

    private static final class CapturingHttp implements RaCommandPort {
        private final AtomicReference<OutboundCommand> lastRef = new AtomicReference<>();
        volatile OutboundCommand last;
        final List<OutboundCommand> all = new CopyOnWriteArrayList<>();

        @Override
        public void sendCommand(OutboundCommand command) {
            last = command;
            lastRef.set(command);
            all.add(command);
        }
    }

    private static final class CapturingCdr extends CdrService {
        volatile CdrPhase phase;
        volatile String status;
        volatile String detail;
        volatile Long gateMs;
        volatile Long ewmaMs;

        @Override
        public void write(String correlationId, CdrPhase phase, String msisdn,
                          String shortCode, String status, String detail,
                          int networkId, String tenantId, String originationType,
                          Long gateMs, Long observedEwmaMs) {
            this.phase = phase;
            this.status = status;
            this.detail = detail;
            this.gateMs = gateMs;
            this.ewmaMs = observedEwmaMs;
        }
    }
}
