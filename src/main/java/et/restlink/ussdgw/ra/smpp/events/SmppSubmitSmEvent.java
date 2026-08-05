package et.restlink.ussdgw.ra.smpp.events;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;

/**
 * Inbound SMPP {@code submit_sm} from an AS ESME (USSD GW ingress).
 */
@EventType(name = "SmppSubmitSm", vendor = "et.restlink", version = "1.0")
public final class SmppSubmitSmEvent implements SleeEvent {

    private final String sessionId;
    private final String systemId;
    private final String destAddr;
    private final byte[] shortMessage;
    private final byte dataCoding;
    private final byte esmClass;
    private final byte protocolId;
    private final int sequenceNumber;
    private final int networkId;

    public SmppSubmitSmEvent(String sessionId, String systemId, String destAddr,
                             byte[] shortMessage, byte dataCoding, byte esmClass,
                             int sequenceNumber) {
        this(sessionId, systemId, destAddr, shortMessage, dataCoding, esmClass,
                (byte) 0, sequenceNumber, 0);
    }

    public SmppSubmitSmEvent(String sessionId, String systemId, String destAddr,
                             byte[] shortMessage, byte dataCoding, byte esmClass,
                             int sequenceNumber, int networkId) {
        this(sessionId, systemId, destAddr, shortMessage, dataCoding, esmClass,
                (byte) 0, sequenceNumber, networkId);
    }

    public SmppSubmitSmEvent(String sessionId, String systemId, String destAddr,
                             byte[] shortMessage, byte dataCoding, byte esmClass,
                             byte protocolId, int sequenceNumber, int networkId) {
        this.sessionId = sessionId;
        this.systemId = systemId;
        this.destAddr = destAddr;
        this.shortMessage = shortMessage == null ? new byte[0] : shortMessage;
        this.dataCoding = dataCoding;
        this.esmClass = esmClass;
        this.protocolId = protocolId;
        this.sequenceNumber = sequenceNumber;
        this.networkId = networkId;
    }

    public String getSessionId() { return sessionId; }
    public String getSystemId() { return systemId; }
    public String getDestAddr() { return destAddr; }
    public byte[] getShortMessage() { return shortMessage; }
    public byte getDataCoding() { return dataCoding; }
    public byte getEsmClass() { return esmClass; }
    public byte getProtocolId() { return protocolId; }
    public int getSequenceNumber() { return sequenceNumber; }
    public int getNetworkId() { return networkId; }

    /** True when this looks like SIM Data Download / OTA binary (not plain SMS). */
    public boolean looksLikeOta() {
        if ((protocolId & 0xFF) == 0x7F) {
            return true;
        }
        // 8-bit + UDHI (Class 2 0xF6 or legacy 0x04)
        boolean udhi = (esmClass & 0x40) != 0;
        int dcs = dataCoding & 0xFF;
        return udhi && (dcs == 0x04 || dcs == 0xF6);
    }
}
