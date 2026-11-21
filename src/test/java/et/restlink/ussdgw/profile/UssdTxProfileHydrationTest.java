package et.restlink.ussdgw.profile;

import com.microjainslee.core.MicroSleeContainer;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionState;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.config.UssdConfigService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hydration round-trip for the G2 claim fields
 * ({@code msDigitInvokeId}/{@code msDigitInFlight}) through the real
 * {@code ussdTx} ProfileFacility row and {@link UssdTxProfileMapper}.
 */
class UssdTxProfileHydrationTest {

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
    void msDigitFieldsRoundTripViaMapper() {
        VirtualSession s = new VirtualSession("vs-hyd", "corr-hyd-g2", "req-hyd",
                "251911000007", 1, "dlg-hyd", "*804#");
        s.setState(VirtualSessionState.ACTIVE);
        s.setMsDigitInvokeId(777L);
        s.setMsDigitInFlight(true);
        store.put(s);

        VirtualSession back = store.get("corr-hyd-g2").orElseThrow();
        assertThat(back.msDigitInvokeId()).isEqualTo(777L);
        assertThat(back.msDigitInFlight()).isTrue();

        // Claim path writes the same row fields the mapper reads back.
        store.releaseMsDigitInFlight("corr-hyd-g2");
        VirtualSession released = store.get("corr-hyd-g2").orElseThrow();
        assertThat(released.msDigitInvokeId()).isEqualTo(777L);
        assertThat(released.msDigitInFlight()).isFalse();
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
