package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.admin.smpp.SmppAdminBindings;
import et.restlink.ussdgw.admin.smpp.SmppAdminController;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.UssdSagaCoordinator;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.cdr.CdrRecord;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.SmppConfigSupport;
import et.restlink.ussdgw.config.Ss7ConfigSupport;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.ra.smpp.SmppEndpointRegistry;
import et.restlink.ussdgw.service.AsPullClient;
import et.restlink.ussdgw.service.BridgeGateScheduler;
import et.restlink.ussdgw.service.GrpcApplyService;
import et.restlink.ussdgw.service.HttpApplyService;
import et.restlink.ussdgw.service.SmppApplyService;
import et.restlink.ussdgw.service.Ss7ApplyService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microjainslee.admin.RaAdminHttpResponse;
import com.microjainslee.monitor.MonitorHandler;
import com.microjainslee.ra.httpserver.admin.HttpServerAdminBindings;
import com.microjainslee.ra.jss7.admin.Ss7AdminBindings;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Admin + Monitor Hub HTTP surface (OTA pattern).
 * Plane GETs {@code /admin/ss7|smpp|http} serve Routing-style form shells (no hub redirect).
 * {@code /admin/ss7/config} aliases the same panel for POST/HTMX. Monitor Hub = metrics only.
 */
@ApplicationScoped
public class AdminHttpHandler {
    private static final Logger LOG = LogManager.getLogger(AdminHttpHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject UssdConfigService config;
    @Inject LinkStatusService linkStatus;
    @Inject CdrService cdr;
    @Inject VirtualSessionBridge bridge;
    @Inject VirtualSessionStore store;
    @Inject AdaptiveTimeout adaptive;
    @Inject AdminCatalogHandler catalog;
    @Inject AdminPlaneHandler planes;
    @Inject AdminCampaignHandler campaigns;
    @Inject AdminMyCampaignHandler myCampaigns;
    @Inject AdminAppUserHandler appUsers;
    @Inject AdminSipTrunkHandler sipTrunks;
    @Inject AdminLabMoHandler labMo;
    @Inject HttpApplyService httpApply;
    @Inject GrpcApplyService grpcApply;
    @Inject AdminHttpAsModeHandler httpAsModes;
    @Inject SmppApplyService smppApply;
    @Inject Ss7ApplyService ss7Apply;
    @Inject SmppConfigSupport smppConfig;
    @Inject Ss7ConfigSupport ss7Config;
    @Inject SmppEndpointRegistry smppRegistry;
    @Inject AdminAuthService adminAuth;
    @Inject AdminPageRenderer pages;
    @Inject AdminNavRenderer nav;
    @Inject BridgeGateScheduler bridgeGate;
    @Inject AsPullClient asPull;
    @Inject UssdSagaCoordinator saga;

    /**
     * {@code Secure} on the admin session cookie. Defaults to on; the plain-HTTP Digicom lab
     * (nginx :80 → :8088, no TLS) sets {@code ussd.admin.cookie-secure=false}. Field initialisers
     * mirror {@code defaultValue} so non-CDI construction in tests is fail-closed too.
     */
    @ConfigProperty(name = "ussd.admin.cookie-secure", defaultValue = "true")
    boolean cookieSecure = true;

    /** Double-submit CSRF on cookie-authenticated POSTs. Lab escape hatch: set to false. */
    @ConfigProperty(name = "ussd.admin.csrf.enabled", defaultValue = "true")
    boolean csrfEnabled = true;

    private volatile MonitorHandler monitorHub;

    /**
     * Admin HTTP reply. Headers stay a flat {@code Map} to match {@code HttpResponseExCommand},
     * but {@code Set-Cookie} may carry multiple values joined by {@link #SET_COOKIE_SEP} —
     * ra-http-server expands them with {@code addHeader} so login can emit session + CSRF
     * cookies on one 302.
     */
    public record HttpReply(int status, String contentType, byte[] body, Map<String, String> headers) {
        /** Joiner for multiple Set-Cookie values inside the flat header map. */
        public static final String SET_COOKIE_SEP = "\n";

        public static HttpReply html(String html) {
            return new HttpReply(200, "text/html; charset=utf-8",
                    html.getBytes(StandardCharsets.UTF_8), Map.of());
        }
        public static HttpReply json(int status, Object node) {
            try {
                return new HttpReply(status, "application/json",
                        JSON.writeValueAsBytes(node), Map.of());
            } catch (Exception e) {
                return new HttpReply(500, "application/json",
                        "{\"error\":\"serialize\"}".getBytes(StandardCharsets.UTF_8), Map.of());
            }
        }
        public static HttpReply text(int status, String body) {
            return new HttpReply(status, "text/plain; charset=utf-8",
                    body.getBytes(StandardCharsets.UTF_8), Map.of());
        }
        public static HttpReply bytes(String contentType, byte[] body) {
            return new HttpReply(200, contentType, body == null ? new byte[0] : body, Map.of());
        }
        public static HttpReply notFound() {
            return text(404, "not found");
        }
        public static HttpReply redirect(String location) {
            return new HttpReply(302, "text/plain; charset=utf-8",
                    ("Redirect: " + location).getBytes(StandardCharsets.UTF_8),
                    Map.of("Location", location));
        }
        public HttpReply withHeader(String name, String value) {
            Map<String, String> h = new LinkedHashMap<>(
                    headers == null ? Map.of() : headers);
            h.put(name, value);
            return new HttpReply(status, contentType, body, Map.copyOf(h));
        }

        /** Append a Set-Cookie without clobbering ones already on this reply. */
        public HttpReply addSetCookie(String cookie) {
            if (cookie == null || cookie.isBlank()) {
                return this;
            }
            Map<String, String> h = new LinkedHashMap<>(
                    headers == null ? Map.of() : headers);
            String existing = null;
            String existingKey = null;
            for (Map.Entry<String, String> e : h.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) {
                    existing = e.getValue();
                    existingKey = e.getKey();
                    break;
                }
            }
            if (existingKey != null) {
                h.remove(existingKey);
            }
            if (existing == null || existing.isBlank()) {
                h.put("Set-Cookie", cookie);
            } else {
                h.put("Set-Cookie", existing + SET_COOKIE_SEP + cookie);
            }
            return new HttpReply(status, contentType, body, Map.copyOf(h));
        }
    }

