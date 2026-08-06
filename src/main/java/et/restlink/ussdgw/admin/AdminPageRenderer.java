package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.admin.AdminHttpHandler.HttpReply;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Disk-backed admin HTML ({@code ussd.admin.ui-dir}, default {@code app/html}).
 * Templates use {@code {{TOKEN}}} substitution — leftovers are stripped, never left raw.
 */
@ApplicationScoped
public class AdminPageRenderer {

    private static final Logger LOG = LogManager.getLogger(AdminPageRenderer.class);
    private static final AtomicBoolean LOGGED_ROOT = new AtomicBoolean();
    private static final Pattern TEMPLATE_TOKEN = Pattern.compile("\\{\\{[A-Z][A-Z0-9_]*\\}\\}");

    @ConfigProperty(name = "ussd.admin.ui-dir", defaultValue = "app/html")
    String uiDir;

    public HttpReply pageWith(String name, Map<String, String> vars) throws Exception {
        byte[] raw = readFile(uiRoot(), "admin/" + name);
        if (raw == null) {
            LOG.warn("[admin] missing UI file {}/admin/{}", uiRoot(), name);
            if ("index.html".equals(name)) {
                return HttpReply.html(fallbackDashboard());
            }
            return HttpReply.notFound();
        }
        String html = applyTemplateVars(name, new String(raw, StandardCharsets.UTF_8), vars);
        return HttpReply.html(html);
    }

    public String renderPartial(String name, Map<String, String> vars) {
        return renderPartialFrom(uiRoot(), name, vars);
    }

    public static String renderPartialStatic(String name, Map<String, String> vars) {
        return renderPartialFrom(resolveUiRoot(System.getProperty("ussd.admin.ui-dir", "app/html")),
                name, vars);
    }

    static String renderPartialFrom(Path root, String name, Map<String, String> vars) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String rel = name.startsWith("partials/") ? name : "partials/" + name;
        try {
            byte[] raw = readFile(root, rel);
            if (raw == null) {
                LOG.warn("[admin] missing partial {}/{}", root, rel);
                return "";
            }
            return applyTemplateVars(rel, new String(raw, StandardCharsets.UTF_8), vars);
        } catch (IOException ex) {
            LOG.warn("[admin] failed reading partial {}: {}", rel, ex.toString());
            return "";
        }
    }

    static String applyTemplateVars(String templateName, String html, Map<String, String> vars) {
        if (html == null) {
            return "";
        }
        String out = html;
        if (vars != null) {
            for (var entry : vars.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                out = out.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
            }
        }
        Matcher leftover = TEMPLATE_TOKEN.matcher(out);
        if (leftover.find()) {
            StringBuilder found = new StringBuilder();
            leftover.reset();
            while (leftover.find()) {
                if (found.length() > 0) {
                    found.append(", ");
                }
                found.append(leftover.group());
            }
            LOG.warn("[admin] unsubstituted template token(s) in {}: {} — stripping to empty",
                    templateName == null ? "?" : templateName, found);
            out = leftover.replaceAll("");
        }
        return out;
    }

    public HttpReply staticResource(String rest) throws Exception {
        Path root = uiRoot();
        byte[] preferred = readFile(root, "admin/static/" + rest);
        byte[] raw = preferred != null ? preferred : readFile(root, "admin/" + rest);
        if (raw == null) {
            return HttpReply.notFound();
        }
        String ct = rest.endsWith(".css") ? "text/css"
                : rest.endsWith(".js") ? "application/javascript; charset=utf-8"
                : "application/octet-stream";
        return HttpReply.bytes(ct, raw);
    }

    public HttpReply landingPage() throws Exception {
        byte[] raw = readFile(uiRoot(), "landing.html");
        if (raw == null) {
            return HttpReply.html(fallbackLandingHtml());
        }
        return HttpReply.html(new String(raw, StandardCharsets.UTF_8));
    }

    public static String fallbackLandingHtml() {
        return """
                <!DOCTYPE html><html lang="en"><head><meta charset="utf-8"/>
                <title>RestLink USSD GW</title></head>
                <body>
                <h1>RestLink USSD GW</h1>
                <p>3GPP USSD pull/push gateway.</p>
                <p><a href="/admin/login">Admin login</a> · <a href="/health">Health</a></p>
                </body></html>
                """;
    }

    public static String fallbackDashboard() {
        return """
                <!DOCTYPE html><html><head><meta charset="utf-8"/><title>USSD Admin</title></head>
                <body>
                <h1>RestLink USSD Admin</h1>
                <p>Static templates missing from app/html — serving fallback.</p>
                <ul>
                  <li><a href="/telemetry/?tab=ss7">SS7</a></li>
                  <li><a href="/telemetry/?tab=smpp">SMPP</a></li>
                  <li><a href="/telemetry/?tab=http">HTTP</a></li>
                  <li><a href="/health">/health</a></li>
                </ul>
                </body></html>
                """;
    }

    public static String esc(Object o) {
        return o == null ? "" : escapeHtml(o.toString());
    }

    public static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    Path uiRoot() {
        Path root = resolveUiRoot(uiDir);
        if (LOGGED_ROOT.compareAndSet(false, true)) {
            LOG.info("[admin] UI directory: {} (exists={})", root, Files.isDirectory(root));
        }
        return root;
    }

    static Path resolveUiRoot(String configured) {
        String cfg = configured == null || configured.isBlank() ? "app/html" : configured.trim();
        Path p = Path.of(cfg);
        if (!p.isAbsolute()) {
            p = Path.of(System.getProperty("user.dir", ".")).resolve(p);
        }
        return p.toAbsolutePath().normalize();
    }

    static byte[] readFile(Path root, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.contains("..")) {
            return null;
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            return null;
        }
        if (!Files.isRegularFile(resolved)) {
            return null;
        }
        return Files.readAllBytes(resolved);
    }
}
