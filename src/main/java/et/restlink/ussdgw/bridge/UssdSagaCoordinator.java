package et.restlink.ussdgw.bridge;

import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.service.MapDialogHelper;

import com.microjainslee.api.RaCommandPort;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
                    reason == null ? "NI_FAIL" : reason, null);
            return;
        }
        compensate(opt.get(), reason == null ? "NI_FAIL" : reason, false);
    }

    private void compensate(VirtualSession s, String reason, boolean useWaitMessage) {
        LOG.warn("Saga compensate corr={} state={} reason={}",
                s.correlationId(), s.state(), reason);
        if (s.originationType() == OriginationType.MAP && s.dialogAlive()) {
            RaCommandPort port = ss7();
            if (useWaitMessage) {
                MapDialogHelper.replyAndEnd(port, s.dialogId(), s.invokeId(),
                        config.asyncWaitMessage());
            } else {
                MapDialogHelper.abort(port, s.dialogId());
            }
            s.setDialogAlive(false);
        }
        s.setState(VirtualSessionState.FAILED);
        store.put(s);
        store.remove(s.correlationId());
        cdr.write(s.correlationId(), CdrPhase.FAILED, s.msisdn(), s.shortCode(),
                reason, "saga-compensate", s.networkId(), s.tenantId(),
                s.originationType().name());
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
