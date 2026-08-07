package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.api.AsWireCodec;
import et.restlink.ussdgw.events.PullGrpcEvent;
import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.service.AsPullClient;
import et.restlink.ussdgw.service.AsPullState;
import et.restlink.ussdgw.service.AsPullTarget;
import et.restlink.ussdgw.service.SbbServices;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ra.grpc.command.InvokeGrpc;
import com.microjainslee.ra.grpc.events.GrpcInvokeResponseEvent;

/**
 * gRPC pull toward AS — UTF-8 JSON bytes on InvokeGrpc (greenfield contract).
 * Completion via GrpcInvokeResponseEvent. FORBIDDEN: setTimer / 50ms poll.
 *
 * <p>Stateless like {@link HttpClientSbb}: the RA fires the completion on an activity named after
 * the bare correlation id, which resolves to a different pooled instance than the one that
 * submitted. Per-correlation state belongs to {@code AsPullStateRegistry}.
 */
public final class GrpcClientSbb implements Sbb, SleeEventHandler {
    private final SbbServices services;

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
        AsPullTarget.Grpc target =
                new AsPullTarget.Grpc(pull.target(), pull.fullMethod(), req);
        String circuitKey = target.circuitKey();
        AsPullClient.Admit admit = svc().asPull().tryAdmit(circuitKey);
        if (!admit.allow()) {
            svc().saga().onAsPullFailed(corr, admit.reason());
            return "circuit-open corr=" + corr;
        }
        // Resolve the transport before registering state: an absent RA must not leave an entry
        // (and a request payload) behind.
        RaCommandPort port = grpc;
        if (port == null) {
            svc().asPull().recordFailure(circuitKey);
            svc().saga().onAsPullFailed(corr, "NO_GRPC_RA");
            return "no-ra";
        }
        if (svc().asPullState().open(corr, target, System.currentTimeMillis()).isEmpty()) {
            svc().asPull().recordFailure(circuitKey);
            svc().saga().onAsPullFailed(corr, "AS_PULL_STATE_SATURATED");
            return "state-saturated corr=" + corr;
        }
        try {
            invoke(port, corr, target);
        } catch (RuntimeException e) {
            svc().asPullState().close(corr);
            throw e;
        }
        return "submitted corr=" + corr;
    }

    private String onCompleted(GrpcInvokeResponseEvent done) {
        String corr = done.correlationId();
        AsPullState state = svc().asPullState().peek(corr).orElse(null);
        AsPullTarget.Grpc target =
                (state != null && state.target() instanceof AsPullTarget.Grpc g) ? g : null;
        // No state → no latency sample and no breaker key (see HttpClientSbb).
        long latency = state == null ? -1L : state.latencyMsAt(System.currentTimeMillis());

        if (!done.isOk() || done.payload() == null) {
            String err = done.statusDescription();
            int status = done.statusCode();
            String reason = "AS_GRPC_" + (err == null ? status : err);
            if (target != null) {
                RaCommandPort port = grpc;
                if (port != null && svc().asPull().shouldRetry(
                        target.circuitKey(), state.attempt(), status <= 0 ? 0 : 500, err)) {
                    AsPullState retry =
                            svc().asPullState().beginRetry(corr, System.currentTimeMillis())
                                    .orElse(null);
                    if (retry != null) {
                        invoke(port, corr, target);
                        return "retry attempt=" + retry.attempt() + " corr=" + corr;
                    }
                }
                svc().asPullState().close(corr);
                svc().asPull().recordFailure(target.circuitKey());
            } else {
                svc().asPullState().close(corr);
            }
            svc().saga().onAsPullFailed(corr, reason);
            return "fail " + err;
        }
        svc().asPullState().close(corr);
        if (target != null) {
            svc().asPull().recordSuccess(target.circuitKey());
        }
        AsResponse resp = AsWireCodec.decodeResponse(done.payload(), corr);
        svc().bridge().onAsResponse(resp, latency);
        return "ok latencyMs=" + latency;
    }

    private void invoke(RaCommandPort port, String corr, AsPullTarget.Grpc target) {
        port.sendCommand(new InvokeGrpc(
                corr, target.endpoint(), target.fullMethod(),
                AsWireCodec.encodeRequest(target.request()),
                svc().config().grpcInvokeTimeoutMs()));
    }
}
