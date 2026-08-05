package et.restlink.ussdgw.ra.smpp;

import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.ra.smpp.events.SmppSubmitSmEvent;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppServerConfiguration;
import com.cloudhopper.smpp.SmppServerHandler;
import com.cloudhopper.smpp.SmppServerSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppServer;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.BaseBind;
import com.cloudhopper.smpp.pdu.BaseBindResp;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.SmppProcessingException;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.RaBootstrapPort;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;

/**
 * Minimal SMPP server (AS ESME → USSD GW). Fires {@link SmppSubmitSmEvent}.
 */
public final class SmppServerResourceAdaptor {

    private static final Logger LOG = LogManager.getLogger(SmppServerResourceAdaptor.class);

    private volatile int port = 2776;
    private volatile String systemId = "otasmsgw";
    private volatile String password = "password";
    /** Logical plane id stamped onto ingress {@link SmppSubmitSmEvent}s. */
    private volatile int networkId = 0;
    /**
     * A blank server password used to accept any bind. A bind decides whose delivery receipts
     * a peer receives, so it fails closed now; set {@code false} only for a lab with no ESME
     * allowlist and no shared secret.
     */
    private volatile boolean requirePassword = true;
    private volatile RaBootstrapPort bootstrap;
    private final EsmeSessionRegistry sessions = new EsmeSessionRegistry();
    /** (systemId, password) → allowed. Null = fall back to server systemId/password. */
    private volatile BiPredicate<String, String> bindAuthenticator;

    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicLong seq = new AtomicLong();
    private volatile DefaultSmppServer server;
    private volatile ScheduledExecutorService monitorExecutor;

    public SmppServerResourceAdaptor setPort(int port) { this.port = port; return this; }
    public SmppServerResourceAdaptor setSystemId(String id) { this.systemId = id; return this; }
    public SmppServerResourceAdaptor setPassword(String pw) { this.password = pw; return this; }
    public SmppServerResourceAdaptor setNetworkId(int networkId) { this.networkId = networkId; return this; }
    public SmppServerResourceAdaptor setRequirePassword(boolean require) {
        this.requirePassword = require;
        return this;
    }
    public int getNetworkId() { return networkId; }
    public int getPort() { return port; }
    public String getSystemId() { return systemId; }

    /** RA lifecycle (LISTEN) — <em>not</em> peer UP; use {@link #isPeerBound()}. */
    public boolean isActive() { return active.get(); }

    /**
     * Honest SMPP server peer UP: at least one BOUND ESME session.
     * Local LISTEN alone is insufficient.
     */
    public boolean isPeerBound() {
        return active.get() && sessions.hasBoundSession();
    }

    public EsmeSessionRegistry sessions() { return sessions; }
    public void setBindAuthenticator(BiPredicate<String, String> auth) {
        this.bindAuthenticator = auth;
    }
    public void setBootstrap(RaBootstrapPort bootstrap) { this.bootstrap = bootstrap; }

    public void doConfigure() {
        if (systemId == null) systemId = "otasmsgw";
        if (password == null) password = "";
        LOG.info("[smpp-server-ra] configured port={} systemId={} networkId={}", port, systemId, networkId);
    }

    public void doStart() {
        if (!active.compareAndSet(false, true)) {
            return;
        }
        try {
            monitorExecutor = Executors.newScheduledThreadPool(1);
            SmppServerConfiguration cfg = new SmppServerConfiguration();
            cfg.setPort(port);
            cfg.setMaxConnectionSize(10);
            cfg.setNonBlockingSocketsEnabled(true);
            cfg.setDefaultRequestExpiryTimeout(30_000);
            cfg.setDefaultWindowMonitorInterval(15_000);
            cfg.setDefaultWindowSize(5);
            cfg.setDefaultWindowWaitTimeout(60_000);
            cfg.setDefaultSessionCountersEnabled(true);
            cfg.setJmxEnabled(false);

            server = new DefaultSmppServer(cfg, new Handler(), monitorExecutor);
            server.start();
            LOG.info("[smpp-server-ra] listening on {}", port);
        } catch (Exception e) {
            active.set(false);
            LOG.error("[smpp-server-ra] start failed: {}", e.toString());
        }
    }

