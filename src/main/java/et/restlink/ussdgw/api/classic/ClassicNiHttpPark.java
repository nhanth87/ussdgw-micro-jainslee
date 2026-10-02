package et.restlink.ussdgw.api.classic;

import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.GatedSessionMeta;
import et.restlink.ussdgw.bridge.GatedSessionRegistry;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionState;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.service.GatedAsNotifyService;
import et.restlink.ussdgw.service.MapDialogHelper;

import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.httpserver.command.HttpServerCommand;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
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
        /**
         * Outstanding MAP op awaits UE input (Request menu), not a one-shot Notify.
         * The park budget is then the UI timeout (P1-1), not the AS ceiling.
         */
        private volatile boolean requestUi;
        /** Wins exactly one HTTP reply (completeParked vs adaptive gate). */
        private final AtomicBoolean settled = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> gateFuture;
        /** Adaptive gate delay that was scheduled for this park (ms). */
        private volatile long appliedGateMs;

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
        public boolean requestUi() { return requestUi; }
        public void setRequestUi(boolean requestUi) { this.requestUi = requestUi; }
        public String jsessionId() { return jsessionId; }
        public String correlationId() { return correlationId; }
        public AsHttpWireFormat format() { return format; }
        public int networkId() { return networkId; }
        public long parkedAtMs() { return parkedAtMs; }
        public boolean emptyHandshake() { return emptyHandshake; }
        public boolean expired() { return settled.get(); }
        public long appliedGateMs() { return appliedGateMs; }
        /** @return true when this caller owns the single HTTP reply */
        boolean trySettle() { return settled.compareAndSet(false, true); }
    }

    @Inject AdaptiveTimeout adaptive;
    @Inject UssdConfigService config;
    @Inject AsWireFacade wireFacade;
    @Inject CdrService cdr;
    @Inject VirtualSessionStore store;
    @Inject GatedSessionRegistry gatedSessions;
    @Inject GatedAsNotifyService gatedAsNotify;

    private final ConcurrentHashMap<String, ParkRecord> byJsession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ParkRecord> byCorr = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "classic-ni-http-park");
        t.setDaemon(true);
        return t;
    });

    private volatile Supplier<RaCommandPort> httpSupplier = () -> null;
    private volatile Supplier<RaCommandPort> ss7Supplier = () -> null;

    /**
     * Park retention: interactive menus re-park on every AS hop (fresh
     * {@code parkedAtMs}), so anything older never progresses. Well above the NI
     * Request UI timeout (P1-1, step 4); session-gone records go regardless of age.
     */
    public static final long PARK_TTL_MS = 600_000L;

    @PostConstruct
    void armOnBoot() {
        // Executor is created at field init — log so Digicom restart proves NI AdaptiveTimeout
        // park is live without waiting for the first /ussd (no admin Start).
        LOG.info("ClassicNiHttpPark AdaptiveTimeout gate scheduler armed (thread=classic-ni-http-park)");
    }

    public void bindHttp(Supplier<RaCommandPort> supplier) {
        this.httpSupplier = supplier == null ? () -> null : supplier;
    }

    /** MAP port for closing the dialog when a park expires with a live leg (P1-1). */
    public void bindSs7(Supplier<RaCommandPort> supplier) {
        this.ss7Supplier = supplier == null ? () -> null : supplier;
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
     * and unpark. Stamps {@code gate_ms}/{@code observed_ewma_ms} onto CDR (GATED / GATE_EXPIRED).
     * Budget is per outstanding op (P1-1): Request awaiting UE input uses the UI
     * timeout ({@code ussd.ni.request-ui-timeout-ms}, clamped to the dialog timeout);
     * Notify / first-hop parks keep the configured async-gate ceiling (not EWMA×1.5).
     */
    public void scheduleAdaptiveGate(ParkRecord rec) {
        if (rec == null) {
            return;
        }
        long gateMs;
        String budget;
        if (rec.requestUi()) {
            gateMs = config.niRequestUiTimeoutMs();
            budget = "request-ui";
        } else {
            gateMs = adaptive.effectiveGateMs(
                    rec.networkId(),
                    config.asyncGateTimeoutMs(),
                    config.dialogTimeoutMs());
            budget = "ceiling";
        }
        rec.appliedGateMs = gateMs;
        stampSessionGate(rec, gateMs);
        scheduleGate(rec, gateMs);
        cdrWrite(rec, CdrPhase.S1_ACTIVE, et.restlink.ussdgw.cdr.CdrStatuses.GATE_ARMED,
                "service=ClassicNiHttpPark|AdaptiveTimeout|gateBudget=" + budget
                        + "|op=" + (rec.requestUi() ? "request" : "notify"));
    }

    public void scheduleGate(ParkRecord rec, long gateMs) {
        if (rec == null) {
            return;
        }
        cancelGate(rec);
        long delay = Math.max(1L, gateMs);
        if (rec.appliedGateMs <= 0) {
            rec.appliedGateMs = delay;
        }
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

    /**
     * Settle parked HTTP with a pre-encoded body (e.g. classic Notify_Response), cancel gate,
     * keep {@code JSESSIONID}→corr for AS END / continue. Wins the same CAS as {@link #completeParked}.
     */
    public boolean completeParkedEncoded(String correlationId, String encodedBody) {
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
        if (!rec.trySettle()) {
            return false;
        }
        rec.setHttpSessionId(null);
        String body = encodedBody == null ? "" : encodedBody;
        reply(rec, httpId, 200, body);
        return true;
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
        LOG.info("NI HTTP park gate expired corr={} jsession={} gateMs={}",
                correlationId, rec.jsessionId(), rec.appliedGateMs());
        // P2-2: side effects (CDR, MAP close, gated stamp, AS notify) must not prevent
        // the HTTP reply. Compute the body in a try block; always reply in finally.
        String body;
        try {
            cdrWrite(rec, CdrPhase.FAILED, "GATE_EXPIRED",
                    "service=ClassicNiHttpPark|AdaptiveTimeout");
            // P1-1: the MAP leg must not linger when the AS gives up. Win the terminal
            // transition first (a concurrent bridge claim owns the dialog otherwise),
            // then abort the live leg, drop the digit claim, and retire the session.
            // A late UE digit then gets a hard-fail, not a silent swallow (P1-2).
            closeLiveMapLeg(rec);
            GatedSessionMeta meta = buildGatedMeta(rec);
            if (gatedSessions != null) {
                gatedSessions.stamp(meta);
            }
            if (gatedAsNotify != null) {
                VirtualSession sess = null;
                if (store != null) {
                    try {
                        sess = store.get(rec.correlationId()).orElse(null);
                    } catch (RuntimeException ignored) {
                        // best-effort
                    }
                }
                try {
                    gatedAsNotify.pushToAs(meta, sess);
                } catch (RuntimeException e) {
                    LOG.warn("Gated AS XML push failed corr={}: {}", rec.correlationId(), e.toString());
                }
            }
            body = wireFacade.encodeNiGatedAbort(meta, rec.format());
        } catch (RuntimeException e) {
            LOG.warn("onGateExpired side-effect failed corr={}: {}; still replying to AS",
                    correlationId, e.toString());
            body = "";
        }
        // Keep JSESSIONID→corr mapping (like completeParked) so AS can re-push
        // with the same Cookie after learning prior session was gated.
        cancelGate(rec);
        rec.setHttpSessionId(null);
        reply(rec, httpId, 200, body);
    }

    /**
     * P1-1: close the MAP leg parked behind an expired NI HTTP wait. CAS-law — the
     * terminal transition is won before the MAP abort, so a concurrent bridge claim
     * (which owns the dialog after its CAS) never sees a second close.
     */
    private void closeLiveMapLeg(ParkRecord rec) {
        if (store == null || rec == null) {
            return;
        }
        VirtualSession sess;
        try {
            sess = store.get(rec.correlationId()).orElse(null);
        } catch (RuntimeException ignored) {
            return;
        }
        if (sess == null || !sess.dialogAlive()
                || sess.originationType() != OriginationType.MAP) {
            return;
        }
        boolean owned;
        try {
            owned = store.compareAndTransitionAny(rec.correlationId(),
                    List.of(
                            VirtualSessionState.ACTIVE,
                            VirtualSessionState.AWAITING_AS,
                            VirtualSessionState.RESPONDING,
                            VirtualSessionState.PUSH_PENDING,
                            VirtualSessionState.S1_RELEASED),
                    VirtualSessionState.COMPLETED).isPresent();
        } catch (RuntimeException e) {
            LOG.debug("NI park gate close skipped corr={}: {}",
                    rec.correlationId(), e.toString());
            return;
        }
        if (!owned) {
            return;
        }
        try {
            MapDialogHelper.abort(ss7(), sess.dialogId());
        } catch (RuntimeException e) {
            LOG.debug("NI park gate abort skipped corr={}: {}",
                    rec.correlationId(), e.toString());
        }
        try {
            store.setDialogAlive(rec.correlationId(), false);
            store.clearMsDigitClaim(rec.correlationId());
        } catch (RuntimeException ignored) {
            // terminal bookkeeping is best-effort; the MAP close above already ran
        }
    }

    private GatedSessionMeta buildGatedMeta(ParkRecord rec) {        String msisdn = null;
        String shortCode = null;
        String sessionId = null;
        int networkId = rec.networkId();
        long gateMs = rec.appliedGateMs() > 0 ? rec.appliedGateMs() : 0L;
        if (store != null) {
            try {
                VirtualSession s = store.get(rec.correlationId()).orElse(null);
                if (s != null) {
                    msisdn = s.msisdn();
                    shortCode = s.shortCode();
                    sessionId = s.virtualSessionId();
                    networkId = s.networkId();
                    if (gateMs <= 0 && s.gateMs() > 0) {
                        gateMs = s.gateMs();
                    }
                }
            } catch (RuntimeException ignored) {
                // best-effort enrich
            }
        }
        return GatedSessionMeta.niPark(
                rec.correlationId(), rec.jsessionId(), gateMs,
                observedEwmaMs(networkId), networkId, msisdn, shortCode, sessionId);
    }

    private void stampSessionGate(ParkRecord rec, long gateMs) {
        if (store == null || rec == null || gateMs <= 0) {
            return;
        }
        try {
            // CAS-law: caller-owned fields singly — never a detached full put over a
            // concurrent bridge CAS (state) or abort (dialogAlive).
            store.setGateMs(rec.correlationId(), gateMs);
            store.get(rec.correlationId()).ifPresent(s -> {
                if (s.pullStartedAtMs() <= 0) {
                    store.setPullStartedAtMs(rec.correlationId(), rec.parkedAtMs());
                }
            });
        } catch (RuntimeException e) {
            LOG.debug("NI park gate stamp skipped corr={}: {}", rec.correlationId(), e.toString());
        }
    }

    private void cdrWrite(ParkRecord rec, CdrPhase phase, String status, String detail) {
        if (cdr == null || rec == null) {
            return;
        }
        String msisdn = null;
        String shortCode = null;
        String tenantId = null;
        String origin = "MAP";
        int networkId = rec.networkId();
        Long gate = rec.appliedGateMs() > 0 ? rec.appliedGateMs() : null;
        if (store != null) {
            try {
                VirtualSession s = store.get(rec.correlationId()).orElse(null);
                if (s != null) {
                    msisdn = s.msisdn();
                    shortCode = s.shortCode();
                    tenantId = s.tenantId();
                    networkId = s.networkId();
                    origin = s.originationType() == null ? "MAP" : s.originationType().name();
                    if (gate == null && s.gateMs() > 0) {
                        gate = s.gateMs();
                    }
                }
            } catch (RuntimeException ignored) {
                // CDR enrichment is best-effort; park reply must still proceed.
            }
        }
        Long ewma = observedEwmaMs(networkId);
        cdr.write(rec.correlationId(), phase, msisdn, shortCode, status, detail,
                networkId, tenantId, origin, gate, ewma);
    }

    private Long observedEwmaMs(int networkId) {
        if (adaptive == null) {
            return null;
        }
        double v = adaptive.observedLatencyMs(networkId);
        return v > 0d ? Math.round(v) : null;
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

    private RaCommandPort ss7() {
        try {
            return ss7Supplier.get();
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

    /**
     * Drop parks that can never complete (P1-9): records older than
     * {@link #PARK_TTL_MS}, or whose ussdTx row is gone (terminal/removed —
     * Notify-only flows where the AS never sends END, gate-expired parks, TTL
     * reclaims). Runs periodically from {@code BridgeGateScheduler.reclaimExpiredTx}.
     *
     * @return number of records dropped
     */
    public int sweepStaleParked() {
        long now = System.currentTimeMillis();
        int dropped = 0;
        for (ParkRecord rec : byCorr.values().toArray(new ParkRecord[0])) {
            boolean stale = now - rec.parkedAtMs() > PARK_TTL_MS;
            if (!stale && store != null) {
                try {
                    stale = store.get(rec.correlationId()).isEmpty();
                } catch (RuntimeException ignored) {
                    // keep on read failure; age TTL still applies
                }
            }
            if (stale && unpark(rec.correlationId()).isPresent()) {
                dropped++;
            }
        }
        if (dropped > 0) {
            LOG.info("NI HTTP park swept {} stale records (parked={})", dropped, byCorr.size());
        }
        return dropped;
    }

    /**
     * Settle a parked AS HTTP immediately with an ABORT dialog (P1-9): on MAP
     * abort the parked request must be answered now, not after the 25s gate.
     * Drops the park (the session is terminal — a later AS END gets 404).
     *
     * @return true when a parked HTTP was settled
     */
    public boolean abortParked(String correlationId) {
        Optional<ParkRecord> opt = findByCorr(correlationId);
        if (opt.isEmpty()) {
            return false;
        }
        ParkRecord rec = opt.get();
        String httpId = rec.httpSessionId();
        if (httpId == null || httpId.isBlank()) {
            unpark(correlationId);
            return false;
        }
        if (!rec.trySettle()) {
            return false;
        }
        String body;
        try {
            body = wireFacade == null ? ""
                    : wireFacade.encodeNiResponse(rec.correlationId(), "", AsAction.ABORT,
                            false, rec.format());
        } catch (RuntimeException e) {
            LOG.debug("NI park abort encode skipped corr={}: {}", correlationId, e.toString());
            body = "";
        }
        cancelGate(rec);
        rec.setHttpSessionId(null);
        reply(rec, httpId, 200, body);
        unpark(correlationId);
        return true;
    }
}
