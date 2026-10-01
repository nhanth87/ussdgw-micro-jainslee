package et.restlink.ussdgw.bridge;

import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.api.classic.ClassicNiHttpPark;
import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.service.BridgeGateScheduler;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.core.MicroSleeContainer;

import io.quarkus.scheduler.Scheduled;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 3 (P1-8 / P1-9 / P1-10): no per-tick write storm, bounded registries,
 * isolated sweeps.
 */
class GateDeferAndRegistrySweepTest {
    private MicroSleeContainer container;
    private VirtualSessionStore store;
    private UssdConfigService config;
    private RecordingCdr cdr;
    private VirtualSessionBridge bridge;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        store = new VirtualSessionStore();
        set(store, "container", container);
        set(store, "config", new UssdConfigService());
        set(store, "profileTtlMs", 120_000L);
        store.ensureTable();

        config = new UssdConfigService();
        set(config, "bridgeEnabledProp", false);
        set(config, "asyncGateTimeoutMsProp", 7000L);
        set(config, "asyncWaitMessageProp", "Please wait...");
        set(config, "asyncHardFailMessageProp", "unavailable");
        set(config, "dialogTimeoutMsProp", 60_000L);

        cdr = new RecordingCdr();
        bridge = new VirtualSessionBridge();
        set(bridge, "store", store);
        set(bridge, "adaptive", new AdaptiveTimeout());
        set(bridge, "config", config);
        set(bridge, "cdr", cdr);
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    // ------------------------------------------------------------------ P1-8

    @Test
    void deferredGateLeavesTheDueIndex() {
        String corr = "defer-1";
        VirtualSession s = new VirtualSession("vs", corr, corr, "251911000001", 0,
                "dlg-defer-1", "*123#");
        s.setState(VirtualSessionState.AWAITING_AS);
        s.setDialogAlive(true);
        s.setOriginationType(OriginationType.MAP);
        s.setMap2mapHopOutstanding(true);
        s.setGateDeadlineMs(System.currentTimeMillis() - 1);
        store.put(s);

        VirtualSession tickSnapshot = store.get(corr).orElseThrow();
        assertThat(bridge.onGateExpired(tickSnapshot)).isFalse();

        // One CDR row for the defer — not one per tick.
        assertThat(cdr.statuses(corr)).containsExactly("MAP2MAP_MO_HOLD");

        // Re-indexed ~+30s (fallback hop TTL): invisible to near-term ticks…
        long now = System.currentTimeMillis();
        assertThat(store.awaitingPastDeadline(now + 29_000))
                .extracting(VirtualSession::correlationId)
                .doesNotContain(corr);
        // …but still due after the defer window.
        assertThat(store.awaitingPastDeadline(now + 31_000))
                .extracting(VirtualSession::correlationId)
                .contains(corr);
    }

    // ------------------------------------------------------------------ P1-9

    @Test
    void gatedRegistrySweepsAndCaps() {
        GatedSessionRegistry reg = new GatedSessionRegistry();
        reg.setTtlMs(1_000L); // clamped floor
        reg.stamp(GatedSessionMeta.niPark("old-1", "js-1", 25_000L, null, 0,
                "2519", "*1#", "vs-1"));
        try {
            Thread.sleep(1_100L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        reg.sweepExpired();
        assertThat(reg.size()).isZero();

        GatedSessionRegistry capped = new GatedSessionRegistry();
        for (int i = 0; i < GatedSessionRegistry.MAX_ENTRIES + 50; i++) {
            capped.stamp(GatedSessionMeta.niPark("c-" + i, null, 25_000L, null, 0,
                    "2519", "*1#", "vs"));
        }
        assertThat(capped.size()).isLessThanOrEqualTo(GatedSessionRegistry.MAX_ENTRIES);
    }

    @Test
    void parkSweepsSessionGoneRecordsAndAbortsSettleImmediately() {
        Map<String, VirtualSession> rows = new ConcurrentHashMap<>();
        VirtualSessionStore stubStore = new VirtualSessionStore() {
            @Override public void ensureTable() { }
            @Override public Optional<VirtualSession> get(String corr) {
                return Optional.ofNullable(rows.get(corr));
            }
        };

        ClassicNiHttpPark park = new ClassicNiHttpPark();
        set(park, "adaptive", new AdaptiveTimeout());
        set(park, "config", new UssdConfigService());
        set(park, "wireFacade", new AsWireFacade());
        set(park, "store", stubStore);
        CapturingHttp http = new CapturingHttp();
        park.bindHttp(() -> http);

        // Ghost record (ussdTx row gone) is swept…
        park.park("http-ghost", "js-ghost", "ghost-corr", AsHttpWireFormat.XML, 0, false);
        assertThat(park.sweepStaleParked()).isEqualTo(1);
        assertThat(park.findByCorr("ghost-corr")).isEmpty();

        // …live records are kept, and abort settles the HTTP right away.
        VirtualSession live = new VirtualSession("vs", "live-corr", "r", "2519", 0,
                "live-corr", "");
        rows.put("live-corr", live);
        park.park("http-live", "js-live", "live-corr", AsHttpWireFormat.XML, 0, false);
        assertThat(park.sweepStaleParked()).isZero();
        assertThat(park.findByCorr("live-corr")).isPresent();

        assertThat(park.abortParked("live-corr")).isTrue();
        assertThat(http.bodies()).hasSize(1);
        assertThat(park.findByCorr("live-corr")).isEmpty();
        // Second settle loses (already settled) — exactly one HTTP reply.
        assertThat(park.abortParked("live-corr")).isFalse();
        assertThat(http.bodies()).hasSize(1);

        // Network abort on the session also settles the park (bridge path).
        park.park("http-live-2", "js-live-2", "live-corr", AsHttpWireFormat.XML, 0, false);
        assertThat(park.abortParked("missing-corr")).isFalse();
    }

    // ------------------------------------------------------------------ P1-10

    @Test
    void sweepsNeverOverlap() throws Exception {
        for (String method : List.of("tickGates", "reclaimExpiredTx", "sweepPendingCorrelations")) {
            var m = BridgeGateScheduler.class.getDeclaredMethod(method);
            Scheduled ann = m.getAnnotation(Scheduled.class);
            assertThat(ann).as("@Scheduled on " + method).isNotNull();
            assertThat(ann.concurrentExecution())
                    .as("concurrentExecution on " + method)
                    .isEqualTo(Scheduled.ConcurrentExecution.SKIP);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void set(Object target, String field, Object value) {
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

    private static final class RecordingCdr extends CdrService {
        final List<String[]> rows = new CopyOnWriteArrayList<>();

        @Override
        public void write(String correlationId, CdrPhase phase, String msisdn,
                          String shortCode, String status, String detail,
                          int networkId, String tenantId, String originationType,
                          Long gateMs, Long observedEwmaMs) {
            rows.add(new String[]{correlationId, status});
        }

        List<String> statuses(String correlationId) {
            return rows.stream()
                    .filter(r -> correlationId.equals(r[0]))
                    .map(r -> r[1])
                    .toList();
        }
    }

    private static final class CapturingHttp implements RaCommandPort {
        final List<OutboundCommand> commands = new CopyOnWriteArrayList<>();
        @Override public void sendCommand(OutboundCommand command) { commands.add(command); }

        List<String> bodies() {
            return commands.stream()
                    .map(c -> {
                        try {
                            var m = c.getClass().getMethod("body");
                            Object b = m.invoke(c);
                            return b == null ? "" : b.toString();
                        } catch (ReflectiveOperationException e) {
                            return c.toString();
                        }
                    })
                    .toList();
        }
    }
}
