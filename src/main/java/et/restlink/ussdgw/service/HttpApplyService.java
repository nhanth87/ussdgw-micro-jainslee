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
    @ConfigProperty(name = "http.ra.event-loop-threads", defaultValue = "8")
    int httpRaEventLoopProp;
    @ConfigProperty(name = "http.ra.worker-pool-size", defaultValue = "256")
    int httpRaWorkerPoolProp;
    @ConfigProperty(name = "http.ra.accept-backlog", defaultValue = "8192")
    int httpRaAcceptBacklogProp;

    private volatile HttpServerRaEndpoint serverEndpoint;
    /** The HTTP client RA entities behind the "http-callback-ra" link (see wire()). */
    private volatile java.util.List<HttpCallbackRaEndpoint> clientEntities = java.util.List.of();

    public String apply() {
        return tearDown() + ";" + wire();
    }

    public String start() {
        if (serverEndpoint != null || !clientEntities.isEmpty()) return apply();
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
        if (!clientEntities.isEmpty()) {
            clientMsg = "http-client-drained=ok";
            for (HttpCallbackRaEndpoint entity : clientEntities) {
                try {
                    entity.deactivate();
                } catch (RuntimeException e) {
                    LOG.warn("http client RA {} deactivate: {}", entity.getRaName(), e.toString());
                    clientMsg = "http-client-drained=warn";
                }
            }
            clientEntities = java.util.List.of();
        }
        linkStatus.clearHttp();
        return serverMsg + ";" + clientMsg;
    }

    public String wire() {
        StringBuilder detail = new StringBuilder();
        if (config.httpClientEnabled()) {
            // Several HTTP client RA entities behind one link name. One entity is one Vert.x
            // instance with one connection pool per AS host, and that single pipe — not the CPU —
            // limited AS throughput. SBBs keep using @InjectRa("http-callback-ra"); the link sends
            // all requests of a USSD session to the same entity.
            int entities = config.httpClientRaInstances();
            int poolPerEntity = Math.max(1, config.httpClientMaxPoolSize() / entities);
            java.util.List<HttpCallbackRaEndpoint> created = new java.util.ArrayList<>(entities);
            for (int i = 0; i < entities; i++) {
                HttpCallbackClientRa ra = new HttpCallbackClientRa();
                ra.setConnectTimeoutMs(config.httpConnectTimeoutMs());
                ra.setRequestTimeoutMs(config.httpRequestTimeoutMs());
                ra.setMaxPoolSize(poolPerEntity);
                // Share the Vert.x event loops between the entities (Vert.x default: 2 x cores each).
                ra.setEventLoopThreads(Math.max(2,
                        2 * Runtime.getRuntime().availableProcessors() / entities));
                // Never re-send a POST the AS may already have acted on (classic behaviour).
                ra.setMaxRetries(config.httpClientMaxRetries());
                created.add(new HttpCallbackRaEndpoint(HttpCallbackRaEndpoint.NAME + "#" + i, ra));
            }
            container.registerRaEntityGroup(HttpCallbackRaEndpoint.NAME, created);
            clientEntities = java.util.List.copyOf(created);
            detail.append("http-client=wired;entities=").append(entities)
                    .append(";connectMs=").append(config.httpConnectTimeoutMs())
                    .append(";requestMs=").append(config.httpRequestTimeoutMs())
                    .append(";poolPerEntity=").append(poolPerEntity)
                    .append(";raRetries=").append(config.httpClientMaxRetries()).append(';');
        } else {
            detail.append("http-client=off;");
        }

        if (config.httpServerEnabled()) {
            int port = store.getInt(RuntimeConfigStore.Keys.HTTP_RA_PORT, httpRaPortProp);
            String host = store.getOr(RuntimeConfigStore.Keys.HTTP_RA_HOST, httpRaHostProp);
            int eventLoop = store.getInt(RuntimeConfigStore.Keys.HTTP_RA_EVENT_LOOP, httpRaEventLoopProp);
            int workerPool = store.getInt(RuntimeConfigStore.Keys.HTTP_RA_WORKER_POOL, httpRaWorkerPoolProp);
            int acceptBacklog = store.getInt(RuntimeConfigStore.Keys.HTTP_RA_ACCEPT_BACKLOG, httpRaAcceptBacklogProp);
            HttpServerResourceAdaptor ra = new HttpServerResourceAdaptor();
            ra.setPort(port);
            ra.setHost(host);
            // Optional tunables — present on newer ra-http-server; absent on older 1.2.0-SNAPSHOT.
            // Call via reflection so ECJ/Maven never bake "Unresolved compilation problem" stubs
            // when compile classpath lags the jain-slee tree.
            invokeIntSetter(ra, "setEventLoopThreads", eventLoop);
            invokeIntSetter(ra, "setWorkerPoolSize", workerPool);
            invokeIntSetter(ra, "setAcceptBacklog", acceptBacklog);
            serverEndpoint = new HttpServerRaEndpoint(ra);
            serverEndpoint.setPort(port);
            container.registerRa(serverEndpoint, serverEndpoint);
            HttpServerAdminBindings.bind(serverEndpoint);
            linkStatus.markHttpListen(port);
            detail.append("http-server=wired;listen=").append(host).append(':').append(port)
                    .append(";eventLoop=").append(eventLoop)
                    .append(";workerPool=").append(workerPool)
                    .append(";acceptBacklog=").append(acceptBacklog)
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
    /** First client RA entity (status pages); all entities: {@link #clientEntities()}. */
    public HttpCallbackRaEndpoint clientEndpoint() { return clientEntities.isEmpty() ? null : clientEntities.get(0); }

    public java.util.List<HttpCallbackRaEndpoint> clientEntities() { return clientEntities; }
    public boolean serverUp() { return serverEndpoint != null; }
    public boolean clientUp() { return !clientEntities.isEmpty(); }

    public int listenPort() {
        return store.getInt(RuntimeConfigStore.Keys.HTTP_RA_PORT, httpRaPortProp);
    }

    public String listenHost() {
        return store.getOr(RuntimeConfigStore.Keys.HTTP_RA_HOST, httpRaHostProp);
    }

    /**
     * Best-effort int setter for ra-http-server knobs that are not on every installed SNAPSHOT.
     * Missing method → log once at debug and keep Vert.x defaults.
     */
    private static void invokeIntSetter(Object target, String method, int value) {
        try {
            target.getClass().getMethod(method, int.class).invoke(target, value);
        } catch (ReflectiveOperationException ex) {
            LOG.debug("HTTP RA {} unavailable on {}: {}", method, target.getClass().getName(), ex.toString());
        }
    }
}
