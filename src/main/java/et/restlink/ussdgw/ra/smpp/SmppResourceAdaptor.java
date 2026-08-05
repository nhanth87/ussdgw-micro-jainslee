/*
 * RestLink USSD GW — in-tree SMPP Resource Adaptor (DELEGATE half).
 *
 * <p>Owns the cloudhopper-smpp transport (RestComm fork
 * {@code org.restcomm.smpp:ch-smpp}): optional SMSC client + companion server RA.
 * Inbound {@code deliver_sm}/DLRs become {@link et.restlink.ussdgw.ra.smpp.events.SmppDeliverSmEvent}
 * on a best-effort basis via {@link com.microjainslee.api.RaBootstrapPort}.</p>
 */
package et.restlink.ussdgw.ra.smpp;

import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.ra.smpp.events.SmppDeliverSmEvent;
import et.restlink.ussdgw.ra.smpp.command.SmppCommand;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.RaBootstrapPort;

import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.SmppSessionHandler;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.Address;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * cloudhopper-backed SMPP transceiver delegate for the app-private
 * {@code smpp-ra}. See the class-level file comment for scope and threading.
 */
public final class SmppResourceAdaptor {

    private static final Logger LOG = LogManager.getLogger(SmppResourceAdaptor.class);

    // ── configuration (set before doStart, mutated only while inactive) ──
    private volatile String host = "127.0.0.1";
    private volatile int port = 2775;
    private volatile String systemId = "";
    private volatile String password = "";
    private volatile String systemType = "";
    private volatile String sourceAddr = "";
    private volatile byte sourceTon = SmppConstants.TON_INTERNATIONAL;
    private volatile byte sourceNpi = SmppConstants.NPI_E164;
    private volatile byte destTon = SmppConstants.TON_INTERNATIONAL;
    private volatile byte destNpi = SmppConstants.NPI_E164;
    private volatile boolean requestDeliveryReceipt = false;
    /** Logical plane id (multi-network / CDR); not sent on the wire. */
    private volatile int networkId = 0;

    private volatile long bindTimeoutMillis = 10_000L;
    private volatile long submitTimeoutMillis = 5_000L;
    private volatile long unbindTimeoutMillis = 3_000L;

    // ── SLEE wiring ──
    private volatile RaBootstrapPort bootstrap;

    // ── transport state ──
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicLong deliverSeq = new AtomicLong();
    private volatile DefaultSmppClient client;
    private volatile SmppSession session;

    // ── configuration setters (chainable) ─────────────────────────────
    public SmppResourceAdaptor setHost(String host) { this.host = host; return this; }
    public SmppResourceAdaptor setPort(int port) { this.port = port; return this; }
    public SmppResourceAdaptor setSystemId(String id) { this.systemId = id; return this; }
    public SmppResourceAdaptor setPassword(String pw) { this.password = pw; return this; }
    public SmppResourceAdaptor setSystemType(String t) { this.systemType = t; return this; }
    public SmppResourceAdaptor setSourceAddr(String a) { this.sourceAddr = a; return this; }
    public SmppResourceAdaptor setSourceTon(byte ton) { this.sourceTon = ton; return this; }
    public SmppResourceAdaptor setSourceNpi(byte npi) { this.sourceNpi = npi; return this; }
    public SmppResourceAdaptor setDestTon(byte ton) { this.destTon = ton; return this; }
    public SmppResourceAdaptor setDestNpi(byte npi) { this.destNpi = npi; return this; }
    public SmppResourceAdaptor setRequestDeliveryReceipt(boolean b) {
        this.requestDeliveryReceipt = b; return this;
    }
    public SmppResourceAdaptor setNetworkId(int networkId) { this.networkId = networkId; return this; }
    public int getNetworkId() { return networkId; }
    public SmppResourceAdaptor setBindTimeoutMillis(long ms) { this.bindTimeoutMillis = ms; return this; }
    public SmppResourceAdaptor setSubmitTimeoutMillis(long ms) { this.submitTimeoutMillis = ms; return this; }
    public SmppResourceAdaptor setUnbindTimeoutMillis(long ms) { this.unbindTimeoutMillis = ms; return this; }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getSystemId() { return systemId; }
    public String getSystemType() { return systemType; }
    public String getSourceAddr() { return sourceAddr; }

    /**
     * True when transceiver session exists and is bound.
     * Prefer {@link #isPeerReady()} in status/gates — same truth, clearer name.
     */
    public boolean isBound() {
        SmppSession s = this.session;
        return active.get() && s != null && s.isBound();
    }

