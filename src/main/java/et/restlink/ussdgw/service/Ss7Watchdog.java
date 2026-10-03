package et.restlink.ussdgw.service;

import et.restlink.ussdgw.admin.LinkStatusService;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Self-healing for the SS7 plane: no admin button, no restart.
 *
 * <p>Failure it covers (seen on digicom-nb 2026-10-03): the container restarts,
 * kernel SCTP associations come back ESTABLISHED, but the jSS7
 * {@code Association} object is stale — every ASPUP fails with
 * "Association is not started or underlying sctp/tcp channel is down" and
 * {@code ss7.live} stays false forever. Only a full re-wire
 * ({@code tearDown + wireIfConfigured}) rebinds the objects. GMLC hit the same
 * case on 2026-10-01 and added the same layer there.
 *
 * <p>Rules, all fail-closed:
 * <ul>
 *   <li>Truth is {@code ss7.live} (M3UA route ready), never SCTP-LISTEN alone.</li>
 *   <li>Never re-wire a stack the operator stopped ({@code /admin/ss7 Stop}).</li>
 *   <li>Boot grace: no recovery before the stack had a chance to activate.</li>
 *   <li>Down threshold: transient flaps below it are left alone.</li>
 *   <li>Cooldown between recoveries: a peer that bans flapping ASPs must not
 *   see us redial every tick.</li>
 *   <li>Single-flight: one recovery at a time ({@code ConcurrentExecution.SKIP}
 *   plus a CAS, so a slow re-wire cannot stack up).</li>
 * </ul>
 *
 * <p>Arms automatically on Quarkus boot via {@code @Scheduled} — same pattern
 * as {@link BridgeGateScheduler}. Services log via Log4j2 (never
 * SleeEventTrace — that is for SBB/RA boundaries only).
 */
@ApplicationScoped
public class Ss7Watchdog {
    private static final Logger LOG = LogManager.getLogger(Ss7Watchdog.class);

    @Inject LinkStatusService linkStatus;
    @Inject Ss7ApplyService ss7Apply;

    @ConfigProperty(name = "ussd.ss7.watchdog.enabled", defaultValue = "true")
    boolean enabledProp;
    @ConfigProperty(name = "ussd.ss7.watchdog.tick-seconds", defaultValue = "30")
    long tickSecondsProp;
    @ConfigProperty(name = "ussd.ss7.watchdog.boot-grace-seconds", defaultValue = "180")
    long bootGraceSecondsProp;
    @ConfigProperty(name = "ussd.ss7.watchdog.down-seconds", defaultValue = "180")
    long downSecondsProp;
    @ConfigProperty(name = "ussd.ss7.watchdog.cooldown-seconds", defaultValue = "600")
    long cooldownSecondsProp;
    @ConfigProperty(name = "ussd.ss7.watchdog.rapid-retries", defaultValue = "3")
    int rapidRetriesProp;

    private final Instant bootAt = Instant.now();
    private volatile Instant downSince;
    private volatile Instant lastRecoverAt;
    private volatile int rapidUsed;
    private final AtomicBoolean recovering = new AtomicBoolean(false);

    @Scheduled(every = "${ussd.ss7.watchdog.tick-seconds:30}s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        try {
            if (!enabledProp) return;
            // Operator Stop is intentional. A Stop that heals itself is not a Stop.
            if (linkStatus.isSs7IntentionallyStopped()) {
                downSince = null;
                return;
            }
            if (linkStatus.ss7Live()) {
                if (downSince != null) {
                    LOG.info("ss7-watchdog: route back (was down since {})", downSince);
                }
                downSince = null;
                rapidUsed = 0;
                return;
            }
            Instant now = Instant.now();
            if (Duration.between(bootAt, now).getSeconds() < Math.max(0, bootGraceSecondsProp)) {
                return;
            }
            if (downSince == null) {
                downSince = now;
                LOG.warn("ss7-watchdog: M3UA route down, watching (threshold {}s)", downSecondsProp);
                return;
            }
            long downFor = Duration.between(downSince, now).getSeconds();
            if (downFor < Math.max(0, downSecondsProp)) return;
            if (lastRecoverAt != null
                    && Duration.between(lastRecoverAt, now).getSeconds()
                        < Math.max(0, cooldownSecondsProp)
                    && rapidUsed >= Math.max(0, rapidRetriesProp)) {
                return;
            }
            if (!recovering.compareAndSet(false, true)) return;
            try {
                lastRecoverAt = now;
                rapidUsed++;
                LOG.warn("ss7-watchdog: route down {}s, re-wiring SS7 (attempt {}, cooldown {}s)",
                        downFor, rapidUsed, cooldownSecondsProp);
                String result;
                try {
                    result = ss7Apply.start();
                } catch (Throwable t) {
                    LOG.warn("ss7-watchdog: re-wire failed: {}", String.valueOf(t.getMessage()));
                    return;
                }
                LOG.warn("ss7-watchdog: re-wire issued: {}", result);
                if (linkStatus.ss7Live()) {
                    LOG.info("ss7-watchdog: route back immediately after re-wire");
                    downSince = null;
                    rapidUsed = 0;
                }
                // Else downSince stays: the next tick retries inside rapid-retries,
                // then the cooldown governs. Never a tight loop against the STP.
            } finally {
                recovering.set(false);
            }
        } catch (Throwable t) {
            LOG.warn("ss7-watchdog: tick failed: {}", String.valueOf(t.getMessage()));
        }
    }
}
