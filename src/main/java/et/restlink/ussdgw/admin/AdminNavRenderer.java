package et.restlink.ussdgw.admin;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;

/** Admin header nav fragments (RBAC-aware). OTA shell adapted for USSD pages only. */
@ApplicationScoped
public class AdminNavRenderer {

    public static String authNavHtml(boolean loggedIn) {
        if (loggedIn) {
            return "<a class=\"hover:text-signal\" href=\"/admin/logout\">Logout</a>";
        }
        return "<a class=\"hover:text-signal\" href=\"/admin/login\">Login</a>";
    }

    public static String themeToggleHtml() {
        return "<button type=\"button\" class=\"theme-toggle\" data-theme-toggle "
                + "@click=\"$store.ussd.toggleTheme()\" "
                + "aria-label=\"Toggle light or dark theme\" title=\"Toggle theme\">"
                + "<span class=\"theme-toggle-label\">Theme</span></button>";
    }

    /** Full ADMIN/OPS nav — USSD pages only (no fleet/CAP/OTA portal). */
    public static String adminNavLinks(boolean loggedIn) {
        return """
                <a class="hover:text-signal" href="/admin">Dashboard</a>
                <a class="hover:text-signal" href="/telemetry/">Monitor Hub</a>
                <a class="hover:text-signal" href="/admin/ss7/config">SS7</a>
                <a class="hover:text-signal" href="/admin/smpp/config">SMPP</a>
                <a class="hover:text-signal" href="/admin/http/config">HTTP</a>
                <a class="hover:text-signal" href="/admin/grpc">gRPC</a>
                <a class="hover:text-signal" href="/admin/routing">Routing</a>
                <a class="hover:text-signal" href="/admin/bridge">Bridge</a>
                <a class="hover:text-signal" href="/admin/campaigns">Campaigns</a>
                <a class="hover:text-signal" href="/admin/cdr">CDR</a>
                <a class="hover:text-signal" href="/admin/tenants">Tenants</a>
                <a class="hover:text-signal" href="/admin/users">Users</a>
                <a class="hover:text-signal" href="/admin/diameter">Diameter</a>
                <a class="hover:text-signal" href="/admin/sip">SIP</a>
                <a class="hover:text-signal" href="/admin/lab-mo">Lab MO</a>
                %s
                %s
                """.formatted(themeToggleHtml(), authNavHtml(loggedIn)).trim();
    }

    public Map<String, String> adminPageVars(boolean loggedIn, Map<String, String> extra) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("{{NAV_LINKS}}", adminNavLinks(loggedIn));
        m.put("{{AUTH_NAV}}", authNavHtml(loggedIn));
        m.put("{{SUCCESS_BANNER}}", "");
        m.put("{{NOTICE}}", "");
        m.put("{{ERROR}}", "");
        if (extra != null) {
            m.putAll(extra);
        }
        return m;
    }
}
