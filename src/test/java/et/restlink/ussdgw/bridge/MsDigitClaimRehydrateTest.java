package et.restlink.ussdgw.bridge;

import com.microjainslee.api.ProfileID;
import com.microjainslee.api.ProfileLocalObject;
import com.microjainslee.core.MicroSleeContainer;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.profile.UssdTxProfile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2 proof: MS-digit-continue claim survives rehydrate / cross-node takeover.
 *
 * <p>Source of truth is the replicated {@code ussdTx} row
 * ({@code msDigitInvokeId}/{@code msDigitInFlight}); the heap map is a fast-path
 * mirror only. A fresh {@link VirtualSessionStore} on the same
 * {@code ProfileFacility} table (empty heap, as after failover) must still reject
 * duplicates.
 */
class MsDigitClaimRehydrateTest {

    private MicroSleeContainer container;
    private VirtualSessionStore store;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        store = newStore();
        store.ensureTable();
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    private VirtualSessionStore newStore() {
        VirtualSessionStore s = new VirtualSessionStore();
        set(s, "container", container);
        set(s, "config", new UssdConfigService());
        set(s, "profileTtlMs", 120_000L);
        return s;
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

    private void putSession(String corr) {
        VirtualSession s = new VirtualSession("vs-" + corr, corr, "req-" + corr,
                "251911000001", 1, "dlg-" + corr, "*804#");
        s.setState(VirtualSessionState.ACTIVE);
        store.put(s);
    }

    private UssdTxProfile readProfile(String corr) {
        ProfileLocalObject plo = container.getProfileFacility()
                .getProfile(new ProfileID(UssdTxProfile.TABLE_NAME, corr));
        assertThat(plo).isNotNull();
        return (UssdTxProfile) plo.getProfile();
    }

    @Test
    void claimSurvivesRehydrate() {
        String corr = "corr-rehydrate-g2";
        putSession(corr);

        long a = 101L;
        long b = 102L;

        // A wins on the first store.
        assertThat(store.tryClaimMsDigitContinue(corr, a)).isEmpty();
        // B while A in-flight loses.
        assertThat(store.tryClaimMsDigitContinue(corr, b))
                .contains(VirtualSessionStore.MsDigitClaimLoss.IN_FLIGHT);

        // Durable truth is on the row, not the heap.
        assertThat(readProfile(corr).getMsDigitInFlight()).isEqualTo(Boolean.TRUE);
        assertThat(readProfile(corr).getMsDigitInvokeId()).isEqualTo(Long.valueOf(a));

        // Simulate failover: a brand-new store (empty heap) on the same facility table.
        VirtualSessionStore rehydrated = newStore();
        rehydrated.ensureTable();

        // Duplicate B on the rehydrated node must still lose — G2 proof.
        Optional<VirtualSessionStore.MsDigitClaimLoss> dup =
                rehydrated.tryClaimMsDigitContinue(corr, b);
        assertThat(dup).contains(VirtualSessionStore.MsDigitClaimLoss.IN_FLIGHT);

        // Same-invoke duplicate of the winner loses as INVOKE, even after rehydrate.
        assertThat(rehydrated.tryClaimMsDigitContinue(corr, a))
                .contains(VirtualSessionStore.MsDigitClaimLoss.INVOKE);

        // Release on the rehydrated node clears the durable in-flight flag.
        rehydrated.releaseMsDigitInFlight(corr);
        assertThat(readProfile(corr).getMsDigitInFlight()).isEqualTo(Boolean.FALSE);
        assertThat(readProfile(corr).getMsDigitInvokeId()).isEqualTo(Long.valueOf(a));

        // Next real digit C wins on either node.
        long c = 103L;
        assertThat(rehydrated.tryClaimMsDigitContinue(corr, c)).isEmpty();
        assertThat(readProfile(corr).getMsDigitInvokeId()).isEqualTo(Long.valueOf(c));

        // Late dups of A/B while C is in-flight still lose (IN_FLIGHT); dup of C loses (INVOKE).
        assertThat(store.tryClaimMsDigitContinue(corr, a))
                .contains(VirtualSessionStore.MsDigitClaimLoss.IN_FLIGHT);
        assertThat(store.tryClaimMsDigitContinue(corr, b))
                .contains(VirtualSessionStore.MsDigitClaimLoss.IN_FLIGHT);
        assertThat(store.tryClaimMsDigitContinue(corr, c))
                .contains(VirtualSessionStore.MsDigitClaimLoss.INVOKE);

        // Clear resets heap + persisted fields.
        rehydrated.clearMsDigitClaim(corr);
        UssdTxProfile cleared = readProfile(corr);
        assertThat(cleared.getMsDigitInFlight()).isEqualTo(Boolean.FALSE);
        assertThat(cleared.getMsDigitInvokeId() == null
                || cleared.getMsDigitInvokeId() == Long.MIN_VALUE).isTrue();

        // After clear a fresh digit wins on a new heap.
        VirtualSessionStore afterClear = newStore();
        afterClear.ensureTable();
        assertThat(afterClear.tryClaimMsDigitContinue(corr, 201L)).isEmpty();
    }

    @Test
    void putDoesNotClobberWinningClaim() {
        String corr = "corr-put-preserve-g2";
        putSession(corr);
        assertThat(store.tryClaimMsDigitContinue(corr, 55L)).isEmpty();

        // A stale session snapshot put() (pre-claim defaults) must not wipe the claim:
        // mapper initializes claim fields only when the row has none.
        VirtualSession stale = new VirtualSession("vs-" + corr, corr, "req-" + corr,
                "251911000001", 1, "dlg-" + corr, "*804#");
        stale.setState(VirtualSessionState.AWAITING_AS);
        store.put(stale);

        assertThat(readProfile(corr).getMsDigitInFlight()).isEqualTo(Boolean.TRUE);
        assertThat(readProfile(corr).getMsDigitInvokeId()).isEqualTo(Long.valueOf(55L));
        assertThat(store.tryClaimMsDigitContinue(corr, 56L))
                .contains(VirtualSessionStore.MsDigitClaimLoss.IN_FLIGHT);
    }
}