    /**
     * Bind SMPP + SS7 Monitor Hub hooks (call once after Apply services are ready).
     */
    public void wireRaAdminHub() {
        SmppAdminBindings.bindRegistry(smppRegistry);
        SmppAdminBindings.bindHooks(
                smppApply::apply,
                smppApply::start,
                smppApply::stop,
                smppConfig::validateHookJson,
                smppConfig::activeJsonOrLab,
                smppConfig::saveHookJson);
        Ss7AdminBindings.bindHooks(
                ss7Apply::apply,
                ss7Apply::start,
                ss7Apply::stop,
                ss7Config::validateHookJson,
                ss7Config::activeJsonOrProps,
                json -> {
                    String r = ss7Config.saveHookJson(json);
                    if (r != null && r.startsWith("{\"ok\":true")) {
                        Ss7AdminBindings.setLastConfigJson(json);
                    }
                    return r;
                });
        HttpServerAdminBindings.bindAppPanels(httpAsModes::hubGet, httpAsModes::hubPost);
        this.monitorHub = new MonitorHandler(null);
        LOG.info("[admin] RA admin hub wired (jainslee-monitor + local smpp-ra + HTTP AS panels)");
    }

    public void clearRaAdminHub() {
        SmppAdminBindings.clear();
        Ss7AdminBindings.clearHooks();
        HttpServerAdminBindings.clearAppPanels();
        monitorHub = null;
    }

    private MonitorHandler monitor() {
        MonitorHandler h = monitorHub;
        if (h == null) {
            synchronized (this) {
                h = monitorHub;
                if (h == null) {
                    h = new MonitorHandler(null);
                    monitorHub = h;
                }
            }
        }
        return h;
    }

    public boolean authorized(Map<String, String> headers, Map<String, String> query) {
        return adminAuth.authenticate(headers, query).isPresent();
    }

    public Optional<HttpReply> tryHandle(String method, String path,
                                         Map<String, String> headers,
                                         Map<String, String> query,
                                         String body) {
        if (path == null) return Optional.empty();
        String p = path.startsWith("/") ? path : "/" + path;
        if (p.equals("/health") || p.equals("/healthz")) {
            return Optional.of(HttpReply.json(200, Map.of(
                    "status", "UP",
                    "ss7.live", linkStatus.isM3uaRouteReady())));
        }

        Optional<AdminAuthService.Principal> principal = adminAuth.authenticate(headers, query);

        if (isMonitorHubPath(p)) {
            if (!isPublicMonitorStatic(method, p) && principal.isEmpty()) {
                return Optional.of(HttpReply.text(401, "unauthorized"));
            }
            AdminRequestContext.set(principal.orElse(null));
            try {
                Optional<RaAdminHttpResponse> hit = monitor().handle(method, p, query, body);
                return hit.map(AdminHttpHandler::toHttpReply);
            } finally {
                AdminRequestContext.clear();
            }
        }

        if (!(p.startsWith("/admin") || p.equals("/"))) {
            return Optional.empty();
        }

        boolean sessionOk = hasBrowserSession(principal);

        // Root → login first (OTA lab UX for Digicom); session holders go to dashboard
        if (p.equals("/")) {
            return Optional.of(HttpReply.redirect(sessionOk ? "/admin" : "/admin/login"));
        }
        if (p.startsWith("/admin/static/")) {
            try {
                return Optional.of(pages.staticResource(p.substring("/admin/static/".length())));
            } catch (Exception e) {
                return Optional.of(HttpReply.notFound());
            }
        }
        if (p.equals("/admin/login")) {
            if (sessionOk && !"POST".equalsIgnoreCase(method)) {
                return Optional.of(HttpReply.redirect("/admin"));
            }
            if ("POST".equalsIgnoreCase(method)) {
                return Optional.of(handleLoginPost(body));
            }
            try {
                return Optional.of(pages.pageWith("login.html",
                        nav.adminPageVars(false, Map.of("{{ERROR}}", ""))));
            } catch (Exception e) {
                return Optional.of(HttpReply.html("<form method=post action=/admin/login>"
                        + "user<input name=username> pass<input name=password type=password>"
                        + "<button>Login</button></form>"));
            }
        }
        if (p.equals("/admin/logout")) {
            return Optional.of(HttpReply.redirect("/admin/login")
                    .addSetCookie(SignedSessionCookie.clearCookieHeader(cookieSecure))
                    .addSetCookie(SignedSessionCookie.clearCsrfCookieHeader(cookieSecure)));
        }

        // Browser HTML shells require a form-login session cookie (API key alone is not enough).
        // API key / Basic still authorize HTMX fragments + JSON for automation.
        if (wantsShellPage(method, headers, p)) {
            if (!sessionOk) {
                return Optional.of(HttpReply.redirect("/admin/login"));
            }
            AdminAuthService.Principal shellWho = principal.orElse(null);
            if (isIdentityAdminPath(p) && !isAdminRole(shellWho)) {
                return Optional.of(identityForbidden(shellWho));
            }
            Optional<HttpReply> shell = serveShellPage(p, shellWho, query)
                    .map(r -> withCsrfCookie(r, headers));
            if (shell.isPresent()) return shell;
        }

        if (principal.isEmpty() && !isPublicAsset(p)) {
            return Optional.of(HttpReply.text(401, "unauthorized"));
        }

        AdminAuthService.Principal who = principal.orElse(null);

        // User / tenant management creates and re-roles principals — ADMIN only. OPS is scoped
        // with ADMIN for the SS7 stack editor, not for identity CRUD.
        if (isIdentityAdminPath(p) && !isAdminRole(who)) {
            return Optional.of(identityForbidden(who));
        }

        if ("POST".equalsIgnoreCase(method)) {
            Optional<HttpReply> csrf = csrfFailure(method, headers, who);
            if (csrf.isPresent()) return csrf;
            Optional<HttpReply> post = handlePost(p, body, who);
            if (post.isPresent()) return post;
        }

        String tab = query == null ? null : query.get("tab");
        return switch (p) {
            case "/admin/status" -> Optional.of(statusHtml());
            case "/admin/status.json" -> Optional.of(statusJson());
            case "/admin/cdr", "/admin/cdr/partial" -> Optional.of(cdrRowsReply(query, who));
            case "/admin/bridge" -> Optional.of(planes.bridgeGet());
            case "/admin/ss7", "/admin/ss7/config" -> Optional.of(planes.ss7Get(who));
            case "/admin/ss7/status" -> Optional.of(planes.ss7StatusGet());
            case "/admin/hlr", "/admin/hlr/config" -> Optional.of(planes.hlrGet(who));
            case "/admin/smpp", "/admin/smpp/config" -> Optional.of(planes.smppGet());
            case "/admin/smpp/status" -> Optional.of(planes.smppStatusGet());
            case "/admin/http", "/admin/http/config" -> Optional.of(planes.httpGet());
            case "/admin/http/sync" -> Optional.of(httpAsModes.get("sync", who));
            case "/admin/http/async" -> Optional.of(httpAsModes.get("async", who));
            case "/admin/http/callback" -> Optional.of(httpAsModes.get("callback", who));
            case "/admin/grpc" -> Optional.of(planes.grpcGet());
            case "/admin/sip/trunks" -> {
                if (who != null && who.isTenantScoped()) {
                    yield Optional.of(HttpReply.text(403, "forbidden for TENANT role"));
                }
                yield Optional.of(sipTrunks.get());
            }
            case "/admin/routing", "/admin/rules", "/admin/routing/partial" ->
                    Optional.of(catalog.routingGet(who));
            case "/admin/tenants", "/admin/tenants/partial" -> Optional.of(catalog.tenantsGet(who));
            case "/admin/users", "/admin/users/partial" -> Optional.of(catalog.usersGet(who));
            case "/admin/app-users", "/admin/app-users/partial" -> Optional.of(appUsers.get(who));
            case "/admin/campaigns" -> Optional.of(campaigns.get(who));
            case "/admin/my-campaigns" -> Optional.of(myCampaigns.get(who));
            case "/admin/monitor-feed" -> Optional.of(HttpReply.json(200, monitorFeedMap()));
            case "/admin/lab/mo", "/admin/lab-mo" -> Optional.of(labMo.get(who));
            case "/admin", "/admin/" -> Optional.of(statusHtml());
            case "/admin/hub", "/admin/links", "/admin/links/ss7", "/admin/links/smpp",
                 "/admin/links/http", "/telemetry/partial" -> Optional.of(
                    HttpReply.html(linkStatus.htmlPartial(resolveLinkTab(p, tab))));
            case "/admin/links/smpp.html" -> Optional.of(HttpReply.html(
                    new SmppAdminController().statusHtml(null).bodyAsString()));
            default -> {
                if (p.startsWith("/admin/")) {
                    String name = p.substring(p.lastIndexOf('/') + 1);
                    yield Optional.of(serveFile(name));
                }
                yield Optional.empty();
            }
        };
    }

