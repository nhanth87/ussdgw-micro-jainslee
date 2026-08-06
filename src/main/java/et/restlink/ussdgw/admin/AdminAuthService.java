package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.persist.AdminUserEntity;
import et.restlink.ussdgw.persist.TenantEntity;
import et.restlink.ussdgw.tenant.AdminUserService;
import et.restlink.ussdgw.tenant.TenantGuard;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Resolves admin principal for data scoping.
 * Global {@code ussd.admin.api-key} → full access.
 * Session cookie (login) / Basic auth / tenant {@code httpApiKey}.
 */
@ApplicationScoped
public class AdminAuthService {
    public record Principal(String role, String tenantId, String username, boolean fromSession) {
        public Principal(String role, String tenantId) {
            this(role, tenantId, null, false);
        }
        public boolean isTenantScoped() {
            return "TENANT".equals(role) && tenantId != null && !tenantId.isBlank();
        }
    }

    @Inject UssdConfigService config;
    @Inject TenantGuard tenantGuard;
    @Inject AdminUserService users;

    @ConfigProperty(name = "ussd.admin.session-hmac-secret",
            defaultValue = "ussd-dev-session-hmac-change-me")
    String sessionHmacSecret;

    public Optional<Principal> authenticate(Map<String, String> headers, Map<String, String> query) {
        try {
            return authenticate0(headers, query);
        } catch (RuntimeException ex) {
            // SLEE thread may lack request context — never turn auth into 500
            return Optional.empty();
        }
    }

    /** Issue a signed session cookie after successful password login. */
    public Optional<String> login(String username, String password) {
        if (username == null || password == null || !users.authenticate(username, password)) {
            return Optional.empty();
        }
        Optional<AdminUserEntity> u = users.byUsername(username);
        if (u.isEmpty() || !u.get().enabled) {
            return Optional.empty();
        }
        String role = u.get().role == null ? "OPS" : u.get().role;
        String tid = "TENANT".equals(role) ? u.get().tenantId : null;
        Instant exp = Instant.now().plus(1, ChronoUnit.DAYS);
        return Optional.of(SignedSessionCookie.issue(sessionHmacSecret, username, role, tid, exp));
    }

    public String sessionHmacSecret() {
        return sessionHmacSecret;
    }

    private Optional<Principal> authenticate0(Map<String, String> headers, Map<String, String> query) {
        Optional<String> cookieTok = SignedSessionCookie.extractFromCookieHeader(
                header(headers, "Cookie"));
        if (cookieTok.isPresent()) {
            Optional<SignedSessionCookie.Claims> claims =
                    SignedSessionCookie.verify(sessionHmacSecret, cookieTok.get());
            if (claims.isPresent()) {
                SignedSessionCookie.Claims c = claims.get();
                return Optional.of(new Principal(c.role(), c.tenantId(), c.username(), true));
            }
        }
        String key = header(headers, "X-USSD-Admin-Key");
        if (key == null && query != null) key = query.get("key");
        if (key != null && config.adminKeyOk(key)) {
            return Optional.of(new Principal("ADMIN", null));
        }
        if (key != null) {
            Optional<TenantEntity> t = tenantGuard.byHttpApiKey(key);
            if (t.isPresent()) {
                return Optional.of(new Principal("TENANT", t.get().tenantId));
            }
        }
        String basic = header(headers, "Authorization");
        if (basic != null && basic.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String decoded = new String(Base64.getDecoder().decode(basic.substring(6).trim()),
                        StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                if (colon > 0) {
                    String user = decoded.substring(0, colon);
                    String pass = decoded.substring(colon + 1);
                    if (users.authenticate(user, pass)) {
                        Optional<AdminUserEntity> u = users.byUsername(user);
                        if (u.isPresent() && u.get().enabled) {
                            String role = u.get().role == null ? "OPS" : u.get().role;
                            String tid = "TENANT".equals(role) ? u.get().tenantId : null;
                            return Optional.of(new Principal(role, tid, user, false));
                        }
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // bad base64
            }
        }
        return Optional.empty();
    }

    private static String header(Map<String, String> headers, String name) {
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