    public void doStop() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        try {
            if (server != null) {
                server.stop();
                server.destroy();
            }
        } catch (Exception e) {
            LOG.warn("[smpp-server-ra] stop: {}", e.toString());
        }
        if (monitorExecutor != null) {
            monitorExecutor.shutdownNow();
        }
        server = null;
        monitorExecutor = null;
        sessions.clear();
    }

    private final class Handler implements SmppServerHandler {
        @Override
        public void sessionBindRequested(Long sessionId, SmppSessionConfiguration sessionConfiguration,
                                         BaseBind bindRequest) throws SmppProcessingException {
            String id = bindRequest.getSystemId();
            String pw = bindRequest.getPassword();
            BiPredicate<String, String> auth = bindAuthenticator;
            if (auth != null) {
                if (!auth.test(id, pw == null ? "" : pw)) {
                    LOG.warn("[smpp-server-ra] reject bind (allowlist) systemId={}", id);
                    throw new SmppProcessingException(SmppConstants.STATUS_INVPASWD);
                }
                sessionConfiguration.setName("esme-" + id);
                return;
            }
            if (!SmppEndpointRegistry.constantTimeEquals(systemId, id)) {
                LOG.warn("[smpp-server-ra] reject bind systemId={}", id);
                throw new SmppProcessingException(SmppConstants.STATUS_INVSYSID);
            }
            if (password == null || password.isBlank()) {
                if (requirePassword) {
                    LOG.warn("[smpp-server-ra] reject bind systemId={} — no server password "
                            + "configured (set smpp.server.password, or "
                            + "smpp.server.require-password=false to accept unauthenticated binds)",
                            id);
                    throw new SmppProcessingException(SmppConstants.STATUS_INVPASWD);
                }
            } else if (!SmppEndpointRegistry.constantTimeEquals(password, pw)) {
                throw new SmppProcessingException(SmppConstants.STATUS_INVPASWD);
            }
            sessionConfiguration.setName("esme-" + id);
        }

        @Override
        public void sessionCreated(Long sessionId, SmppServerSession session,
                                   BaseBindResp preparedBindResponse) {
            session.serverReady(new SessionHandler(session));
            String sid = session.getConfiguration().getSystemId();
            sessions.put(sid, session);
            LOG.info("[smpp-server-ra] session created id={} systemId={}",
                    sessionId, sid);
        }

        @Override
        public void sessionDestroyed(Long sessionId, SmppServerSession session) {
            String sid = session.getConfiguration().getSystemId();
            sessions.remove(sid, session);
            LOG.info("[smpp-server-ra] session destroyed id={} systemId={}", sessionId, sid);
        }
    }

    private final class SessionHandler extends DefaultSmppSessionHandler {
        private final SmppServerSession session;

        SessionHandler(SmppServerSession session) {
            this.session = session;
        }

        @Override
        public PduResponse firePduRequestReceived(PduRequest pduRequest) {
            if (pduRequest instanceof SubmitSm submit) {
                String dest = submit.getDestAddress() == null ? ""
                        : submit.getDestAddress().getAddress();
                byte[] msg = submit.getShortMessage();
                String sid = "smpp-" + seq.incrementAndGet();
                String system = session.getConfiguration().getSystemId();
                fire(new SmppSubmitSmEvent(sid, system, dest, msg,
                        submit.getDataCoding(), submit.getEsmClass(),
                        (byte) submit.getProtocolId(),
                        submit.getSequenceNumber(), networkId));
                SubmitSmResp resp = submit.createResponse();
                resp.setMessageId(sid);
                return resp;
            }
            return pduRequest.createResponse();
        }
    }

    private void fire(SmppSubmitSmEvent event) {
        RaBootstrapPort bp = this.bootstrap;
        if (bp == null) {
            LOG.warn("[smpp-server-ra] no bootstrap — drop submit");
            return;
        }
        SleeEventTrace.raFire("smpp-server-ra", event,
                "systemId=" + event.getSystemId()
                        + " dest=" + event.getDestAddr()
                        + " session=" + event.getSessionId());
        ActivityHandle handle = bp.createActivityHandle(event.getSessionId());
        bp.fireEvent(event, handle, null);
    }
}
