package et.restlink.ussdgw.service;

import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionState;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.cdr.Map2MapCdr;
import et.restlink.ussdgw.events.Map2MapRequestEvent;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.telemetry.Map2MapTelemetry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * After a MAP2MAP UnstructuredSS-Response (or lab skip), route the MO pull to the rule's
 * {@code asUrl} with hop text + originated dial.
 *
 * <p>Bridge / AdaptiveTimeout is armed at MAP2MAP <em>ingress</em>
 * ({@code MapUssdParentSbb} map2map branch). Completion must not break CAS:
 * <ul>
 *   <li>{@link VirtualSessionState#AWAITING_AS} — re-arm gate for the AS pull phase
 *       (fresh EWMA sample; hop latency must not consume the AS budget)</li>
 *   <li>{@link VirtualSessionState#S1_RELEASED} — gate already fired during hop
 *       (UE has async-wait + gated XML); keep state and AS-pull for late reconcile</li>
 * </ul>
 *
 * <p>AS {@code ussdString} = hop response when non-blank; else original dialed USSD.
 * Additive: {@code originatedUssd}, {@code shortCode}, {@code codeKind} (SHORT|LONG).
 * Slow AS (or slow hop before AS) → {@link VirtualSessionBridge#onGateExpired} →
 * {@link GatedAsNotifyService} gated XML. Never {@code Thread.sleep}.
 */
@ApplicationScoped
public class Map2MapCompletionService {
    private static final Logger LOG = LogManager.getLogger(Map2MapCompletionService.class);

    @Inject VirtualSessionStore store;
    @Inject VirtualSessionBridge bridge;
    @Inject AsPullRouter asPullRouter;
    @Inject CdrService cdr;
    @Inject Map2MapTelemetry map2MapTelemetry;

    public String onMap2MapResponse(Map2MapRequestEvent req, String hopText) {
        if (req == null) {
            return "map2map-complete-null";
        }
        String hop = hopText == null ? "" : hopText.trim();
        VirtualSession session = store.get(req.correlationId()).orElse(null);
        if (session == null) {
            LOG.warn("MAP2MAP complete missing session corr={}", req.correlationId());
            return "map2map-no-session";
        }
        if (!hop.isEmpty()) {
            session.setPendingText(hop);
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
        if (afterGate) {
            // Gate already won during hop — do not startAwaitingAs (would reset S1_RELEASED
            // and double-break CAS). AS pull continues; claimForAsResponse late-reconciles.
            store.put(session);
            LOG.info("MAP2MAP complete after gate corr={} state={} hopLen={}",
                    req.correlationId(), st, hop.length());
            try {
                map2MapTelemetry.completionAfterGate();
            } catch (Throwable ignored) { }
        } else if (st == VirtualSessionState.AWAITING_AS) {
            // Re-arm for AS pull: reset gate deadline + pullStartedAt so hop RTT does not
            // starve the AS budget / poison EWMA.
            bridge.startAwaitingAs(session);
        } else {
            // Lab skip / unexpected ACTIVE — arm for AS.
            bridge.startAwaitingAs(session);
        }

        String dialed = req.dialedUssd() == null ? "" : req.dialedUssd();
        String asUssd = !hop.isEmpty() ? hop : dialed;
        String codeKind = ShortCodeRule.codeKind(dialed, req.mark(), req.shortCode());
        AsRequest asReq = new AsRequest(
                session.virtualSessionId(), req.correlationId(), req.requestId(),
                session.generation(), req.msisdn(), req.shortCode(), asUssd, req.networkId())
                .withOriginated(dialed.isEmpty() ? null : dialed, codeKind);
        ShortCodeRule rule = ShortCodeRule.ofReroute(
                req.shortCode(),
                // SLEE AS plane only — never persist/route RE_ROUTE as a wire type.
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
                                afterGate ? "phase=after-gate" : "phase=as"),
                        req.networkId(), req.tenantId(), "MAP",
                        Map2MapCdr.gateMs(session), null);
            } catch (Throwable ignored) { }
            return "map2map-as-fail";
        }
        try {
            map2MapTelemetry.hopOk();
            map2MapTelemetry.asRouted();
        } catch (Throwable ignored) { }
        try {
            String status = afterGate ? Map2MapCdr.COMPLETE_AFTER_GATE : Map2MapCdr.OK;
            String d = Map2MapCdr.detail(req,
                    hop.isEmpty() ? "hop-empty" : ("hopLen=" + hop.length()),
                    "codeKind=" + codeKind,
                    "virtualBridgeId=" + session.virtualSessionId(),
                    afterGate ? "phase=after-gate" : "phase=as-rearm",
                    "asRouted=true");
            cdr.write(req.correlationId(), CdrPhase.S1_ACTIVE, req.msisdn(),
                    req.shortCode(), status, d,
                    req.networkId(), req.tenantId(), "MAP",
                    Map2MapCdr.gateMs(session), null);
        } catch (Throwable ignored) { }
        return "map2map-ok " + routed;
    }
}
