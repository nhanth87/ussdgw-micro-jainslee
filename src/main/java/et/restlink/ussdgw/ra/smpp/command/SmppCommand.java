package et.restlink.ussdgw.ra.smpp.command;

import com.microjainslee.api.OutboundCommand;

/** Sealed SMPP outbound commands for the in-tree smpp-ra. */
public sealed interface SmppCommand extends OutboundCommand
        permits SmppCommand.SubmitSm {

    record SubmitSm(String destAddr, byte[] tpUd, boolean udhiSet, byte dataCoding, byte protocolId)
            implements SmppCommand {

        public static SubmitSm text(String destAddr, byte[] tpUd, boolean udhiSet, byte dataCoding) {
            return new SubmitSm(destAddr, tpUd, udhiSet, dataCoding, (byte) 0x00);
        }
    }
}
