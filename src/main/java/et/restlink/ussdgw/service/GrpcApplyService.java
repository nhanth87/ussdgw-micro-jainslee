package et.restlink.ussdgw.service;

import et.restlink.ussdgw.admin.LinkStatusService;
import et.restlink.ussdgw.config.RuntimeConfigStore;
import et.restlink.ussdgw.config.UssdConfigService;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.grpc.GenericGrpcClientRaEndpoint;
import com.microjainslee.ra.grpcserver.GrpcServerRa;
import com.microjainslee.ra.grpcserver.GrpcServerRaEndpoint;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class GrpcApplyService {
    private static final Logger LOG = LogManager.getLogger(GrpcApplyService.class);

    @Inject MicroSleeContainer container;
    @Inject LinkStatusService linkStatus;
    @Inject UssdConfigService config;
    @Inject RuntimeConfigStore store;

    @ConfigProperty(name = "ussd.grpc.server.port", defaultValue = "9099")
    int grpcServerPortProp;

    private volatile GrpcServerRaEndpoint serverEndpoint;
    private volatile GenericGrpcClientRaEndpoint clientEndpoint;

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
        deactivate(serverEndpoint); serverEndpoint = null;
        deactivate(clientEndpoint); clientEndpoint = null;
        linkStatus.clearGrpc();
        return "grpc-drained=ok";
    }

    public String wire() {
        StringBuilder detail = new StringBuilder();
        if (config.grpcClientEnabled()) {
            clientEndpoint = new GenericGrpcClientRaEndpoint();
            container.registerRa(clientEndpoint, clientEndpoint);
            detail.append("grpc-client=wired;invokeMs=").append(config.grpcInvokeTimeoutMs()).append(';');
        } else {
            detail.append("grpc-client=off;");
        }

        if (config.grpcServerEnabled()) {
            int port = store.getInt(RuntimeConfigStore.Keys.GRPC_SERVER_PORT, grpcServerPortProp);
            GrpcServerRa ra = new GrpcServerRa();
            ra.setPort(port);
            serverEndpoint = new GrpcServerRaEndpoint(ra);
            container.registerRa(serverEndpoint, serverEndpoint);
            linkStatus.markGrpcListen(port);
            detail.append("grpc-server=wired;listen=").append(port);
        } else {
            linkStatus.clearGrpc();
            detail.append("grpc-server=off");
        }
        String d = detail.toString();
        linkStatus.setGrpcDetail(d);
        LOG.info("gRPC apply: {}", d);
        return d;
    }

    public GrpcServerRaEndpoint serverEndpoint() { return serverEndpoint; }
    public GenericGrpcClientRaEndpoint clientEndpoint() { return clientEndpoint; }
    public boolean serverUp() { return serverEndpoint != null; }
    public boolean clientUp() { return clientEndpoint != null; }

    public int listenPort() {
        return store.getInt(RuntimeConfigStore.Keys.GRPC_SERVER_PORT, grpcServerPortProp);
    }

    private static void deactivate(com.microjainslee.api.RaEndpointPort ep) {
        if (ep == null) return;
        try { ep.deactivate(); } catch (RuntimeException ignored) { }
    }
}
