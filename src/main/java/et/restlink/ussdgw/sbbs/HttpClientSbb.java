package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.events.PullHttpEvent;
import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.service.AsPullClient;
import et.restlink.ussdgw.service.SbbServices;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ra.httpclient.command.HttpCallbackCommand;
import com.microjainslee.ra.httpclient.events.HttpCallbackCompletedEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** HTTP pull toward AS — request/response via RA JsonPost (raw body, no poll). */
public final class HttpClientSbb implements Sbb, SleeEventHandler {
    private final SbbServices services;
    private final Map<String, Long> startedAt = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private final Map<String, String> urlByCorr = new ConcurrentHashMap<>();
    private final Map<String, String> payloadByCorr = new ConcurrentHashMap<>();
    private final Map<String, AsHttpWireFormat> formatByCorr = new ConcurrentHashMap<>();

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
        String tenantId = svc().store().get(corr).map(VirtualSession::tenantId).orElse(null);
        AsHttpWireFormat format = svc().wireFormatResolver().resolve(tenantId);
        String payload = svc().wireFacade().encodePullRequest(req, format);
        startedAt.put(corr, System.currentTimeMillis());
        attempts.put(corr, new AtomicInteger(0));
        urlByCorr.put(corr, pull.asUrl());
        payloadByCorr.put(corr, payload);
        formatByCorr.put(corr, format);
        RaCommandPort port = httpClient;
        if (port == null) {
            svc().asPull().recordFailure(pull.asUrl());
            svc().saga().onAsPullFailed(corr, "NO_HTTP_RA");
            return "no-ra";
        }
        submitPost(port, corr, pull.asUrl(), payload, format);
        return "submitted corr=" + corr + " wire=" + format;
    }

    private String onCompleted(HttpCallbackCompletedEvent done) {
        String corr = done.getSessionId();
        Long start = startedAt.get(corr);
        long latency = start == null ? -1 : System.currentTimeMillis() - start;
        String url = urlByCorr.getOrDefault(corr, "");
        AsHttpWireFormat format = formatByCorr.getOrDefault(corr, AsHttpWireFormat.XML);
        int status = done.getStatusCode();
        String err = done.getErrorMessage();
        AtomicInteger att = attempts.getOrDefault(corr, new AtomicInteger(0));
        int attempt = att.get();

        boolean transportFail = (err != null && !err.isBlank() && status <= 0);
        boolean httpFail = status >= 400;
        if (transportFail || httpFail) {
            if (svc().asPull().shouldRetry(url, attempt, status, err)) {
                att.incrementAndGet();
                String payload = payloadByCorr.get(corr);
                RaCommandPort port = httpClient;
                if (port != null && payload != null) {
                    startedAt.put(corr, System.currentTimeMillis());
                    submitPost(port, corr, url, payload, format);
                    return "retry attempt=" + att.get() + " corr=" + corr;
                }
            }
            clear(corr);
            svc().asPull().recordFailure(url);
            svc().saga().onAsPullFailed(corr, transportFail ? "AS_TRANSPORT" : "AS_HTTP_" + status);
            return "fail " + (err == null ? status : err);
        }

        String body = done.getResponseBody();
        if (body == null || body.isBlank()) {
            clear(corr);
            svc().asPull().recordFailure(url);
            svc().saga().onAsPullFailed(corr, "AS_EMPTY_BODY");
            return "empty-body";
        }
        clear(corr);
        svc().asPull().recordSuccess(url);
        AsResponse resp = svc().wireFacade().decodePullResponse(body, format, corr);
        // EWMA via bridge.onAsResponse(latency) only — avoid double-sample
        svc().bridge().onAsResponse(resp, latency);
        return "ok latencyMs=" + latency + " wire=" + format;
    }

    /** POST raw AS pull body with format Content-Type (XML or JSON). */
    private static void submitPost(RaCommandPort port, String corr, String url,
                                   String payload, AsHttpWireFormat format) {
        AsHttpWireFormat fmt = format == null ? AsHttpWireFormat.XML : format;
        port.sendCommand(new HttpCallbackCommand.JsonPostRequest(
                corr, url, payload, fmt.contentType()));
    }

    private void clear(String corr) {
        startedAt.remove(corr);
        attempts.remove(corr);
        urlByCorr.remove(corr);
        payloadByCorr.remove(corr);
        formatByCorr.remove(corr);
    }
}
