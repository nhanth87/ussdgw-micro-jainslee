package et.restlink.ussdgw.ra.smpp.events;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;

/**
 * Inbound {@code deliver_sm} from RestLink A2P SMSC (client RA) — DLR or MO.
 */
@EventType(name = "SmppDeliverSm", vendor = "et.restlink", version = "1.0")
public final class SmppDeliverSmEvent implements SleeEvent {

    private final String sourceAddr;
    private final String destAddr;
    private final byte esmClass;
    private final byte dataCoding;
    private final byte[] userData;
    private final boolean deliveryReceipt;

    public SmppDeliverSmEvent(String sourceAddr, String destAddr, byte esmClass,
                              byte dataCoding, byte[] userData, boolean deliveryReceipt) {
        this.sourceAddr = sourceAddr == null ? "" : sourceAddr;
        this.destAddr = destAddr == null ? "" : destAddr;
        this.esmClass = esmClass;
        this.dataCoding = dataCoding;
        this.userData = userData == null ? new byte[0] : userData;
        this.deliveryReceipt = deliveryReceipt;
    }

    public String getSourceAddr() { return sourceAddr; }
    public String getDestAddr() { return destAddr; }
    public byte getEsmClass() { return esmClass; }
    public byte getDataCoding() { return dataCoding; }
    public byte[] getUserData() { return userData; }
    public boolean isDeliveryReceipt() { return deliveryReceipt; }
}
