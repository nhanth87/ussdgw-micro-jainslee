package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.service.SbbServices;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ra.grpcserver.command.SendGrpcResponse;
import com.microjainslee.ra.grpcserver.events.GrpcRequestEvent;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** gRPC async callback / push ingress from AS. */
public final class GrpcServerSbb implements Sbb, SleeEventHandler {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SbbServices services;

    @InjectRa(name = "grpc-server-ra")
    private volatile RaCommandPort grpcServer;

    public GrpcServerSbb() { this(null); }
    public GrpcServerSbb(SbbServices services) { this.services = services; }
    private SbbServices svc() { return services != null ? services : SbbServices.get(); }

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (!(event instanceof GrpcRequestEvent req)) return;
        SleeEventTrace.inSbb("GrpcServerSbb", event, req.fullMethod());
        String detail;
        try {
            detail = handle(req);
        } catch (Throwable t) {
            detail = "error=" + t.getClass().getSimpleName();
        }
        SleeEventTrace.outSbb("GrpcServerSbb", event, detail);
    }

    private String handle(GrpcRequestEvent req) throws Exception {
        if (!svc().config().grpcServerEnabled()) {
            RaCommandPort port = grpcServer;
            if (port != null) {
                byte[] ack = JSON.writeValueAsBytes(Map.of("error", "grpc callback server disabled"));
                port.sendCommand(new SendGrpcResponse(req.callId(), ack));
            }
            return "callback-disabled";
        }
        String json = new String(req.payload() == null ? new byte[0] : req.payload(), StandardCharsets.UTF_8);
        AsResponse resp = JSON.readValue(json.isBlank() ? "{}" : json, AsResponse.class);
        svc().bridge().onAsResponse(resp, -1);
        RaCommandPort port = grpcServer;
        if (port != null) {
            byte[] ack = JSON.writeValueAsBytes(Map.of("accepted", true));
            port.sendCommand(new SendGrpcResponse(req.callId(), ack));
        }
        return "callback accepted";
    }
}