    private HttpReply handleLoginPost(String body) {
        Map<String, String> form = parseForm(body);
        Optional<String> token = adminAuth.login(form.get("username"), form.get("password"));
        if (token.isEmpty()) {
            try {
                return pages.pageWith("login.html", nav.adminPageVars(false,
                        Map.of("{{ERROR}}", "Invalid username or password")));
            } catch (Exception e) {
                return HttpReply.text(401, "invalid credentials");
            }
        }
        // Session + CSRF on the same 302. Multiple Set-Cookie values are newline-joined in the
        // flat Map and expanded by ra-http-server (addHeader). See HttpReply.addSetCookie.
        String csrf = SignedSessionCookie.csrfToken(adminAuth.sessionHmacSecret(), token.get());
        return HttpReply.redirect("/admin")
                .addSetCookie(SignedSessionCookie.setCookieHeader(token.get(), cookieSecure))
                .addSetCookie(SignedSessionCookie.setCsrfCookieHeader(csrf, cookieSecure));
    }

    /** Refresh the JS-readable CSRF companion on every shell page load (HTMX SPA stays current). */
    private HttpReply withCsrfCookie(HttpReply reply, Map<String, String> headers) {
        if (!csrfEnabled) return reply;
        Optional<String> session =
                SignedSessionCookie.extractFromCookieHeader(headerValue(headers, "Cookie"));
        if (session.isEmpty()) return reply;
        String csrf = SignedSessionCookie.csrfToken(adminAuth.sessionHmacSecret(), session.get());
        if (csrf.isEmpty()) return reply;
        return reply.addSetCookie(SignedSessionCookie.setCsrfCookieHeader(csrf, cookieSecure));
    }

    /**
     * Double-submit CSRF check. Only cookie-authenticated mutations need it: an API-key or Basic
     * caller supplies its own credential per request, so it cannot be ridden by a foreign origin.
     */
    private Optional<HttpReply> csrfFailure(String method, Map<String, String> headers,
                                            AdminAuthService.Principal who) {
        if (!csrfEnabled || !"POST".equalsIgnoreCase(method)) return Optional.empty();
        if (who == null || !who.fromSession()) return Optional.empty();
        Optional<String> session =
                SignedSessionCookie.extractFromCookieHeader(headerValue(headers, "Cookie"));
        if (session.isEmpty()) return Optional.empty();
        String presented = headerValue(headers, SignedSessionCookie.CSRF_HEADER);
        if (SignedSessionCookie.csrfMatches(adminAuth.sessionHmacSecret(), session.get(),
                presented)) {
            return Optional.empty();
        }
        LOG.warn("[admin] CSRF token missing/invalid on session POST by username={}",
                who.username());
        return Optional.of(HttpReply.text(403,
                "CSRF token missing or invalid — reload /admin/login "
                        + "(lab escape hatch: ussd.admin.csrf.enabled=false)"));
    }

