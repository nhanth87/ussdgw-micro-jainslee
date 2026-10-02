package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.api.AsWireCodec;
import et.restlink.ussdgw.bridge.VirtualSession;
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

import java.util.Optional;

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
            catch (Throwable t) {
                detail = "error=" + t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
                org.apache.logging.log4j.LogManager.getLogger(GrpcClientSbb.class)
                        .error("GrpcClientSbb sendPull failed target={}", pull.target(), t);
            }
            SleeEventTrace.outSbb("GrpcClientSbb", event, detail);
            return;
        }
        if (event instanceof GrpcInvokeResponseEvent done) {
            SleeEventTrace.inSbb("GrpcClientSbb", event, "status=" + done.statusCode());
            String detail;
            try { detail = onCompleted(done); }
            catch (Throwable t) {
                detail = "error=" + t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
                org.apache.logging.log4j.LogManager.getLogger(GrpcClientSbb.class)
                        .error("GrpcClientSbb onCompleted failed status={}", done.statusCode(), t);
            }
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
        // P2-1: capture the session's current generation at send time (not the request's generation,
        // which may be stale). A slow response stamped to its own turn loses the claim once the
        // session moved on, instead of being applied to the next turn.
        int sessionGen = 0;
        try {
            sessionGen = svc().store().get(corr).map(VirtualSession::generation).orElse(0);
        } catch (RuntimeException ignored) {
            // store not available (test stub) or session not found — fall back to 0
        }
        if (svc().asPullState().open(corr, target, System.currentTimeMillis(),
                sessionGen).isEmpty()) {
            svc().asPull().recordFailure(circuitKey);
            svc().saga().onAsPullFailed(corr, "AS_PULL_STATE_SATURATED");
            return "state-saturated corr=" + corr;
        }
        try {
            invoke(port, corr, target);
        } catch (RuntimeException e) {
            svc().asPullState().close(corr);
            svc().asPull().recordFailure(circuitKey);
            svc().saga().onAsPullFailed(corr, "AS_SUBMIT_" + e.getClass().getSimpleName());
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
        int wireGen = resp.generation();
        // P2-1 stamp rule (see HttpClientSbb): trust wire gen > 1, else send-time turn.
        if (wireGen <= 1) {
            int pullGen = state == null ? 0 : state.generation();
            // Classic / JSON omit or hardcode gen=1; after MS digit session may be ≥2 (Sip parity).
            Optional<VirtualSession> sess = svc().store().get(corr);
            int stampFrom = pullGen > 0 ? pullGen : sess.map(VirtualSession::generation).orElse(0);
            if (stampFrom > 0) {
                resp = resp.stampedToSessionGeneration(stampFrom);
            }
        }
        svc().bridge().onAsResponse(resp, latency);
        String action = resp.action() == null ? "?" : resp.action().name();
        String asSnip = et.restlink.ussdgw.cdr.CdrUssdSnippet.of(resp.text(), 24);
        return "ok latencyMs=" + latency
                + " wireGen=" + wireGen + " gen=" + resp.generation()
                + " asAction=" + action
                + (asSnip.isEmpty() ? "" : " asUssd=" + asSnip);
    }

    private void invoke(RaCommandPort port, String corr, AsPullTarget.Grpc target) {
        port.sendCommand(new InvokeGrpc(
                corr, target.endpoint(), target.fullMethod(),
                AsWireCodec.encodeRequest(target.request()),
                svc().config().grpcInvokeTimeoutMs()));
    }
}
