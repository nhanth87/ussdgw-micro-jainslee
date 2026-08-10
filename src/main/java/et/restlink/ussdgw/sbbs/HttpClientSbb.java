package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrStatuses;
import et.restlink.ussdgw.events.GatedAsNotifyEvent;
import et.restlink.ussdgw.events.PullHttpEvent;
import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.service.AsPullClient;
import et.restlink.ussdgw.service.AsPullState;
import et.restlink.ussdgw.service.AsPullTarget;
import et.restlink.ussdgw.service.GatedAsNotifyService;
import et.restlink.ussdgw.service.SbbServices;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ra.httpclient.command.HttpCallbackCommand;
import com.microjainslee.ra.httpclient.events.HttpCallbackCompletedEvent;

import java.util.Optional;

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
        if (event instanceof GatedAsNotifyEvent gated) {
            SleeEventTrace.inSbb("HttpClientSbb", event, "gated-url=" + gated.asUrl());
            String detail;
            try { detail = sendGatedNotify(gated); }
            catch (Throwable t) {
                detail = "error=" + t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
                org.apache.logging.log4j.LogManager.getLogger(HttpClientSbb.class)
                        .error("HttpClientSbb sendGatedNotify failed url={}", gated.asUrl(), t);
            }
            SleeEventTrace.outSbb("HttpClientSbb", event, detail);
            return;
        }
        if (event instanceof PullHttpEvent pull) {
            SleeEventTrace.inSbb("HttpClientSbb", event, "url=" + pull.asUrl());
            String detail;
            try { detail = sendPull(pull); }
            catch (Throwable t) {
                detail = "error=" + t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
                org.apache.logging.log4j.LogManager.getLogger(HttpClientSbb.class)
                        .error("HttpClientSbb sendPull failed url={}", pull.asUrl(), t);
            }
            SleeEventTrace.outSbb("HttpClientSbb", event, detail);
            return;
        }
        if (event instanceof HttpCallbackCompletedEvent done) {
            SleeEventTrace.inSbb("HttpClientSbb", event, "status=" + done.getStatusCode());
            String detail;
            try { detail = onCompleted(done); }
            catch (Throwable t) {
                detail = "error=" + t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
                org.apache.logging.log4j.LogManager.getLogger(HttpClientSbb.class)
                        .error("HttpClientSbb onCompleted failed status={}", done.getStatusCode(), t);
            }
            SleeEventTrace.outSbb("HttpClientSbb", event, detail);
        }
    }

    /**
     * Fire-and-forget classic XML POST to AS asUrl. Uses {@code gated-{corr}} as the
     * HTTP client session id so completion never merges into an in-flight pull.
     */
    private String sendGatedNotify(GatedAsNotifyEvent gated) {
        if (gated == null || !gated.isValid()) {
            return "invalid";
        }
        String corr = gated.meta().correlationId();
        String sessionId = GatedAsNotifyEvent.httpSessionId(corr);
        AsPullClient.Admit admit = svc().asPull().tryAdmit(gated.asUrl());
        if (!admit.allow()) {
            return "circuit-open corr=" + corr;
        }
        RaCommandPort port = httpClient;
        if (port == null) {
            svc().asPull().recordFailure(gated.asUrl());
            return "no-ra";
        }
        AsHttpWireFormat format = GatedAsNotifyService.wireFormat();
        try {
            port.sendCommand(new HttpCallbackCommand.JsonPostRequest(
                    sessionId, gated.asUrl(), gated.xmlBody(), format.contentType()));
        } catch (RuntimeException e) {
            svc().asPull().recordFailure(gated.asUrl());
            throw e;
        }
        return "gated-submitted corr=" + corr + " reason=" + gated.meta().gateReason();
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
            svc().asPull().recordFailure(pull.asUrl());
            svc().saga().onAsPullFailed(corr, "AS_SUBMIT_" + e.getClass().getSimpleName());
            throw e;
        }
        return "submitted corr=" + corr + " wire=" + format;
    }

    private String onCompleted(HttpCallbackCompletedEvent done) {
        String corr = done.getSessionId();
        // Gated AS notify completions are advisory — never drive bridge/saga.
        if (corr != null && corr.startsWith("gated-")) {
            int status = done.getStatusCode();
            String err = done.getErrorMessage();
            boolean ok = (err == null || err.isBlank()) && status > 0 && status < 400;
            String realCorr = corr.substring("gated-".length());
            writeGatedAsCdr(realCorr, ok, status, err);
            if (ok) {
                return "gated-ack status=" + status;
            }
            return "gated-fail status=" + status
                    + (err == null || err.isBlank() ? "" : " err=" + err);
        }
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
        Optional<VirtualSession> sess = svc().store().get(corr);
        String shortCode = sess.map(VirtualSession::shortCode).orElse(null);
        int networkId = sess.map(VirtualSession::networkId).orElse(0);
        String tenantId = sess.map(VirtualSession::tenantId).orElse(null);
        String url = target == null ? null : target.url();
        // Response Content-Type is not on HttpCallbackCompletedEvent — log request CT when known.
        String contentType = target == null ? null : target.format().contentType();

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
            String body = done.getResponseBody();
            int bodyLen = body == null ? 0 : body.length();
            svc().asPull().logHttpPullComplete(corr, url, shortCode, networkId, tenantId,
                    status, bodyLen, body == null || body.isBlank(), contentType, reason);
            svc().saga().onAsPullFailed(corr, reason);
            return "fail " + (err == null ? status : err)
                    + " corr=" + corr + (url == null ? "" : " url=" + url);
        }

        String body = done.getResponseBody();
        int bodyLen = body == null ? 0 : body.length();
        if (body == null || body.isBlank()) {
            svc().asPullState().close(corr);
            if (target != null) {
                svc().asPull().recordFailure(target.url());
            }
            svc().asPull().logHttpPullComplete(corr, url, shortCode, networkId, tenantId,
                    status, bodyLen, true, contentType, "AS_EMPTY_BODY");
            svc().saga().onAsPullFailed(corr, "AS_EMPTY_BODY");
            return "empty-body corr=" + corr
                    + (url == null ? "" : " url=" + url)
                    + (shortCode == null || shortCode.isBlank() ? "" : " sc=" + shortCode);
        }
        svc().asPullState().close(corr);
        if (target != null) {
            svc().asPull().recordSuccess(target.url());
        }
        svc().asPull().logHttpPullComplete(corr, url, shortCode, networkId, tenantId,
                status, bodyLen, false, contentType, "ok");
        AsResponse resp = svc().wireFacade().decodePullResponse(body, format, corr);
        int wireGen = resp.generation();
        // Any wire: XML hardcodes gen=1; JSON may echo 1 / omit (0). Digit → session ≥2.
        if (sess.isPresent()) {
            resp = resp.stampedToSessionGeneration(sess.get().generation());
        }
        // EWMA via bridge.onAsResponse(latency) only — avoid double-sample
        svc().bridge().onAsResponse(resp, latency);
        String action = resp.action() == null ? "?" : resp.action().name();
        String asSnip = et.restlink.ussdgw.cdr.CdrUssdSnippet.of(resp.text(), 24);
        return "ok latencyMs=" + latency + " wire=" + format
                + " status=" + status + " bodyLen=" + bodyLen
                + " wireGen=" + wireGen + " gen=" + resp.generation()
                + " asAction=" + action
                + (asSnip.isEmpty() ? "" : " asUssd=" + asSnip);
    }

    /** POST raw AS pull body with format Content-Type (XML or JSON). */
    private static void submitPost(RaCommandPort port, String corr, AsPullTarget.Http target) {
        port.sendCommand(new HttpCallbackCommand.JsonPostRequest(
                corr, target.url(), target.body(), target.format().contentType()));
    }

    /** Advisory CDR for gated-{corr} HTTP completion (does not touch bridge/saga). */
    private void writeGatedAsCdr(String correlationId, boolean ok, int httpStatus, String err) {
        if (correlationId == null || correlationId.isBlank()) {
            return;
        }
        try {
            Optional<VirtualSession> sess = svc().store().get(correlationId);
            String msisdn = sess.map(VirtualSession::msisdn).orElse(null);
            String shortCode = sess.map(VirtualSession::shortCode).orElse(null);
            int networkId = sess.map(VirtualSession::networkId).orElse(0);
            String tenantId = sess.map(VirtualSession::tenantId).orElse(null);
            String origin = sess.map(s -> s.originationType() == null
                    ? "MAP" : s.originationType().name()).orElse("MAP");
            Long gate = sess.filter(s -> s.gateMs() > 0).map(VirtualSession::gateMs).orElse(null);
            Long ewma = null;
            try {
                double v = svc().adaptive().observedLatencyMs(networkId);
                if (v > 0d) {
                    ewma = Math.round(v);
                }
            } catch (Throwable ignored) { }
            String detail = ok
                    ? ("service=HttpClientSbb|gated-ack|http=" + httpStatus)
                    : ("service=HttpClientSbb|gated-fail|http=" + httpStatus
                            + (err == null || err.isBlank() ? "" : "|err=" + err));
            svc().cdr().write(correlationId,
                    ok ? CdrPhase.S1_RELEASED : CdrPhase.FAILED,
                    msisdn, shortCode,
                    ok ? CdrStatuses.GATED_AS_ACK : CdrStatuses.GATED_AS_FAIL,
                    detail, networkId, tenantId, origin, gate, ewma);
        } catch (Throwable ignored) { }
    }
}
