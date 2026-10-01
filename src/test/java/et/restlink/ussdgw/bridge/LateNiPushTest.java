package et.restlink.ussdgw.bridge;

import et.restlink.ussdgw.access.AccessNiDispatcher;
import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.sbbs.MapUssdParentSbb;
import et.restlink.ussdgw.service.Map2MapCompletionService;
import et.restlink.ussdgw.service.PendingMap2MapRegistry;
import et.restlink.ussdgw.service.SbbServices;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.jss7.event.Ss7MapEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 5 (P1-3 / P1-4 / P1-5 / P1-7): late reconcile honors the AS action
 * (D2b: END/CONTINUE → Notify, ABORT → no push), defers behind a busy handset,
 * retries transient S2 errors with backoff, falls back terminally otherwise,
 * and never lets teardown noise kill a bridged push.
 */
class LateNiPushTest {
    private MicroSleeContainer container;
    private VirtualSessionStore store;
    private RecordingCdr cdr;
    private CountingNiDispatcher ni;
    private NiPushRetryRegistry retries;
    private VirtualSessionBridge bridge;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        store = new VirtualSessionStore();
        set(store, "container", container);
        set(store, "config", new UssdConfigService());
        set(store, "profileTtlMs", 120_000L);
        store.ensureTable();

        cdr = new RecordingCdr();
        ni = new CountingNiDispatcher();
        retries = new NiPushRetryRegistry();
        bridge = newBridge();
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    private VirtualSessionBridge newBridge() {
        VirtualSessionBridge b = new VirtualSessionBridge();
        set(b, "store", store);
        set(b, "adaptive", new AdaptiveTimeout());
        set(b, "config", new UssdConfigService());
        set(b, "cdr", cdr);
        set(b, "accessNi", ni);
        set(b, "niPushRetries", retries);
        return b;
    }

    // ------------------------------------------------------------------ P1-3

    @Test
    void bridgedEndPushesNotifyNotRequest() {
        String corr = "late-end-1";
        seedBridged(corr, "251911000001");

        bridge.onAsResponse(new AsResponse(corr, corr, 1, "Final text", AsAction.END, false), 20);

        assertThat(ni.pushesFor(corr)).isEqualTo(1);
        assertThat(ni.notifyOnlyFor(corr)).as("late END must be one-shot Notify").isTrue();
        assertThat(store.get(corr).orElseThrow().state()).isEqualTo(VirtualSessionState.PUSH_PENDING);
        assertThat(cdr.statuses(corr)).contains("QUEUED");
        assertThat(cdr.statuses(corr)).doesNotContain("AS_DROP");
    }

    @Test
    void bridgedContinuePushesNotify() {
        String corr = "late-cont-1";
        seedBridged(corr, "251911000002");

        bridge.onAsResponse(
                new AsResponse(corr, corr, 1, "Menu over S2", AsAction.CONTINUE, false), 20);

        assertThat(ni.pushesFor(corr)).isEqualTo(1);
        assertThat(ni.notifyOnlyFor(corr)).as("D2b: bridged menu ends as Notify").isTrue();
        assertThat(store.get(corr).orElseThrow().state()).isEqualTo(VirtualSessionState.PUSH_PENDING);
    }

    @Test
    void bridgedAbortPushesNothing() {
        String corr = "late-abort-1";
        seedBridged(corr, "251911000003");

        bridge.onAsResponse(new AsResponse(corr, corr, 1, "bye", AsAction.ABORT, false), 20);

        assertThat(ni.pushesFor(corr)).isZero();
        assertThat(store.get(corr)).as("ABORTED row retired").isEmpty();
        assertThat(cdr.statuses(corr)).contains("ABORTED");
    }

    // ------------------------------------------------------------------ P1-4

