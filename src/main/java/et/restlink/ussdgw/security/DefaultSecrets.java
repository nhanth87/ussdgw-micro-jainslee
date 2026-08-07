package et.restlink.ussdgw.security;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in credential defaults and the scan that refuses to let them reach production.
 *
 * <p>The values below are duplicated as {@code @ConfigProperty(defaultValue = ...)} in
 * {@code AdminAuthService} / {@code UssdConfigService}, so deleting the property lines from
 * {@code application.properties} does not remove them — only overriding the property (or the
 * matching env var) does.
 */
public final class DefaultSecrets {

    public static final String SESSION_HMAC_SECRET = "ussd-dev-session-hmac-change-me";
    public static final String ADMIN_API_KEY = "ussd-admin";
    public static final String FIRST_RUN_PASSWORD = "ussd-admin";

    public static final String PROP_SESSION_HMAC_SECRET = "ussd.admin.session-hmac-secret";
    public static final String PROP_ADMIN_API_KEY = "ussd.admin.api-key";
    public static final String PROP_FIRST_RUN_PASSWORD = "ussd.admin.first-run-password";
    public static final String PROP_ALLOW_DEFAULTS = "ussd.lab.allow-default-secrets";

    public static final String ENV_SESSION_HMAC_SECRET = "USSD_ADMIN_SESSION_HMAC_SECRET";
    public static final String ENV_ADMIN_API_KEY = "USSD_ADMIN_API_KEY";
    public static final String ENV_FIRST_RUN_PASSWORD = "USSD_ADMIN_FIRST_RUN_PASSWORD";

    private DefaultSecrets() {
    }

    /** One unsafe credential: which property holds it, why it is unsafe, how to fix it. */
    public record Finding(String property, String reason, String envVar) {
        public String describe() {
            return property + " — " + reason + " (set " + property + "=… or " + envVar + "=…)";
        }
    }

    /**
     * Scans the three credentials that grant full ADMIN. The HMAC secret is the worst of the
     * three: knowing it is enough to forge an ADMIN session cookie without ever logging in.
     */
    public static List<Finding> scan(String sessionHmacSecret, String adminApiKey,
                                     String firstRunPassword) {
        List<Finding> out = new ArrayList<>(3);
        if (isBlank(sessionHmacSecret)) {
            out.add(new Finding(PROP_SESSION_HMAC_SECRET,
                    "blank — session cookies cannot be signed", ENV_SESSION_HMAC_SECRET));
        } else if (SESSION_HMAC_SECRET.equals(sessionHmacSecret.trim())) {
            out.add(new Finding(PROP_SESSION_HMAC_SECRET,
                    "still the built-in default — anyone who knows it can forge an ADMIN "
                            + "session cookie without a password",
                    ENV_SESSION_HMAC_SECRET));
        }
        if (isBlank(adminApiKey)) {
            out.add(new Finding(PROP_ADMIN_API_KEY, "blank", ENV_ADMIN_API_KEY));
        } else if (ADMIN_API_KEY.equals(adminApiKey.trim())) {
            out.add(new Finding(PROP_ADMIN_API_KEY,
                    "still the built-in default — grants unscoped ADMIN over the whole admin API",
                    ENV_ADMIN_API_KEY));
        }
        if (firstRunPassword != null && FIRST_RUN_PASSWORD.equals(firstRunPassword.trim())) {
            out.add(new Finding(PROP_FIRST_RUN_PASSWORD,
                    "still the built-in default — seeds a known ADMIN form login; "
                            + "leave blank to mint a random password instead",
                    ENV_FIRST_RUN_PASSWORD));
        }
        return List.copyOf(out);
    }

    /** Actionable startup message naming every property the operator must change. */
    public static String message(List<Finding> findings, boolean labOptOutActive) {
        StringBuilder sb = new StringBuilder();
        sb.append(labOptOutActive
                ? "[secrets] RUNNING WITH BUILT-IN DEFAULT CREDENTIALS ("
                : "[secrets] refusing to start — built-in default credentials in use (");
        sb.append(findings.size()).append("):");
        for (Finding f : findings) {
            sb.append("\n  * ").append(f.describe());
        }
        if (labOptOutActive) {
            sb.append("\n  Allowed only because ").append(PROP_ALLOW_DEFAULTS)
                    .append("=true. Remove that line before production.");
        } else {
            sb.append("\n  Lab escape hatch: set ").append(PROP_ALLOW_DEFAULTS)
                    .append("=true in dist/configs/application.properties to boot anyway.");
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
