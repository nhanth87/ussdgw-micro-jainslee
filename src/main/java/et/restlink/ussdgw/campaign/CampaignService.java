package et.restlink.ussdgw.campaign;

import et.restlink.ussdgw.api.UssdAlphabet;
import et.restlink.ussdgw.persist.CampaignEntity;
import et.restlink.ussdgw.persist.CampaignTargetEntity;
import et.restlink.ussdgw.tenant.TenantService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * NI USSD push campaigns — create / start / pause / cancel / claim / complete.
 */
@ApplicationScoped
public class CampaignService {
    private static final Logger LOG = LogManager.getLogger(CampaignService.class);
    public static final int MAX_TEXT_LEN = 182;

    @Inject TenantService tenants;

    @ConfigProperty(name = "ussd.campaign.enabled", defaultValue = "true")
    boolean enabled;
    @ConfigProperty(name = "ussd.campaign.claim-limit", defaultValue = "10")
    int claimLimit;
    @ConfigProperty(name = "ussd.campaign.max-targets", defaultValue = "5000")
    int maxTargets;

    public boolean enabled() {
        return enabled;
    }

    public int claimLimit() {
        return Math.max(1, claimLimit);
    }

    @Transactional
    public CampaignEntity create(String name, String tenantId, String text, String alphabet,
                                 int networkId, int maxTps, String msisdnBlob) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name required");
        }
        String body = text == null ? "" : text.trim();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("text required");
        }
        if (body.length() > MAX_TEXT_LEN) {
            body = body.substring(0, MAX_TEXT_LEN);
        }
        List<String> msisdns = parseMsisdns(msisdnBlob);
        if (msisdns.isEmpty()) {
            throw new IllegalArgumentException("at least one MSISDN required");
        }
        if (msisdns.size() > maxTargets) {
            throw new IllegalArgumentException("max targets is " + maxTargets);
        }
        String tid = blank(tenantId);
        int net = networkId;
        if (tid != null && net == 0) {
            net = tenants.byId(tid).map(t -> t.networkId).orElse(0);
        }
        Instant now = Instant.now();
        CampaignEntity c = new CampaignEntity();
        c.id = UUID.randomUUID();
        c.name = name.trim();
        c.tenantId = tid;
        c.text = body;
        c.alphabet = UssdAlphabet.parse(alphabet).name();
        c.networkId = Math.max(0, net);
        c.status = CampaignStatus.DRAFT.name();
        c.maxTps = maxTps <= 0 ? 5 : Math.min(maxTps, 100);
        c.sentCount = 0;
        c.failCount = 0;
        c.createdAt = now;
        c.updatedAt = now;
        c.persist();
        for (String ms : msisdns) {
            CampaignTargetEntity t = new CampaignTargetEntity();
            t.id = UUID.randomUUID();
            t.campaignId = c.id;
            t.msisdn = ms;
            t.status = CampaignTargetStatus.PENDING.name();
            t.correlationId = t.id.toString();
            t.updatedAt = now;
            t.persist();
        }
        LOG.info("Campaign created id={} targets={} tenant={}", c.id, msisdns.size(), tid);
        return c;
    }

    @Transactional
    public CampaignEntity start(UUID id) {
        CampaignEntity c = require(id);
        if (CampaignStatus.CANCELLED.name().equals(c.status)
                || CampaignStatus.COMPLETED.name().equals(c.status)) {
            throw new IllegalStateException("cannot start " + c.status);
        }
        c.status = CampaignStatus.RUNNING.name();
        c.updatedAt = Instant.now();
        return c;
    }

    @Transactional
    public CampaignEntity pause(UUID id) {
        CampaignEntity c = require(id);
        if (!CampaignStatus.RUNNING.name().equals(c.status)) {
            throw new IllegalStateException("pause only from RUNNING");
        }
        c.status = CampaignStatus.PAUSED.name();
        c.updatedAt = Instant.now();
        return c;
    }

    @Transactional
    public CampaignEntity cancel(UUID id) {
        CampaignEntity c = require(id);
        c.status = CampaignStatus.CANCELLED.name();
        c.updatedAt = Instant.now();
        CampaignTargetEntity.update(
                "status = ?1, updatedAt = ?2 where campaignId = ?3 and status = ?4",
                CampaignTargetStatus.FAILED.name(), Instant.now(), id,
                CampaignTargetStatus.PENDING.name());
        return c;
    }

    @Transactional
    public List<CampaignEntity> list(String tenantScope) {
        if (tenantScope != null && !tenantScope.isBlank()) {
            return CampaignEntity.list("tenantId = ?1 order by createdAt desc", tenantScope.trim());
        }
        return CampaignEntity.list("order by createdAt desc");
    }

    @Transactional
    public Optional<CampaignEntity> byId(UUID id) {
        return CampaignEntity.findByIdOptional(id);
    }

    @Transactional
    public long targetCount(UUID campaignId, String status) {
        return CampaignTargetEntity.count("campaignId = ?1 and status = ?2", campaignId, status);
    }

    /**
     * Claim up to {@code limit} PENDING targets across RUNNING campaigns.
     * Skips MSISDNs that already have a SENDING target (busy-UE).
     */
    @Transactional
    public List<ClaimedTarget> claim(int limit) {
        int lim = Math.min(Math.max(1, limit), claimLimit());
        List<CampaignEntity> running = CampaignEntity.list("status = ?1", CampaignStatus.RUNNING.name());
        List<ClaimedTarget> out = new ArrayList<>();
        Instant now = Instant.now();
        for (CampaignEntity camp : running) {
            if (out.size() >= lim) break;
            int campBudget = Math.min(camp.maxTps <= 0 ? 5 : camp.maxTps, lim - out.size());
            if (campBudget <= 0) continue;
            List<CampaignTargetEntity> pending = CampaignTargetEntity.find(
                            "campaignId = ?1 and status = ?2",
                            camp.id, CampaignTargetStatus.PENDING.name())
                    .page(0, campBudget * 3)
                    .list();
            for (CampaignTargetEntity t : selectClaimable(pending, this::isBusyUe, campBudget)) {
                if (out.size() >= lim) break;
                long updated = CampaignTargetEntity.update(
                        "status = ?1, updatedAt = ?2 where id = ?3 and status = ?4",
                        CampaignTargetStatus.SENDING.name(), now, t.id,
                        CampaignTargetStatus.PENDING.name());
                if (updated != 1) continue;
                t.status = CampaignTargetStatus.SENDING.name();
                t.updatedAt = now;
                if (t.correlationId == null || t.correlationId.isBlank()) {
                    t.correlationId = t.id.toString();
                }
                out.add(new ClaimedTarget(camp, t));
            }
            maybeComplete(camp);
        }
        return out;
    }

    @Transactional
    public void onNiDone(String correlationId, boolean ok, String error) {
        if (correlationId == null || correlationId.isBlank()) return;
        Optional<CampaignTargetEntity> opt = CampaignTargetEntity
                .find("correlationId = ?1", correlationId.trim())
                .firstResultOptional();
        if (opt.isEmpty()) return;
        CampaignTargetEntity t = opt.get();
        if (!CampaignTargetStatus.SENDING.name().equals(t.status)
                && !CampaignTargetStatus.PENDING.name().equals(t.status)) {
            return;
        }
        Instant now = Instant.now();
        t.status = ok ? CampaignTargetStatus.SENT.name() : CampaignTargetStatus.FAILED.name();
        t.error = error == null ? null : (error.length() > 512 ? error.substring(0, 512) : error);
        t.updatedAt = now;
        CampaignEntity camp = CampaignEntity.findById(t.campaignId);
        if (camp != null) {
            if (ok) camp.sentCount++;
            else camp.failCount++;
            camp.updatedAt = now;
            maybeComplete(camp);
        }
    }

    private void maybeComplete(CampaignEntity c) {
        long left = CampaignTargetEntity.count(
                "campaignId = ?1 and status in (?2, ?3)",
                c.id, CampaignTargetStatus.PENDING.name(), CampaignTargetStatus.SENDING.name());
        if (left == 0 && CampaignStatus.RUNNING.name().equals(c.status)) {
            c.status = CampaignStatus.COMPLETED.name();
            c.updatedAt = Instant.now();
            LOG.info("Campaign completed id={} sent={} fail={}", c.id, c.sentCount, c.failCount);
        }
    }

    private boolean isBusyUe(String msisdn) {
        return CampaignTargetEntity.count(
                "msisdn = ?1 and status = ?2", msisdn, CampaignTargetStatus.SENDING.name()) > 0;
    }

    /**
     * Pick PENDING targets up to {@code budget}, skipping busy MSISDNs.
     * Also skips a second PENDING for an MSISDN already selected in this batch.
     */
    static List<CampaignTargetEntity> selectClaimable(
            List<CampaignTargetEntity> pending,
            java.util.function.Predicate<String> busyUe,
            int budget) {
        if (pending == null || pending.isEmpty() || budget <= 0) return List.of();
        List<CampaignTargetEntity> picked = new ArrayList<>();
        LinkedHashSet<String> batchBusy = new LinkedHashSet<>();
        for (CampaignTargetEntity t : pending) {
            if (picked.size() >= budget) break;
            if (t == null || t.msisdn == null) continue;
            if (batchBusy.contains(t.msisdn) || busyUe.test(t.msisdn)) continue;
            batchBusy.add(t.msisdn);
            picked.add(t);
        }
        return picked;
    }

    /** Allowed campaign status after cancel. */
    static String cancelledStatus() {
        return CampaignStatus.CANCELLED.name();
    }

    /** PENDING targets become FAILED when campaign is cancelled. */
    static String cancelledTargetStatus() {
        return CampaignTargetStatus.FAILED.name();
    }

    /** Claim CAS: PENDING → SENDING. */
    static String claimedTargetStatus() {
        return CampaignTargetStatus.SENDING.name();
    }

    private CampaignEntity require(UUID id) {
        CampaignEntity c = CampaignEntity.findById(id);
        if (c == null) throw new IllegalArgumentException("campaign not found: " + id);
        return c;
    }

    /** Parse newline/comma/space separated MSISDNs; digits only; dedupe preserve order. */
    public static List<String> parseMsisdns(String blob) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (blob == null || blob.isBlank()) return List.of();
        for (String part : blob.split("[,;\\s]+")) {
            String d = part.replaceAll("\\D", "");
            if (d.length() >= 8 && d.length() <= 15) {
                set.add(d);
            }
        }
        return List.copyOf(set);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public record ClaimedTarget(CampaignEntity campaign, CampaignTargetEntity target) {}
}