    /**
     * Honest SMPP client link UP: BOUND to peer SMSC.
     * Never treat {@link #isActive()} (local RA lifecycle) as peer UP.
     */
    public boolean isPeerReady() {
        return isBound();
    }

    /** Injected by the endpoint on {@code activate}; cleared on {@code deactivate}. */
    public void setBootstrap(RaBootstrapPort bootstrap) { this.bootstrap = bootstrap; }

    /** RA lifecycle active — <em>not</em> peer-bound; use {@link #isPeerReady()}. */
    public boolean isActive() { return active.get(); }

    // ── lifecycle ──────────────────────────────────────────────────────

    /** Validate/normalise configuration. Pure — no I/O, safe to call twice. */
    public void doConfigure() {
        if (systemId == null) systemId = "";
        if (password == null) password = "";
        if (systemType == null) systemType = "";
        if (sourceAddr == null) sourceAddr = "";
        LOG.info("[smpp-ra] configured host={} port={} systemId={} systemType={} srcAddr={} networkId={}",
                host, port, systemId, systemType, sourceAddr, networkId);
    }

    /**
     * Bind the SMPP transceiver. Guards against double-bind. A bind failure is
     * logged and the RA is left INACTIVE — it does NOT propagate uncontrolled
     * out of {@code activate()} (see {@link SmppRaEndpoint#activate}). Callers
     * may still {@code doStart()} again later once the SMSC is reachable.
     */
    public void doStart() {
        if (!active.compareAndSet(false, true)) {
            LOG.warn("[smpp-ra] doStart ignored — already active/binding (guard against double-bind)");
            return;
        }
        DefaultSmppClient c = null;
        try {
            c = new DefaultSmppClient();
            this.client = c;

            SmppSessionConfiguration cfg = new SmppSessionConfiguration();
            cfg.setType(SmppBindType.TRANSCEIVER);
            cfg.setName("smpp-ra/" + systemId);
            cfg.setHost(host);
            cfg.setPort(port);
            cfg.setSystemId(systemId);
            cfg.setPassword(password);
            cfg.setSystemType(systemType);
            cfg.setInterfaceVersion(SmppConstants.VERSION_3_4);
            cfg.setBindTimeout(bindTimeoutMillis);

            SmppSessionHandler handler = new InboundHandler();
            this.session = c.bind(cfg, handler);
            LOG.info("[smpp-ra] BOUND transceiver to {}:{} as systemId={}", host, port, systemId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("[smpp-ra] bind interrupted to {}:{}", host, port, e);
            failStart();
        } catch (Exception e) {
            // SmppTimeout/Channel/Bind/UnrecoverablePdu — never thrown out of activate()
            LOG.error("[smpp-ra] bind FAILED to {}:{} — RA left inactive", host, port, e);
            failStart();
        }
    }

    /** Clean unbind + destroy. Idempotent; never leaks the session or client. */
    public void doStop() {
        if (!active.compareAndSet(true, false)) {
            LOG.debug("[smpp-ra] doStop ignored — not active");
            // still release any half-open transport from a failed bind
            destroyTransport();
            return;
        }
        SmppSession s = this.session;
        if (s != null) {
            try {
                if (s.isBound()) {
                    s.unbind(unbindTimeoutMillis);
                }
            } catch (Exception e) {
                LOG.warn("[smpp-ra] unbind error (continuing to destroy)", e);
            }
        }
        destroyTransport();
        LOG.info("[smpp-ra] UNBOUND and destroyed");
    }

    // ── outbound ────────────────────────────────────────────────────────

    /**
     * Build and send a {@code submit_sm}. Blocks only up to
     * {@code submitTimeoutMillis} (bounded), satisfying the SLEE-thread
     * no-blocking rule. Errors are logged, never thrown to the caller.
     *
     * @return the SMSC message id on success, or {@code null} on failure
     */
    public String handleSubmit(SmppCommand.SubmitSm cmd) {
        SmppSession s = this.session;
        if (!active.get() || s == null || !s.isBound()) {
            LOG.warn("[smpp-ra] not bound — submit_sm to {} dropped", cmd.destAddr());
            return null;
        }
        try {
            SubmitSm pdu = new SubmitSm();
            pdu.setSourceAddress(new Address(sourceTon, sourceNpi, sourceAddr));
            pdu.setDestAddress(new Address(destTon, destNpi, cmd.destAddr()));
            pdu.setDataCoding(cmd.dataCoding());
            // protocol_id → RestLink SMSC MtSbb copies into TP-PID (0x7F = SIM Data Download)
            pdu.setProtocolId(cmd.protocolId());

            byte esm = 0;
            if (cmd.udhiSet()) {
                // UDHI bit — TP-UD already carries the UDH; do NOT re-add it.
                esm |= SmppConstants.ESM_CLASS_UDHI_MASK;
            }
            pdu.setEsmClass(esm);

            if (requestDeliveryReceipt) {
                pdu.setRegisteredDelivery(SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED);
            }

            byte[] ud = cmd.tpUd() == null ? new byte[0] : cmd.tpUd();
            // short_message field carries the TP-UD as-is (UDH included when UDHI set)
            pdu.setShortMessage(ud);

            SubmitSmResp resp = s.submit(pdu, submitTimeoutMillis);
            String messageId = resp == null ? null : resp.getMessageId();
            int status = resp == null ? -1 : resp.getCommandStatus();
            LOG.info("[smpp-ra] submit_sm dest={} udhi={} dcs=0x{} pid=0x{} udLen={} status=0x{} msgId={}",
                    cmd.destAddr(), cmd.udhiSet(),
                    Integer.toHexString(cmd.dataCoding() & 0xFF),
                    Integer.toHexString(cmd.protocolId() & 0xFF), ud.length,
                    Integer.toHexString(status), messageId);
            return messageId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("[smpp-ra] submit_sm interrupted dest={}", cmd.destAddr(), e);
            return null;
        } catch (Exception e) {
            LOG.error("[smpp-ra] submit_sm FAILED dest={}", cmd.destAddr(), e);
            return null;
        }
    }

    // ── inbound (best-effort) ─────────────────────────────────────────────

    /**
     * Fire an inbound {@link SmppDeliverSmEvent} into the SLEE via the bootstrap
     * port. Best-effort: if no bootstrap is wired or firing throws, the DLR is
     * simply logged — inbound is not the RA's egress contract.
     */
    private void fireDeliver(DeliverSm ds) {
        RaBootstrapPort bp = this.bootstrap;
        String src = ds.getSourceAddress() == null ? "" : ds.getSourceAddress().getAddress();
        String dst = ds.getDestAddress() == null ? "" : ds.getDestAddress().getAddress();
        boolean dlr = (ds.getEsmClass() & SmppConstants.ESM_CLASS_MT_SMSC_DELIVERY_RECEIPT) != 0;
        byte[] ud = ds.getShortMessage() == null ? new byte[0] : ds.getShortMessage();

        if (bp == null) {
            LOG.info("[smpp-ra] deliver_sm (bootstrap unset, dropped) src={} dst={} dlr={} udLen={}",
                    src, dst, dlr, ud.length);
            return;
        }
        try {
            String id = "smpp-deliver-" + deliverSeq.incrementAndGet();
            ActivityHandle handle = bp.createActivityHandle(id);
            SmppDeliverSmEvent ev = new SmppDeliverSmEvent(src, dst, ds.getEsmClass(),
                    ds.getDataCoding(), ud, dlr);
            SleeEventTrace.raFire("smpp-ra", ev,
                    "id=" + id + " dlr=" + dlr + " src=" + src + " dst=" + dst
                            + " udLen=" + ud.length);
            bp.fireEvent(ev, handle, null);
        } catch (RuntimeException e) {
            LOG.warn("[smpp-ra] deliver_sm fireEvent failed (best-effort) src={} dst={}", src, dst, e);
        }
    }

    // ── internals ─────────────────────────────────────────────────────────

    private void failStart() {
        active.set(false);
        destroyTransport();
    }

    private void destroyTransport() {
        SmppSession s = this.session;
        if (s != null) {
            try {
                s.destroy();
            } catch (Exception e) {
                LOG.debug("[smpp-ra] session.destroy ignored: {}", e.getMessage());
            }
            this.session = null;
        }
        DefaultSmppClient c = this.client;
        if (c != null) {
            try {
                c.destroy();
            } catch (Exception e) {
                LOG.debug("[smpp-ra] client.destroy ignored: {}", e.getMessage());
            }
            this.client = null;
        }
    }

    /**
     * Session handler: answers requests so the link stays healthy and turns
     * inbound {@code deliver_sm} into SLEE events. Extends the cloudhopper
     * default so enquire_link, expiry, exceptions etc. are handled sanely.
     */
    private final class InboundHandler extends DefaultSmppSessionHandler {

        @Override
        public PduResponse firePduRequestReceived(PduRequest pduRequest) {
            if (pduRequest instanceof DeliverSm ds) {
                fireDeliver(ds);
            }
            // Always ACK the request (deliver_sm_resp, enquire_link_resp, …).
            return pduRequest.createResponse();
        }

        @Override
        public void fireChannelUnexpectedlyClosed() {
            LOG.warn("[smpp-ra] SMPP channel unexpectedly closed — marking inactive");
            active.set(false);
        }
    }
}
