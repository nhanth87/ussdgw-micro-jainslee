package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.api.AsWireCodec;
import et.restlink.ussdgw.events.PullGrpcEvent;
import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.service.AsPullClient;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * gRPC pull toward AS — UTF-8 JSON bytes on InvokeGrpc (greenfield contract).
 * Completion via GrpcInvokeResponseEvent. FORBIDDEN: setTimer / 50ms poll.
 */
public final class GrpcClientSbb implements Sbb, SleeEventHandler {
    private final SbbServices services;
    private final Map<String, Long> startedAt = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private final Map<String, PullGrpcEvent> pullByCorr = new ConcurrentHashMap<>();

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
        String corr = req.correlationId();
        String circuitKey = pull.target() + "|" + pull.fullMethod();
        AsPullClient.Admit admit = svc().asPull().tryAdmit(circuitKey);
        if (!admit.allow()) {
            svc().saga().onAsPullFailed(corr, admit.reason());
            return "circuit-open corr=" + corr;
        }
        byte[] payload = AsWireCodec.encodeRequest(req);
        startedAt.put(corr, System.currentTimeMillis());
        attempts.put(corr, new AtomicInteger(0));
        pullByCorr.put(corr, pull);
        RaCommandPort port = grpc;
        if (port == null) {
            svc().asPull().recordFailure(circuitKey);
            svc().saga().onAsPullFailed(corr, "NO_GRPC_RA");
            return "no-ra";
        }
        port.sendCommand(new InvokeGrpc(
                corr, pull.target(), pull.fullMethod(), payload,
                svc().config().grpcInvokeTimeoutMs()));
        return "submitted corr=" + corr;
    }

    private String onCompleted(GrpcInvokeResponseEvent done) {
        String corr = done.correlationId();
        Long start = startedAt.get(corr);
        long latency = start == null ? -1 : System.currentTimeMillis() - start;
        PullGrpcEvent pull = pullByCorr.get(corr);
        String circuitKey = pull == null ? "" : pull.target() + "|" + pull.fullMethod();
        AtomicInteger att = attempts.getOrDefault(corr, new AtomicInteger(0));
        int attempt = att.get();

        if (!done.isOk() || done.payload() == null) {
            String err = done.statusDescription();
            int status = done.statusCode();
            if (svc().asPull().shouldRetry(circuitKey, attempt, status <= 0 ? 0 : 500, err)
                    && pull != null) {
                att.incrementAndGet();
                RaCommandPort port = grpc;
                if (port != null) {
                    startedAt.put(corr, System.currentTimeMillis());
                    port.sendCommand(new InvokeGrpc(
                            corr, pull.target(), pull.fullMethod(),
                            AsWireCodec.encodeRequest(pull.request()),
                            svc().config().grpcInvokeTimeoutMs()));
                    return "retry attempt=" + att.get() + " corr=" + corr;
                }
            }
            clear(corr);
            svc().asPull().recordFailure(circuitKey);
            svc().saga().onAsPullFailed(corr, "AS_GRPC_" + (err == null ? status : err));
            return "fail " + err;
        }
        clear(corr);
        svc().asPull().recordSuccess(circuitKey);
        AsResponse resp = AsWireCodec.decodeResponse(done.payload(), corr);
        svc().bridge().onAsResponse(resp, latency);
        return "ok latencyMs=" + latency;
    }

    private void clear(String corr) {
        startedAt.remove(corr);
        attempts.remove(corr);
        pullByCorr.remove(corr);
    }
}
