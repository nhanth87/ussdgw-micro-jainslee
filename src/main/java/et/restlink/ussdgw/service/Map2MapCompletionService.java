package et.restlink.ussdgw.service;

import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionState;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.cdr.Map2MapCdr;
import et.restlink.ussdgw.events.Map2MapRequestEvent;
import et.restlink.ussdgw.profile.UssdUserProfileStore;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.telemetry.Map2MapTelemetry;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RE_ROUTE (MAP2MAP Case 2) completion: after upper-HLR hop, SLEE-route AS pull.
 *
 * <p>AdaptiveTimeout budget is armed <em>after hop USSD is sent</em> ({@code GATE_ARMED}).
 * On hop <strong>text</strong>, re-arm the gate for the AS budget. On hop
 * <strong>REJECT / Abort / empty / timeout</strong> (RE_ROUTE only): that <em>is</em> the hop
 * response (TCAP Abort is not visible under a {@code gsm_map}-only Wireshark filter) — clear
 * hop-outstanding, pull AS with {@code string="hlr reject"} or empty {@code string=}
 * ({@code hlrResult=none}), then
 * MO {@code returnResultLast}. Never second {@code GATE_ARMED}. AS pull always carries
 * {@code originatedUssd} (dialed), {@code redirectUssd}/{@code hopUssd} (re-route codes),
 * and hop RESULT text in {@code ussdString} when present (never a displayable
 * {@code hlr none} placeholder — AS menus must not echo empty-hop status onto the UE).
 *
 * <p>TC-END often delivers Dialog {@code CLOSE} before the Service
 * {@code processUnstructuredSS-Response} in the same packet. CLOSE is deferred briefly so
 * RESULT text can claim the AS pull first; empty CLOSE then no-ops if pending was taken.
 *
 * <p>Also stamps durable {@link UssdUserProfileStore} (PK=MSISDN) with last MAP2MAP TX fields.
 */
@ApplicationScoped
public class Map2MapCompletionService {
    private static final Logger LOG = LogManager.getLogger(Map2MapCompletionService.class);
    /** Grace so same-TC-END RESULT can win over Dialog CLOSE ordering. */
    public static final long HOP_CLOSE_DEFER_MS = 100L;

    @Inject VirtualSessionStore store;
    @Inject VirtualSessionBridge bridge;
    @Inject AsPullRouter asPullRouter;
    @Inject CdrService cdr;
    @Inject Map2MapTelemetry map2MapTelemetry;
    @Inject UssdUserProfileStore userProfiles;
    @Inject AdaptiveTimeout adaptive;

    private final ConcurrentHashMap<String, ScheduledFuture<?>> deferredClose =
            new ConcurrentHashMap<>();
    private final AtomicInteger deferThreads = new AtomicInteger();
    private final ScheduledExecutorService hopCloseDefer = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "map2map-hop-close-defer-" + deferThreads.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdown() {
        deferredClose.values().forEach(f -> f.cancel(false));
        deferredClose.clear();
        hopCloseDefer.shutdownNow();
    }

    /**
     * Soft-CLOSE: leave pending for RESULT; after {@link #HOP_CLOSE_DEFER_MS} run
     * {@code onStillPending} only if the hop was not completed by RESULT.
     */
    public void deferHopClose(String outboundCorr, Runnable onStillPending) {
        if (outboundCorr == null || outboundCorr.isBlank() || onStillPending == null) {
            return;
        }
        String key = outboundCorr.trim();
        cancelDeferredHopClose(key);
        ScheduledFuture<?> fut = hopCloseDefer.schedule(() -> {
            deferredClose.remove(key);
            try {
                onStillPending.run();
            } catch (Throwable t) {
                LOG.warn("MAP2MAP deferred CLOSE failed outbound={}: {}", key, t.toString());
            }
        }, HOP_CLOSE_DEFER_MS, TimeUnit.MILLISECONDS);
        deferredClose.put(key, fut);
    }

