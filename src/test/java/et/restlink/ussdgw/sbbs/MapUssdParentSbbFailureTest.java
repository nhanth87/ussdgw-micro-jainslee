package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.service.SbbServices;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.jss7.command.Ss7Command;
import com.microjainslee.ra.jss7.event.Ss7MapEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.restcomm.protocols.ss7.map.api.MAPMessageType;
import org.restcomm.protocols.ss7.map.api.service.supplementary.ProcessUnstructuredSSRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H7 — a handler that throws must not leave the MAP dialog open. The handset would otherwise sit on
 * a blank screen until the network timer fires and the dialog would leak on both ends.
 */
class MapUssdParentSbbFailureTest {

    private CapturingPort port;
    private MapUssdParentSbb sbb;

    @BeforeEach
    void setUp() {
        port = new CapturingPort();
        // Nothing is injected: every collaborator call inside the handler blows up, which is exactly
        // the "internal error" case under test.
        sbb = new MapUssdParentSbb(new SbbServices());
        set(sbb, "ss7", port);
    }

    @Test
    void throwingMoHandlerEndsTheSubscriberFacingDialog() {
        var event = new Ss7MapEvent.Service("dlg-mo", MAPMessageType.processUnstructuredSSRequest_Request,
                throwingProcessUnstructured(42L));

        sbb.onEvent(event, null);

        assertThat(port.cmds).hasSize(1);
        var reply = (Ss7Command.MapProcessUnstructuredSsResponse) port.cmds.get(0);
        assertThat(reply.dialogId()).isEqualTo("dlg-mo");
        assertThat(reply.invokeId()).isEqualTo(42L);
        assertThat(reply.endDialog()).isTrue();
        assertThat(reply.text()).isNotBlank();
    }

    @Test
    void throwingNonSubscriberHandlerAbortsTheDialog() {
        // SRI response handling touches the pending registries, which are null here.
        var event = new Ss7MapEvent.Service("dlg-sri", MAPMessageType.sendRoutingInfoForSM_Response, null);

        sbb.onEvent(event, null);

        assertThat(port.cmds).hasSize(1);
        assertThat(port.cmds.get(0)).isInstanceOf(Ss7Command.MapDialogAbort.class);
        assertThat(((Ss7Command.MapDialogAbort) port.cmds.get(0)).dialogId()).isEqualTo("dlg-sri");
    }

    @Test
    void throwingHandlerOnAnAlreadyTornDownDialogSendsNothing() {
        // The peer already aborted; answering or aborting back would break the MAP state machine.
        var event = new Ss7MapEvent.Dialog("dlg-gone", Ss7MapEvent.Kind.USER_ABORT, "peer abort");

        sbb.onEvent(event, null);

        assertThat(port.cmds).isEmpty();
    }

    /** Reports a usable invokeId but fails on any payload access. */
    private static ProcessUnstructuredSSRequest throwingProcessUnstructured(long invokeId) {
        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "getInvokeId" -> invokeId;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "toString" -> "throwing-ProcessUnstructuredSSRequest";
            default -> throw new IllegalStateException("decode failure");
        };
        return (ProcessUnstructuredSSRequest) Proxy.newProxyInstance(
                MapUssdParentSbbFailureTest.class.getClassLoader(),
                new Class<?>[]{ProcessUnstructuredSSRequest.class}, h);
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

    static final class CapturingPort implements RaCommandPort {
        final List<OutboundCommand> cmds = new ArrayList<>();
        @Override public void sendCommand(OutboundCommand command) { cmds.add(command); }
    }
}
