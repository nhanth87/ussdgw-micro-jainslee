package et.restlink.ussdgw.ra.smpp;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** 3-port endpoint for app-internal {@code smpp-server-ra}. */
public final class SmppServerRaEndpoint implements RaEndpointPort, RaCommandPort {

    private static final Logger LOG = LogManager.getLogger(SmppServerRaEndpoint.class);

    private final SmppServerResourceAdaptor delegate = new SmppServerResourceAdaptor();
    private final String raName;

    public SmppServerRaEndpoint() {
        this("smpp-server-ra");
    }

    public SmppServerRaEndpoint(String raName) {
        this.raName = raName;
    }

    public SmppServerRaEndpoint setPort(int port) { delegate.setPort(port); return this; }
    public SmppServerRaEndpoint setSystemId(String id) { delegate.setSystemId(id); return this; }
    public SmppServerRaEndpoint setPassword(String pw) { delegate.setPassword(pw); return this; }
    public SmppServerRaEndpoint setNetworkId(int networkId) { delegate.setNetworkId(networkId); return this; }
    public SmppServerRaEndpoint setRequirePassword(boolean require) {
        delegate.setRequirePassword(require);
        return this;
    }
    public int getNetworkId() { return delegate.getNetworkId(); }
    public int getPort() { return delegate.getPort(); }
    public String getSystemId() { return delegate.getSystemId(); }
    public boolean isActive() { return delegate.isActive(); }

    /** See {@link SmppServerResourceAdaptor#isPeerBound()} — honest SMPP server peer UP. */
    public boolean isPeerBound() { return delegate.isPeerBound(); }

    public EsmeSessionRegistry sessions() { return delegate.sessions(); }

    public SmppServerRaEndpoint setBindAuthenticator(
            java.util.function.BiPredicate<String, String> auth) {
        delegate.setBindAuthenticator(auth);
        return this;
    }

    @Override public String getRaName() { return raName; }

    @Override
    public void activate(RaBootstrapPort bootstrap) {
        delegate.setBootstrap(bootstrap);
        delegate.doConfigure();
        delegate.doStart();
        LOG.info("smpp-server-ra [{}] activated", raName);
    }

    @Override
    public void deactivate() {
        delegate.doStop();
        LOG.info("smpp-server-ra [{}] deactivated", raName);
    }

    @Override
    public void sendCommand(OutboundCommand command) {
        // Ingress-only RA — no outbound commands yet.
        LOG.debug("smpp-server-ra ignoring outbound {}", command);
    }
}
