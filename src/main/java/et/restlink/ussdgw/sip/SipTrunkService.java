package et.restlink.ussdgw.sip;

import et.restlink.ussdgw.persist.SipTrunkEntity;
import et.restlink.ussdgw.persist.TenantEntity;
import et.restlink.ussdgw.security.AsUrlValidator;
import et.restlink.ussdgw.tenant.TenantService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@ApplicationScoped
public class SipTrunkService {
    private static final Logger LOG = LogManager.getLogger(SipTrunkService.class);

    @Inject TenantService tenants;
    @Inject AsUrlValidator asUrlValidator;

    @Transactional
    public List<SipTrunkEntity> list() {
        return SipTrunkEntity.find("order by trunkId").list();
    }

    @Transactional
    public Optional<SipTrunkEntity> byId(String trunkId) {
        if (trunkId == null || trunkId.isBlank()) {
            return Optional.empty();
        }
        return SipTrunkEntity.findByIdOptional(trunkId.trim());
    }

    @Transactional
    public Optional<SipTrunkEntity> resolveForTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        Optional<TenantEntity> t = tenants.byId(tenantId.trim());
        if (t.isEmpty() || t.get().sipTrunkId == null || t.get().sipTrunkId.isBlank()) {
            return Optional.empty();
        }
        return byId(t.get().sipTrunkId).filter(x -> x.enabled);
    }

    /**
     * Match inbound peer host against an enabled trunk (exact host, case-insensitive).
     * From-host matching is a weak peer check — not SIP digest auth.
     */
    @Transactional
    public Optional<SipTrunkEntity> matchPeer(String peerHost) {
        if (peerHost == null || peerHost.isBlank()) {
            return Optional.empty();
        }
        String host = peerHost.trim();
        List<SipTrunkEntity> all = SipTrunkEntity.find("enabled = true").list();
        for (SipTrunkEntity t : all) {
            if (hostEquals(t.peerHost, host)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /**
     * Shared trunk (blank tenantId) may be used by any rule tenant; otherwise trunk.tenantId
     * must equal the rule/session tenantId.
     */
    public static boolean trunkAllowsTenant(SipTrunkEntity trunk, String tenantId) {
        if (trunk == null) {
            return false;
        }
        if (trunk.tenantId == null || trunk.tenantId.isBlank()) {
            return true;
        }
        if (tenantId == null || tenantId.isBlank()) {
            return false;
        }
        return trunk.tenantId.trim().equals(tenantId.trim());
    }

    @Transactional
    public SipTrunkEntity upsert(String trunkId, String displayName, String peerHost, int peerPort,
                                 String transport, String fromUri, String requestUriTemplate,
                                 String inboundBody, String tenantId, boolean enabled) {
        if (trunkId == null || trunkId.isBlank()) {
            throw new IllegalArgumentException("trunkId required");
        }
        if (peerHost == null || peerHost.isBlank()) {
            throw new IllegalArgumentException("peerHost required");
        }
        if (asUrlValidator != null) {
            Optional<String> ssrf = asUrlValidator.rejectSipRequestUriTemplate(requestUriTemplate);
            if (ssrf.isPresent()) {
                throw new IllegalArgumentException(ssrf.get());
            }
        }
        String id = trunkId.trim();
        String peer = peerHost.trim();
        if (enabled) {
            rejectDuplicateEnabledPeer(id, peer);
        }
        SipTrunkEntity e = SipTrunkEntity.findById(id);
        if (e == null) {
            e = new SipTrunkEntity();
            e.trunkId = id;
            e.createdAt = Instant.now();
        }
        e.displayName = blank(displayName);
        e.peerHost = peer;
        e.peerPort = peerPort <= 0 ? 5060 : peerPort;
        e.transport = normalizeTransport(transport);
        e.fromUri = blank(fromUri);
        e.requestUriTemplate = blank(requestUriTemplate);
        e.inboundBody = "SDP".equalsIgnoreCase(inboundBody) ? "SDP" : "BODY";
        e.tenantId = blank(tenantId);
        e.enabled = enabled;
        e.persist();
        LOG.info("SIP trunk upsert id={} peer={}:{}", id, e.peerHost, e.peerPort);
        return e;
    }

    private void rejectDuplicateEnabledPeer(String trunkId, String peerHost) {
        ensurePeerHostAvailable(trunkId, peerHost, SipTrunkEntity.find("enabled = true").list());
    }

    @Transactional
    public boolean delete(String trunkId) {
        return SipTrunkEntity.deleteById(trunkId == null ? "" : trunkId.trim());
    }

    /**
     * Resolve Request-URI; {@code {msisdn}} is replaced with digits-only (SSRF / injection guard).
     */
    public String resolveToUri(SipTrunkEntity trunk, String msisdn, String fallbackTemplate) {
        String digits = digitsOnly(msisdn);
        String tpl = trunk != null && trunk.requestUriTemplate != null
                && !trunk.requestUriTemplate.isBlank()
                ? trunk.requestUriTemplate
                : fallbackTemplate;
        if (tpl == null || tpl.isBlank()) {
            String peer = trunk == null ? "peer.invalid" : trunk.peerHost;
            int port = trunk == null ? 5060 : trunk.peerPort;
            return "sip:" + digits + "@" + peer
                    + (port == 5060 ? "" : ":" + port);
        }
        return tpl.replace("{msisdn}", digits);
    }

    public String resolveFromUri(SipTrunkEntity trunk, String fallback) {
        if (trunk != null && trunk.fromUri != null && !trunk.fromUri.isBlank()) {
            return trunk.fromUri.trim();
        }
        return fallback == null ? "sip:ussdgw@restlink.local" : fallback;
    }

    /** Strip non-digits for URI substitution (empty when none). */
    public static String digitsOnly(String msisdn) {
        if (msisdn == null || msisdn.isBlank()) {
            return "";
        }
        return msisdn.replaceAll("\\D", "");
    }

    /**
     * Fail-closed unique peer host (case-insensitive). Package-visible for unit tests.
     */
    static void ensurePeerHostAvailable(String trunkId, String peerHost,
                                        List<SipTrunkEntity> existing) {
        if (existing == null || peerHost == null || peerHost.isBlank()) {
            return;
        }
        String id = trunkId == null ? "" : trunkId.trim();
        for (SipTrunkEntity t : existing) {
            if (t == null || t.trunkId == null) {
                continue;
            }
            if (id.equals(t.trunkId.trim())) {
                continue;
            }
            if (hostEquals(t.peerHost, peerHost)) {
                throw new IllegalArgumentException(
                        "peerHost already used by enabled trunk " + t.trunkId);
            }
        }
    }

    private static boolean hostEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.trim().equalsIgnoreCase(b.trim());
    }

    private static String normalizeTransport(String t) {
        if (t == null || t.isBlank()) {
            return "UDP";
        }
        String u = t.trim().toUpperCase();
        return "TCP".equals(u) ? "TCP" : "UDP";
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