    public void cancelDeferredHopClose(String outboundCorr) {
        if (outboundCorr == null || outboundCorr.isBlank()) {
            return;
        }
        ScheduledFuture<?> fut = deferredClose.remove(outboundCorr.trim());
        if (fut != null) {
            fut.cancel(false);
        }
    }

    public String onMap2MapResponse(Map2MapRequestEvent req, String hopText) {
        String hop = hopText == null ? "" : hopText.trim();
        String outcome = hop.isEmpty() ? Map2MapCdr.OUTCOME_EMPTY : Map2MapCdr.OUTCOME_TEXT;
        return onMap2MapResponse(req, hopText, outcome);
    }

    /**
     * @param hopOutcome {@link Map2MapCdr#OUTCOME_TEXT} / {@code reject} / {@code empty} / …
     */
    public String onMap2MapResponse(Map2MapRequestEvent req, String hopText, String hopOutcome) {
        if (req == null) {
            return "map2map-complete-null";
        }
        cancelDeferredHopClose(req.outboundCorr());
        String hop = hopText == null ? "" : hopText.trim();
        String outcome = hopOutcome == null || hopOutcome.isBlank()
                ? (hop.isEmpty() ? Map2MapCdr.OUTCOME_EMPTY : Map2MapCdr.OUTCOME_TEXT)
                : hopOutcome.trim();
        VirtualSession session = store.get(req.correlationId()).orElse(null);
        if (session == null) {
            LOG.warn("MAP2MAP complete missing session corr={}", req.correlationId());
            return "map2map-no-session";
        }
        if (!hop.isEmpty()) {
            session.setPendingText(hop);
        }
        if (!session.tryClaimMap2MapAsRoute()) {
            LOG.info("MAP2MAP AS already routed corr={} hopOutcome={} hopLen={}",
                    req.correlationId(), outcome, hop.length());
            return "map2map-already-routed";
        }
        session.setAdaptiveBridgeArm(true);

        VirtualSessionState st = session.state();
        if (st != null && st.terminal()) {
            LOG.warn("MAP2MAP complete on terminal session corr={} state={}",
                    req.correlationId(), st);
            return "map2map-session-terminal";
        }
        boolean afterGate = st == VirtualSessionState.S1_RELEASED
                || st == VirtualSessionState.PUSH_PENDING
                || st == VirtualSessionState.RESPONDING;
        // RE_ROUTE: terminal hop (reject/empty/…) → keep ingress gate, never second GATED.
        boolean terminalHop = Map2MapCdr.isTerminalHopOutcome(outcome) || hop.isEmpty();
        boolean rearmed = false;
        if (afterGate) {
            store.put(session);
            LOG.info("MAP2MAP complete after gate corr={} state={} hopOutcome={}",
                    req.correlationId(), st, outcome);
            try {
                map2MapTelemetry.completionAfterGate();
            } catch (Throwable ignored) { }
        } else if (terminalHop && st == VirtualSessionState.AWAITING_AS) {
            store.put(session);
            LOG.info("MAP2MAP RE_ROUTE hop-terminal no-rearm corr={} hopOutcome={}",
                    req.correlationId(), outcome);
        } else if (st == VirtualSessionState.AWAITING_AS) {
            bridge.startAwaitingAs(session);
            rearmed = true;
        } else {
            bridge.startAwaitingAs(session);
            rearmed = true;
        }

        String dialed = req.dialedUssd() == null ? "" : req.dialedUssd();
        String asUssd = Map2MapCdr.asUssdForReRouteHop(hop, outcome);
        String codeKind = ShortCodeRule.codeKind(dialed, req.mark(), req.shortCode());
        String redirect = blankToNull(req.redirectUssd());
        String hopCode = resolveHopCode(req);
        AsRequest asReq = new AsRequest(
                session.virtualSessionId(), req.correlationId(), req.requestId(),
                session.generation(), req.msisdn(), req.shortCode(), asUssd, req.networkId())
                .withOriginated(dialed.isEmpty() ? null : dialed, codeKind)
                .withMap2MapCodes(redirect, hopCode);
        ShortCodeRule rule = ShortCodeRule.ofReroute(
                req.shortCode(),
                req.ruleType() == null ? RuleType.HTTP : req.ruleType().asPullPlane(),
                req.asUrl(),
                true,
                req.tenantId(),
                req.networkId(),
                req.mark(),
                null,
                true,
                req.redirectUssd(),
                req.hlrMode(),
                req.hopDestGt(),
                req.hopDestSsn());
        String routed;
        try {
            routed = asPullRouter.route(rule, asReq, req.correlationId());
        } catch (RuntimeException ex) {
            LOG.warn("MAP2MAP AS route failed corr={}: {}", req.correlationId(), ex.toString());
            try {
                cdr.write(req.correlationId(), CdrPhase.FAILED, req.msisdn(),
                        req.shortCode(), Map2MapCdr.AS_ROUTE_FAIL,
                        Map2MapCdr.detail(req, "err=" + ex.getMessage(),
                                "hopOutcome=" + outcome,
                                "asUssd=" + asUssd,
                                afterGate ? "phase=after-gate" : "phase=as"),
                        req.networkId(), req.tenantId(), "MAP",
                        Map2MapCdr.gateMs(session), null);
            } catch (Throwable ignored) { }
            return "map2map-as-fail";
        }
        try {
            if (!terminalHop) {
                map2MapTelemetry.hopOk();
            }
            map2MapTelemetry.asRouted();
        } catch (Throwable ignored) { }
        try {
            String status;
            if (afterGate) {
                status = Map2MapCdr.COMPLETE_AFTER_GATE;
            } else if (terminalHop) {
                status = Map2MapCdr.AS_ROUTED;
            } else {
                status = Map2MapCdr.OK;
            }
            String d = Map2MapCdr.detail(req,
                    "hopOutcome=" + outcome,
                    "asUssd=" + asUssd,
                    hop.isEmpty() ? "hop-empty" : ("hopLen=" + hop.length()),
                    "codeKind=" + codeKind,
                    "virtualBridgeId=" + session.correlationId(),
                    afterGate ? "phase=after-gate"
                            : (rearmed ? "phase=as-rearm" : "phase=as-no-rearm"),
                    "asRouted=true");
            cdr.write(req.correlationId(), CdrPhase.S1_ACTIVE, req.msisdn(),
                    req.shortCode(), status, d,
                    req.networkId(), req.tenantId(), "MAP",
                    Map2MapCdr.gateMs(session), null);
        } catch (Throwable ignored) { }
        persistUserMap2Map(req, session, outcome);
        return "map2map-ok " + routed;
    }


    /** Resolved hop USSD actually sent to upper HLR (redirect + mark/long fold). */
    private static String resolveHopCode(Map2MapRequestEvent req) {
        if (req == null) {
            return null;
        }
        String hop = ShortCodeRule.resolveHopUssd(
                req.dialedUssd(), req.mark(), req.shortCode(), req.redirectUssd());
        return blankToNull(hop != null && !hop.isBlank() ? hop : req.redirectUssd());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Last MAP2MAP TX → durable per-MSISDN {@code ussdUser} profile (not ussdTx). */
    private void persistUserMap2Map(Map2MapRequestEvent req, VirtualSession session,
                                    String hopOutcome) {
        if (userProfiles == null || req == null) {
            return;
        }
        try {
            Long gate = Map2MapCdr.gateMs(session);
            Long ewma = null;
            if (adaptive != null) {
                double v = adaptive.observedLatencyMs(req.networkId(), req.msisdn());
                if (v > 0d) {
                    ewma = Math.round(v);
                }
            }
            userProfiles.recordMap2Map(req, hopOutcome, gate, ewma);
        } catch (Throwable t) {
            LOG.warn("ussdUser MAP2MAP persist failed msisdn={}: {}",
                    req.msisdn(), t.toString());
        }
    }
}