    @Test
    void busyHandsetDefersPushToRetry() {
        String slow = "late-busy-1";
        seedBridged(slow, "251911000010");
        // Same subscriber redialed: open MO dialog elsewhere.
        VirtualSession other = new VirtualSession("vs", "mo-busy-1", "r", "251911000010", 0,
                "dlg-mo-busy-1", "*804#");
        other.setState(VirtualSessionState.ACTIVE);
        other.setDialogAlive(true);
        store.put(other);

        bridge.onAsResponse(new AsResponse(slow, slow, 1, "Late text", AsAction.END, false), 20);

        assertThat(ni.pushesFor(slow)).as("no S2 push into a busy handset").isZero();
        assertThat(store.get(slow).orElseThrow().state()).isEqualTo(VirtualSessionState.PUSH_PENDING);
        assertThat(cdr.statuses(slow)).contains("PUSH_DEFERRED");
        assertThat(retries.size()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ P1-5

    @Test
    void s2BusyErrorArmsRetryKeepsPending() {
        String corr = "s2-busy-1";
        seedPushPending(corr, "251911000020", "Hello");

        String detail = bridge.onNiPushError(corr, "ussd-Busy");

        assertThat(detail).contains("retry-armed");
        assertThat(store.get(corr).orElseThrow().state()).isEqualTo(VirtualSessionState.PUSH_PENDING);
        assertThat(cdr.statuses(corr)).contains("MAP_RETURN_ERROR", "NI_RETRY_ARMED");
        assertThat(retries.size()).isEqualTo(1);
    }

    @Test
    void s2AbsentSubscriberFallsBackTerminally() {
        String corr = "s2-absent-1";
        seedPushPending(corr, "251911000021", "Hello");

        String detail = bridge.onNiPushError(corr, "absentSubscriber");

        assertThat(detail).contains("ni-push-failed");
        assertThat(store.get(corr)).as("FAILED row retired").isEmpty();
        assertThat(cdr.statuses(corr)).contains("MAP_RETURN_ERROR", "NI_FALLBACK");
        assertThat(retries.size()).isZero();
    }

    @Test
    void retryExhaustionFallsBack() {
        String corr = "s2-exhaust-1";
        seedPushPending(corr, "251911000022", "Hello");

        assertThat(bridge.onNiPushError(corr, "systemFailure")).contains("retry-armed");
        assertThat(bridge.onNiPushError(corr, "systemFailure")).contains("retry-armed");
        assertThat(bridge.onNiPushError(corr, "systemFailure")).contains("retry-armed");
        assertThat(store.get(corr).orElseThrow().state()).isEqualTo(VirtualSessionState.PUSH_PENDING);

        assertThat(bridge.onNiPushError(corr, "systemFailure")).contains("ni-push-failed");
        assertThat(store.get(corr)).isEmpty();
        assertThat(cdr.statuses(corr)).contains("NI_FALLBACK");
    }

    @Test
    void s2CloseDeliversAndRetires() {
        String corr = "s2-close-1";
        seedPushPending(corr, "251911000023", "Hello");

        assertThat(bridge.onNiPushDelivered(corr)).isEqualTo("ni-push-ok");
        assertThat(store.get(corr)).isEmpty();
        assertThat(cdr.statuses(corr)).contains("NI_PUSH_OK");

        // Lost race: row already ACTIVE (reused) — never complete another owner's state.
        String other = "s2-close-2";
        VirtualSession s = new VirtualSession("vs", other, "r", "2519", 0, other, "");
        s.setState(VirtualSessionState.ACTIVE);
        store.put(s);
        assertThat(bridge.onNiPushDelivered(other)).isEqualTo("ni-push-close-lost");
        assertThat(store.get(other).orElseThrow().state()).isEqualTo(VirtualSessionState.ACTIVE);
    }

    @Test
    void retryTickRepushesPendingText() {
        String corr = "s2-retry-1";
        seedPushPending(corr, "251911000024", "Retry me");

        bridge.retryNiPush(new NiPushRetryRegistry.RetryEntry(corr, 1, 0L, true));

        assertThat(ni.pushesFor(corr)).isEqualTo(1);
        assertThat(ni.notifyOnlyFor(corr)).isTrue();
        assertThat(cdr.statuses(corr)).contains("NI_RETRY");
        assertThat(store.get(corr).orElseThrow().state()).isEqualTo(VirtualSessionState.PUSH_PENDING);
    }

    @Test
    void retryTickDefersWhileBusy() {
        String corr = "s2-retry-busy-1";
        seedPushPending(corr, "251911000025", "Retry me");
        VirtualSession other = new VirtualSession("vs", "mo-busy-2", "r", "251911000025", 0,
                "dlg-mo-busy-2", "*804#");
        other.setState(VirtualSessionState.ACTIVE);
        store.put(other);

        bridge.retryNiPush(new NiPushRetryRegistry.RetryEntry(corr, 1, 0L, true));

        assertThat(ni.pushesFor(corr)).isZero();
        assertThat(retries.size()).isEqualTo(1);
        assertThat(cdr.statuses(corr)).contains("NI_RETRY_DEFERRED");
    }

    @Test
    void retryPolicy() {
        assertThat(NiPushRetryPolicy.isRetryable("ussd-Busy")).isTrue();
        assertThat(NiPushRetryPolicy.isRetryable("systemFailure")).isTrue();
        assertThat(NiPushRetryPolicy.isRetryable("timeout")).isTrue();
        assertThat(NiPushRetryPolicy.isRetryable("absentSubscriber")).isFalse();
        assertThat(NiPushRetryPolicy.isRetryable("unknownSubscriber")).isFalse();
        assertThat(NiPushRetryPolicy.isRetryable(null)).isFalse();
        assertThat(NiPushRetryPolicy.isRetryable("something-new")).isFalse();
        assertThat(NiPushRetryPolicy.delayMs(1)).isEqualTo(3_000L);
        assertThat(NiPushRetryPolicy.delayMs(2)).isEqualTo(8_000L);
        assertThat(NiPushRetryPolicy.delayMs(3)).isEqualTo(15_000L);
        assertThat(NiPushRetryPolicy.MAX_ATTEMPTS).isEqualTo(3);
    }

    @Test
    void retryRegistryDueAndCancel() {
        NiPushRetryRegistry reg = new NiPushRetryRegistry();
        assertThat(reg.advance("a", true)).isEqualTo(1);
        assertThat(reg.advance("a", true)).isEqualTo(2);
        assertThat(reg.takeDue(System.currentTimeMillis()).size()).isZero();
        assertThat(reg.takeDue(System.currentTimeMillis() + 60_000).size()).isEqualTo(1);
        assertThat(reg.size()).isZero();
        reg.advance("b", false);
        reg.cancel("b");
        assertThat(reg.size()).isZero();
    }

    // ------------------------------------------------------------------ P1-7

    @Test
    void abortAfterBridgeKeepsPush() {
        String corr = "bridge-abort-1";
        VirtualSession s = new VirtualSession("vs", corr, corr, "251911000030", 0,
                "dlg-bridge-abort-1", "*804#");
        s.setState(VirtualSessionState.S1_RELEASED);
        s.setDialogAlive(false);
        store.put(s);

        bridge.onNetworkAbort("dlg-bridge-abort-1");

        assertThat(store.get(corr).orElseThrow().state())
                .as("teardown noise must not cancel the committed S2 push")
                .isEqualTo(VirtualSessionState.S1_RELEASED);
        assertThat(cdr.statuses(corr)).contains("ABORT_AFTER_BRIDGE");
    }

    // ------------------------------------------------- SBB wiring (P1-5/P1-7)

    @Test
    void s2ErrorRoutesToRetryWithoutMapEmit() {
        Fixture f = parentFixture();
        String corr = "s2w-busy-1";
        seedPushPending(corr, "251911000040", "Hello");

        f.sbb.onEvent(new com.microjainslee.ra.jss7.event.Ss7MapEvent.Error(
                corr, 11L, "ussd-Busy", "returnError"), null);

        assertThat(f.ss7.cmds).as("S2 leg is dead — no second close").isEmpty();
        assertThat(store.get(corr).orElseThrow().state()).isEqualTo(VirtualSessionState.PUSH_PENDING);
        assertThat(f.retries.size()).isEqualTo(1);
    }

    @Test
    void s2TerminalErrorFallsBackWithoutMapEmit() {
        Fixture f = parentFixture();
        String corr = "s2w-absent-1";
        seedPushPending(corr, "251911000041", "Hello");

        f.sbb.onEvent(new com.microjainslee.ra.jss7.event.Ss7MapEvent.Error(
                corr, 11L, "absentSubscriber", "returnError"), null);

        assertThat(f.ss7.cmds).isEmpty();
        assertThat(store.get(corr)).isEmpty();
        assertThat(cdr.statuses(corr)).contains("NI_FALLBACK");
    }

    @Test
    void s2CloseDeliversWithoutMapEmit() {
        Fixture f = parentFixture();
        String corr = "s2w-close-1";
        seedPushPending(corr, "251911000042", "Hello");

        f.sbb.onEvent(new com.microjainslee.ra.jss7.event.Ss7MapEvent.Dialog(
                corr, com.microjainslee.ra.jss7.event.Ss7MapEvent.Kind.CLOSE, null), null);

        assertThat(f.ss7.cmds).isEmpty();
        assertThat(store.get(corr)).isEmpty();
        assertThat(cdr.statuses(corr)).contains("NI_PUSH_OK");
    }

    @Test
    void s2TimeoutArmsRetry() {
        Fixture f = parentFixture();
        String corr = "s2w-timeout-1";
        seedPushPending(corr, "251911000043", "Hello");

        f.sbb.onEvent(new com.microjainslee.ra.jss7.event.Ss7MapEvent.Dialog(
                corr, com.microjainslee.ra.jss7.event.Ss7MapEvent.Kind.TIMEOUT, null), null);

        assertThat(f.ss7.cmds).isEmpty();
        assertThat(store.get(corr).orElseThrow().state()).isEqualTo(VirtualSessionState.PUSH_PENDING);
        assertThat(f.retries.size()).isEqualTo(1);
    }

    @Test
    void inboundAbortOnBridgedSessionKeepsPush() {
        Fixture f = parentFixture();
        String corr = "s2w-bab-1";
        VirtualSession s = new VirtualSession("vs", corr, corr, "251911000044", 0,
                "dlg-s2w-bab-1", "*804#");
        s.setState(VirtualSessionState.S1_RELEASED);
        s.setDialogAlive(false);
        store.put(s);

        f.sbb.onEvent(new com.microjainslee.ra.jss7.event.Ss7MapEvent.Dialog(
                "dlg-s2w-bab-1",
                com.microjainslee.ra.jss7.event.Ss7MapEvent.Kind.PROVIDER_ABORT, null), null);

        assertThat(f.ss7.cmds).as("bridged stay: no MAP emit on teardown noise").isEmpty();
        assertThat(store.get(corr).orElseThrow().state()).isEqualTo(VirtualSessionState.S1_RELEASED);
        assertThat(cdr.statuses(corr)).contains("ABORT_AFTER_BRIDGE");
    }

    // ---------------------------------------------------------------- helpers

    private void seedBridged(String corr, String msisdn) {
        VirtualSession s = new VirtualSession("vs-" + corr, corr, corr, msisdn, 0,
                "dlg-" + corr, "*804#");
        s.setState(VirtualSessionState.S1_RELEASED);
        s.setDialogAlive(false);
        store.put(s);
    }

    private void seedPushPending(String corr, String msisdn, String text) {
        VirtualSession s = new VirtualSession("vs-" + corr, corr, corr, msisdn, 0,
                corr, "");
        s.setState(VirtualSessionState.PUSH_PENDING);
        s.setDialogAlive(false);
        s.setPendingText(text);
        store.put(s);
    }

    private record Fixture(MapUssdParentSbb sbb, CapturingSs7 ss7, NiPushRetryRegistry retries) {}

    private Fixture parentFixture() {
        NiPushRetryRegistry reg = new NiPushRetryRegistry();
        set(bridge, "niPushRetries", reg);
        SbbServices services = new SbbServices();
        set(services, "store", store);
        set(services, "config", new UssdConfigService());
        set(services, "wireFacade", new et.restlink.ussdgw.api.AsWireFacade());
        set(services, "pendingMap2Map", new PendingMap2MapRegistry());
        set(services, "map2MapCompletion", new Map2MapCompletionService());
        set(services, "bridge", bridge);
        set(services, "cdr", cdr);
        MapUssdParentSbb sbb = new MapUssdParentSbb(services);
        CapturingSs7 ss7 = new CapturingSs7();
        set(sbb, "ss7", ss7);
        return new Fixture(sbb, ss7, reg);
    }

    private static void set(Object target, String field, Object value) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                var f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("No field " + field + " on " + target.getClass());
    }

    private static final class RecordingCdr extends CdrService {
        final List<String[]> rows = new CopyOnWriteArrayList<>();

        @Override
        public void write(String correlationId, CdrPhase phase, String msisdn,
                          String shortCode, String status, String detail,
                          int networkId, String tenantId, String originationType,
                          Long gateMs, Long observedEwmaMs) {
            rows.add(new String[]{correlationId, status});
        }

        List<String> statuses(String correlationId) {
            return rows.stream()
                    .filter(r -> correlationId.equals(r[0]))
                    .map(r -> r[1])
                    .toList();
        }
    }

    private static final class CountingNiDispatcher extends AccessNiDispatcher {
        final Map<String, Boolean> pushes = new ConcurrentHashMap<>();

        @Override
        public void requestNiPush(VirtualSession session, String text) {
            requestNiPush(session, text, false);
        }

        @Override
        public void requestNiPush(VirtualSession session, String text, boolean notifyOnly) {
            pushes.put(session.correlationId(), notifyOnly);
        }

        int pushesFor(String correlationId) {
            return pushes.containsKey(correlationId) ? 1 : 0;
        }

        Boolean notifyOnlyFor(String correlationId) {
            return pushes.get(correlationId);
        }
    }

    private static final class CapturingSs7 implements RaCommandPort {
        final List<OutboundCommand> cmds = new CopyOnWriteArrayList<>();
        @Override public void sendCommand(OutboundCommand command) { cmds.add(command); }
    }
}
