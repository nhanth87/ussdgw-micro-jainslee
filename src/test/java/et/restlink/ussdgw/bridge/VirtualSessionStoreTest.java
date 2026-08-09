package et.restlink.ussdgw.bridge;

import et.restlink.ussdgw.config.UssdConfigService;

import com.microjainslee.core.MicroSleeContainer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        s.setOriginatedUssd("*804#");
        s.setRedirectUssd("*875#");
        s.setHopUssd("*8775#");
        store.put(s);

        OptionalSessionAssert present = new OptionalSessionAssert(store.get("corr-rt"));
        present.isPresent();
        assertThat(present.get().tenantId()).isEqualTo("ethio-bank");
        assertThat(present.get().state()).isEqualTo(VirtualSessionState.AWAITING_AS);
        assertThat(present.get().pendingAlphabet())
                .isEqualTo(et.restlink.ussdgw.api.UssdAlphabet.UNICODE);
        assertThat(present.get().pendingText()).isEqualTo("ሰላም");
        assertThat(present.get().originatedUssd()).isEqualTo("*804#");
        assertThat(present.get().redirectUssd()).isEqualTo("*875#");
        assertThat(present.get().hopUssd()).isEqualTo("*8775#");
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

    @Test
    void concurrentMsisdnsKeepSeparateProfileRows() {
        VirtualSession a = new VirtualSession("vs-a", "corr-a", "req-a",
                "251911230398", 1, "corr-a", "");
        VirtualSession b = new VirtualSession("vs-b", "corr-b", "req-b",
                "251911230399", 1, "corr-b", "");
        a.setMscGt("251971200146");
        a.setImsi("636010024533522");
        b.setMscGt("251971200999");
        b.setImsi("636010024533599");
        store.put(a);
        store.put(b);

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.get("corr-a").orElseThrow().msisdn()).isEqualTo("251911230398");
        assertThat(store.get("corr-a").orElseThrow().mscGt()).isEqualTo("251971200146");
        assertThat(store.get("corr-b").orElseThrow().msisdn()).isEqualTo("251911230399");
        assertThat(store.get("corr-b").orElseThrow().imsi()).isEqualTo("636010024533599");
        assertThat(store.findAwaitingAsByMsisdn("251911230398", null)).isEmpty();
    }

    @Test
    void putRejectsCorrelationReuseAcrossMsisdn() {
        store.put(new VirtualSession("vs1", "corr-shared", "req-1",
                "251911230398", 0, "corr-shared", ""));
        assertThatThrownBy(() -> store.put(new VirtualSession("vs2", "corr-shared", "req-2",
                "251911230399", 0, "corr-shared", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already bound to msisdn=251911230398");
        assertThat(store.get("corr-shared").orElseThrow().msisdn()).isEqualTo("251911230398");
    }

    @Test
    void assertSameMsisdnAllowsBlankOrEqual() {
        VirtualSessionStore.assertSameMsisdn("c", "", "2519");
        VirtualSessionStore.assertSameMsisdn("c", "2519", "");
        VirtualSessionStore.assertSameMsisdn("c", "2519", "2519");
        assertThatThrownBy(() -> VirtualSessionStore.assertSameMsisdn("c", "2519", "2520"))
                .isInstanceOf(IllegalStateException.class);
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
