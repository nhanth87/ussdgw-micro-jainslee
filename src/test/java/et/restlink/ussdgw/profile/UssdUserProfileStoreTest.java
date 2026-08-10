package et.restlink.ussdgw.profile;

import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.cdr.CdrUssdSnippet;
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

    @Test
    void recordMenuStateRoundTripAndTruncates() {
        String longMenu = "A".repeat(80);
        store.recordMenuState("251911230398", new UssdUserProfileStore.MenuStateSnapshot(
                "corr-menu-1", "*804#", 2, "2", longMenu, "CONTINUE",
                "dlg-1", 2500L, 1800L, 0, "lab"));

        UssdUserProfile p = store.get("251911230398").orElseThrow();
        assertThat(p.getLastCorrId()).isEqualTo("corr-menu-1");
        assertThat(p.getLastGeneration()).isEqualTo(2);
        assertThat(p.getLastDigit()).isEqualTo("2");
        assertThat(p.getLastAsAction()).isEqualTo("CONTINUE");
        assertThat(p.getLastDialogId()).isEqualTo("dlg-1");
        assertThat(p.getLastMenuAsUssd()).hasSizeLessThanOrEqualTo(CdrUssdSnippet.MAX_CHARS + 1);
        assertThat(p.getLastMenuAsUssd()).startsWith("AAAA");
        assertThat(p.getMap2mapTxCount()).isEqualTo(0);
    }

    @Test
    void menuStateDoesNotWipeHopGt() {
        store.recordMap2Map("251911230399", new UssdUserProfileStore.Map2MapTxSnapshot(
                "c-hop", "*804#", "*875#", "251971200201", 6, Map2MapCdr.OUTCOME_TEXT,
                1000L, 900L, 0, "lab"));
        store.recordMenuState("251911230399", new UssdUserProfileStore.MenuStateSnapshot(
                "c-hop", "*804#", 2, "1", "Packages…", "CONTINUE",
                "dlg-x", null, null, 0, "lab"));

        UssdUserProfile p = store.get("251911230399").orElseThrow();
        assertThat(p.getLastHopDestGt()).isEqualTo("251971200201");
        assertThat(p.getLastHopDestSsn()).isEqualTo(6);
        assertThat(p.getLastHopOutcome()).isEqualTo(Map2MapCdr.OUTCOME_TEXT);
        assertThat(p.getLastGeneration()).isEqualTo(2);
        assertThat(p.getLastDigit()).isEqualTo("1");
        assertThat(p.getMap2mapTxCount()).isEqualTo(1);
    }

    @Test
    void seedAdaptiveFromProfileOnce() {
        AdaptiveTimeout at = new AdaptiveTimeout();
        store.recordMap2Map("251911111111", new UssdUserProfileStore.Map2MapTxSnapshot(
                "c1", "*804#", null, null, null, Map2MapCdr.OUTCOME_TEXT,
                2000L, 1500L, 0, null));

        assertThat(store.seedAdaptiveFromProfile(at, "251911111111", 0)).isTrue();
        assertThat(at.isMsisdnSeeded("251911111111")).isTrue();
        assertThat(Math.round(at.observedLatencyMs(0, "251911111111"))).isEqualTo(1500L);
        assertThat(store.seedAdaptiveFromProfile(at, "251911111111", 0)).isFalse();
    }

    @Test
    void lastCorrIdIsSnapshotOnlyNeverReadForRouting() {
        // Plan lock: ussdUser.lastCorrId is ops snapshot — seedAdaptive uses lastEwmaMs only.
        store.recordMap2Map("251922222222", new UssdUserProfileStore.Map2MapTxSnapshot(
                "old-corr-must-not-resurrect", "*804#", "*875#", null, null,
                Map2MapCdr.OUTCOME_TEXT, 1000L, 1500L, 0, null));
        UssdUserProfile p = store.get("251922222222").orElseThrow();
        assertThat(p.getLastCorrId()).isEqualTo("old-corr-must-not-resurrect");
        AdaptiveTimeout at = new AdaptiveTimeout();
        assertThat(store.seedAdaptiveFromProfile(at, "+251922222222", 0)).isTrue();
        // Seed applied EWMA only — no API path returns lastCorrId into AS session mint.
        assertThat(Math.round(at.observedLatencyMs(0, "251922222222"))).isEqualTo(1500L);
    }

    @Test
    void plusAndDigitsMsisdnShareSameUserRow() {
        store.recordMap2Map("+251-911-000-001", new UssdUserProfileStore.Map2MapTxSnapshot(
                "c-plus", "*804#", null, null, null, Map2MapCdr.OUTCOME_TEXT,
                1000L, null, 0, null));
        store.recordMenuState("251911000001", new UssdUserProfileStore.MenuStateSnapshot(
                "c-plus", "*804#", 2, "2", "Packages", "CONTINUE",
                "dlg", null, null, 0, null));
        UssdUserProfile p = store.get("+251911000001").orElseThrow();
        assertThat(p.getMsisdn()).isEqualTo("251911000001");
        assertThat(p.getLastDigit()).isEqualTo("2");
        assertThat(p.getLastCorrId()).isEqualTo("c-plus");
        // Sibling MSISDN untouched.
        assertThat(store.get("251911000002")).isEmpty();
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
