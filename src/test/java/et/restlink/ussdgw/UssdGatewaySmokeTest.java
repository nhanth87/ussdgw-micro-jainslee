package et.restlink.ussdgw;

import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionState;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.config.UssdConfigService;

import com.microjainslee.core.MicroSleeContainer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** In-process smoke without @QuarkusTest / ASM. */
class UssdGatewaySmokeTest {
    private MicroSleeContainer container;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void adaptiveAndStorePipeline() {
        AdaptiveTimeout at = new AdaptiveTimeout();
        at.recordLatency(1, 1500);
        long gate = at.suggestGateMs(1, 7000);
        assertThat(gate).isEqualTo(2250);

        VirtualSessionStore store = new VirtualSessionStore();
        set(store, "container", container);
        set(store, "config", new UssdConfigService());
        set(store, "profileTtlMs", 120_000L);
        store.ensureTable();

        VirtualSession s = new VirtualSession("v", "c", "r", "2519", 1, "d", "*123#");
        s.setState(VirtualSessionState.AWAITING_AS);
        s.setGateDeadlineMs(System.currentTimeMillis() - 1);
        store.put(s);
        assertThat(store.awaitingPastDeadline(System.currentTimeMillis()))
                .extracting(VirtualSession::correlationId)
                .contains("c");
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
