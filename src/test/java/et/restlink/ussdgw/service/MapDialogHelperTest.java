package et.restlink.ussdgw.service;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.jss7.command.Ss7Command;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MapDialogHelperTest {
    @Test
    void replyAndEndSendsProcessUnstructuredResponse() {
        CapturingPort port = new CapturingPort();
        MapDialogHelper.replyAndEnd(port, "dlg-1", 7L, "bye");
        assertThat(port.cmds).hasSize(1);
        assertThat(port.cmds.get(0)).isInstanceOf(Ss7Command.MapProcessUnstructuredSsResponse.class);
        var c = (Ss7Command.MapProcessUnstructuredSsResponse) port.cmds.get(0);
        assertThat(c.dialogId()).isEqualTo("dlg-1");
        assertThat(c.invokeId()).isEqualTo(7L);
        assertThat(c.endDialog()).isTrue();
        assertThat(c.text()).isEqualTo("bye");
        assertThat(c.dataCoding()).isEqualTo(0x0F);
    }

    @Test
    void replyHonorsAsAlphabetUcs8() {
        CapturingPort port = new CapturingPort();
        MapDialogHelper.replyAndEnd(port, "dlg", 1L, "OK",
                et.restlink.ussdgw.api.UssdAlphabet.UCS8);
        var c = (Ss7Command.MapProcessUnstructuredSsResponse) port.cmds.get(0);
        assertThat(c.dataCoding()).isEqualTo(et.restlink.ussdgw.codec.SmsTextCodec.CBS_GSM8);
    }

    @Test
    void replyUsesUcs2DcsForUnicode() {
        CapturingPort port = new CapturingPort();
        MapDialogHelper.replyAndEnd(port, "dlg", 1L, "ሰላም",
                et.restlink.ussdgw.api.UssdAlphabet.AUTO);
        var c = (Ss7Command.MapProcessUnstructuredSsResponse) port.cmds.get(0);
        assertThat(c.dataCoding()).isEqualTo(et.restlink.ussdgw.codec.SmsTextCodec.CBS_UCS2);
    }

    @Test
    void niPushSendsUnstructuredSsRequest() {
        CapturingPort port = new CapturingPort();
        MapDialogHelper.niPush(port, "corr", "251911", "100", "push text", 1);
        assertThat(port.cmds.get(0)).isInstanceOf(Ss7Command.MapUnstructuredSsRequest.class);
        var c = (Ss7Command.MapUnstructuredSsRequest) port.cmds.get(0);
        assertThat(c.dialogId()).isEqualTo("corr");
        assertThat(c.text()).isEqualTo("push text");
        assertThat(c.targetAddress().globalTitle()).isEqualTo("251911");
    }

    @Test
    void nullPortIsNoOp() {
        MapDialogHelper.replyAndEnd(null, "d", 1, "x");
        MapDialogHelper.niPush(null, "c", "1", "2", "t", 0);
    }

    static final class CapturingPort implements RaCommandPort {
        final List<OutboundCommand> cmds = new ArrayList<>();
        @Override public void sendCommand(OutboundCommand command) { cmds.add(command); }
    }
}
