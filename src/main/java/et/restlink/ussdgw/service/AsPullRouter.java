package et.restlink.ussdgw.service;

import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.events.PullGrpcEvent;
import et.restlink.ussdgw.events.PullHttpEvent;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;

import com.microjainslee.core.MicroSleeContainer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Shared AS pull dispatch — MAP SBB and lab/stub MO use the same route.
 */
@ApplicationScoped
public class AsPullRouter {
    @Inject MicroSleeContainer container;

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
        if (rule.ruleType() == RuleType.HTTP) {
            container.routeEvent(new PullHttpEvent(url, asReq),
                    container.createActivityContext("pull-http-" + corr));
            return "routed HTTP sc=" + asReq.shortCode();
        }
        String[] parts = url.split("\\|", 2);
        String target = parts[0];
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("gRPC target empty for shortCode=" + rule.shortCode());
        }
        String method = parts.length > 1 ? parts[1] : "et.restlink.ussdgw.as.UssdAs/Pull";
        container.routeEvent(new PullGrpcEvent(target, method, asReq),
                container.createActivityContext("pull-grpc-" + corr));
        return "routed GRPC sc=" + asReq.shortCode();
    }
}
