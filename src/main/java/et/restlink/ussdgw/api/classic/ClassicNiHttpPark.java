package et.restlink.ussdgw.api.classic;

import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.config.UssdConfigService;

import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.httpserver.command.HttpServerCommand;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Classic NI HTTP sync park registry: hold AS HTTP pending responses keyed by
 * {@code JSESSIONID} / correlation until MAP progress or AdaptiveTimeout gate expiry.
 * Never blocks the SBB thread with {@code Thread.sleep}.
 */
@ApplicationScoped
public class ClassicNiHttpPark {
    private static final Logger LOG = LogManager.getLogger(ClassicNiHttpPark.class);

    public static final class ParkRecord {
        private volatile String httpSessionId;
        private final String jsessionId;
        private final String correlationId;
        private final AsHttpWireFormat format;
        private final int networkId;
        private final long parkedAtMs;
        private final boolean emptyHandshake;
        /** Wins exactly one HTTP reply (completeParked vs adaptive gate). */
        private final AtomicBoolean settled = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> gateFuture;

        ParkRecord(String httpSessionId, String jsessionId, String correlationId,
                   AsHttpWireFormat format, int networkId, long parkedAtMs,
                   boolean emptyHandshake) {
            this.httpSessionId = httpSessionId;
            this.jsessionId = jsessionId;
            this.correlationId = correlationId;
            this.format = format == null ? AsHttpWireFormat.XML : format;
            this.networkId = networkId;
            this.parkedAtMs = parkedAtMs;
            this.emptyHandshake = emptyHandshake;
        }

        public String httpSessionId() { return httpSessionId; }
        public void setHttpSessionId(String httpSessionId) { this.httpSessionId = httpSessionId; }
        public String jsessionId() { return jsessionId; }
        public String correlationId() { return correlationId; }
        public AsHttpWireFormat format() { return format; }
        public int networkId() { return networkId; }
        public long parkedAtMs() { return parkedAtMs; }
        public boolean emptyHandshake() { return emptyHandshake; }
        public boolean expired() { return settled.get(); }
        /** @return true when this caller owns the single HTTP reply */
        boolean trySettle() { return settled.compareAndSet(false, true); }
    }

    @Inject AdaptiveTimeout adaptive;
    @Inject UssdConfigService config;
    @Inject AsWireFacade wireFacade;

