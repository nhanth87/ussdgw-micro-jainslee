package et.restlink.ussdgw.profile;

import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.cdr.Map2MapCdr;
import et.restlink.ussdgw.events.Map2MapRequestEvent;
import et.restlink.ussdgw.routing.RuleType;

import com.microjainslee.core.MicroSleeContainer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UssdUserProfileStoreTest {
    private MicroSleeContainer container;
    private UssdUserProfileStore store;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        store = new UssdUserProfileStore();
        set(store, "container", container);
        store.ensureTable();
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void map2mapTxPersistsAcrossGetByMsisdn() {
        Map2MapRequestEvent req = new Map2MapRequestEvent(
                "corr-m2m-1", "m2m-corr-m2m-1", "dlg-in", 1L,
                "+251-911-230-398", "*804#", "*804#", "*875#",
                "http://127.0.0.1:8090/ussd/pull", RuleType.HTTP, 0, "lab",
                "vs-1", "req-1", false, null, "251971200201", 6);
        store.recordMap2Map(req, Map2MapCdr.OUTCOME_PENDING, 2500L, null);

        UssdUserProfile pending = store.get("251911230398").orElseThrow();
        assertThat(pending.getMsisdn()).isEqualTo("251911230398");
        assertThat(pending.getLastCorrId()).isEqualTo("corr-m2m-1");
        assertThat(pending.getLastShortCode()).isEqualTo("*804#");
        assertThat(pending.getLastRedirectUssd()).isEqualTo("*875#");
        assertThat(pending.getLastHopDestGt()).isEqualTo("251971200201");
        assertThat(pending.getLastHopDestSsn()).isEqualTo(6);
        assertThat(pending.getLastHopOutcome()).isEqualTo(Map2MapCdr.OUTCOME_PENDING);
        assertThat(pending.getLastGateMs()).isEqualTo(2500L);
        assertThat(pending.getMap2mapTxCount()).isEqualTo(0);

        store.recordMap2Map(req, Map2MapCdr.OUTCOME_REJECT, 2500L, 1800L);
        UssdUserProfile done = store.get("251911230398").orElseThrow();
        assertThat(done.getLastHopOutcome()).isEqualTo(Map2MapCdr.OUTCOME_REJECT);
        assertThat(done.getLastEwmaMs()).isEqualTo(1800L);
        assertThat(done.getMap2mapTxCount()).isEqualTo(1);
        assertThat(done.getLastUpdatedAtMs()).isPositive();
    }

    @Test
    void separateMsisdnsDoNotClobber() {
        store.recordMap2Map("251911000001", new UssdUserProfileStore.Map2MapTxSnapshot(
                "c1", "*804#", "*875#", null, null, Map2MapCdr.OUTCOME_TEXT,
                1000L, null, 0, null));
        store.recordMap2Map("251911000002", new UssdUserProfileStore.Map2MapTxSnapshot(
                "c2", "*101#", "*8744#", "2519", 6, Map2MapCdr.OUTCOME_ABORT,
                2000L, 1500L, 1, "t1"));

        assertThat(store.get("251911000001").orElseThrow().getLastCorrId()).isEqualTo("c1");
        assertThat(store.get("251911000002").orElseThrow().getLastHopOutcome())
                .isEqualTo(Map2MapCdr.OUTCOME_ABORT);
        assertThat(store.get("251911000002").orElseThrow().getMap2mapTxCount()).isEqualTo(1);
    }

    @Test
    void normalizeMatchesAdaptiveTimeout() {
        assertThat(AdaptiveTimeout.normalizeMsisdn("+251 911")).isEqualTo("251911");
        assertThat(store.get("")).isEmpty();
        assertThat(store.get(null)).isEmpty();
    }

    @Test
    void countableOutcomeIgnoresPending() {
        assertThat(UssdUserProfileStore.isCountableOutcome("pending")).isFalse();
        assertThat(UssdUserProfileStore.isCountableOutcome("reject")).isTrue();
        assertThat(UssdUserProfileStore.isCountableOutcome("text")).isTrue();
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
