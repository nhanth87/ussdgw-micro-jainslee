package et.restlink.ussdgw.campaign;

import et.restlink.ussdgw.admin.LinkStatusService;
import et.restlink.ussdgw.api.UssdAlphabet;
import et.restlink.ussdgw.events.NiPushRequestEvent;
import et.restlink.ussdgw.tenant.TenantGuard;

import com.microjainslee.core.MicroSleeContainer;

import io.quarkus.scheduler.Scheduled;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Claims RUNNING campaign targets once per second and routes {@link NiPushRequestEvent}.
 */
@ApplicationScoped
public class CampaignScheduler {
    private static final Logger LOG = LogManager.getLogger(CampaignScheduler.class);

    @Inject CampaignService campaigns;
    @Inject MicroSleeContainer container;
    @Inject LinkStatusService linkStatus;
    @Inject TenantGuard tenantGuard;

    @ConfigProperty(name = "ussd.campaign.enabled", defaultValue = "true")
    boolean enabled;

    private final AtomicLong claimed = new AtomicLong();
    private final AtomicLong skippedSs7 = new AtomicLong();

    @Scheduled(every = "1s")
    void tick() {
        if (!enabled || !campaigns.enabled()) return;
        if (!linkStatus.isM3uaRouteReady()) {
            skippedSs7.incrementAndGet();
            return;
        }
        List<CampaignService.ClaimedTarget> batch;
        try {
            batch = campaigns.claim(campaigns.claimLimit());
        } catch (RuntimeException e) {
            LOG.warn("Campaign claim failed: {}", e.toString());
            return;
        }
        for (CampaignService.ClaimedTarget ct : batch) {
            var camp = ct.campaign();
            var tgt = ct.target();
            if (camp.tenantId != null && !camp.tenantId.isBlank()) {
                TenantGuard.Decision d = tenantGuard.admit(camp.tenantId);
                if (!d.allowed()) {
                    campaigns.onNiDone(tgt.correlationId, false, "TENANT_" + d.reason());
                    continue;
                }
            }
            String corr = tgt.correlationId != null ? tgt.correlationId : tgt.id.toString();
            UssdAlphabet alpha = UssdAlphabet.parse(camp.alphabet);
            try {
                container.routeEvent(
                        new NiPushRequestEvent(corr, tgt.msisdn, camp.text, camp.networkId, alpha),
                        container.createActivityContext("camp-ni-" + corr));
                claimed.incrementAndGet();
            } catch (RuntimeException e) {
                LOG.warn("Campaign NI route fail corr={}: {}", corr, e.toString());
                campaigns.onNiDone(corr, false, e.getClass().getSimpleName());
            }
        }
    }

    public long claimed() { return claimed.get(); }
    public long skippedSs7() { return skippedSs7.get(); }
}
