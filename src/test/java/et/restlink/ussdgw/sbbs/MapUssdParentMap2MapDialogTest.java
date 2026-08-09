package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionState;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.events.Map2MapRequestEvent;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.cdr.Map2MapCdr;
import et.restlink.ussdgw.service.Map2MapCompletionService;
import et.restlink.ussdgw.service.PendingMap2MapRegistry;
import et.restlink.ussdgw.service.SbbServices;

import com.microjainslee.ra.jss7.event.Ss7MapEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Digicom prove: hop REJECT must AS-pull; inbound CLOSE after gate must not cancel hop / zombie.
 */
class MapUssdParentMap2MapDialogTest {

    private final ConcurrentHashMap<String, VirtualSession> sessions = new ConcurrentHashMap<>();
    private MapUssdParentSbb sbb;
    private PendingMap2MapRegistry pending;
    private Map2MapCompletionService completion;
    private AtomicInteger asPulls;
    private AtomicReference<String> lastHop;
    private AtomicReference<String> lastOutcome;
    private AtomicInteger networkAborts;

    @BeforeEach
    void setUp() throws Exception {
        sessions.clear();
        pending = new PendingMap2MapRegistry();
        asPulls = new AtomicInteger();
        lastHop = new AtomicReference<>();
        lastOutcome = new AtomicReference<>();
        networkAborts = new AtomicInteger();

        VirtualSessionStore store = new VirtualSessionStore() {
            @Override public Optional<VirtualSession> get(String corr) {
                return Optional.ofNullable(sessions.get(corr));
            }
            @Override public VirtualSession put(VirtualSession s) {
                if (s != null && s.correlationId() != null) {
                    sessions.put(s.correlationId(), s);
                }
                return s;
            }
            @Override public Optional<VirtualSession> byDialogId(String dialogId) {
                return sessions.values().stream()
                        .filter(s -> dialogId != null && dialogId.equals(s.dialogId()))
                        .findFirst();
            }
        };

        completion = new Map2MapCompletionService() {
            @Override
            public String onMap2MapResponse(Map2MapRequestEvent req, String hopText) {
                return onMap2MapResponse(req, hopText,
                        hopText == null || hopText.isBlank()
                                ? Map2MapCdr.OUTCOME_EMPTY : Map2MapCdr.OUTCOME_TEXT);
            }

            @Override
            public String onMap2MapResponse(Map2MapRequestEvent req, String hopText, String hopOutcome) {
                if (req != null) {
                    cancelDeferredHopClose(req.outboundCorr());
                }
                asPulls.incrementAndGet();
                lastHop.set(hopText == null ? "" : hopText);
                lastOutcome.set(hopOutcome);
                return "map2map-ok routed-test";
            }
        };

        VirtualSessionBridge bridge = new VirtualSessionBridge() {
            @Override
            public void onNetworkAbort(String dialogId) {
                networkAborts.incrementAndGet();
                store.byDialogId(dialogId).ifPresent(s -> {
                    s.setDialogAlive(false);
                    s.setState(VirtualSessionState.ZOMBIE);
                    store.put(s);
                });
            }
        };

        SbbServices services = new SbbServices();
        set(services, "store", store);
        set(services, "pendingMap2Map", pending);
        set(services, "map2MapCompletion", completion);
        set(services, "bridge", bridge);
        sbb = new MapUssdParentSbb(services);
    }

    @Test
    void hopRejectRoutesEmptyHopAsPull() {
        String corr = "corr-reject";
        String out = PendingMap2MapRegistry.outboundCorr(corr);
        sessions.put(corr, session(corr, "dlg-in"));
        Map2MapRequestEvent req = sample(corr, out);
        pending.putUssd(out, req, "251971200201", null);

        sbb.onEvent(new Ss7MapEvent.Dialog(out, Ss7MapEvent.Kind.REJECT, null), null);

        assertThat(pending.peek(out)).isEmpty();
        assertThat(asPulls.get()).isEqualTo(1);
        assertThat(lastHop.get()).isEmpty();
        assertThat(lastOutcome.get()).isEqualTo(Map2MapCdr.OUTCOME_REJECT);
        assertThat(networkAborts.get()).isZero();
        assertThat(sessions.get(corr).state()).isEqualTo(VirtualSessionState.AWAITING_AS);
    }

