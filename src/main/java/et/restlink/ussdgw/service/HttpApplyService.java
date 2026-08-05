package et.restlink.ussdgw.service;

import et.restlink.ussdgw.admin.LinkStatusService;
import et.restlink.ussdgw.config.RuntimeConfigStore;
import et.restlink.ussdgw.config.UssdConfigService;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.httpclient.HttpCallbackClientRa;
import com.microjainslee.ra.httpclient.HttpCallbackRaEndpoint;
import com.microjainslee.ra.httpserver.HttpServerRaEndpoint;
import com.microjainslee.ra.httpserver.HttpServerResourceAdaptor;
import com.microjainslee.ra.httpserver.admin.HttpServerAdminBindings;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class HttpApplyService {
    private static final Logger LOG = LogManager.getLogger(HttpApplyService.class);

    @Inject MicroSleeContainer container;
    @Inject LinkStatusService linkStatus;
    @Inject UssdConfigService config;
    @Inject RuntimeConfigStore store;

    @ConfigProperty(name = "http.ra.port", defaultValue = "8088")
    int httpRaPortProp;
    @ConfigProperty(name = "http.ra.host", defaultValue = "0.0.0.0")
    String httpRaHostProp;

    private volatile HttpServerRaEndpoint serverEndpoint;
    private volatile HttpCallbackRaEndpoint clientEndpoint;

    public String apply() {
        return tearDown() + ";" + wire();
    }

    public String start() {
        if (serverEndpoint != null || clientEndpoint != null) return apply();
        return wire();
    }

    public String stop() {
        return tearDown();
    }

    public String tearDown() {
        HttpServerAdminBindings.clear();
        String serverMsg = "http-server-drained=noop";
        if (serverEndpoint != null) {
            try {
                serverEndpoint.deactivate();
                serverMsg = "http-server-drained=ok";
            } catch (RuntimeException e) {
                LOG.warn("http server RA deactivate: {}", e.toString());
                serverMsg = "http-server-drained=warn";
            } finally {
                serverEndpoint = null;
            }
        }
        String clientMsg = "http-client-drained=noop";
        if (clientEndpoint != null) {
            try {
                clientEndpoint.deactivate();
                clientMsg = "http-client-drained=ok";
            } catch (RuntimeException e) {
                LOG.warn("http client RA deactivate: {}", e.toString());
                clientMsg = "http-client-drained=warn";
            } finally {
                clientEndpoint = null;
            }
        }
        linkStatus.clearHttp();
        return serverMsg + ";" + clientMsg;
    }

    public String wire() {
        StringBuilder detail = new StringBuilder();
        if (config.httpClientEnabled()) {
            HttpCallbackClientRa ra = new HttpCallbackClientRa();
            ra.setConnectTimeoutMs(config.httpConnectTimeoutMs());
            ra.setRequestTimeoutMs(config.httpRequestTimeoutMs());
            clientEndpoint = new HttpCallbackRaEndpoint(ra);
            container.registerRa(clientEndpoint, clientEndpoint);
            detail.append("http-client=wired;connectMs=").append(config.httpConnectTimeoutMs())
                    .append(";requestMs=").append(config.httpRequestTimeoutMs()).append(';');
        } else {
            detail.append("http-client=off;");
        }

        if (config.httpServerEnabled()) {
            int port = store.getInt(RuntimeConfigStore.Keys.HTTP_RA_PORT, httpRaPortProp);
            String host = store.getOr(RuntimeConfigStore.Keys.HTTP_RA_HOST, httpRaHostProp);
            HttpServerResourceAdaptor ra = new HttpServerResourceAdaptor();
            ra.setPort(port);
            ra.setHost(host);
            serverEndpoint = new HttpServerRaEndpoint(ra);
            serverEndpoint.setPort(port);
            container.registerRa(serverEndpoint, serverEndpoint);
            HttpServerAdminBindings.bind(serverEndpoint);
            linkStatus.markHttpListen(port);
            detail.append("http-server=wired;listen=").append(host).append(':').append(port)
                    .append(";callback=").append(config.httpCallbackPath());
        } else {
            linkStatus.clearHttp();
            detail.append("http-server=off");
        }
        String d = detail.toString();
        linkStatus.setHttpDetail(d);
        LOG.info("HTTP apply: {}", d);
        return d;
    }

    public HttpServerRaEndpoint serverEndpoint() { return serverEndpoint; }
    public HttpCallbackRaEndpoint clientEndpoint() { return clientEndpoint; }
    public boolean serverUp() { return serverEndpoint != null; }
    public boolean clientUp() { return clientEndpoint != null; }

    public int listenPort() {
        return store.getInt(RuntimeConfigStore.Keys.HTTP_RA_PORT, httpRaPortProp);
    }

    public String listenHost() {
        return store.getOr(RuntimeConfigStore.Keys.HTTP_RA_HOST, httpRaHostProp);
    }
}