    private final ConcurrentHashMap<String, ParkRecord> byJsession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ParkRecord> byCorr = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "classic-ni-http-park");
        t.setDaemon(true);
        return t;
    });

    private volatile Supplier<RaCommandPort> httpSupplier = () -> null;

    public void bindHttp(Supplier<RaCommandPort> supplier) {
        this.httpSupplier = supplier == null ? () -> null : supplier;
    }

    public ParkRecord park(String httpSessionId, String jsessionId, String correlationId,
                           AsHttpWireFormat format, int networkId, boolean emptyHandshake) {
        if (jsessionId == null || jsessionId.isBlank()
                || correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("jsessionId and correlationId required");
        }
        Optional<ParkRecord> prior = unpark(correlationId);
        prior.ifPresent(this::cancelGate);
        ParkRecord rec = new ParkRecord(
                httpSessionId, jsessionId.trim(), correlationId.trim(),
                format, networkId, System.currentTimeMillis(), emptyHandshake);
        byJsession.put(rec.jsessionId(), rec);
        byCorr.put(rec.correlationId(), rec);
        return rec;
    }

    public Optional<ParkRecord> findByJsession(String jsessionId) {
        if (jsessionId == null || jsessionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byJsession.get(jsessionId.trim()));
    }

    public Optional<ParkRecord> findByCorr(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byCorr.get(correlationId.trim()));
    }

    /** Remove park by correlation id (also drops jsession index). */
    public Optional<ParkRecord> unpark(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return Optional.empty();
        }
        ParkRecord rec = byCorr.remove(correlationId.trim());
        if (rec == null) {
            return Optional.empty();
        }
        byJsession.remove(rec.jsessionId(), rec);
        cancelGate(rec);
        return Optional.of(rec);
    }

    /**
     * Schedule AdaptiveTimeout gate; on fire, if still parked, reply ABORT dialog + Set-Cookie
     * and unpark.
     */
    public void scheduleAdaptiveGate(ParkRecord rec) {
        if (rec == null) {
            return;
        }
        long gateMs = adaptive.effectiveGateMs(
                rec.networkId(),
                config.asyncGateTimeoutMs(),
                config.dialogTimeoutMs());
        scheduleGate(rec, gateMs);
    }

    public void scheduleGate(ParkRecord rec, long gateMs) {
        if (rec == null) {
            return;
        }
        cancelGate(rec);
        long delay = Math.max(1L, gateMs);
        rec.gateFuture = scheduler.schedule(() -> onGateExpired(rec.correlationId()), delay, TimeUnit.MILLISECONDS);
    }

    /** Lab path: short async delay then {@link #completeParked} (no Thread.sleep on SBB thread). */
    public void scheduleLabEcho(String correlationId, String text, long delayMs) {
        long d = Math.max(1L, delayMs);
        scheduler.schedule(() -> completeParked(correlationId, text, AsAction.CONTINUE),
                d, TimeUnit.MILLISECONDS);
    }

    /**
     * Reply CONTINUE/END/ABORT to the parked HTTP session and clear the pending HTTP id,
     * but keep {@code JSESSIONID}→corr mapping for classic multi-request NI continues.
     * Use {@link #unpark} for final teardown (END / gate abort already replied).
     *
     * @return true if a parked HTTP was completed
     */
    public boolean completeParked(String correlationId, String text, AsAction action) {
        AsAction act = action == null ? AsAction.END : action;
        Optional<ParkRecord> opt = findByCorr(correlationId);
        if (opt.isEmpty()) {
            return false;
        }
        ParkRecord rec = opt.get();
        cancelGate(rec);
        String httpId = rec.httpSessionId();
        if (httpId == null || httpId.isBlank()) {
            return false;
        }
        // CAS before reply so a concurrent adaptive gate cannot also respond.
        if (!rec.trySettle()) {
            return false;
        }
        rec.setHttpSessionId(null);
        String body = wireFacade.encodeNiResponse(rec.correlationId(), text, act, false, rec.format());
        reply(rec, httpId, 200, body);
        return true;
    }

    public boolean completeParked(String correlationId, AsResponse response) {
        if (response == null) {
            return completeParked(correlationId, "", AsAction.END);
        }
        return completeParked(correlationId, response.text(), response.action());
    }

    public boolean isHttpNi(String correlationId) {
        return findByCorr(correlationId).isPresent();
    }

    private void onGateExpired(String correlationId) {
        Optional<ParkRecord> opt = findByCorr(correlationId);
        if (opt.isEmpty()) {
            return;
        }
        ParkRecord rec = opt.get();
        String httpId = rec.httpSessionId();
        if (httpId == null || httpId.isBlank()) {
            unpark(correlationId);
            return;
        }
        if (!rec.trySettle()) {
            // completeParked (or another gate tick) already owns the HTTP reply.
            return;
        }
        LOG.info("NI HTTP park gate expired corr={} jsession={}",
                correlationId, rec.jsessionId());
        String body = wireFacade.encodeNiResponse(
                rec.correlationId(), "", AsAction.ABORT, false, rec.format());
        if (unpark(correlationId).isPresent()) {
            reply(rec, httpId, 200, body);
        }
    }

    private void reply(ParkRecord rec, String httpSessionId, int status, String body) {
        RaCommandPort port = http();
        if (port == null) {
            LOG.warn("NI HTTP reply skipped (no http RA) corr={}", rec.correlationId());
            return;
        }
        String cookie = "JSESSIONID=" + rec.jsessionId() + "; Path=/; HttpOnly";
        port.sendCommand(new HttpServerCommand.HttpResponseExCommand(
                httpSessionId,
                status,
                rec.format().contentType(),
                body,
                null,
                Map.of("Set-Cookie", cookie)));
    }

    private RaCommandPort http() {
        try {
            return httpSupplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void cancelGate(ParkRecord rec) {
        if (rec == null) {
            return;
        }
        ScheduledFuture<?> f = rec.gateFuture;
        rec.gateFuture = null;
        if (f != null) {
            f.cancel(false);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
        byJsession.clear();
        byCorr.clear();
    }

    /** Test / admin visibility. */
    public int size() {
        return byCorr.size();
    }
}
