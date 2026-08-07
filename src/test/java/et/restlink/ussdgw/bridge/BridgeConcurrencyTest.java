package et.restlink.ussdgw.bridge;

import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.jss7.command.Ss7Command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Races that the single-threaded bridge tests cannot reach: an AS response landing at the
 * same instant as the adaptive gate, and two AS channels delivering one correlation.
 * <p>
 * The MAP dialog state machine allows exactly one response per invoke, and the subscriber
 * must see exactly one NI push — both are asserted through a capturing {@link RaCommandPort}.
 */
class BridgeConcurrencyTest {

    /** Enough rounds to hit the interleaving; each round is a fresh correlation. */
    private static final int ROUNDS = 300;

    private MicroSleeContainer container;
    private VirtualSessionStore store;
    private VirtualSessionBridge bridge;
    private CapturingPort port;
    private CountingNiDispatcher ni;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        store = newStore(container);
        port = new CapturingPort();
        ni = new CountingNiDispatcher();
        bridge = newBridge(store, ni);
        bridge.bindSs7(() -> port);
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    // ---------------------------------------------------------------- B1

    @Test
    void gateExpiryRacingAsResponseEmitsExactlyOneMapReply() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ROUNDS; i++) {
                String corr = "gate-race-" + i;
                String dialogId = "dlg-gate-" + i;
                seedAwaiting(corr, dialogId, System.currentTimeMillis() - 1);

                VirtualSession tickSnapshot = store.get(corr).orElseThrow();
                CyclicBarrier gun = new CyclicBarrier(2);
                Future<?> gate = pool.submit(() -> {
                    sync(gun);
                    bridge.onGateExpired(tickSnapshot);
                });
                Future<?> as = pool.submit(() -> {
                    sync(gun);
                    bridge.onAsResponse(
                            new AsResponse(corr, corr, 1, "Balance 42.00", AsAction.END, false), 25);
                });
                gate.get(20, TimeUnit.SECONDS);
                as.get(20, TimeUnit.SECONDS);

                assertThat(port.repliesFor(dialogId))
                        .as("MAP responses on one invoke for %s", dialogId)
                        .isEqualTo(1);
                assertThat(ni.pushesFor(corr))
                        .as("NI pushes for %s", corr)
                        .isLessThanOrEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void duplicateAsDeliveriesOnBridgedSessionEmitExactlyOneNiPush() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ROUNDS; i++) {
                String corr = "push-race-" + i;
                VirtualSession s = seedAwaiting(corr, "dlg-push-" + i, 0);
                // Already bridged: the MO leg is gone, so both channels want an NI push.
                s.setState(VirtualSessionState.S1_RELEASED);
                s.setDialogAlive(false);
                store.put(s);

                AsResponse pull = new AsResponse(corr, corr, 1, "Late menu", AsAction.END, false);
                AsResponse callback = new AsResponse(corr, corr, 1, "Late menu", AsAction.END, false);
                CyclicBarrier gun = new CyclicBarrier(2);
                Future<?> a = pool.submit(() -> {
                    sync(gun);
                    bridge.onAsResponse(pull, 30);
                });
                Future<?> b = pool.submit(() -> {
                    sync(gun);
                    bridge.onAsResponse(callback, -1);
                });
                a.get(20, TimeUnit.SECONDS);
                b.get(20, TimeUnit.SECONDS);

                assertThat(ni.pushesFor(corr))
                        .as("NI pushes for %s — the subscriber must not be pushed twice", corr)
                        .isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSyncResponsesOnLiveDialogEmitExactlyOneMapReply() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ROUNDS; i++) {
                String corr = "sync-race-" + i;
                String dialogId = "dlg-sync-" + i;
                seedAwaiting(corr, dialogId, 0);

                AsResponse r = new AsResponse(corr, corr, 1, "OK", AsAction.END, false);
                CyclicBarrier gun = new CyclicBarrier(2);
                Future<?> a = pool.submit(() -> {
                    sync(gun);
                    bridge.onAsResponse(r, 20);
                });
                Future<?> b = pool.submit(() -> {
                    sync(gun);
                    bridge.onAsResponse(r, 20);
                });
                a.get(20, TimeUnit.SECONDS);
                b.get(20, TimeUnit.SECONDS);

                assertThat(port.repliesFor(dialogId)).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- B2

    @Test
    void compareAndTransitionDoesNotRevertConcurrentFieldWrite() {
        AtomicBoolean armed = new AtomicBoolean(false);
        // Injects a writer into the exact window the old implementation opened: between the
        // CAS on `state` and the caller's read-modify-write of every CMP field.
        VirtualSessionStore racy = new VirtualSessionStore() {
            @Override
            public Optional<VirtualSession> get(String correlationId) {
                Optional<VirtualSession> read = super.get(correlationId);
                if (armed.compareAndSet(true, false)) {
                    super.setDialogAlive(correlationId, false);
                }
                return read;
            }
        };
        racy.container = container;
        racy.config = new UssdConfigService();
        racy.profileTtlMs = 120_000L;
        racy.ensureTable();

        VirtualSession s = new VirtualSession("vs", "cas-b2", "cas-b2", "2519", 0, "dlg-b2", "*1#");
        s.setState(VirtualSessionState.AWAITING_AS);
        s.setDialogAlive(true);
        racy.put(s);

        armed.set(true);
        assertThat(racy.compareAndTransition("cas-b2",
                VirtualSessionState.AWAITING_AS, VirtualSessionState.S1_RELEASED)).isPresent();

        VirtualSession after = store.get("cas-b2").orElseThrow();
        assertThat(after.state()).isEqualTo(VirtualSessionState.S1_RELEASED);
        assertThat(after.dialogAlive())
                .as("dialogAlive written during the CAS window must survive")
                .isFalse();
    }

    @Test
    void casRacingSingleFieldWriteNeverLosesTheUpdate() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ROUNDS; i++) {
                String corr = "cas-race-" + i;
                VirtualSession s = seedAwaiting(corr, "dlg-cas-" + i, 0);
                assertThat(s.dialogAlive()).isTrue();

                CyclicBarrier gun = new CyclicBarrier(2);
                Future<?> cas = pool.submit(() -> {
                    sync(gun);
                    store.compareAndTransition(corr,
                            VirtualSessionState.AWAITING_AS, VirtualSessionState.S1_RELEASED);
                });
                Future<?> field = pool.submit(() -> {
                    sync(gun);
                    store.setDialogAlive(corr, false);
                });
                cas.get(20, TimeUnit.SECONDS);
                field.get(20, TimeUnit.SECONDS);

                VirtualSession after = store.get(corr).orElseThrow();
                assertThat(after.state()).isEqualTo(VirtualSessionState.S1_RELEASED);
                assertThat(after.dialogAlive()).isFalse();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- M3

    @Test
    void sessionTtlActuallyExpires() {
        long now = System.currentTimeMillis();
        VirtualSession old = new VirtualSession("vs", "ttl-old", "r", "2519", 0, "dlg-ttl", "*1#");
        old.setCreatedAtMs(now - 600_000L); // older than profileTtlMs (120s)
        old.setState(VirtualSessionState.AWAITING_AS);
        store.put(old);
        // A second write must not slide the expiry forward — the anchor is createdAtMs.
        store.put(old);

        VirtualSession fresh = new VirtualSession("vs", "ttl-new", "r", "2519", 0, "dlg-t2", "*1#");
        fresh.setState(VirtualSessionState.AWAITING_AS);
        store.put(fresh);

        assertThat(store.reclaimExpired(now)).isEqualTo(1);
        assertThat(store.get("ttl-old")).isEmpty();
        assertThat(store.get("ttl-new")).isPresent();
    }

    // ---------------------------------------------------------------- H8

    @Test
    void gateIndexTracksOnlyArmedAwaitingSessions() {
        VirtualSession armed = seedAwaiting("idx-armed", "dlg-idx-1", System.currentTimeMillis() + 60_000);
        seedAwaiting("idx-due", "dlg-idx-2", System.currentTimeMillis() - 1);
        VirtualSession active = new VirtualSession("vs", "idx-active", "r", "2519", 0, "dlg-idx-3", "*1#");
        active.setState(VirtualSessionState.ACTIVE);
        active.setGateDeadlineMs(1);
        store.put(active);

        assertThat(store.armedGateCount()).isEqualTo(2);
        assertThat(store.awaitingPastDeadline(System.currentTimeMillis()))
                .extracting(VirtualSession::correlationId)
                .containsExactly("idx-due");

        // Leaving AWAITING_AS must retire the gate, otherwise the tick keeps re-reading it.
        store.compareAndTransition("idx-armed",
                VirtualSessionState.AWAITING_AS, VirtualSessionState.S1_RELEASED);
        assertThat(store.armedGateCount()).isEqualTo(1);
        store.remove("idx-due");
        assertThat(store.armedGateCount()).isZero();
        assertThat(armed.correlationId()).isEqualTo("idx-armed");
    }

    // ---------------------------------------------------------------- helpers

    private VirtualSession seedAwaiting(String corr, String dialogId, long gateDeadlineMs) {
        VirtualSession s = new VirtualSession("vs-" + corr, corr, corr, "251911000001", 0,
                dialogId, "*123#");
        s.setInvokeId(7);
        s.setDialogAlive(true);
        s.setState(VirtualSessionState.AWAITING_AS);
        s.setGateDeadlineMs(gateDeadlineMs);
        store.put(s);
        return s;
    }

    private static void sync(CyclicBarrier gun) {
        try {
            gun.await(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static VirtualSessionStore newStore(MicroSleeContainer container) {
        VirtualSessionStore s = new VirtualSessionStore();
        s.container = container;
        s.config = new UssdConfigService();
        s.profileTtlMs = 120_000L;
        s.ensureTable();
        return s;
    }

    static VirtualSessionBridge newBridge(VirtualSessionStore store, CountingNiDispatcher ni) {
        VirtualSessionBridge bridge = new VirtualSessionBridge();
        set(bridge, "store", store);
        set(bridge, "adaptive", new AdaptiveTimeout());
        UssdConfigService cfg = new UssdConfigService();
        set(cfg, "bridgeEnabledProp", true);
        set(cfg, "asyncGateTimeoutMsProp", 7000L);
        set(cfg, "asyncWaitMessageProp", "Please wait...");
        set(cfg, "asyncHardFailMessageProp", "unavailable");
        set(cfg, "dialogTimeoutMsProp", 60_000L);
        set(bridge, "config", cfg);
        set(bridge, "cdr", new SilentCdr());
        set(bridge, "accessNi", ni);
        return bridge;
    }

    static void set(Object target, String field, Object value) {
        try {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    var f = c.getDeclaredField(field);
                    f.setAccessible(true);
                    f.set(target, value);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            throw new NoSuchFieldException(field);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static final class SilentCdr extends CdrService {
        @Override
        public void write(String correlationId, CdrPhase phase, String msisdn, String shortCode,
                          String status, String detail, int networkId, String tenantId,
                          String originationType, Long gateMs, Long observedEwmaMs) { }
    }

    static final class CountingNiDispatcher extends et.restlink.ussdgw.access.AccessNiDispatcher {
        private final ConcurrentHashMap<String, AtomicInteger> pushes = new ConcurrentHashMap<>();

        @Override
        public void requestNiPush(VirtualSession session, String text) {
            pushes.computeIfAbsent(session.correlationId(), k -> new AtomicInteger())
                  .incrementAndGet();
        }

        int pushesFor(String correlationId) {
            AtomicInteger n = pushes.get(correlationId);
            return n == null ? 0 : n.get();
        }
    }

    /** Counts MAP responses per dialog — one invoke may be answered exactly once. */
    static final class CapturingPort implements RaCommandPort {
        private final ConcurrentHashMap<String, AtomicInteger> replies = new ConcurrentHashMap<>();

        @Override
        public void sendCommand(OutboundCommand command) {
            String dialogId = switch (command) {
                case Ss7Command.MapProcessUnstructuredSsResponse r -> r.dialogId();
                case Ss7Command.MapUnstructuredSsRequest r -> r.dialogId();
                default -> null;
            };
            if (dialogId != null) {
                replies.computeIfAbsent(dialogId, k -> new AtomicInteger()).incrementAndGet();
            }
        }

        int repliesFor(String dialogId) {
            AtomicInteger n = replies.get(dialogId);
            return n == null ? 0 : n.get();
        }
    }
}