    /** Digicom 13:28:36Z — peer ACCEPT+NOTICE+CLOSE without RESULT must not stamp TIMEOUT. */
    @Test
    void hopCloseWithoutResultRoutesAsPullNotTimeout() throws Exception {
        String corr = "corr-close";
        String out = PendingMap2MapRegistry.outboundCorr(corr);
        sessions.put(corr, session(corr, "dlg-in"));
        pending.putUssd(out, sample(corr, out), "251971200201", null);

        sbb.onEvent(new Ss7MapEvent.Dialog(out, Ss7MapEvent.Kind.CLOSE, null), null);

        assertThat(pending.peek(out)).isPresent();
        assertThat(asPulls.get()).isZero();
        Thread.sleep(Map2MapCompletionService.HOP_CLOSE_DEFER_MS + 80L);
        assertThat(pending.peek(out)).isEmpty();
        assertThat(asPulls.get()).isEqualTo(1);
        assertThat(lastOutcome.get()).isEqualTo(Map2MapCdr.OUTCOME_CLOSE);
        assertThat(Map2MapCdr.statusForDialogLost("CLOSE", false)).isEqualTo(Map2MapCdr.HOP_FAIL);
        assertThat(Map2MapCdr.statusForDialogLost("CLOSE", false))
                .isNotEqualTo(Map2MapCdr.TIMEOUT)
                .isNotEqualTo(Map2MapCdr.HOP_CLOSE);
        assertThat(networkAborts.get()).isZero();
    }

    /** RESULT after soft-CLOSE must win AS pull with hop text (same-TC-END ordering). */
    @Test
    void hopResultAfterCloseDeferWinsAsPullWithText() {
        String corr = "corr-result-wins";
        String out = PendingMap2MapRegistry.outboundCorr(corr);
        sessions.put(corr, session(corr, "dlg-in"));
        pending.putUssd(out, sample(corr, out), "251971200201", null);

        sbb.onEvent(new Ss7MapEvent.Dialog(out, Ss7MapEvent.Kind.CLOSE, null), null);
        assertThat(asPulls.get()).isZero();
        assertThat(pending.peek(out)).isPresent();

        // Simulate RESULT taking pending before defer fires (Parent hop path).
        var taken = pending.takeIfPhase(out, PendingMap2MapRegistry.Phase.AWAITING_USSD);
        assertThat(taken).isPresent();
        String amharic = "ውድ ደንበኛ ፣ ውጤቱ በአጭር መልእክት ተልኳል። ኢትዮ ቴሌኮም";
        completion.onMap2MapResponse(taken.get().req(), amharic, Map2MapCdr.OUTCOME_TEXT);

        assertThat(asPulls.get()).isEqualTo(1);
        assertThat(lastHop.get()).isEqualTo(amharic);
        assertThat(lastOutcome.get()).isEqualTo(Map2MapCdr.OUTCOME_TEXT);

        // Deferred CLOSE must not second-route empty.
        try {
            Thread.sleep(Map2MapCompletionService.HOP_CLOSE_DEFER_MS + 80L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(asPulls.get()).isEqualTo(1);
    }

    @Test
    void inboundCloseAfterGateKeepsPendingHop() {
        String corr = "corr-bridged";
        String out = PendingMap2MapRegistry.outboundCorr(corr);
        VirtualSession session = session(corr, "dlg-in");
        session.setState(VirtualSessionState.S1_RELEASED);
        session.setDialogAlive(false);
        sessions.put(corr, session);
        pending.putUssd(out, sample(corr, out), "251971200201", null);

        sbb.onEvent(new Ss7MapEvent.Dialog("dlg-in", Ss7MapEvent.Kind.CLOSE, null), null);

        assertThat(pending.peek(out)).isPresent();
        assertThat(asPulls.get()).isZero();
        assertThat(networkAborts.get()).isZero();
        assertThat(sessions.get(corr).state()).isEqualTo(VirtualSessionState.S1_RELEASED);
    }

    @Test
    void inboundUserAbortCancelsPendingAndZombies() {
        String corr = "corr-abort";
        String out = PendingMap2MapRegistry.outboundCorr(corr);
        sessions.put(corr, session(corr, "dlg-in"));
        pending.putUssd(out, sample(corr, out), "251971200201", null);

        sbb.onEvent(new Ss7MapEvent.Dialog("dlg-in", Ss7MapEvent.Kind.USER_ABORT, "peer"), null);

        assertThat(pending.peek(out)).isEmpty();
        assertThat(networkAborts.get()).isEqualTo(1);
        assertThat(sessions.get(corr).state()).isEqualTo(VirtualSessionState.ZOMBIE);
    }

    private static VirtualSession session(String corr, String dialogId) {
        VirtualSession s = new VirtualSession(
                "vs-" + corr, corr, "req-" + corr, "251911000001", 0, dialogId, "*804#");
        s.setInvokeId(7L);
        s.setDialogAlive(true);
        s.setOriginationType(OriginationType.MAP);
        s.setAdaptiveBridgeArm(true);
        s.setState(VirtualSessionState.AWAITING_AS);
        s.setGateDeadlineMs(System.currentTimeMillis() + 60_000L);
        return s;
    }

    private static Map2MapRequestEvent sample(String corr, String outbound) {
        return new Map2MapRequestEvent(
                corr, outbound, "dlg-" + corr, 7L, "251911000001", "*804#", "*804#",
                "*875#", "https://happy-phoenix-66.webhook.cool", RuleType.RE_ROUTE, 0, null,
                "vs-" + corr, "req-" + corr, false, null, "251971200201", 6);
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new IllegalStateException("No field " + field + " on " + target.getClass());
    }
}
