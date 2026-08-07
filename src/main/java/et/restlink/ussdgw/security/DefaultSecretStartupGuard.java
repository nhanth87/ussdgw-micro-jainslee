package et.restlink.ussdgw.security;

import io.quarkus.runtime.StartupEvent;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Fails startup while the admin session HMAC secret or API key still equals the value baked
 * into the source. Runs before schema init so a misconfigured node never reaches the network.
 *
 * <p>Lab escape hatch: {@code ussd.lab.allow-default-secrets=true} downgrades the failure to a
 * loud warning. The shipped {@code build/application.properties} sets it so the Digicom lab
 * keeps booting; production must delete that line.
 */
@ApplicationScoped
public class DefaultSecretStartupGuard {

    private static final Logger LOG = LogManager.getLogger(DefaultSecretStartupGuard.class);

    @ConfigProperty(name = DefaultSecrets.PROP_SESSION_HMAC_SECRET,
            defaultValue = DefaultSecrets.SESSION_HMAC_SECRET)
    String sessionHmacSecret;

    @ConfigProperty(name = DefaultSecrets.PROP_ADMIN_API_KEY,
            defaultValue = DefaultSecrets.ADMIN_API_KEY)
    String adminApiKey;

    @ConfigProperty(name = DefaultSecrets.PROP_FIRST_RUN_PASSWORD, defaultValue = "")
    String firstRunPassword;

    /** Field initialiser mirrors {@code defaultValue} so non-CDI construction is fail-closed. */
    @ConfigProperty(name = DefaultSecrets.PROP_ALLOW_DEFAULTS, defaultValue = "false")
    boolean allowDefaultSecrets = false;

    void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE + 50) StartupEvent ev) {
        enforce();
    }

    /** Throws when a built-in default is still in use and the lab opt-out is not set. */
    public void enforce() {
        List<DefaultSecrets.Finding> findings =
                DefaultSecrets.scan(sessionHmacSecret, adminApiKey, firstRunPassword);
        if (findings.isEmpty()) {
            LOG.info("[secrets] OK — no built-in default admin credentials in use");
            return;
        }
        String message = DefaultSecrets.message(findings, allowDefaultSecrets);
        if (allowDefaultSecrets) {
            LOG.warn(message);
            return;
        }
        LOG.error(message);
        throw new IllegalStateException(message);
    }
}
