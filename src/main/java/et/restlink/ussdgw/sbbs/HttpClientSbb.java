package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.api.AsWireCodec;
import et.restlink.ussdgw.events.PullHttpEvent;
import et.restlink.ussdgw.logging.SleeEventTrace;
import et.restlink.ussdgw.service.SbbServices;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ra.httpclient.command.HttpCallbackCommand;
import com.microjainslee.ra.httpclient.events.HttpCallbackCompletedEvent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** HTTP pull toward AS — completion via RA callback event (no poll). */
public final class HttpClientSbb implements Sbb, SleeEventHandler {
    private final SbbServices services;
    private final Map<String, Long> startedAt = new ConcurrentHashMap<>();

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
        String payload = AsWireCodec.encodeRequestString(req);
        startedAt.put(req.correlationId(), System.currentTimeMillis());
        RaCommandPort port = httpClient;
        if (port == null) return "no-ra";
        port.sendCommand(new HttpCallbackCommand.CallbackRequest(
                req.correlationId(), pull.asUrl(), payload));
        return "submitted corr=" + req.correlationId();
    }

    private String onCompleted(HttpCallbackCompletedEvent done) {
        String corr = done.getSessionId();
        Long start = startedAt.remove(corr);
        long latency = start == null ? -1 : System.currentTimeMillis() - start;
        if (done.getErrorMessage() != null && !done.getErrorMessage().isBlank()
                && done.getStatusCode() <= 0) {
            return "fail " + done.getErrorMessage();
        }
        String body = done.getResponseBody();
        if (body == null || body.isBlank()) return "empty-body";
        AsResponse resp = AsWireCodec.decodeResponse(body, corr);
        svc().bridge().onAsResponse(resp, latency);
        return "ok latencyMs=" + latency;
    }
}
