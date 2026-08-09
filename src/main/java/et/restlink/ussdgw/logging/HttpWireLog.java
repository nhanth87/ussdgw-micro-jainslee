package et.restlink.ussdgw.logging;

import jakarta.enterprise.context.ApplicationScoped;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Durable HTTP wire log for AS <strong>pull</strong> (GW→AS) and <strong>push/NI</strong>
 * (AS→GW) so operators can verify later. Log4j2 logger {@code USSD_HTTP} → async
 * RollingFile ({@code ussd-http.log}); hot path never blocks on disk when the async
 * ring has capacity ({@code blocking=false}).
 *
 * <p>Line shape (single line, pipe fields; body newlines flattened):
 * {@code side|plane|corr|method|target|httpStatus|contentType|bodyLen|body|extra}
 */
@ApplicationScoped
public class HttpWireLog {
    private static final Logger HTTP = LogManager.getLogger("USSD_HTTP");

    public static final String SIDE_OUT = "OUT";
    public static final String SIDE_IN = "IN";
    public static final String PLANE_PULL = "PULL";
    public static final String PLANE_NI = "NI";
    public static final String PLANE_CALLBACK = "CALLBACK";
    public static final String PLANE_GATED = "GATED";

    @ConfigProperty(name = "ussd.http.wire-log.enabled", defaultValue = "true")
    boolean enabled;

    /** Soft cap per body field — USSD XML/JSON is small; protects async ring at 10k TPS. */
    @ConfigProperty(name = "ussd.http.wire-log.max-body-chars", defaultValue = "1048576")
    int maxBodyChars;

    public boolean enabled() {
        return enabled;
    }

    /** Outbound AS pull request (GW → AS). */
    public void pullRequest(String corr, String url, String contentType, String body) {
        write(SIDE_OUT, PLANE_PULL, corr, "POST", url, -1, contentType, body, "phase=req");
    }

    /** Outbound AS pull response (AS → GW completion). */
    public void pullResponse(String corr, String url, int status, String contentType,
                             String body, String outcome) {
        write(SIDE_OUT, PLANE_PULL, corr, "POST", url, status, contentType, body,
                outcome == null || outcome.isBlank() ? "phase=resp" : ("phase=resp|" + outcome.trim()));
    }

    /** Outbound gated AS notify request. */
    public void gatedRequest(String corr, String url, String contentType, String body) {
        write(SIDE_OUT, PLANE_GATED, corr, "POST", url, -1, contentType, body, "phase=req");
    }

    /** Outbound gated AS notify response. */
    public void gatedResponse(String corr, String url, int status, String body, String outcome) {
        write(SIDE_OUT, PLANE_GATED, corr, "POST", url, status, null, body,
                outcome == null || outcome.isBlank() ? "phase=resp" : ("phase=resp|" + outcome.trim()));
    }

    /** Inbound classic NI push ({@code /ussd}) request from AS. */
    public void niRequest(String corr, String path, String contentType, String body) {
        write(SIDE_IN, PLANE_NI, corr, "POST", path, -1, contentType, body, "phase=req");
    }

    /** Inbound NI HTTP response body GW returns to AS (sync or park complete). */
    public void niResponse(String corr, String path, int status, String contentType, String body) {
        write(SIDE_IN, PLANE_NI, corr, "POST", path, status, contentType, body, "phase=resp");
    }

    /** Inbound AS async callback request. */
    public void callbackRequest(String corr, String path, String contentType, String body) {
        write(SIDE_IN, PLANE_CALLBACK, corr, "POST", path, -1, contentType, body, "phase=req");
    }

    public void callbackResponse(String corr, String path, int status, String contentType, String body) {
        write(SIDE_IN, PLANE_CALLBACK, corr, "POST", path, status, contentType, body, "phase=resp");
    }

    void write(String side, String plane, String corr, String method, String target,
               int httpStatus, String contentType, String body, String extra) {
        if (!enabled) {
            return;
        }
        try {
            String b = flattenBody(body);
            int len = b == null ? 0 : b.length();
            HTTP.info(String.join("|",
                    nullToEmpty(side),
                    nullToEmpty(plane),
                    nullToEmpty(corr),
                    nullToEmpty(method),
                    sanitizeField(target),
                    httpStatus < 0 ? "" : Integer.toString(httpStatus),
                    sanitizeField(contentType),
                    Integer.toString(len),
                    b == null ? "" : b,
                    sanitizeField(extra)));
        } catch (Throwable ignored) {
            // Never fail MAP/HTTP path on wire-log IO.
        }
    }

    String flattenBody(String body) {
        if (body == null) {
            return null;
        }
        String t = body.replace('\r', ' ').replace('\n', ' ').replace('|', '/');
        int max = Math.max(256, maxBodyChars);
        if (t.length() > max) {
            return t.substring(0, max) + "…truncated=" + t.length();
        }
        return t;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static String sanitizeField(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return s.replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim();
    }
}
