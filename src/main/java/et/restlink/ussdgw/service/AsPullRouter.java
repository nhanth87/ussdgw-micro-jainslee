package et.restlink.ussdgw.service;

import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsPullMetadata;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.GatedSessionRegistry;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionState;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.events.PullGrpcEvent;
import et.restlink.ussdgw.events.PullHttpEvent;
import et.restlink.ussdgw.logging.Pii;
import et.restlink.ussdgw.persist.SipTrunkEntity;
import et.restlink.ussdgw.persist.TenantEntity;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.sip.SipTrunkService;
import et.restlink.ussdgw.sip.SipUssdBodyCodec;
import et.restlink.ussdgw.tenant.TenantService;

import com.microjainslee.api.RaCommandPort;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.sipservlet.command.SendMessage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Shared AS pull dispatch — MAP SBB and lab/stub MO use the same route.
 * Enriches pull with virtualBridgeId / adaptiveTimeoutMs / asMode for HTTP and gRPC.
 * {@link RuleType#SIP} sends MESSAGE over the tenant/rule SIP trunk.
 * After a successful SIP SendMessage the session is armed {@code AWAITING_AS} (when present)
 * so the gate sweeper can reclaim; inbound SIP reply correlation is best-effort V1
 * (Call-ID {@code pull-{corr}}, wire body, MSISDN) — see {@code SipUssiSbb}.
 */
@ApplicationScoped
public class AsPullRouter {
    private static final Logger LOG = LogManager.getLogger(AsPullRouter.class);

    /** Classic XML pull over SIP MESSAGE (3GPP USSD MIME). */
    static final String SIP_CT_XML = "application/vnd.3gpp.ussd+xml";
    /** Greenfield JSON pull over SIP MESSAGE. */
    static final String SIP_CT_JSON = "application/json";
    static final String SIP_CT_PLAIN = "text/plain";

    @Inject MicroSleeContainer container;
    @Inject VirtualSessionStore store;
    @Inject VirtualSessionBridge bridge;
    @Inject AdaptiveTimeout adaptive;
    @Inject UssdConfigService config;
    @Inject SipTrunkService sipTrunks;
    @Inject SipApplyService sipApply;
    @Inject AsWireFacade wireFacade;
    @Inject TenantService tenants;
    @Inject GatedSessionRegistry gatedSessions;

    public String route(ShortCodeRule rule, AsRequest asReq, String correlationId) {
        if (rule == null || asReq == null) {
            throw new IllegalArgumentException("rule and request required");
        }
        String corr = correlationId == null || correlationId.isBlank()
                ? asReq.correlationId() : correlationId.trim();
        String url = rule.asUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("AS URL empty for shortCode=" + rule.shortCode());
        }

        String tenantId = resolveTenantId(rule, corr);
        // SLEE AS plane only (HTTP|GRPC|SIP) — RE_ROUTE / Case 2 is orthogonal hop flag.
        RuleType plane = rule.asPullType();
        logPullRoute(rule, asReq, corr, tenantId, url, plane);

        if (plane == RuleType.SIP) {
            return routeSip(rule, asReq, corr);
        }

        boolean http = plane.usesHttpAsPull();
        boolean bridgeArmed = false;
        if (config != null) {
            bridgeArmed = http
                    ? config.httpClientBridgeEnabled()
                    : config.grpcClientBridgeEnabled();
        }
        VirtualSession session = store == null ? null : store.get(corr).orElse(null);
        AsRequest enriched = AsPullMetadata.enrich(asReq, session, adaptive, config, bridgeArmed,
                gatedSessions);

        if (http) {
            container.routeEvent(new PullHttpEvent(url, enriched),
                    container.createActivityContext(pullActivityName(corr)));
            return "routed HTTP sc=" + asReq.shortCode();
        }
        String[] parts = url.split("\\|", 2);
        String target = parts[0];
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("gRPC target empty for shortCode=" + rule.shortCode());
        }
        String method = parts.length > 1 ? parts[1] : "et.restlink.ussdgw.as.UssdAs/Pull";
        container.routeEvent(new PullGrpcEvent(target, method, enriched),
                container.createActivityContext(pullActivityName(corr)));
        return "routed GRPC sc=" + asReq.shortCode();
    }

    /** Ops-visible short-code → AS URL route (no full MSISDN). */
    private void logPullRoute(ShortCodeRule rule, AsRequest asReq, String corr,
                              String tenantId, String url, RuleType plane) {
        int networkId = asReq != null ? asReq.networkId() : rule.networkId();
        LOG.info("AS pull route shortCode={} asUrl={} ruleType={} asPull={} networkId={} tenantId={} corr={} {}",
                rule.shortCode(), url, rule.ruleType(), plane, networkId,
                tenantId == null || tenantId.isBlank() ? "-" : tenantId,
                corr, Pii.msisdnDetail(asReq == null ? null : asReq.msisdn()));
    }

    private String routeSip(ShortCodeRule rule, AsRequest asReq, String corr) {
        if (sipApply == null || !sipApply.raActive()) {
            throw new IllegalStateException("SIP RA not active for RuleType.SIP");
        }
        RaCommandPort port = sipApply.endpoint();
        if (port == null) {
            throw new IllegalStateException("SIP endpoint missing");
        }
        SipTrunkEntity trunk = resolveTrunk(rule, asReq, corr);
        if (trunk == null || !trunk.enabled) {
            throw new IllegalArgumentException("SIP trunk not found for asUrl=" + rule.asUrl());
        }
        boolean bridgeArmed = config != null && config.bridgeEnabled();
        VirtualSession session = store == null || corr == null || corr.isBlank()
                ? null : store.get(corr).orElse(null);
        AsRequest enriched = AsPullMetadata.enrich(asReq, session, adaptive, config, bridgeArmed,
                gatedSessions);
        String from = sipTrunks.resolveFromUri(trunk, sipApply.fromUri());
        String to = sipTrunks.resolveToUri(trunk, enriched.msisdn(),
                config == null ? null : config.sipRequestUriTemplate());
        String tenantId = resolveTenantId(rule, corr);
        SipPullBody encoded = encodeSipPull(enriched, corr, tenantId);
        String callId = "pull-" + (corr == null || corr.isBlank() ? UUID.randomUUID() : corr);
        try {
            port.sendCommand(new SendMessage(
                    callId, from, to, encoded.contentType(), encoded.body()));
        } catch (RuntimeException ex) {
            LOG.warn("SIP pull send failed corr={}: {}", corr, ex.toString());
            throw ex;
        }
        // Park like HTTP/gRPC callers: gate sweeper reclaim. SIP reply correlation is
        // best-effort V1 (Call-ID pull-{corr} / wire body / MSISDN) — not in-dialog reliable.
        armSipPullBridge(session, bridgeArmed);
        return "routed SIP trunk=" + trunk.trunkId + " sc=" + asReq.shortCode();
    }

    /**
     * Ensure the pull session is {@link VirtualSessionState#AWAITING_AS} with an armed gate.
     * Package-visible for unit tests.
     */
    void armSipPullBridge(VirtualSession session, boolean bridgeArmed) {
        if (session == null || bridge == null) {
            return;
        }
        session.setAdaptiveBridgeArm(bridgeArmed);
        boolean needsArm = session.state() != VirtualSessionState.AWAITING_AS
                || session.gateDeadlineMs() <= 0;
        if (needsArm) {
            bridge.startAwaitingAs(session);
        } else if (store != null) {
            store.put(session);
        }
    }

    /**
     * Package-visible: prefer {@link AsWireFacade} XML/JSON; fall back to plain text when
     * the facade is unavailable (unit tests / incomplete CDI).
     */
    SipPullBody encodeSipPull(AsRequest asReq, String corr, String tenantId) {
        AsHttpWireFormat format = resolveSipWireFormat(tenantId);
        if (wireFacade != null) {
            AsRequest forEncode = asReq;
            if (corr != null && !corr.isBlank()
                    && (asReq.correlationId() == null || asReq.correlationId().isBlank())) {
                forEncode = new AsRequest(asReq.sessionId(), corr, asReq.requestId(),
                        asReq.generation(), asReq.msisdn(), asReq.shortCode(),
                        asReq.ussdString(), asReq.networkId(), asReq.virtualBridgeId(),
                        asReq.adaptiveTimeoutMs(), asReq.asMode(), asReq.jsessionId(),
                        asReq.gateReason(), asReq.observedEwmaMs(),
                        asReq.originatedUssd(), asReq.codeKind());
            }
            String ct = format == AsHttpWireFormat.JSON ? SIP_CT_JSON : SIP_CT_XML;
            return new SipPullBody(ct, wireFacade.encodePullRequest(forEncode, format));
        }
        return new SipPullBody(SIP_CT_PLAIN, SipUssdBodyCodec.encodePullPlain(
                corr, asReq.msisdn(), asReq.shortCode(), asReq.ussdString()));
    }

    /**
     * Tenant {@code httpAsWireFormat} via {@link TenantService}; missing tenant → XML.
     * Does not apply the global HTTP wire-format overlay (SIP pull stays classic-default).
     */
    AsHttpWireFormat resolveSipWireFormat(String tenantId) {
        if (tenants == null || tenantId == null || tenantId.isBlank()) {
            return AsHttpWireFormat.XML;
        }
        Optional<TenantEntity> t = tenants.byId(tenantId.trim());
        if (t.isEmpty()) {
            return AsHttpWireFormat.XML;
        }
        return AsHttpWireFormat.parse(t.get().httpAsWireFormat);
    }

    String resolveTenantId(ShortCodeRule rule, String corr) {
        VirtualSession session = store == null || corr == null || corr.isBlank()
                ? null : store.get(corr).orElse(null);
        if (session != null && session.tenantId() != null && !session.tenantId().isBlank()) {
            return session.tenantId();
        }
        return rule == null ? null : rule.tenantId();
    }

    /**
     * Package-visible for unit tests without a live SIP RA.
     * {@code asUrl} for SIP rules is the trunk_id (or {@code trunk_id|ignored}).
     * Ownership: trunk must be shared (blank tenantId) or match session/rule tenantId.
     */
    SipTrunkEntity resolveTrunk(ShortCodeRule rule, AsRequest asReq, String corr) {
        String id = rule.asUrl().split("\\|", 2)[0].trim();
        String tid = resolveTenantId(rule, corr != null ? corr : asReq.correlationId());
        if (sipTrunks != null) {
            Optional<SipTrunkEntity> byId = sipTrunks.byId(id);
            if (byId.isPresent()) {
                SipTrunkEntity t = byId.get();
                if (!SipTrunkService.trunkAllowsTenant(t, tid)) {
                    throw new IllegalArgumentException(
                            "SIP trunk tenant mismatch trunk=" + t.trunkId
                                    + " ruleTenant=" + tid);
                }
                return t;
            }
            return sipTrunks.resolveForTenant(tid)
                    .filter(t -> SipTrunkService.trunkAllowsTenant(t, tid))
                    .orElse(null);
        }
        return null;
    }

    public static String pullActivityName(String correlationId) {
        return correlationId;
    }

    /** Encoded SIP MESSAGE body + Content-Type for AS pull. */
    record SipPullBody(String contentType, String body) {}
}
