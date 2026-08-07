package et.restlink.ussdgw.service;

import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionState;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.config.UssdConfigService;

import com.microjainslee.core.MicroSleeContainer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate tick is the only thing that unparks a bridged MAP dialog. One poisoned session
 * must therefore never be able to abort the tick: the due list comes back in deadline order,
 * so an unguarded throw would put the same session first forever and every parked dialog
 * would hang to MSC timeout (a dialog leak).
 */
class BridgeGateSchedulerResilienceTest {

    private MicroSleeContainer container;
    private VirtualSessionStore store;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        store = new VirtualSessionStore();
        set(store, "container", container);
        set(store, "config", new UssdConfigService());
        set(store, "profileTtlMs", 120_000L);
        store.ensureTable();
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void oneThrowingSessionDoesNotStarveTheRemainingGates() {
        long past = System.currentTimeMillis() - 1000;
        seed("aaa-poison", past);          // sorts first: earliest deadline, lowest corr
        seed("bbb-good", past + 1);
        seed("ccc-good", past + 2);

        List<String> gated = new CopyOnWriteArrayList<>();
        BridgeGateScheduler scheduler = new BridgeGateScheduler();
        set(scheduler, "store", store);
        set(scheduler, "bridge", new VirtualSessionBridge() {
            @Override
            public boolean onGateExpired(VirtualSession s) {
                if ("aaa-poison".equals(s.correlationId())) {
                    throw new IllegalStateException("ProfileFacility transiently unavailable");
                }
                gated.add(s.correlationId());
                return true;
            }
        });

        scheduler.tickGates();

        assertThat(gated).containsExactly("bbb-good", "ccc-good");
        // M8: the metric counts real expiries, not attempts — the poisoned one did not expire.
        assertThat(scheduler.gateExpired()).isEqualTo(2);
    }

    @Test
    void gateMetricIgnoresLostCasAttempts() {
        seed("cas-loser", System.currentTimeMillis() - 1000);

        BridgeGateScheduler scheduler = new BridgeGateScheduler();
        set(scheduler, "store", store);
        set(scheduler, "bridge", new VirtualSessionBridge() {
            @Override
            public boolean onGateExpired(VirtualSession s) {
                return false; // an AS response won the CAS first
            }
        });

        scheduler.tickGates();

        assertThat(scheduler.gateExpired()).isZero();
    }

    @Test
    void reclaimSurvivesAStoreThatThrows() {
        BridgeGateScheduler scheduler = new BridgeGateScheduler();
        set(scheduler, "store", new VirtualSessionStore() {
            @Override
            public int reclaimExpired(long nowMs) {
                throw new IllegalStateException("MicroSleeContainer ProfileFacility not available");
            }
        });
        set(scheduler, "bridge", new VirtualSessionBridge());

        scheduler.reclaimExpiredTx();

        assertThat(scheduler.reclaimCount()).isZero();
    }

    private void seed(String corr, long deadlineMs) {
        VirtualSession s = new VirtualSession("vs-" + corr, corr, corr, "2519", 0,
                "dlg-" + corr, "*123#");
        s.setState(VirtualSessionState.AWAITING_AS);
        s.setGateDeadlineMs(deadlineMs);
        store.put(s);
    }

    private static void set(Object target, String field, Object value) {
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
}
