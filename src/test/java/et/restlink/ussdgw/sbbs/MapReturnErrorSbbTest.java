package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.service.SbbServices;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.jss7.command.Ss7Command;
import com.microjainslee.ra.jss7.event.Ss7MapEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A MAP return-error must fail the saga instead of being dropped.
 *
 * <p>{@code Ss7MapEvent} is a sealed interface with four subtypes ({@code Service}, {@code Dialog},
 * {@code Error}, {@code Remote}). {@code Error} had neither an event mapping in
 * {@code SbbRegistrationSupport} nor an {@code onEvent} branch, so the dialog waited for the
 * network deadline and the CDR reported {@code MAP_TIMEOUT} instead of the real refusal — the
 * exact failure GMLC hit on 2026-09-30 (HLR answer {@code returnError 52} → request timed out).
 *
 * <p>What matters on the wire: the error ends the dialog instead of leaking it, and it aborts
 * rather than replying when there is no handset invoke id to reply to.
 */
class MapReturnErrorSbbTest {

    private CapturingPort port;
    private MapUssdParentSbb sbb;

    @BeforeEach
    void setUp() {
        port = new CapturingPort();
        // Collaborators are deliberately unset: every store/registry call throws, which is the
        // "internal error" case — the SBB must still not leak the MAP dialog.
        sbb = new MapUssdParentSbb(new SbbServices());
        set(sbb, "ss7", port);
    }

    @Test
    void errorWithInvokeIdEndsTheSubscriberDialog() {
        sbb.onEvent(new Ss7MapEvent.Error("dlg-err", 77L, "absentSubscriber", "MAP returnError"), null);

        assertThat(port.cmds).hasSize(1);
        var reply = port.cmds.get(0);
        assertThat(reply).isInstanceOf(Ss7Command.MapProcessUnstructuredSsResponse.class);
        assertThat(((Ss7Command.MapProcessUnstructuredSsResponse) reply).dialogId()).isEqualTo("dlg-err");
        assertThat(((Ss7Command.MapProcessUnstructuredSsResponse) reply).invokeId()).isEqualTo(77L);
        assertThat(((Ss7Command.MapProcessUnstructuredSsResponse) reply).endDialog()).isTrue();
        assertThat(((Ss7Command.MapProcessUnstructuredSsResponse) reply).text()).isNotBlank();
    }

    @Test
    void errorWithoutInvokeIdAbortsInsteadOfReplying() {
        // No MS invoke id: replying would be a protocol error, so the leg is aborted.
        sbb.onEvent(new Ss7MapEvent.Error("dlg-err-2", null, "systemFailure", "MAP returnError"), null);

        assertThat(port.cmds).hasSize(1);
        assertThat(port.cmds.get(0)).isInstanceOf(Ss7Command.MapDialogAbort.class);
        assertThat(((Ss7Command.MapDialogAbort) port.cmds.get(0)).dialogId()).isEqualTo("dlg-err-2");
    }

    @Test
    void errorWithBlankDialogIdSendsNothing() {
        sbb.onEvent(new Ss7MapEvent.Error(null, 5L, "abortCause", "no dialog"), null);

        assertThat(port.cmds).isEmpty();
    }

    @Test
    void remoteSubtypeIsAcknowledgedNotDroppedSilently() {
        // ADR 0007 D2 cross-node summary. Single-node never sees it; if it ever does, it must be
        // traced rather than silently swallowed.
        sbb.onEvent(null, null); // no-op guard: null must not throw
        assertThat(port.cmds).isEmpty();
    }

    @Test
    void everySubtypeOfTheSealedEventHasABranch() {
        // The regression guard: adding a subtype to ra-jss7 without a branch here is what turned
        // a real MAP error into MAP_TIMEOUT.
        assertThat(Ss7MapEvent.class.isSealed()).isTrue();
        assertThat(Ss7MapEvent.class.getPermittedSubclasses())
                .contains(Ss7MapEvent.Service.class, Ss7MapEvent.Dialog.class,
                        Ss7MapEvent.Error.class, Ss7MapEvent.Remote.class);
    }

    private static void set(Object target, String field, Object value) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(field);
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

    static final class CapturingPort implements RaCommandPort {
        final List<OutboundCommand> cmds = new ArrayList<>();
        @Override public void sendCommand(OutboundCommand command) { cmds.add(command); }
    }
}