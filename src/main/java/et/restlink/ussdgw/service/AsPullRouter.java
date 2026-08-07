package et.restlink.ussdgw.service;

import et.restlink.ussdgw.api.AsPullMetadata;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.events.PullGrpcEvent;
import et.restlink.ussdgw.events.PullHttpEvent;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;

import com.microjainslee.core.MicroSleeContainer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Shared AS pull dispatch — MAP SBB and lab/stub MO use the same route.
 * Enriches pull with virtualBridgeId / adaptiveTimeoutMs / asMode for HTTP and gRPC.
 */
@ApplicationScoped
public class AsPullRouter {
    @Inject MicroSleeContainer container;
    @Inject VirtualSessionStore store;
    @Inject AdaptiveTimeout adaptive;
    @Inject UssdConfigService config;

    /**
     * Fail-closed when rule or URL missing. Returns a short detail string for traces.
     */
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
        boolean http = rule.ruleType() == RuleType.HTTP;
        boolean bridgeArmed = false;
        if (config != null) {
            bridgeArmed = http
                    ? config.httpClientBridgeEnabled()
                    : config.grpcClientBridgeEnabled();
        }
        VirtualSession session = store == null ? null : store.get(corr).orElse(null);
        AsRequest enriched = AsPullMetadata.enrich(asReq, session, adaptive, config, bridgeArmed);

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

    /**
     * Activity context name for an outbound pull.
     *
     * <p>Must be the bare correlation id: both client RAs fire their completion on
     * {@code createActivityHandle(correlationId)}, and the container derives the SBB entity id
     * from the activity context name. A decorated name here ({@code "pull-http-" + corr}) put the
     * submit and the completion on two different entities — and therefore two different pooled
     * SBB instances.
     */
    public static String pullActivityName(String correlationId) {
        return correlationId;
    }
}
