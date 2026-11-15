package et.restlink.ussdgw.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ra.httpclient.command.HttpCallbackCommand;
import com.microjainslee.ra.httpclient.cookie.CookieJar;
import com.microjainslee.ra.httpclient.events.HttpCallbackCompletedEvent;
import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrStatuses;
import et.restlink.ussdgw.cdr.CdrUssdSnippet;
import et.restlink.ussdgw.events.GatedAsNotifyEvent;
import et.restlink.ussdgw.events.PullHttpEvent;
import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.service.AsPullClient;
import et.restlink.ussdgw.service.AsPullState;
import et.restlink.ussdgw.service.AsPullTarget;
import et.restlink.ussdgw.service.GatedAsNotifyService;
import et.restlink.ussdgw.service.SbbServices;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Talks HTTP to the AS (the application server that owns the USSD menu).
 *
 * <h2>What it does</h2>
 * One USSD turn = one HTTP POST. The flow is:
 * <ol>
 *   <li>{@link PullHttpEvent} arrives (the subscriber dialed or pressed a digit) →
 *       {@link #sendPull} encodes the request and asks the HTTP client RA to POST it.</li>
 *   <li>The RA answers later with {@link HttpCallbackCompletedEvent} →
 *       {@link #onAsAnswered} decodes the AS reply and hands it to the virtual-session bridge,
 *       which sends the menu text to the handset.</li>
 * </ol>
 * There is also a one-way POST, {@link GatedAsNotifyEvent}: when the AS answered too late and the
 * subscriber already got the "please wait" message, the AS is told so (the classic gated push).
 *
 * <h2>Keeping the AS session (JSESSIONID)</h2>
 * Like the classic gateway, every POST of the same USSD dialog carries the cookies the AS set on
 * the previous answer, so a servlet AS finds its {@code HttpSession} on every menu turn — and no
 * other dialog ever sees them. See {@link #requestHeaders} and {@link #rememberAsCookies}.
 *
 * <h2>Why there are no fields with per-call state</h2>
 * The POST and its answer are delivered to different pooled instances of this SBB, so a field
 * set in {@link #sendPull} would not be there in {@link #onAsAnswered}. Everything about an
 * in-flight POST lives in {@code AsPullStateRegistry} ({@code svc().asPullState()}).
 *
 * <h2>Rule for every handler</h2>
 * Nothing may escape a SLEE event handler, and losing a cookie, a CDR line or a session lookup
 * must never lose the AS answer. Every side step below is guarded on its own.
 */
public final class HttpClientSbb implements Sbb, SleeEventHandler {
    private static final Logger LOG = LogManager.getLogger(HttpClientSbb.class);
    private static final String NAME = "HttpClientSbb";

    /** HTTP session id prefix of the one-way gated notify; the rest is the correlation id. */
    private static final String GATED_PREFIX = "gated-";

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

    // ------------------------------------------------------------------ event entry point

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (event instanceof PullHttpEvent pull) {
            handle(event, "url=" + pull.asUrl(), () -> sendPull(pull));
        } else if (event instanceof HttpCallbackCompletedEvent done) {
            handle(event, "status=" + done.getStatusCode(), () -> onHttpCompleted(done));
        } else if (event instanceof GatedAsNotifyEvent gated) {
            handle(event, "gated-url=" + gated.asUrl(), () -> sendGatedNotify(gated));
        }
    }

    /** Trace in, run, trace out with the result; an exception becomes the traced result. */
    private static void handle(SleeEvent event, String inDetail, Supplier<String> work) {
        SleeEventTrace.inSbb(NAME, event, inDetail);
        String outDetail;
        try {
            outDetail = work.get();
        } catch (Throwable t) {
            outDetail = "error=" + t.getClass().getSimpleName() + ":" + t.getMessage();
            LOG.error("{} failed on {} ({})", NAME, event.getClass().getSimpleName(), inDetail, t);
        }
        SleeEventTrace.outSbb(NAME, event, outDetail);
    }

    // ------------------------------------------------------------------ 1. send one turn to the AS

    private String sendPull(PullHttpEvent pull) {
        AsRequest request = pull.request();
        String corr = request.correlationId();
        String url = pull.asUrl();

        // Circuit breaker: an AS that keeps failing is not hammered; the subscriber gets the
        // saga's failure text right away instead of waiting for a timeout.
        AsPullClient.Admit admit = svc().asPull().tryAdmit(url);
        if (!admit.allow()) {
            svc().saga().onAsPullFailed(corr, admit.reason());
            return "circuit-open corr=" + corr;
        }
        // Check the RA before registering anything, so a missing RA leaves nothing behind.
        RaCommandPort ra = httpClient;
        if (ra == null) {
            svc().asPull().recordFailure(url);
            svc().saga().onAsPullFailed(corr, "NO_HTTP_RA");
            return "no-ra";
        }

        // One session read serves the wire format, the turn number and the bridge headers.
        Optional<VirtualSession> session = findSession(corr);
        AsHttpWireFormat format = svc().wireFormatResolver()
                .resolve(session.map(VirtualSession::tenantId).orElse(null));
        String body = svc().wireFacade().encodePullRequest(request, format);
        AsPullTarget.Http target = new AsPullTarget.Http(url, body, format);

        // Remember the turn number at send time: a slow answer to an old turn must lose to the
        // current turn, never be shown as the answer to the next one.
        int turnAtSend = session.map(VirtualSession::generation).orElse(0);
        if (svc().asPullState().open(corr, target, System.currentTimeMillis(), turnAtSend).isEmpty()) {
            svc().asPull().recordFailure(url);
            svc().saga().onAsPullFailed(corr, "AS_PULL_STATE_SATURATED");
            return "state-saturated corr=" + corr;
        }
        try {
            post(ra, corr, target, session);
        } catch (RuntimeException e) {
            svc().asPullState().close(corr);
            svc().asPull().recordFailure(url);
            svc().saga().onAsPullFailed(corr, "AS_SUBMIT_" + e.getClass().getSimpleName());
            throw e;
        }
        return "submitted corr=" + corr + " wire=" + format;
    }

    /** One-way POST telling the AS its late answer was not shown (classic gated push). */
    private String sendGatedNotify(GatedAsNotifyEvent gated) {
        if (gated == null || !gated.isValid()) {
            return "invalid";
        }
        String corr = gated.meta().correlationId();
        String url = gated.asUrl();
        if (!svc().asPull().tryAdmit(url).allow()) {
            return "circuit-open corr=" + corr;
        }
        RaCommandPort ra = httpClient;
        if (ra == null) {
            svc().asPull().recordFailure(url);
            return "no-ra";
        }
        // Its own HTTP session id, so this answer never mixes with an in-flight pull.
        String httpSessionId = GatedAsNotifyEvent.httpSessionId(corr);
        AsHttpWireFormat format = GatedAsNotifyService.wireFormat();
        try {
            ra.sendCommand(new HttpCallbackCommand.PostRequest(httpSessionId, url, gated.xmlBody(),
                    format.contentType(), requestHeaders(corr, format, findSession(corr))));
        } catch (RuntimeException e) {
            svc().asPull().recordFailure(url);
            throw e;
        }
        return "gated-submitted corr=" + corr + " reason=" + gated.meta().gateReason();
    }

    private void post(RaCommandPort ra, String corr, AsPullTarget.Http target,
                      Optional<VirtualSession> session) {
        ra.sendCommand(new HttpCallbackCommand.PostRequest(corr, target.url(), target.body(),
                target.format().contentType(), requestHeaders(corr, target.format(), session)));
    }

    /**
     * The headers the classic gateway sent on every AS POST:
     * <ul>
     *   <li>{@code Accept}: {@code text/xml} for the classic wire, JSON otherwise;</li>
     *   <li>{@code Cookie}: this dialog's cookies (JSESSIONID…), so the AS finds its session;</li>
     *   <li>{@code X-Ussd-Correlation-Id} / {@code X-Ussd-Request-Id} when the HTTP client bridge
     *       is on (classic SessionBridgeSupport).</li>
     * </ul>
     */
    private Map<String, String> requestHeaders(String corr, AsHttpWireFormat format,
                                               Optional<VirtualSession> session) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", format == AsHttpWireFormat.JSON ? "application/json" : "text/xml");
        String cookie = storedCookie(corr);
        if (cookie != null && !cookie.isBlank()) {
            headers.put("Cookie", cookie);
        }
        if (corr != null && bridgeHeadersEnabled()) {
            String requestId = session.map(VirtualSession::requestId)
                    .filter(id -> !id.isBlank()).orElse(corr);
            headers.put("X-Ussd-Correlation-Id", corr);
            headers.put("X-Ussd-Request-Id", requestId);
        }
        return headers;
    }

    // ------------------------------------------------------------------ 2. the AS answered

    private String onHttpCompleted(HttpCallbackCompletedEvent done) {
        String httpSessionId = done.getSessionId();
        rememberAsCookies(httpSessionId, done);
        if (httpSessionId != null && httpSessionId.startsWith(GATED_PREFIX)) {
            return onGatedNotifyAnswered(httpSessionId.substring(GATED_PREFIX.length()), done);
        }
        return onAsAnswered(httpSessionId, done);
    }

    /** The answer to one USSD turn: failure → retry or give up; success → bridge → handset. */
    private String onAsAnswered(String corr, HttpCallbackCompletedEvent done) {
        Turn turn = new Turn(corr, svc().asPullState().peek(corr).orElse(null), findSession(corr));
        int status = done.getStatusCode();
        String error = done.getErrorMessage();
        String body = done.getResponseBody();

        boolean transportFailed = error != null && !error.isBlank() && status <= 0;
        if (transportFailed || status >= 400) {
            return onAsFailed(turn, status, error, body, transportFailed ? "AS_TRANSPORT" : "AS_HTTP_" + status);
        }
        if (body == null || body.isBlank()) {
            svc().asPullState().close(corr);
            if (turn.target != null) {
                svc().asPull().recordFailure(turn.target.url());
            }
            logCompletion(turn, status, body, "AS_EMPTY_BODY");
            svc().saga().onAsPullFailed(corr, "AS_EMPTY_BODY");
            return "empty-body corr=" + corr + turn.urlSuffix()
                    + (turn.shortCode() == null || turn.shortCode().isBlank() ? "" : " sc=" + turn.shortCode());
        }

        svc().asPullState().close(corr);
        if (turn.target != null) {
            svc().asPull().recordSuccess(turn.target.url());
        }
        logCompletion(turn, status, body, "ok");
        AsResponse response = svc().wireFacade().decodePullResponse(body, turn.format(), corr);
        int wireTurn = response.generation();
        response = stampTurn(response, turn);
        // The bridge also feeds AdaptiveTimeout with this latency — do not sample it twice.
        svc().bridge().onAsResponse(response, turn.latencyMs);

        String asText = CdrUssdSnippet.of(response.text(), 24);
        return "ok latencyMs=" + turn.latencyMs + " wire=" + turn.format()
                + " status=" + status + " bodyLen=" + (body == null ? 0 : body.length())
                + " wireGen=" + wireTurn + " gen=" + response.generation()
                + " asAction=" + (response.action() == null ? "?" : response.action().name())
                + (asText.isEmpty() ? "" : " asUssd=" + asText);
    }

    /** HTTP error or no connection: retry when the policy allows, else end the turn as failed. */
    private String onAsFailed(Turn turn, int status, String error, String body, String reason) {
        String corr = turn.corr;
        if (turn.target != null) {
            RaCommandPort ra = httpClient;
            if (ra != null && svc().asPull().shouldRetry(turn.target.url(), turn.state.attempt(), status, error)) {
                AsPullState retry = svc().asPullState().beginRetry(corr, System.currentTimeMillis()).orElse(null);
                if (retry != null) {
                    post(ra, corr, turn.target, turn.session);
                    return "retry attempt=" + retry.attempt() + " corr=" + corr;
                }
            }
            svc().asPull().recordFailure(turn.target.url());
        }
        // Close in every case — a non-HTTP or swept entry must not stay behind as an orphan.
        svc().asPullState().close(corr);
        logCompletion(turn, status, body, reason);
        svc().saga().onAsPullFailed(corr, reason);
        return "fail " + (error == null ? status : error) + " corr=" + corr + turn.urlSuffix();
    }

    /**
     * Which turn does this answer belong to? An AS that numbers its turns (wire generation &gt; 1)
     * is trusted. Classic XML always says 1 and JSON may say nothing, so those answers are stamped
     * with the turn that was current when the POST was sent.
     */
    private static AsResponse stampTurn(AsResponse response, Turn turn) {
        if (response.generation() > 1) {
            return response;
        }
        int turnAtSend = turn.state == null ? 0 : turn.state.generation();
        int stamp = turnAtSend > 0 ? turnAtSend : turn.session.map(VirtualSession::generation).orElse(0);
        return stamp > 0 ? response.stampedToSessionGeneration(stamp) : response;
    }

    private void logCompletion(Turn turn, int status, String body, String outcome) {
        svc().asPull().logHttpPullComplete(turn.corr, turn.url(), turn.shortCode(), turn.networkId(),
                turn.tenantId(), status, body == null ? 0 : body.length(), body == null || body.isBlank(),
                turn.target == null ? null : turn.target.format().contentType(), outcome);
    }

    /** The gated notify is advisory: it is written to the CDR and never touches the dialog. */
    private String onGatedNotifyAnswered(String corr, HttpCallbackCompletedEvent done) {
        int status = done.getStatusCode();
        String error = done.getErrorMessage();
        boolean ok = (error == null || error.isBlank()) && status > 0 && status < 400;
        writeGatedNotifyCdr(corr, ok, status, error);
        return ok ? "gated-ack status=" + status
                : "gated-fail status=" + status + (error == null || error.isBlank() ? "" : " err=" + error);
    }

    /**
     * Everything known about the turn an answer belongs to. {@code state} is null when the entry
     * was already swept; then there is no latency (the bridge uses its own clock) and no URL (a
     * made-up one would merge every AS onto one circuit breaker).
     */
    private static final class Turn {
        final String corr;
        final AsPullState state;
        final AsPullTarget.Http target;
        final Optional<VirtualSession> session;
        final long latencyMs;

        Turn(String corr, AsPullState state, Optional<VirtualSession> session) {
            this.corr = corr;
            this.state = state;
            this.target = state != null && state.target() instanceof AsPullTarget.Http h ? h : null;
            this.session = session;
            this.latencyMs = state == null ? -1L : state.latencyMsAt(System.currentTimeMillis());
        }

        AsHttpWireFormat format() { return target == null ? AsHttpWireFormat.XML : target.format(); }
        String url() { return target == null ? null : target.url(); }
        String urlSuffix() { return target == null ? "" : " url=" + target.url(); }
        String shortCode() { return session.map(VirtualSession::shortCode).orElse(null); }
        int networkId() { return session.map(VirtualSession::networkId).orElse(0); }
        String tenantId() { return session.map(VirtualSession::tenantId).orElse(null); }
    }

    // ------------------------------------------------------------------ guarded side steps

    /** Session lookup that can never cost us the AS answer. */
    private Optional<VirtualSession> findSession(String corr) {
        try {
            var store = svc().store();
            return store == null ? Optional.empty() : store.get(corr);
        } catch (RuntimeException e) {
            LOG.warn("{} session lookup failed corr={}: {}", NAME, corr, e.toString());
            return Optional.empty();
        }
    }

    private String storedCookie(String corr) {
        try {
            var store = svc().store();
            return store == null ? null : store.asCookie(corr);
        } catch (RuntimeException e) {
            return null;   // no store (unit stub): send without a cookie
        }
    }

    private boolean bridgeHeadersEnabled() {
        try {
            return svc().config() != null && svc().config().httpClientBridgeEnabled();
        } catch (RuntimeException e) {
            return false;  // classic default: no correlation headers
        }
    }

    /**
     * Add the AS's {@code Set-Cookie} to this dialog's cookies ({@code ussdTx.asCookie}). The
     * gated notify ({@code gated-<corr>}) belongs to the same dialog.
     */
    private void rememberAsCookies(String httpSessionId, HttpCallbackCompletedEvent done) {
        try {
            if (httpSessionId == null || done == null) {
                return;
            }
            List<String> setCookies = done.getHeaderValues("Set-Cookie");
            var store = svc().store();
            if (setCookies.isEmpty() || store == null) {
                return;
            }
            String corr = httpSessionId.startsWith(GATED_PREFIX)
                    ? httpSessionId.substring(GATED_PREFIX.length()) : httpSessionId;
            store.setAsCookie(corr, CookieJar.merge(store.asCookie(corr), setCookies));
        } catch (RuntimeException e) {
            LOG.debug("AS cookie not stored session={}: {}", httpSessionId, e.toString());
        }
    }

    private void writeGatedNotifyCdr(String corr, boolean ok, int httpStatus, String error) {
        if (corr == null || corr.isBlank()) {
            return;
        }
        try {
            Optional<VirtualSession> s = findSession(corr);
            int networkId = s.map(VirtualSession::networkId).orElse(0);
            Long ewma = null;
            try {
                double observed = svc().adaptive().observedLatencyMs(networkId);
                if (observed > 0d) {
                    ewma = Math.round(observed);
                }
            } catch (Throwable ignored) { }
            String detail = "service=" + NAME + "|" + (ok ? "gated-ack" : "gated-fail") + "|http=" + httpStatus
                    + (ok || error == null || error.isBlank() ? "" : "|err=" + error);
            svc().cdr().write(corr,
                    ok ? CdrPhase.S1_RELEASED : CdrPhase.FAILED,
                    s.map(VirtualSession::msisdn).orElse(null),
                    s.map(VirtualSession::shortCode).orElse(null),
                    ok ? CdrStatuses.GATED_AS_ACK : CdrStatuses.GATED_AS_FAIL,
                    detail, networkId,
                    s.map(VirtualSession::tenantId).orElse(null),
                    s.map(v -> v.originationType() == null ? "MAP" : v.originationType().name()).orElse("MAP"),
                    s.filter(v -> v.gateMs() > 0).map(VirtualSession::gateMs).orElse(null),
                    ewma);
        } catch (Throwable ignored) { }
    }
}
