/*
 * RestLink USSD GW — in-tree SMPP Resource Adaptor (WRAPPER half, 3-port).
 *
 * <p>Exposes app-private {@code smpp-ra} through {@link RaEndpointPort} /
 * {@link RaCommandPort}; transport lives in {@link SmppResourceAdaptor}.
 */
package et.restlink.ussdgw.ra.smpp;

import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.ra.smpp.command.SmppCommand;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 3-port endpoint for the SMPP RA. RA name is fixed to {@code "smpp-ra"} so
 * SBBs / the adapter can route {@link OutboundCommand}s to it.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@link #activate(RaBootstrapPort)} → inject bootstrap, configure, bind
 *       the SMPP transceiver.</li>
 *   <li>{@link #sendCommand(OutboundCommand)} → pattern-match
 *       {@link SmppCommand} and delegate.</li>
 *   <li>{@link #deactivate()} → unbind + destroy, clear bootstrap.</li>
 * </ol>
 */
public final class SmppRaEndpoint implements RaEndpointPort, RaCommandPort {

    private static final Logger LOG = LogManager.getLogger(SmppRaEndpoint.class);

    private final SmppResourceAdaptor delegate;
    private final String raName;

    public SmppRaEndpoint(SmppResourceAdaptor delegate) {
        this(delegate, "smpp-ra");
    }

    public SmppRaEndpoint(SmppResourceAdaptor delegate, String raName) {
        this.delegate = delegate;
        this.raName = raName == null || raName.isBlank() ? "smpp-ra" : raName;
    }

    /** Convenience: create the endpoint with its own fresh delegate. */
    public SmppRaEndpoint() {
        this(new SmppResourceAdaptor(), "smpp-ra");
    }

    /** Named client endpoint for multi-SMPP registry. */
    public SmppRaEndpoint(String raName) {
        this(new SmppResourceAdaptor(), raName);
    }

    // ── configuration setters (delegate to the RA, chainable) ───────────
    public SmppRaEndpoint setHost(String host) { delegate.setHost(host); return this; }
    public SmppRaEndpoint setPort(int port) { delegate.setPort(port); return this; }
    public SmppRaEndpoint setSystemId(String id) { delegate.setSystemId(id); return this; }
    public SmppRaEndpoint setPassword(String pw) { delegate.setPassword(pw); return this; }
    public SmppRaEndpoint setSystemType(String t) { delegate.setSystemType(t); return this; }
    public SmppRaEndpoint setSourceAddr(String a) { delegate.setSourceAddr(a); return this; }
    public SmppRaEndpoint setNetworkId(int networkId) { delegate.setNetworkId(networkId); return this; }
    public int getNetworkId() { return delegate.getNetworkId(); }

    public String getHost() { return delegate.getHost(); }
    public int getPort() { return delegate.getPort(); }
    public String getSystemId() { return delegate.getSystemId(); }
    public boolean isBound() { return delegate.isBound(); }

    /** See {@link SmppResourceAdaptor#isPeerReady()} — honest SMPP client link UP. */
    public boolean isPeerReady() { return delegate.isPeerReady(); }

    /** RA lifecycle — not peer UP; use {@link #isPeerReady()}. */
    public boolean isActive() { return delegate.isActive(); }

    /** Expose the underlying RA (tests / wiring). */
    public SmppResourceAdaptor delegate() {
        return delegate;
    }

    // ── RaEndpointPort ───────────────────────────────────────────────────

    @Override
    public String getRaName() {
        return raName;
    }

    @Override
    public void activate(RaBootstrapPort bootstrap) {
        delegate.setBootstrap(bootstrap);
        try {
            delegate.doConfigure();
            delegate.doStart();
        } catch (RuntimeException e) {
            // doStart already swallows bind failures; this is belt-and-braces
            // so a misconfiguration never takes down container startup.
            LOG.error("[smpp-ra] activate error — RA left inactive", e);
        }
        LOG.info("[smpp-ra] endpoint activated (active={})", delegate.isActive());
    }

    @Override
    public void deactivate() {
        try {
            delegate.doStop();
        } catch (RuntimeException e) {
            LOG.warn("[smpp-ra] error during doStop", e);
        } finally {
            delegate.setBootstrap(null);
        }
        LOG.info("[smpp-ra] endpoint deactivated");
    }

    // ── RaCommandPort ────────────────────────────────────────────────────

    @Override
    public void sendCommand(OutboundCommand command) {
        switch (command) {
            case SmppCommand.SubmitSm submit -> {
                SleeEventTrace.raOut("smpp-ra", "SubmitSm",
                        "dest=" + submit.destAddr());
                String msgId = delegate.handleSubmit(submit);
                if (msgId == null) {
                    LOG.warn("[smpp-ra] submit_sm failed or not bound dest={}", submit.destAddr());
                } else {
                    SleeEventTrace.raOut("smpp-ra", "SubmitSmResp",
                            "dest=" + submit.destAddr() + " msgId=" + msgId);
                }
            }
            case null -> LOG.warn("[smpp-ra] received null command");
            default -> LOG.warn("[smpp-ra] received unknown command type: {}",
                    command.getClass().getName());
        }
    }

    /**
     * Synchronous submit that surfaces success/failure to callers ({@code /sendsms}).
     *
     * @return SMSC message id, or {@code null} if not bound / submit failed
     */
    public String submitSm(SmppCommand.SubmitSm cmd) {
        SleeEventTrace.raOut("smpp-ra", "SubmitSm", "dest=" + cmd.destAddr() + " sync=true");
        String msgId = delegate.handleSubmit(cmd);
        SleeEventTrace.raOut("smpp-ra", "SubmitSmResp",
                "dest=" + cmd.destAddr() + " msgId=" + msgId);
        return msgId;
    }
}
