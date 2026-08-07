package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.events.PullHttpEvent;
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
import com.microjainslee.ra.httpclient.command.HttpCallbackCommand;
import com.microjainslee.ra.httpclient.events.HttpCallbackCompletedEvent;

/**
 * HTTP pull toward AS — request/response via RA JsonPost (raw body, no poll).
 *
 * <p>Holds no per-correlation state. The submit and the completion are delivered to different
 * pooled instances of this class (the container derives the SBB entity id from the activity
 * context name, and the RA names the completion activity after the bare correlation id), so
 * anything kept in an instance field would correlate nothing. Ownership lives in
 * {@code AsPullStateRegistry}.
 */
public final class HttpClientSbb implements Sbb, SleeEventHandler {
    private final SbbServices services;

    @InjectRa(name = "http-callback-ra")
    private volatile RaCommandPort httpClient;

    public HttpClientSbb() { this(null); }
    public HttpClientSbb(SbbServices services) { this.services = services; }
    private SbbServices svc() { return services != null ? services : SbbServices.get(); }

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (event instanceof PullHttpEvent pull) {
            SleeEventTrace.inSbb("HttpClientSbb", event, "url=" + pull.asUrl());
            String detail;
            try { detail = sendPull(pull); }
            catch (Throwable t) { detail = "error=" + t.getClass().getSimpleName(); }
            SleeEventTrace.outSbb("HttpClientSbb", event, detail);
            return;
        }
        if (event instanceof HttpCallbackCompletedEvent done) {
            SleeEventTrace.inSbb("HttpClientSbb", event, "status=" + done.getStatusCode());
            String detail;
            try { detail = onCompleted(done); }
            catch (Throwable t) { detail = "error=" + t.getClass().getSimpleName(); }
            SleeEventTrace.outSbb("HttpClientSbb", event, detail);
        }
    }

    private String sendPull(PullHttpEvent pull) {
        AsRequest req = pull.request();
        String corr = req.correlationId();
        AsPullClient.Admit admit = svc().asPull().tryAdmit(pull.asUrl());
        if (!admit.allow()) {
            svc().saga().onAsPullFailed(corr, admit.reason());
            return "circuit-open corr=" + corr;
        }
        // Resolve the transport before registering state: an absent RA must not leave an entry
        // (and a request body) behind.
        RaCommandPort port = httpClient;
        if (port == null) {
            svc().asPull().recordFailure(pull.asUrl());
            svc().saga().onAsPullFailed(corr, "NO_HTTP_RA");
            return "no-ra";
        }
        String tenantId = svc().store().get(corr).map(VirtualSession::tenantId).orElse(null);
        AsHttpWireFormat format = svc().wireFormatResolver().resolve(tenantId);
        String payload = svc().wireFacade().encodePullRequest(req, format);
        AsPullTarget.Http target = new AsPullTarget.Http(pull.asUrl(), payload, format);

        if (svc().asPullState().open(corr, target, System.currentTimeMillis()).isEmpty()) {
            svc().asPull().recordFailure(pull.asUrl());
            svc().saga().onAsPullFailed(corr, "AS_PULL_STATE_SATURATED");
            return "state-saturated corr=" + corr;
        }
        try {
            submitPost(port, corr, target);
        } catch (RuntimeException e) {
            svc().asPullState().close(corr);
            throw e;
        }
        return "submitted corr=" + corr + " wire=" + format;
    }

    private String onCompleted(HttpCallbackCompletedEvent done) {
        String corr = done.getSessionId();
        int status = done.getStatusCode();
        String err = done.getErrorMessage();

        AsPullState state = svc().asPullState().peek(corr).orElse(null);
        AsPullTarget.Http target =
                (state != null && state.target() instanceof AsPullTarget.Http h) ? h : null;
        AsHttpWireFormat format = target == null ? AsHttpWireFormat.XML : target.format();
        // No state → no latency sample and no breaker key. Passing a non-positive latency lets
        // the bridge fall back to its own pull clock; inventing a URL would merge every AS onto
        // one breaker.
        long latency = state == null ? -1L : state.latencyMsAt(System.currentTimeMillis());

        boolean transportFail = (err != null && !err.isBlank() && status <= 0);
        boolean httpFail = status >= 400;
        if (transportFail || httpFail) {
            String reason = transportFail ? "AS_TRANSPORT" : "AS_HTTP_" + status;
            if (target != null) {
                RaCommandPort port = httpClient;
                if (port != null
                        && svc().asPull().shouldRetry(target.url(), state.attempt(), status, err)) {
                    AsPullState retry =
                            svc().asPullState().beginRetry(corr, System.currentTimeMillis())
                                    .orElse(null);
                    if (retry != null) {
                        submitPost(port, corr, target);
                        return "retry attempt=" + retry.attempt() + " corr=" + corr;
                    }
                }
                svc().asPullState().close(corr);
                svc().asPull().recordFailure(target.url());
            } else {
                // State may still be present when the target was not an HTTP pull (or was swept
                // mid-flight) — never leave an orphan behind on a terminal failure.
                svc().asPullState().close(corr);
            }
            svc().saga().onAsPullFailed(corr, reason);
            return "fail " + (err == null ? status : err);
        }

        String body = done.getResponseBody();
        if (body == null || body.isBlank()) {
            svc().asPullState().close(corr);
            if (target != null) {
                svc().asPull().recordFailure(target.url());
            }
            svc().saga().onAsPullFailed(corr, "AS_EMPTY_BODY");
            return "empty-body";
        }
        svc().asPullState().close(corr);
        if (target != null) {
            svc().asPull().recordSuccess(target.url());
        }
        AsResponse resp = svc().wireFacade().decodePullResponse(body, format, corr);
        // EWMA via bridge.onAsResponse(latency) only — avoid double-sample
        svc().bridge().onAsResponse(resp, latency);
        return "ok latencyMs=" + latency + " wire=" + format;
    }

    /** POST raw AS pull body with format Content-Type (XML or JSON). */
    private static void submitPost(RaCommandPort port, String corr, AsPullTarget.Http target) {
        port.sendCommand(new HttpCallbackCommand.JsonPostRequest(
                corr, target.url(), target.body(), target.format().contentType()));
    }
}
