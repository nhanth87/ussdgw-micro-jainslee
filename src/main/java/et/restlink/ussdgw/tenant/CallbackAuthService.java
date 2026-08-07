package et.restlink.ussdgw.tenant;

import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.config.UssdConfigService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Optional;

/**
 * AS callback ingress auth: tenant {@code httpApiKey} (or global admin key) required.
 */
@ApplicationScoped
public class CallbackAuthService {
    public static final String HDR_API_KEY = "X-USSD-Api-Key";
    public static final String HDR_API_KEY_ALT = "X-API-Key";

    @Inject TenantGuard tenantGuard;
    @Inject VirtualSessionStore store;
    @Inject UssdConfigService config;

    public enum Result {
        OK, UNAUTHORIZED, NO_SESSION
    }

    public Result authorizeCallback(String correlationId, Map<String, String> headers, String presentedKey) {
        String key = presentedKey;
        if ((key == null || key.isBlank()) && headers != null) {
            key = header(headers, HDR_API_KEY);
            if (key == null) key = header(headers, HDR_API_KEY_ALT);
        }
        if (key != null && config.adminKeyOk(key)) {
            return Result.OK;
        }
        Optional<VirtualSession> sess = store.get(correlationId);
        if (sess.isEmpty()) {
            // Still require a valid key — avoid probing; treat as unauthorized unless admin
            return Result.UNAUTHORIZED;
        }
        String tenantId = sess.get().tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            // Unbound lab session: admin key already checked; allow only admin
            return Result.UNAUTHORIZED;
        }
        if (tenantGuard.apiKeyMatches(tenantId, key)) {
            return Result.OK;
        }
        return Result.UNAUTHORIZED;
    }

    public Result authorizeCallback(String correlationId, Map<String, String> headers) {
        return authorizeCallback(correlationId, headers, null);
    }

    /**
     * Outcome of authorizing a classic NI push.
     *
     * @param tenantId  owning tenant, or null for the global admin key / disabled auth
     * @param networkId the tenant's network, or null when no tenant was resolved
     */
    public record NiAuth(Result result, String tenantId, Integer networkId) {
        public boolean ok() {
            return result == Result.OK;
        }

        static NiAuth unauthorized() {
            return new NiAuth(Result.UNAUTHORIZED, null, null);
        }
    }

    /**
     * Authorize a network-initiated push on the classic {@code /ussd} ingress.
     *
     * <p>Classic ran this servlet with no security constraint at all — it relied on sitting inside
     * the operator VLAN. That is not a safe default here, so the API key is required by default and
     * an operator must opt out explicitly ({@code ussd.http.ni.auth-required=false}) for lab use.
     * The key resolves the tenant, exactly as the sibling {@code /as/callback} path does.
     */
    public NiAuth authorizeNi(Map<String, String> headers, boolean authRequired) {
        String key = header(headers, HDR_API_KEY);
        if (key == null) key = header(headers, HDR_API_KEY_ALT);
        if (key != null && !key.isBlank()) {
            if (config.adminKeyOk(key)) {
                return new NiAuth(Result.OK, null, null);
            }
            Optional<et.restlink.ussdgw.persist.TenantEntity> tenant =
                    tenantGuard.byHttpApiKey(key.trim());
            if (tenant.isPresent()) {
                return new NiAuth(Result.OK, tenant.get().tenantId, tenant.get().networkId);
            }
            return NiAuth.unauthorized();
        }
        return authRequired ? NiAuth.unauthorized() : new NiAuth(Result.OK, null, null);
    }

    static String header(Map<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        String v = headers.get(name);
        if (v != null) return v;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }
}
