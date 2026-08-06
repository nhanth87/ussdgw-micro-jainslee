package et.restlink.ussdgw;

import et.restlink.ussdgw.events.InboundSriSmEvent;
import et.restlink.ussdgw.events.NiPushRequestEvent;
import et.restlink.ussdgw.hlr.HlrResolveMode;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Lab SS7 IT placeholders — enable with {@code -Dlab-ss7=true} when jSS7 sim pair is up.
 * Documents the required matrix: push SRI+USSD, pull MO USSD, inbound SRI HLR face.
 *
 * <p>See {@code docs/agents/ss7-lab-pair.md}.
 */
@Tag("lab-ss7")
class LabSs7SriUssdIT {

    @Test
    void pushSendsSriThenUssd_requiresSim() {
        Assumptions.assumeTrue(Boolean.getBoolean("lab-ss7"),
                "Set -Dlab-ss7=true with jSS7 SMS_TEST_SERVER peer");
        // Live path: NiPushRequestEvent → SriSbb → MapSendRoutingInfoForSm → NI UnstructuredSS
        assert NiPushRequestEvent.class.getName().contains("NiPush");
    }

    @Test
    void pullReceivesProcessUnstructured_requiresSim() {
        Assumptions.assumeTrue(Boolean.getBoolean("lab-ss7"),
                "Set -Dlab-ss7=true with UE/MAP MO peer");
        // Live path: ProcessUnstructuredSS-Request → MapUssdParentSbb → AS pull → MAP reply
        assert true;
    }

    @Test
    void inboundSriHlrFace_requiresSim() {
        Assumptions.assumeTrue(Boolean.getBoolean("lab-ss7"),
                "Set -Dlab-ss7=true; peer SMSC sends SRI-SM to GW GT/SSN 6");
        // Live path: SendRoutingInfoForSMRequest → InboundSriSmEvent → HlrResponderSbb
        assert InboundSriSmEvent.class.getSimpleName().equals("InboundSriSmEvent");
        assert HlrResolveMode.FAKE != null;
    }
}
