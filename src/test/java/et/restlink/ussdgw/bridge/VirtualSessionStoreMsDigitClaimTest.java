package et.restlink.ussdgw.bridge;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class VirtualSessionStoreMsDigitClaimTest {

    @Test
    void sameInvokeSecondClaimLoses() {
        VirtualSessionStore store = new VirtualSessionStore();
        assertThat(store.tryClaimMsDigitContinue("c1", 7L)).isEmpty();
        assertThat(store.tryClaimMsDigitContinue("c1", 7L))
                .contains(VirtualSessionStore.MsDigitClaimLoss.INVOKE);
    }

    @Test
    void differentInvokeWhileInFlightLoses() {
        VirtualSessionStore store = new VirtualSessionStore();
        assertThat(store.tryClaimMsDigitContinue("c2", 1L)).isEmpty();
        assertThat(store.tryClaimMsDigitContinue("c2", 2L))
                .contains(VirtualSessionStore.MsDigitClaimLoss.IN_FLIGHT);
    }

    @Test
    void releaseAllowsNextInvoke() {
        VirtualSessionStore store = new VirtualSessionStore();
        assertThat(store.tryClaimMsDigitContinue("c3", 10L)).isEmpty();
        store.releaseMsDigitInFlight("c3");
        // Same invoke after release still loses (late duplicate of that component).
        assertThat(store.tryClaimMsDigitContinue("c3", 10L))
                .contains(VirtualSessionStore.MsDigitClaimLoss.INVOKE);
        // New invoke after release wins (real next digit).
        assertThat(store.tryClaimMsDigitContinue("c3", 11L)).isEmpty();
    }

    @Test
    void concurrentSameInvokeOnlyOneWins() throws Exception {
        VirtualSessionStore store = new VirtualSessionStore();
        AtomicInteger wins = new AtomicInteger();
        Thread t1 = new Thread(() -> {
            if (store.tryClaimMsDigitContinue("c4", 42L).isEmpty()) {
                wins.incrementAndGet();
            }
        });
        Thread t2 = new Thread(() -> {
            if (store.tryClaimMsDigitContinue("c4", 42L).isEmpty()) {
                wins.incrementAndGet();
            }
        });
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        assertThat(wins.get()).isEqualTo(1);
        Optional<VirtualSessionStore.MsDigitClaimLoss> third =
                store.tryClaimMsDigitContinue("c4", 42L);
        assertThat(third).contains(VirtualSessionStore.MsDigitClaimLoss.INVOKE);
    }

    @Test
    void claimKeyedByCorrCannotStealOtherSession() {
        VirtualSessionStore store = new VirtualSessionStore();
        assertThat(store.tryClaimMsDigitContinue("corr-a", 1L)).isEmpty();
        // corr-b is independent — must win even while corr-a is in-flight.
        assertThat(store.tryClaimMsDigitContinue("corr-b", 1L)).isEmpty();
        assertThat(store.tryClaimMsDigitContinue("corr-a", 2L))
                .contains(VirtualSessionStore.MsDigitClaimLoss.IN_FLIGHT);
        store.releaseMsDigitInFlight("corr-a");
        assertThat(store.tryClaimMsDigitContinue("corr-a", 2L)).isEmpty();
        // corr-b still in-flight; release on A does not clear B.
        assertThat(store.tryClaimMsDigitContinue("corr-b", 9L))
                .contains(VirtualSessionStore.MsDigitClaimLoss.IN_FLIGHT);
    }
}
