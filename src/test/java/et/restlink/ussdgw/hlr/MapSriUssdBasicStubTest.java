package et.restlink.ussdgw.hlr;

import et.restlink.ussdgw.service.MapDialogHelper;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.jss7.command.Ss7Command;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic matrix stubs: push sends SRI then USSD NI; pull receives MO reply commands.
 * Runs under plain {@code mvn test} (no lab sim required).
 */
class MapSriUssdBasicStubTest {

    @Test
    void pushPathSendsSriThenUnstructuredSs() {
        CapturingPort port = new CapturingPort();
        // SRI toward HLR (NI push client)
        port.sendCommand(new Ss7Command.MapSendRoutingInfoForSm(
                "ni-corr-1",
                com.microjainslee.ra.jss7.Ss7Address.of("251911000001", 6),
                com.microjainslee.ra.jss7.Ss7Address.of("251900000100", 8),
                "251911000001",
                "251900000100",
                0));
        // After SRI resp: USSD NI
        MapDialogHelper.niPush(port, "ni-corr-1", "251911000099", "251900000100", "Hello NI", 0);

        assertThat(port.cmds).hasSize(2);
        assertThat(port.cmds.get(0)).isInstanceOf(Ss7Command.MapSendRoutingInfoForSm.class);
        assertThat(port.cmds.get(1)).isInstanceOf(Ss7Command.MapUnstructuredSsRequest.class);
    }

    @Test
    void pullPathReceivesMoReplyCommands() {
        CapturingPort port = new CapturingPort();
        MapDialogHelper.replyAndEnd(port, "dlg-mo-1", 5L, "OK");
        assertThat(port.cmds.get(0)).isInstanceOf(Ss7Command.MapProcessUnstructuredSsResponse.class);
        var r = (Ss7Command.MapProcessUnstructuredSsResponse) port.cmds.get(0);
        assertThat(r.endDialog()).isTrue();
        assertThat(r.text()).isEqualTo("OK");
    }

    @Test
    void hlrFaceReceivesInboundSriAndAnswersFake() {
        CapturingPort port = new CapturingPort();
        // Simulate HLR face answer (what HlrFaceService emits for FAKE)
        port.sendCommand(new Ss7Command.MapSendRoutingInfoForSmResponse(
                "dlg-inbound-sri", 9L, "636010000000001", "251911000099"));
        assertThat(port.cmds.get(0)).isInstanceOf(Ss7Command.MapSendRoutingInfoForSmResponse.class);
    }

    static final class CapturingPort implements RaCommandPort {
        final List<OutboundCommand> cmds = new ArrayList<>();
        @Override public void sendCommand(OutboundCommand command) { cmds.add(command); }
    }
}
