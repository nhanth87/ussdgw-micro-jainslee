package et.restlink.ussdgw.security;

import et.restlink.ussdgw.persist.AdminUserEntity;
import et.restlink.ussdgw.tenant.AdminUserService;

import io.quarkus.runtime.StartupEvent;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import jakarta.transaction.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Ensures at least one enabled ADMIN can form-login when the table is empty
 * (Digicom lab shipped with zero {@code ussd_admin_user} rows).
 *
 * <p>When {@code ussd.admin.first-run-password} is set (lab), that known password is used.
 * When blank, a random password is minted and printed once (OTA-style).
 */
@ApplicationScoped
public class UssdFirstRunSeeder {

    private static final Logger LOG = LogManager.getLogger(UssdFirstRunSeeder.class);

    /**
     * Console-only logger (log4j2.xml routes {@code USSD_FIRST_RUN} to CONSOLE with
     * {@code additivity="false"}). A generated ADMIN password must not be written into
     * {@code dist/logs/ussdgw.log}, which is rotated but kept for days.
     */
    private static final Logger CONSOLE = LogManager.getLogger("USSD_FIRST_RUN");
    private static final SecureRandom RNG = new SecureRandom();

    static final String ADMIN_USERNAME = "admin";

    @Inject AdminUserService users;

    @ConfigProperty(name = "ussd.admin.first-run-seed", defaultValue = "true")
    boolean firstRunSeed;

    /** Lab: known password. Empty ⇒ random password logged once. */
    @ConfigProperty(name = "ussd.admin.first-run-password", defaultValue = "")
    String firstRunPassword;

    void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE + 200) StartupEvent ev) {
        seed();
    }

    @Transactional
    public void seed() {
        if (!firstRunSeed) {
            return;
        }
        ensureUsableAdmin();
    }

    private void ensureUsableAdmin() {
        List<AdminUserEntity> all = users.list();
        boolean usableAdmin = all.stream().anyMatch(u ->
                u != null
                        && u.enabled
                        && "ADMIN".equalsIgnoreCase(u.role == null ? "" : u.role)
                        && u.passwordHash != null
                        && !u.passwordHash.isBlank());
        if (usableAdmin) {
            return;
        }
        String password = (firstRunPassword == null || firstRunPassword.isBlank())
                ? randomPassword()
                : firstRunPassword.trim();
        if (users.byUsername(ADMIN_USERNAME).isPresent()) {
            users.update(ADMIN_USERNAME, password, "ADMIN", null, "Platform Admin", true);
        } else {
            users.create(ADMIN_USERNAME, password, "ADMIN", null, "Platform Admin", true);
        }
        if (firstRunPassword == null || firstRunPassword.isBlank()) {
            CONSOLE.warn("[first-run] admin user '{}' has a NEW RANDOM password: {} — "
                            + "log in at /admin/login and change it now. Printed to the console "
                            + "only (never to dist/logs/) and not printed again once an ADMIN "
                            + "password exists.",
                    ADMIN_USERNAME, password);
            LOG.warn("[first-run] seeded admin user '{}' with a random password — "
                    + "see the console output of this boot for the value.", ADMIN_USERNAME);
        } else {
            LOG.warn("[first-run] seeded admin user '{}' with the configured lab password "
                            + "(ussd.admin.first-run-password) — change it outside lab.",
                    ADMIN_USERNAME);
        }
    }

    private static String randomPassword() {
        byte[] buf = new byte[15];
        RNG.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
