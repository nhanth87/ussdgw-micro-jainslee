package et.restlink.ussdgw.bridge;

import et.restlink.ussdgw.config.UssdConfigService;

import com.microjainslee.core.MicroSleeContainer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VirtualSessionStoreTest {
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
    void putGetRoundTripViaProfile() {
        VirtualSession s = new VirtualSession("vs", "corr-rt", "req-rt",
                "251911000001", 1, "dlg-rt", "*123#");
        s.setState(VirtualSessionState.AWAITING_AS);
        s.setTenantId("ethio-bank");
        s.setPendingAlphabet(et.restlink.ussdgw.api.UssdAlphabet.UNICODE);
        s.setPendingText("ሰላም");
        store.put(s);

        OptionalSessionAssert present = new OptionalSessionAssert(store.get("corr-rt"));
        present.isPresent();
        assertThat(present.get().tenantId()).isEqualTo("ethio-bank");
        assertThat(present.get().state()).isEqualTo(VirtualSessionState.AWAITING_AS);
        assertThat(present.get().pendingAlphabet())
                .isEqualTo(et.restlink.ussdgw.api.UssdAlphabet.UNICODE);
        assertThat(present.get().pendingText()).isEqualTo("ሰላም");
        assertThat(store.byDialogId("dlg-rt")).isPresent();
        assertThat(store.byRequestId("req-rt")).isPresent();
    }

    @Test
    void rejectsZombiePushPendingAndWrongGeneration() {
        VirtualSession s = new VirtualSession("vs", "corr-1", "req-1",
                "251911000001", 0, "dlg-1", "*123#");
        store.put(s);
        s.setState(VirtualSessionState.ZOMBIE);
        store.put(s);
        assertThat(store.acceptAsResponse("corr-1", 1)).isEmpty();

        s.setState(VirtualSessionState.PUSH_PENDING);
        store.put(s);
        assertThat(store.acceptAsResponse("corr-1", 1)).isEmpty();

        s.setState(VirtualSessionState.AWAITING_AS);
        store.put(s);
        assertThat(store.acceptAsResponse("corr-1", 99)).isEmpty();
        assertThat(store.acceptAsResponse("corr-1", 1)).isPresent();
    }

    @Test
    void awaitingPastDeadline() {
        VirtualSession s = new VirtualSession("vs", "corr-2", "req-2",
                "251911000002", 0, "dlg-2", "*123#");
        s.setState(VirtualSessionState.AWAITING_AS);
        s.setGateDeadlineMs(System.currentTimeMillis() - 10);
        store.put(s);
        assertThat(store.awaitingPastDeadline(System.currentTimeMillis())).hasSize(1);
    }

    @Test
    void compareAndTransitionCas() {
        VirtualSession s = new VirtualSession("vs", "corr-cas", "req-cas",
                "2519", 0, "dlg-cas", "*1#");
        s.setState(VirtualSessionState.AWAITING_AS);
        store.put(s);
        assertThat(store.compareAndTransition("corr-cas",
                VirtualSessionState.AWAITING_AS, VirtualSessionState.S1_RELEASED)).isPresent();
        assertThat(store.compareAndTransition("corr-cas",
                VirtualSessionState.AWAITING_AS, VirtualSessionState.COMPLETED)).isEmpty();
        assertThat(store.get("corr-cas").orElseThrow().state())
                .isEqualTo(VirtualSessionState.S1_RELEASED);
    }

    @Test
    void removeDropsProfile() {
        VirtualSession s = new VirtualSession("vs", "corr-rm", "req-rm",
                "2519", 0, "dlg-rm", "*1#");
        store.put(s);
        store.remove("corr-rm");
        assertThat(store.get("corr-rm")).isEmpty();
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

    /** Tiny helper to avoid Optional generic noise in AssertJ chains. */
    private record OptionalSessionAssert(java.util.Optional<VirtualSession> opt) {
        void isPresent() { assertThat(opt).isPresent(); }
        VirtualSession get() { return opt.orElseThrow(); }
    }
}