    private static String headerValue(Map<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        String v = headers.get(name);
        if (v != null) return v;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return m;
        for (String part : body.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = urlDecode(part.substring(0, eq));
            String v = urlDecode(part.substring(eq + 1));
            m.put(k, v);
        }
        return m;
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return s;
        }
    }

    /**
     * Browser navigation to a shell route (no HX-Request) → disk template.
     * HTMX fragment loads keep returning Java panel HTML.
     */
    static boolean wantsShellPage(String method, Map<String, String> headers, String path) {
        if (method == null || !"GET".equalsIgnoreCase(method)) return false;
        if (headers != null) {
            String hx = headers.get("HX-Request");
            if (hx == null) {
                for (var e : headers.entrySet()) {
                    if (e.getKey() != null && e.getKey().equalsIgnoreCase("HX-Request")) {
                        hx = e.getValue();
                        break;
                    }
                }
            }
            if ("true".equalsIgnoreCase(hx)) return false;
        }
        return shellTemplateName(path) != null;
    }

    private Optional<HttpReply> serveShellPage(String path, AdminAuthService.Principal who,
                                               Map<String, String> query) {
        String name = shellTemplateName(path);
        if (name == null || pages == null || nav == null) return Optional.empty();
        boolean loggedIn = who != null && who.fromSession();
        try {
            Map<String, String> extra = shellExtraVars(name, who, query);
            return Optional.of(pages.pageWith(name, nav.adminPageVars(who, loggedIn, extra)));
        } catch (Exception e) {
            LOG.warn("[admin] shell page {}: {}", name, e.toString());
            return Optional.empty();
        }
    }

    private Map<String, String> shellExtraVars(String name, AdminAuthService.Principal who,
                                               Map<String, String> query) {
        return switch (name) {
            case "cdr.html" -> cdrPageVars(query, who);
            case "routing.html" -> catalog.routingPageVars(who);
            case "tenants.html" -> catalog.tenantsPageVars();
            case "users.html" -> catalog.usersPageVars();
            case "bridge.html" -> planes.bridgePageVars();
            case "ss7.html" -> planes.ss7PageVars(who);
            case "smpp.html" -> planes.smppPageVars();
            case "http.html" -> planes.httpPageVars();
            case "grpc.html" -> planes.grpcPageVars();
            case "diameter.html" -> planes.diameterPageVars();
            case "sip.html" -> {
                Map<String, String> m = new LinkedHashMap<>(planes.sipPageVars());
                m.putAll(sipTrunks.pageVars());
                yield m;
            }
            case "hlr.html" -> planes.hlrPageVars(who);
            case "campaigns.html" -> campaigns.pageVars(who);
            case "my-campaigns.html" -> myCampaigns.pageVars(who);
            case "app-users.html" -> appUsers.pageVars(who);
            case "lab-mo.html" -> labMo.pageVars(who);
            case "index.html" -> monitorStripVars();
            default -> Map.of();
        };
    }

    private Map<String, String> monitorStripVars() {
        Map<String, Object> feed = monitorFeedMap();
        StringBuilder strip = new StringBuilder();
        strip.append("<section class=\"mb-8 rounded-lg border border-ink-line bg-ink-panel/80 p-4\">");
        strip.append("<p class=\"text-xs uppercase tracking-[0.25em] text-signal\">Monitor strip</p>");
        strip.append("<div class=\"mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4 font-mono text-xs\">");
        strip.append(monitorCell("ss7.live", feed.get("ss7.live")));
        strip.append(monitorCell("smpp.live", feed.get("smpp.live")));
        strip.append(monitorCell("http.niPushUrl", feed.get("http.niPushUrl")));
        strip.append(monitorCell("grpc.pushEndpoint", feed.get("grpc.pushEndpoint")));
        strip.append("</div>");
        strip.append("<p class=\"mt-3 text-sm text-ink-mute\">Truth from LinkStatusService · ")
                .append("<a class=\"text-signal hover:underline\" href=\"/admin/monitor-feed\">JSON feed</a> · ")
                .append("<a class=\"text-signal hover:underline\" href=\"/telemetry/?tab=ss7\">Monitor Hub</a></p>");
        strip.append("</section>");
        return Map.of("{{MONITOR_STRIP}}", strip.toString());
    }

    private static String monitorCell(String k, Object v) {
        return "<div class=\"rounded-md border border-ink-line bg-ink/40 p-3\">"
                + "<div class=\"text-ink-mute\">" + esc(k) + "</div>"
                + "<div class=\"mt-1 break-all text-slate-100\">" + esc(String.valueOf(v)) + "</div></div>";
    }

    Map<String, Object> monitorFeedMap() {
        Map<String, Object> m = new LinkedHashMap<>(linkStatus.snapshot());
        String ni = config.httpNiPath();
        m.put("http.niPushUrl", PublicPushUrls.publicNiPushUrl(
                config.publicBaseUrl(), httpApply.listenHost(), httpApply.listenPort(), ni));
        m.put("grpc.pushEndpoint", PublicPushUrls.publicGrpcPushEndpoint(
                config.publicBaseUrl(), grpcApply.listenPort()));
        m.put("links.ss7", "/admin/ss7");
        m.put("links.http", "/admin/http");
        m.put("links.grpc", "/admin/grpc");
        return m;
    }

    static String shellTemplateName(String path) {
        if (path == null) return null;
        return switch (path) {
            case "/admin", "/admin/" -> "index.html";
            case "/admin/routing", "/admin/rules" -> "routing.html";
            case "/admin/bridge" -> "bridge.html";
            case "/admin/campaigns" -> "campaigns.html";
            case "/admin/my-campaigns" -> "my-campaigns.html";
            case "/admin/app-users" -> "app-users.html";
            case "/admin/cdr" -> "cdr.html";
            case "/admin/tenants" -> "tenants.html";
            case "/admin/users" -> "users.html";
            case "/admin/lab/mo", "/admin/lab-mo" -> "lab-mo.html";
            case "/admin/http/sync" -> "http-sync.html";
            case "/admin/http/async" -> "http-async.html";
            case "/admin/http/callback" -> "http-callback.html";
            case "/admin/grpc" -> "grpc.html";
            case "/admin/diameter", "/admin/diameter/config" -> "diameter.html";
            case "/admin/sip", "/admin/sip/config" -> "sip.html";
            case "/admin/hlr", "/admin/hlr/config" -> "hlr.html";
            case "/admin/ss7", "/admin/ss7/config" -> "ss7.html";
            case "/admin/smpp", "/admin/smpp/config" -> "smpp.html";
            case "/admin/http", "/admin/http/config" -> "http.html";
            default -> null;
        };
    }

    private static String resolveLinkTab(String path, String tab) {
        if (tab != null && !tab.isBlank()) return tab;
        if (path.endsWith("/ss7")) return "ss7";
        if (path.endsWith("/smpp") || path.endsWith("/smpp.html")) return "smpp";
        if (path.endsWith("/http")) return "http";
        if (path.endsWith("/grpc")) return "grpc";
        if (path.endsWith("/diameter")) return "diameter";
        if (path.endsWith("/sip")) return "sip";
        return "all";
    }

    static boolean isMonitorHubPath(String path) {
        return path.equals("/telemetry") || path.startsWith("/telemetry/")
                || path.equals("/api/admin/dashboards")
                || path.startsWith("/admin/ra/")
                || path.startsWith("/api/ra/")
                || path.startsWith("/api/telemetry/")
                || path.equals("/api/autonomous/health")
                || path.startsWith("/api/ai");
    }

    /** Inert Monitor Hub assets only — never anything that renders live plane state. */
    private static final java.util.Set<String> PUBLIC_STATIC_EXTENSIONS = java.util.Set.of(
            ".js", ".css", ".svg", ".png", ".ico", ".map", ".woff", ".woff2");

    /**
     * Anonymous GET allowlist for the Monitor Hub. Restricted to static assets by extension:
     * {@code partial/} fragments render live plane state and metrics, so they need a principal
     * like every other data surface. The hub shell itself is served to authenticated users.
     */
    static boolean isPublicMonitorStatic(String method, String path) {
        if (method == null || (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method))) {
            return false;
        }
        if (path.startsWith("/telemetry/") || path.startsWith("/admin/ra/")) {
            return hasPublicStaticExtension(path);
        }
        return false;
    }

    private static boolean hasPublicStaticExtension(String path) {
        int slash = path.lastIndexOf('/');
        String file = (slash < 0 ? path : path.substring(slash + 1)).toLowerCase();
        int dot = file.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return PUBLIC_STATIC_EXTENSIONS.contains(file.substring(dot));
    }

    private static HttpReply toHttpReply(RaAdminHttpResponse r) {
        String ct = r.contentType();
        Map<String, String> headers = r.headers() == null ? Map.of() : r.headers();
        if (ct != null && (ct.startsWith("image/") || ct.equals("application/octet-stream"))) {
            return new HttpReply(r.status(), ct, r.body(), headers);
        }
        return new HttpReply(r.status(), ct,
                r.bodyAsString().getBytes(StandardCharsets.UTF_8), headers);
    }

    private Optional<HttpReply> handlePost(String p, String body, AdminAuthService.Principal who) {
        try {
            if (who != null && who.isTenantScoped()) {
                // TENANT: routing, my-campaigns, app-users (own tenant); no plane/admin campaigns
                return switch (p) {
                    case "/admin/routing", "/admin/rules" -> Optional.of(catalog.routingPost(body, who));
                    case "/admin/my-campaigns" -> Optional.of(myCampaigns.post(body, who));
                    case "/admin/app-users" -> Optional.of(appUsers.post(body, who));
                    case "/admin/lab/mo" -> Optional.of(labMo.post(body, who));
                    case "/admin/http/sync" -> Optional.of(httpAsModes.post("sync", body, who));
                    case "/admin/http/async" -> Optional.of(httpAsModes.post("async", body, who));
                    case "/admin/http/callback" -> Optional.of(httpAsModes.post("callback", body, who));
                    case "/admin/campaigns", "/admin/tenants", "/admin/users",
                         "/admin/ss7", "/admin/ss7/config", "/admin/ss7/apply", "/admin/ss7/start", "/admin/ss7/stop",
                         "/admin/hlr", "/admin/hlr/config", "/admin/hlr/apply",
                         "/admin/smpp", "/admin/smpp/config", "/admin/smpp/apply", "/admin/smpp/start", "/admin/smpp/stop",
                         "/admin/http", "/admin/http/config", "/admin/http/apply", "/admin/http/start", "/admin/http/stop",
                         "/admin/grpc", "/admin/grpc/apply", "/admin/grpc/start", "/admin/grpc/stop",
                         "/admin/diameter", "/admin/diameter/config", "/admin/diameter/apply",
                         "/admin/diameter/start", "/admin/diameter/stop",
                         "/admin/sip", "/admin/sip/config", "/admin/sip/apply",
                         "/admin/sip/start", "/admin/sip/stop", "/admin/sip/trunks",
                         "/admin/bridge" ->
                            Optional.of(HttpReply.text(403, "forbidden for TENANT role"));
                    default -> Optional.empty();
                };
            }
            return switch (p) {
                case "/admin/ss7", "/admin/ss7/config",
                     "/admin/ss7/apply", "/admin/ss7/start", "/admin/ss7/stop" ->
                        Optional.of(delegatePlanePost("ss7", p, body, who));
                case "/admin/hlr", "/admin/hlr/config", "/admin/hlr/apply" ->
                        Optional.of(delegatePlanePost("hlr", p, body, who));
                case "/admin/smpp", "/admin/smpp/config",
                     "/admin/smpp/apply", "/admin/smpp/start", "/admin/smpp/stop" ->
                        Optional.of(delegatePlanePost("smpp", p, body, who));
                case "/admin/http", "/admin/http/config",
                     "/admin/http/apply", "/admin/http/start", "/admin/http/stop" ->
                        Optional.of(delegatePlanePost("http", p, body, who));
                case "/admin/grpc", "/admin/grpc/apply", "/admin/grpc/start", "/admin/grpc/stop" ->
                        Optional.of(delegatePlanePost("grpc", p, body, who));
                case "/admin/diameter", "/admin/diameter/config",
                     "/admin/diameter/apply", "/admin/diameter/start", "/admin/diameter/stop" ->
                        Optional.of(delegatePlanePost("diameter", p, body, who));
                case "/admin/sip", "/admin/sip/config",
                     "/admin/sip/apply", "/admin/sip/start", "/admin/sip/stop" ->
                        Optional.of(delegatePlanePost("sip", p, body, who));
                case "/admin/sip/trunks" -> Optional.of(sipTrunks.post(body));
                case "/admin/bridge" -> Optional.of(planes.bridgePost(body));
                case "/admin/routing", "/admin/rules" -> Optional.of(catalog.routingPost(body, who));
                case "/admin/tenants" -> Optional.of(catalog.tenantsPost(body, who));
                case "/admin/users" -> Optional.of(catalog.usersPost(body, who));
                case "/admin/app-users" -> Optional.of(appUsers.post(body, who));
                case "/admin/campaigns" -> Optional.of(campaigns.post(body, who));
                case "/admin/my-campaigns" -> Optional.of(myCampaigns.post(body, who));
                case "/admin/lab/mo" -> Optional.of(labMo.post(body, who));
                case "/admin/http/sync" -> Optional.of(httpAsModes.post("sync", body, who));
                case "/admin/http/async" -> Optional.of(httpAsModes.post("async", body, who));
                case "/admin/http/callback" -> Optional.of(httpAsModes.post("callback", body, who));
                default -> Optional.empty();
            };
        } catch (RuntimeException ex) {
            return Optional.of(HttpReply.html("<pre>" + esc("error: " + ex.getMessage()) + "</pre>"));
        }
    }

    private HttpReply delegatePlanePost(String plane, String path, String body,
                                        AdminAuthService.Principal who) {
        String action = null;
        if (path.endsWith("/apply")) action = "apply";
        else if (path.endsWith("/start")) action = "start";
        else if (path.endsWith("/stop")) action = "stop";
        String effective = body == null ? "" : body;
        if (action != null) {
            effective = (effective.isBlank() ? "" : effective + "&") + "action=" + action;
        }
        return switch (plane) {
            case "smpp" -> planes.smppPost(effective);
            case "http" -> planes.httpPost(effective);
            case "grpc" -> planes.grpcPost(effective);
            case "diameter" -> planes.diameterPost(effective);
            case "sip" -> planes.sipPost(effective);
            case "hlr" -> planes.hlrPost(effective, who);
            default -> planes.ss7Post(effective, who);
        };
    }

    private boolean isPublicAsset(String p) {
        return p.endsWith(".css") || p.endsWith(".js") || p.endsWith(".svg");
    }

    /** Admin surfaces that mint or re-role principals. */
    static boolean isIdentityAdminPath(String p) {
        return "/admin/users".equals(p) || "/admin/users/partial".equals(p)
                || "/admin/tenants".equals(p) || "/admin/tenants/partial".equals(p);
    }

    /** Null principal = internal/automation call path (admin key already resolved to ADMIN). */
    static boolean isAdminRole(AdminAuthService.Principal who) {
        return who == null || "ADMIN".equals(who.role());
    }

    private static HttpReply identityForbidden(AdminAuthService.Principal who) {
        String role = who == null ? "anonymous" : String.valueOf(who.role());
        return HttpReply.text(403,
                "forbidden — /admin/users and /admin/tenants require role ADMIN (have " + role + ")");
    }

    private static boolean hasBrowserSession(Optional<AdminAuthService.Principal> principal) {
        return principal.isPresent() && principal.get().fromSession();
    }

    private HttpReply statusHtml() {
        Map<String, Object> m = new LinkedHashMap<>(linkStatus.snapshot());
        long sessions = store.size();
        long bridgeCount = bridge.bridgeCount();
        long bridgeRecover = bridge.recoverCount();
        long bridgeZombie = bridge.zombieDrop();
        long gateExpired = bridgeGate != null ? bridgeGate.gateExpired() : 0L;
        long reclaim = bridgeGate != null ? bridgeGate.reclaimCount() : 0L;
        long asRejects = asPull != null ? asPull.openRejects() : 0L;
        long niFail = saga != null ? saga.niFailCount() : 0L;
        long pullFail = saga != null ? saga.pullFailCount() : 0L;
        Object ewma = adaptive.snapshot();

        StringBuilder cards = new StringBuilder();
        cards.append(metricCard("Sessions", String.valueOf(sessions), "active virtual sessions"));
        cards.append(metricCard("Bridge", String.valueOf(bridgeCount),
                "recover " + bridgeRecover + " · zombie " + bridgeZombie
                        + (config.bridgeEnabled() ? " · on" : " · off")));
        cards.append(metricCard("Gate", String.valueOf(gateExpired),
                "expired · ticks " + (bridgeGate != null ? bridgeGate.gateTicks() : 0L)
                        + " · reclaim " + reclaim + " · ceiling "
                        + config.asyncGateTimeoutMs() + "ms"));
        cards.append(metricCard("AS / Saga", String.valueOf(asRejects),
                "circuit rejects · NI fail " + niFail + " · pull fail " + pullFail));
        cards.append(metricCard("Adaptive", String.valueOf(ewma),
                "EWMA · floor " + AdaptiveTimeout.FLOOR_MS + "ms · dialog "
                        + config.dialogTimeoutMs() + "ms"));

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"status\">");
        sb.append("<div class=\"grid gap-3 sm:grid-cols-2 lg:grid-cols-5\">")
                .append(cards).append("</div>");
        sb.append("<div class=\"table-wrap mt-8\">");
        sb.append("<p class=\"text-xs uppercase tracking-[0.25em] text-signal\">Planes</p>");
        sb.append("<div class=\"ops-section-indent mt-3 link-status\">");
        sb.append(linkStatus.htmlPartial("all"));
        sb.append("</div></div>");
        sb.append("<p class=\"mt-4 text-sm text-ink-mute\">");
        sb.append("Config <a class=\"text-signal hover:underline\" href=\"/admin/ss7\">SS7</a>");
        sb.append(" · <a class=\"text-signal hover:underline\" href=\"/admin/hlr\">HLR</a>");
        sb.append(" · <a class=\"text-signal hover:underline\" href=\"/admin/smpp\">SMPP</a>");
        sb.append(" · <a class=\"text-signal hover:underline\" href=\"/admin/http\">HTTP</a>");
        sb.append(" · <a class=\"text-signal hover:underline\" href=\"/admin/grpc\">gRPC</a>");
        sb.append(" · <a class=\"text-signal hover:underline\" href=\"/admin/diameter\">Diameter</a>");
        sb.append(" · <a class=\"text-signal hover:underline\" href=\"/admin/sip\">SIP</a>");
        sb.append(" · <a class=\"text-signal hover:underline\" href=\"/admin/lab-mo\">Lab MO</a>");
        sb.append(" · <a class=\"text-signal hover:underline\" href=\"/admin/bridge\">Bridge</a>");
        sb.append(" · Hub <a class=\"text-signal hover:underline\" href=\"/telemetry/?tab=ss7\">SS7</a>");
        sb.append(" · <a class=\"text-signal hover:underline\" href=\"/telemetry/?tab=smpp\">SMPP</a>");
        sb.append(" · <a class=\"text-signal hover:underline\" href=\"/admin/cdr\">CDR</a>");
        sb.append(" · JSON <code class=\"font-mono text-slate-300\">/admin/status.json</code>");
        sb.append("</p>");
        sb.append("<details class=\"mt-4 rounded-md border border-ink-line bg-ink-panel/60 p-3\">");
        sb.append("<summary class=\"cursor-pointer text-xs uppercase tracking-[0.18em] text-ink-mute\">Link truth dump</summary>");
        sb.append("<pre class=\"mt-2 max-h-40 overflow-auto font-mono text-xs text-ink-mute\">");
        for (String k : new String[]{
                "ss7.live", "ss7.detail", "smpp.detail", "http.detail", "grpc.detail",
                "diameter.live", "diameter.detail", "sip.live", "sip.detail",
                "sessions", "bridge.count", "bridge.enabled", "scheduler.gateTicks", "adaptive.ewma"}) {
            Object v = "sessions".equals(k) ? sessions
                    : "bridge.count".equals(k) ? bridgeCount
                    : "bridge.enabled".equals(k) ? config.bridgeEnabled()
                    : "scheduler.gateTicks".equals(k) ? (bridgeGate != null ? bridgeGate.gateTicks() : 0L)
                    : "adaptive.ewma".equals(k) ? ewma
                    : m.get(k);
            if (v != null) {
                sb.append(esc(k)).append(" = ").append(esc(String.valueOf(v))).append('\n');
            }
        }
        sb.append("</pre></details></div>");
        return HttpReply.html(sb.toString());
    }

    private static String metricCard(String title, String value, String detail) {
        return "<div class=\"rounded-lg border border-ink-line bg-ink-panel/90 p-4\">"
                + "<h3 class=\"text-[0.7rem] font-medium uppercase tracking-[0.18em] text-ink-mute\">"
                + esc(title) + "</h3>"
                + "<p class=\"mt-2 font-mono text-3xl font-medium tabular-nums text-signal\">"
                + esc(value) + "</p>"
                + "<p class=\"mt-1 font-mono text-xs text-ink-mute\">" + esc(detail) + "</p>"
                + "</div>";
    }

    public HttpReply statusJson() {
        Map<String, Object> m = new LinkedHashMap<>(monitorFeedMap());
        m.put("sessions", store.size());
        m.put("bridge.enabled", config.bridgeEnabled());
        m.put("bridge.count", bridge.bridgeCount());
        m.put("bridge.recover", bridge.recoverCount());
        m.put("bridge.zombieDrop", bridge.zombieDrop());
        m.put("bridge.asyncGateMs", config.asyncGateTimeoutMs());
        m.put("adaptive.ewma", adaptive.snapshot());
        m.put("adaptive.floorMs", AdaptiveTimeout.FLOOR_MS);
        if (bridgeGate != null) {
            m.put("scheduler.gateTicks", bridgeGate.gateTicks());
            m.put("scheduler.gateExpired", bridgeGate.gateExpired());
            m.put("scheduler.reclaimCount", bridgeGate.reclaimCount());
            m.put("scheduler.gateTickMs", bridgeGate.configuredGateTickMs());
        }
        if (asPull != null) {
            m.put("as.circuitOpenRejects", asPull.openRejects());
        }
        if (saga != null) {
            m.put("saga.niFail", saga.niFailCount());
            m.put("saga.pullFail", saga.pullFailCount());
        }
        return HttpReply.json(200, m);
    }

    private Map<String, String> cdrPageVars(Map<String, String> query, AdminAuthService.Principal who) {
        String msisdn = query == null ? "" : query.getOrDefault("msisdn", "");
        String corr = query == null ? "" : query.getOrDefault("corr", "");
        int limit = CdrService.clampLimit(query == null ? null : query.get("limit"));
        String rows = cdrRowsHtml(msisdn, corr, limit, who);
        Map<String, String> m = new LinkedHashMap<>();
        m.put("{{ROWS}}", rows);
        m.put("{{MSISDN}}", esc(msisdn == null ? "" : msisdn));
        m.put("{{CORR}}", esc(corr == null ? "" : corr));
        m.put("{{LIMIT}}", Integer.toString(limit));
        m.put("{{ROW_COUNT}}", Integer.toString(countCdrRowPairs(rows)));
        return m;
    }

    private HttpReply cdrRowsReply(Map<String, String> query, AdminAuthService.Principal who) {
        String msisdn = query == null ? null : query.get("msisdn");
        String corr = query == null ? null : query.get("corr");
        int limit = CdrService.clampLimit(query == null ? null : query.get("limit"));
        return HttpReply.html(cdrRowsHtml(msisdn, corr, limit, who))
                .withHeader("Vary", "HX-Request");
    }

    /**
     * CDR ledger rows for HTMX partial + shell seed. Columns mirror classic CDR IA
     * (when / corr / bridge phase / MSISDN / service code / result) plus greenfield
     * gate/EWMA in the expand panel.
     */
    private String cdrRowsHtml(String msisdn, String corr, int limit, AdminAuthService.Principal who) {
        String scope = who != null && who.isTenantScoped() ? who.tenantId() : null;
        StringBuilder sb = new StringBuilder();
        var rows = cdr.listRecords(limit, scope, msisdn, corr);
        if (rows.isEmpty()) {
            sb.append("<tr class=\"cdr-empty\"><td colspan=\"7\" class=\"px-3 py-6 text-ink-mute\">")
                    .append("No CDR rows for this filter. Try MSISDN, correlation id, or raise limit.")
                    .append("</td></tr>");
            return sb.toString();
        }
        int i = 0;
        for (CdrRecord r : rows) {
            String spine = cdrSpineClass(r.phase);
            String statusChip = cdrStatusChipClass(r.phase, r.status);
            String rowId = "cdr-" + (r.id != null ? r.id : i);
            sb.append("<tr class=\"cdr-ledger-row\" data-cdr-row=\"")
                    .append(esc(rowId)).append("\">");
            sb.append("<td class=\"cdr-when px-3 py-2.5\">")
                    .append("<span class=\"cdr-spine ").append(spine).append("\" aria-hidden=\"true\"></span>")
                    .append("<button type=\"button\" class=\"cdr-open\" data-cdr-open=\"")
                    .append(esc(rowId)).append("\" aria-expanded=\"false\" title=\"Expand record\">")
                    .append("<time class=\"cdr-time\">").append(esc(formatCdrWhen(r.createdAt))).append("</time>")
                    .append("</button></td>");
            sb.append("<td class=\"px-3 py-2.5\"><code class=\"cdr-corr\" title=\"")
                    .append(esc(r.correlationId)).append("\">")
                    .append(esc(shortCorr(r.correlationId))).append("</code></td>");
            sb.append("<td class=\"px-3 py-2.5\"><span class=\"cdr-phase-chip ").append(spine).append("\">")
                    .append(esc(nullToDash(r.phase))).append("</span></td>");
            sb.append("<td class=\"px-3 py-2.5 cdr-msisdn\">").append(esc(nullToDash(r.msisdn))).append("</td>");
            sb.append("<td class=\"px-3 py-2.5\">").append(esc(nullToDash(r.shortCode))).append("</td>");
            sb.append("<td class=\"px-3 py-2.5\"><span class=\"cdr-status-chip ").append(statusChip).append("\">")
                    .append(esc(nullToDash(r.status))).append("</span></td>");
            sb.append("<td class=\"px-3 py-2.5 cdr-origin\">")
                    .append(esc(nullToDash(r.originationType))).append("</td>");
            sb.append("</tr>");

            // Expand: greenfield fields + honest stubs for classic-only columns.
            sb.append("<tr class=\"cdr-detail hidden\" data-cdr-detail=\"").append(esc(rowId)).append("\">")
                    .append("<td colspan=\"7\" class=\"px-3 py-3\">")
                    .append("<div class=\"cdr-detail-panel ink-panel\">");
            sb.append("<dl class=\"cdr-detail-grid\">");
            cdrDetailItem(sb, "Correlation", r.correlationId);
            cdrDetailItem(sb, "Phase (bridge)", r.phase);
            cdrDetailItem(sb, "Status / result", r.status);
            cdrDetailItem(sb, "MSISDN", r.msisdn);
            cdrDetailItem(sb, "Short code", r.shortCode);
            cdrDetailItem(sb, "Origination", r.originationType);
            cdrDetailItem(sb, "Network id", r.networkId == null ? null : Integer.toString(r.networkId));
            cdrDetailItem(sb, "Tenant", r.tenantId);
            cdrDetailItem(sb, "Gate ms", r.gateMs == null ? null : Long.toString(r.gateMs));
            cdrDetailItem(sb, "Observed EWMA ms", r.observedEwmaMs == null ? null : Long.toString(r.observedEwmaMs));
            cdrDetailItem(sb, "Detail", r.detail);
            cdrDetailItem(sb, "Recorded at", r.createdAt == null ? null : r.createdAt.toString());
            sb.append("</dl>");
            // TODO(cdr-parity): classic CdrLineFormatter also emitted local/remote SCCP
            // (PC/SSN/RI/GTI/GT), orig/dest AddressString, dialog ids, duration, USSD string,
            // eri IMSI/VLR — not persisted on ussd_cdr yet.
            sb.append("<p class=\"cdr-gap-note\">Classic fields not in store: dialog ids · duration · ")
                    .append("USSD string · SCCP GT · IMSI/VLR · RecordStatus enum</p>");
            sb.append("</div></td></tr>");
            i++;
        }
        return sb.toString();
    }

    private static void cdrDetailItem(StringBuilder sb, String label, String value) {
        sb.append("<div><dt>").append(esc(label)).append("</dt><dd>")
                .append(esc(nullToDash(value))).append("</dd></div>");
    }

    private static String formatCdrWhen(java.time.Instant at) {
        if (at == null) return "—";
        return at.toString().replace('T', ' ').replace("Z", " Z");
    }

    private static String shortCorr(String corr) {
        if (corr == null || corr.isBlank()) return "—";
        if (corr.length() <= 14) return corr;
        return corr.substring(0, 8) + "…" + corr.substring(corr.length() - 4);
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static String cdrSpineClass(String phase) {
        if (phase == null) return "cdr-spine--unknown";
        return switch (phase) {
            case "S1_ACTIVE" -> "cdr-spine--s1";
            case "S1_RELEASED" -> "cdr-spine--s1r";
            case "S2_PUSH" -> "cdr-spine--s2";
            case "COMPLETED" -> "cdr-spine--ok";
            case "FAILED" -> "cdr-spine--fail";
            default -> "cdr-spine--unknown";
        };
    }

    private static String cdrStatusChipClass(String phase, String status) {
        if ("FAILED".equals(phase) || (status != null && status.toUpperCase().contains("FAIL"))) {
            return "cdr-status--fail";
        }
        if ("COMPLETED".equals(phase) || "SUCCESS".equalsIgnoreCase(status)) {
            return "cdr-status--ok";
        }
        return "cdr-status--live";
    }

    /** Count summary rows (not expand rows) for the seeded {{ROW_COUNT}} badge. */
    private static int countCdrRowPairs(String rowsHtml) {
        if (rowsHtml == null || rowsHtml.contains("cdr-empty")) return 0;
        int n = 0;
        int from = 0;
        while (true) {
            int i = rowsHtml.indexOf("cdr-ledger-row", from);
            if (i < 0) break;
            n++;
            from = i + 14;
        }
        return n;
    }

    private HttpReply serveFile(String relative) {
        Path[] roots = {
                Path.of("app/html"),
                Path.of("dist/app/html"),
                Path.of("../app/html")
        };
        for (Path root : roots) {
            Path f = root.resolve(relative).normalize();
            if (Files.isRegularFile(f)) {
                try {
                    byte[] bytes = Files.readAllBytes(f);
                    String ct = relative.endsWith(".js") ? "application/javascript"
                            : relative.endsWith(".css") ? "text/css"
                            : "text/html; charset=utf-8";
                    return new HttpReply(200, ct, bytes, Map.of());
                } catch (IOException e) {
                    return HttpReply.text(500, "read error");
                }
            }
        }
        return HttpReply.text(404, "not found: " + relative);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
