package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.api.AsWireCodec;
import et.restlink.ussdgw.events.PullGrpcEvent;
import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.service.SbbServices;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ra.grpc.command.InvokeGrpc;
import com.microjainslee.ra.grpc.events.GrpcInvokeResponseEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * gRPC pull toward AS — UTF-8 JSON bytes on InvokeGrpc (greenfield contract).
 * Completion via GrpcInvokeResponseEvent. FORBIDDEN: setTimer / 50ms poll.
 */
public final class GrpcClientSbb implements Sbb, SleeEventHandler {
    private final SbbServices services;
    private final Map<String, Long> startedAt = new ConcurrentHashMap<>();

    @InjectRa(name = "grpc-client-ra")
    private volatile RaCommandPort grpc;

    public GrpcClientSbb() { this(null); }
    public GrpcClientSbb(SbbServices services) { this.services = services; }
    private SbbServices svc() { return services != null ? services : SbbServices.get(); }

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (event instanceof PullGrpcEvent pull) {
            SleeEventTrace.inSbb("GrpcClientSbb", event, "target=" + pull.target());
            String detail;
            try { detail = sendPull(pull); }
            catch (Throwable t) { detail = "error=" + t.getClass().getSimpleName(); }
            SleeEventTrace.outSbb("GrpcClientSbb", event, detail);
            return;
        }
        if (event instanceof GrpcInvokeResponseEvent done) {
            SleeEventTrace.inSbb("GrpcClientSbb", event, "status=" + done.statusCode());
            String detail;
            try { detail = onCompleted(done); }
            catch (Throwable t) { detail = "error=" + t.getClass().getSimpleName(); }
            SleeEventTrace.outSbb("GrpcClientSbb", event, detail);
        }
    }

    private String sendPull(PullGrpcEvent pull) {
        AsRequest req = pull.request();
        byte[] payload = AsWireCodec.encodeRequest(req);
        startedAt.put(req.correlationId(), System.currentTimeMillis());
        RaCommandPort port = grpc;
        if (port == null) return "no-ra";
        port.sendCommand(new InvokeGrpc(
                req.correlationId(), pull.target(), pull.fullMethod(), payload,
                svc().config().grpcInvokeTimeoutMs()));
        return "submitted corr=" + req.correlationId();
    }

    private String onCompleted(GrpcInvokeResponseEvent done) {
        String corr = done.correlationId();
        Long start = startedAt.remove(corr);
        long latency = start == null ? -1 : System.currentTimeMillis() - start;
        if (!done.isOk() || done.payload() == null) {
            return "fail " + done.statusDescription();
        }
        AsResponse resp = AsWireCodec.decodeResponse(done.payload(), corr);
        svc().bridge().onAsResponse(resp, latency);
        return "ok latencyMs=" + latency;
    }
}
