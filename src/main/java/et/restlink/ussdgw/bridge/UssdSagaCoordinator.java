package et.restlink.ussdgw.bridge;

import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.service.MapDialogHelper;

import com.microjainslee.api.RaCommandPort;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Thin saga compensation around {@link VirtualSessionBridge}: NI fail / pull fail / abort
 * → FAILED + profile remove + MAP abort when dialog still alive.
 */
@ApplicationScoped
public class UssdSagaCoordinator {
    private static final Logger LOG = LogManager.getLogger(UssdSagaCoordinator.class);

    @Inject VirtualSessionStore store;
    @Inject VirtualSessionBridge bridge;
    @Inject AdaptiveTimeout adaptive;
    @Inject CdrService cdr;
    @Inject UssdConfigService config;

    private volatile Supplier<RaCommandPort> ss7Supplier = () -> null;
    private final AtomicLong niFailCount = new AtomicLong();
    private final AtomicLong pullFailCount = new AtomicLong();

    public void bindSs7(Supplier<RaCommandPort> supplier) {
        this.ss7Supplier = supplier == null ? () -> null : supplier;
        bridge.bindSs7(this.ss7Supplier);
    }

    /**
     * AS pull circuit-open / exhausted retries: end live MAP with wait/hard-fail message,
     * mark FAILED, drop profile.
     */
    public void onAsPullFailed(String correlationId, String reason) {
        pullFailCount.incrementAndGet();
        Optional<VirtualSession> opt = store.get(correlationId);
        if (opt.isEmpty()) {
            LOG.info("AS pull fail no-session corr={} reason={}", correlationId, reason);
            return;
        }
        compensate(opt.get(), reason == null ? "AS_PULL_FAIL" : reason, true);
    }

    /** NI push / SRI failure while PUSH_PENDING or mid-bridge. */
    public void onNiFailed(String correlationId, String reason) {
        niFailCount.incrementAndGet();
        Optional<VirtualSession> opt = store.get(correlationId);
        if (opt.isEmpty()) {
            cdr.write(correlationId, CdrPhase.FAILED, null, null,
                    reason == null ? "NI_FAIL" : reason,
                    "service=UssdSagaCoordinator");
            return;
        }
        compensate(opt.get(), reason == null ? "NI_FAIL" : reason, false);
    }

    private void compensate(VirtualSession s, String reason, boolean useWaitMessage) {
        String corr = s.correlationId();
        LOG.warn("Saga compensate corr={} state={} reason={} shortCode={} hopOutstanding={}",
                corr, s.state(), reason,
                s.shortCode() == null || s.shortCode().isBlank() ? "-" : s.shortCode(),
                s.map2mapHopOutstanding());
        // MAP2MAP: never hard-end MO while outbound hop is still outstanding (Brook gsm_map
        // view looks like "returnResultLast without hop response" when Abort is filtered out —
        // Abort must clear hopOutstanding first via onMap2MapDialogLost).
        // Re-read: the snapshot may predate a concurrent hop clear.
        boolean hopOutstanding = s.map2mapHopOutstanding()
                || store.get(corr).map(VirtualSession::map2mapHopOutstanding).orElse(false);
        if (hopOutstanding && s.originationType() == OriginationType.MAP) {
            LOG.warn("Saga compensate deferred — MAP2MAP hop still outstanding corr={} reason={}",
                    corr, reason);
            cdr.write(corr, CdrPhase.S1_ACTIVE, s.msisdn(), s.shortCode(),
                    "MAP2MAP_MO_HOLD",
                    "service=UssdSagaCoordinator|hopOutstanding|reason=" + reason,
                    s.networkId(), s.tenantId(), s.originationType().name(),
                    s.gateMs() > 0 ? s.gateMs() : null, observedEwmaMs(s.networkId()));
            return;
        }
        // CAS-law: win the terminal transition before any MAP emit — a pull failure
        // racing the gate must not produce two MAP replies.
        Optional<VirtualSession> won = store.compareAndTransitionAny(corr,
                List.of(VirtualSessionState.AWAITING_AS,
                        VirtualSessionState.ACTIVE,
                        VirtualSessionState.PUSH_PENDING,
                        VirtualSessionState.RESPONDING),
                VirtualSessionState.FAILED);
        if (won.isEmpty()) {
            LOG.info("Saga compensate lost CAS (already terminal/owned) corr={} reason={}",
                    corr, reason);
            return;
        }
        VirtualSession cur = won.get();
        if (cur.originationType() == OriginationType.MAP && cur.dialogAlive()) {
            RaCommandPort port = ss7();
            if (useWaitMessage) {
                // Empty/HTTP AS body is a hard AS failure — not AdaptiveTimeout gate expiry.
                // "Please wait..." here misleads operators into chasing bridge/gate.
                String text = isHardAsFailure(reason)
                        ? hardFailMessage()
                        : config.asyncWaitMessage();
                MapDialogHelper.replyAndEnd(port, cur.dialogId(), cur.invokeId(), text);
            } else {
                MapDialogHelper.abort(port, cur.dialogId());
            }
            store.setDialogAlive(corr, false);
        }
        store.remove(corr);
        Long gate = cur.gateMs() > 0 ? cur.gateMs() : null;
        cdr.write(corr, CdrPhase.FAILED, cur.msisdn(), cur.shortCode(),
                reason, "service=UssdSagaCoordinator saga-compensate",
                cur.networkId(), cur.tenantId(), cur.originationType().name(),
                gate, observedEwmaMs(cur.networkId()));
    }

    private static boolean isHardAsFailure(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        return reason.startsWith("AS_EMPTY")
                || reason.startsWith("AS_HTTP_")
                || reason.startsWith("AS_TRANSPORT");
    }

    private String hardFailMessage() {
        try {
            String msg = config.asyncHardFailMessage();
            if (msg != null && !msg.isBlank()) {
                return msg;
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return et.restlink.ussdgw.config.UssdConfigService.DEFAULT_HARD_FAIL_MESSAGE;
    }

    private Long observedEwmaMs(int networkId) {
        if (adaptive == null) {
            return null;
        }
        double v = adaptive.observedLatencyMs(networkId);
        return v > 0d ? Math.round(v) : null;
    }

    private RaCommandPort ss7() {
        try {
            return ss7Supplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public long niFailCount() { return niFailCount.get(); }
    public long pullFailCount() { return pullFailCount.get(); }
}
