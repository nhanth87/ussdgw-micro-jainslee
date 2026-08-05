package et.restlink.ussdgw.bridge;

import et.restlink.ussdgw.config.UssdConfigService;

import com.microjainslee.core.MicroSleeContainer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VirtualSessionBridgeTest {
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
    void acceptAsResponseRejectsCompletedSession() {
        VirtualSession s = new VirtualSession("vs", "c1", "r1", "2519", 0, "d1", "*123#");
        s.setState(VirtualSessionState.COMPLETED);
        store.put(s);
        assertThat(store.acceptAsResponse("c1", 1)).isEmpty();
    }

    @Test
    void gateExpiryCandidatesOnlyAwaiting() {
        VirtualSession a = new VirtualSession("vs", "a", "ra", "1", 0, "da", "*123#");
        a.setState(VirtualSessionState.AWAITING_AS);
        a.setGateDeadlineMs(1);
        VirtualSession b = new VirtualSession("vs2", "b", "rb", "2", 0, "db", "*123#");
        b.setState(VirtualSessionState.ACTIVE);
        b.setGateDeadlineMs(1);
        store.put(a);
        store.put(b);
        assertThat(store.awaitingPastDeadline(System.currentTimeMillis()))
                .extracting(VirtualSession::correlationId)
                .containsExactly("a");
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
