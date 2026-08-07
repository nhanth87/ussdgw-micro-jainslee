package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.config.RuntimeConfigStore;
import et.restlink.ussdgw.config.SmppConfigSupport;
import et.restlink.ussdgw.config.Ss7ConfigSupport;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.service.DiameterApplyService;
import et.restlink.ussdgw.service.GrpcApplyService;
import et.restlink.ussdgw.service.HttpApplyService;
import et.restlink.ussdgw.service.SipApplyService;
import et.restlink.ussdgw.service.SmppApplyService;
import et.restlink.ussdgw.service.Ss7ApplyService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTMX plane panels: jSS7/SMPP JSON editors, HTTP/gRPC status-only, Diameter/SIP form + apply,
 * HLR face form (hot KV, separate from SS7 stack JSON).
 */
@ApplicationScoped
public class AdminPlaneHandler {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject RuntimeConfigStore store;
    @Inject UssdConfigService config;
    @Inject LinkStatusService linkStatus;
    @Inject Ss7ApplyService ss7Apply;
    @Inject SmppApplyService smppApply;
    @Inject HttpApplyService httpApply;
    @Inject GrpcApplyService grpcApply;
    @Inject DiameterApplyService diameterApply;
    @Inject SipApplyService sipApply;
    @Inject SmppConfigSupport smppConfig;
    @Inject Ss7ConfigSupport ss7Config;

    public AdminHttpHandler.HttpReply ss7Get() {
        return ss7Get(null);
    }

    public AdminHttpHandler.HttpReply ss7Get(AdminAuthService.Principal who) {
        return AdminHttpHandler.HttpReply.html(ss7Html(null, who));
    }

    public AdminHttpHandler.HttpReply smppGet() {
        return AdminHttpHandler.HttpReply.html(smppHtml(null));
    }

    public AdminHttpHandler.HttpReply httpGet() {
        return AdminHttpHandler.HttpReply.html(httpHtml(null));
    }

    public AdminHttpHandler.HttpReply grpcGet() {
        return AdminHttpHandler.HttpReply.html(grpcHtml(null));
    }

    public AdminHttpHandler.HttpReply bridgeGet() {
        return AdminHttpHandler.HttpReply.html(bridgeHtml(null));
    }

    /** Disk-template seed vars for {@code bridge.html}. */
    public Map<String, String> bridgePageVars() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("{{BRIDGE_ENABLED}}", String.valueOf(config.bridgeEnabled()));
        m.put("{{ASYNC_GATE_MS}}", String.valueOf(config.asyncGateTimeoutMs()));
        m.put("{{DIALOG_TIMEOUT_MS}}", String.valueOf(config.dialogTimeoutMs()));
        m.put("{{ASYNC_WAIT_MSG}}", esc(config.asyncWaitMessage()));
        m.put("{{ASYNC_HARD_FAIL_MSG}}", esc(config.asyncHardFailMessage()));
        m.put("{{HTTP_BRIDGE}}", String.valueOf(config.httpClientBridgeEnabled()));
        m.put("{{GRPC_BRIDGE}}", String.valueOf(config.grpcClientBridgeEnabled()));
        return m;
    }

    public Map<String, String> ss7PageVars() {
        return ss7PageVars(null);
    }

    public Map<String, String> ss7PageVars(AdminAuthService.Principal who) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("{{MAP_ENABLED}}", String.valueOf(ss7Apply.mapEnabled()));
        m.put("{{CONFIG_FILE}}", esc(ss7Apply.configFile()));
        boolean edit = canEditSs7Stack(who);
        m.put("{{STATUS_HTML}}", edit ? ss7StatusHtml() : linkStatus.htmlPartial("ss7"));
        m.put("{{CONFIG_SUMMARY}}", edit ? esc(ss7Config.propsAsJson()) : "");
        m.put("{{STACK_SUMMARY}}", edit ? ss7StackFileSummary(ss7Apply.configFile()) : "");
        m.put("{{STACK_JSON}}", edit ? esc(readStackJsonRaw(ss7Apply.configFile())) : "");
        m.put("{{EDIT_CLASS}}", edit ? "" : "hidden");
        m.put("{{TENANT_BANNER}}", edit ? ""
                : "<p class=\"mb-4 rounded-md border border-ink-line bg-ink-panel/60 p-3 text-sm text-ink-mute\">"
                + "Live SS7 peer state only. SCTP/SCCP stack and Apply are "
                + "<strong class=\"text-slate-200\">ADMIN/OPS</strong> only.</p>");
        m.put("{{PANEL}}", ss7Html(null, who));
        return m;
    }

    public Map<String, String> smppPageVars() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("{{SMPP_JSON}}", esc(smppConfig.activeJsonOrLab()));
        m.put("{{STATUS_HTML}}", smppStatusHtml());
        m.put("{{PANEL}}", smppHtml(null));
        return m;
    }

    public Map<String, String> httpPageVars() {
        Map<String, String> m = new LinkedHashMap<>();
        String ni = config.httpNiPath();
        if (ni == null || ni.isBlank()) {
            ni = "/ussd";
        }
        m.put("{{NI_PATH}}", esc(ni));
        m.put("{{CALLBACK_PATH}}", esc(config.httpCallbackPath()));
        String host = httpApply.listenHost();
        int port = httpApply.listenPort();
        String niUrl = PublicPushUrls.publicNiPushUrl(config.publicBaseUrl(), host, port, ni);
        if (niUrl.isEmpty()) {
            niUrl = "(set ussd.admin.public-base-url — bind host is "
                    + host + ":" + port + ", not publishable)";
        }
        m.put("{{NI_PUSH_URL}}", esc(niUrl));
        m.put("{{STATUS_HTML}}", linkStatus.htmlPartial("http"));
        m.put("{{PANEL}}", httpHtml(null));
        return m;
    }

    public Map<String, String> grpcPageVars() {
        Map<String, String> m = new LinkedHashMap<>();
        int grpcPort = grpcApply.listenPort();
        String ep = PublicPushUrls.publicGrpcPushEndpoint(config.publicBaseUrl(), grpcPort);
        if (ep.isEmpty()) {
            ep = "(set ussd.admin.public-base-url — gRPC listen port " + grpcPort + ")";
        }
        m.put("{{GRPC_PUSH_ENDPOINT}}", esc(ep));
        m.put("{{GRPC_PORT}}", String.valueOf(grpcPort));
        m.put("{{STATUS_HTML}}", linkStatus.htmlPartial("grpc"));
        m.put("{{PANEL}}", grpcHtml(null));
        return m;
    }

    public Map<String, String> diameterPageVars() {
        Map<String, String> m = new LinkedHashMap<>();
        boolean on = config.diameterEnabled();
        m.put("{{ENABLED_TRUE}}", on ? "selected" : "");
        m.put("{{ENABLED_FALSE}}", on ? "" : "selected");
        m.put("{{HOST}}", esc(config.diameterHost()));
        m.put("{{PORT}}", String.valueOf(config.diameterPort()));
        m.put("{{REALM}}", esc(config.diameterRealm()));
        m.put("{{ORIGIN_HOST}}", esc(config.diameterOriginHost()));
        m.put("{{DEST_REALM}}", esc(config.diameterDestinationRealm()));
        m.put("{{DEST_HOST}}", esc(config.diameterDestinationHost()));
        m.put("{{STATUS_HTML}}", linkStatus.htmlPartial("diameter"));
        m.put("{{PANEL}}", accessPlaneHtml("Diameter", "diameter",
                "Diameter USSD MO/NI — form Save persists KV; Apply wires ra-diameter."));
        return m;
    }

    public Map<String, String> sipPageVars() {
        Map<String, String> m = new LinkedHashMap<>();
        boolean on = config.sipEnabled();
        m.put("{{ENABLED_TRUE}}", on ? "selected" : "");
        m.put("{{ENABLED_FALSE}}", on ? "" : "selected");
        m.put("{{HOST}}", esc(config.sipHost()));
        m.put("{{TCP_PORT}}", String.valueOf(config.sipTcpPort()));
        m.put("{{UDP_PORT}}", String.valueOf(config.sipUdpPort()));
        m.put("{{FROM_URI}}", esc(config.sipFromUri()));
        m.put("{{REQUEST_URI}}", esc(config.sipRequestUriTemplate()));
        m.put("{{STATUS_HTML}}", linkStatus.htmlPartial("sip"));
        m.put("{{PANEL}}", accessPlaneHtml("SIP / USSI", "sip",
                "SIP MESSAGE / USSI — form Save persists KV; Apply wires ra-sip-servlet."));
        return m;
    }

    public Map<String, String> hlrPageVars() {
        return hlrPageVars(null);
    }

    /** Disk-template seed vars for {@code hlr.html}. ADMIN/OPS edit; TENANT read-only. */
    public Map<String, String> hlrPageVars(AdminAuthService.Principal who) {
        Map<String, String> m = new LinkedHashMap<>();
        et.restlink.ussdgw.hlr.HlrResolveMode mode = config.hlrMode();
        m.put("{{MODE_PROXY_MAP}}", mode == et.restlink.ussdgw.hlr.HlrResolveMode.PROXY_MAP ? "selected" : "");
        m.put("{{MODE_FAKE}}", mode == et.restlink.ussdgw.hlr.HlrResolveMode.FAKE ? "selected" : "");
        m.put("{{MODE_PROXY_DIAMETER}}",
                mode == et.restlink.ussdgw.hlr.HlrResolveMode.PROXY_DIAMETER ? "selected" : "");
        m.put("{{MODE_FAKE_THEN_RESOLVE}}",
                mode == et.restlink.ussdgw.hlr.HlrResolveMode.FAKE_THEN_RESOLVE ? "selected" : "");
        m.put("{{MODE_LABEL}}", esc(mode.name()));
        m.put("{{FAKE_IMSI}}", esc(config.hlrFakeImsi()));
        m.put("{{FAKE_MSC_GT}}", esc(config.hlrFakeMscGt()));
        m.put("{{UPPER_GT}}", esc(config.hlrUpperGt()));
        m.put("{{DIAM_DEST_HOST}}", esc(config.hlrDiamDestinationHost()));
        m.put("{{DIAM_DEST_REALM}}", esc(config.hlrDiamDestinationRealm()));
        boolean edit = canEditHlr(who);
        m.put("{{EDIT_CLASS}}", edit ? "" : "hidden");
        m.put("{{TENANT_BANNER}}", edit ? ""
                : "<p class=\"mb-4 rounded-md border border-ink-line bg-ink-panel/60 p-3 text-sm text-ink-mute\">"
                + "HLR face settings are <strong class=\"text-slate-200\">ADMIN/OPS</strong> only. "
                + "Active mode: <code class=\"font-mono text-signal\">" + esc(mode.name()) + "</code>.</p>");
        m.put("{{PANEL}}", hlrHtml(null, who));
        return m;
    }

    public AdminHttpHandler.HttpReply hlrGet() {
        return hlrGet(null);
    }

    public AdminHttpHandler.HttpReply hlrGet(AdminAuthService.Principal who) {
        return AdminHttpHandler.HttpReply.html(hlrHtml(null, who));
    }

    public String ss7StatusHtml() {
        try {
            return new com.microjainslee.ra.jss7.admin.Ss7AdminController()
                    .statusHtml(null).bodyAsString();
        } catch (RuntimeException ex) {
            return linkStatus.htmlPartial("ss7");
        }
    }

    public String smppStatusHtml() {
        try {
            return new et.restlink.ussdgw.admin.smpp.SmppAdminController()
                    .statusHtml(null).bodyAsString();
        } catch (RuntimeException ex) {
            return linkStatus.htmlPartial("smpp");
        }
    }

    public AdminHttpHandler.HttpReply ss7StatusGet() {
        return AdminHttpHandler.HttpReply.html(ss7StatusHtml()).withHeader("Vary", "HX-Request");
    }

    public AdminHttpHandler.HttpReply smppStatusGet() {
        return AdminHttpHandler.HttpReply.html(smppStatusHtml()).withHeader("Vary", "HX-Request");
    }

    public AdminHttpHandler.HttpReply ss7Post(String body) {
        return ss7Post(body, null);
    }

    public AdminHttpHandler.HttpReply ss7Post(String body, AdminAuthService.Principal who) {
        if (who != null && who.isTenantScoped()) {
            return AdminHttpHandler.HttpReply.text(403, "forbidden for TENANT role");
        }
        Map<String, String> f = AdminCatalogHandler.parseForm(body);
        String action = f.getOrDefault("action", "save");
        try {
            if ("save".equalsIgnoreCase(action) || "saveApply".equalsIgnoreCase(action)) {
                if (!canEditSs7Stack(who)) {
                    return AdminHttpHandler.HttpReply.text(403, "SS7 stack editable by ADMIN/OPS only");
                }
                Map<String, String> kv = new LinkedHashMap<>();
                put(kv, RuntimeConfigStore.Keys.MAP_ENABLED, f.get("mapEnabled"));
                put(kv, RuntimeConfigStore.Keys.MAP_CONFIG_FILE, f.get("configFile"));
                if (!kv.isEmpty()) {
                    ss7Config.saveFromForm(kv);
                }
                String stackJson = f.get("stackJson");
                if (stackJson != null && !stackJson.isBlank()) {
                    String cfgPath = f.get("configFile");
                    if (cfgPath == null || cfgPath.isBlank()) {
                        cfgPath = ss7Apply.configFile();
                    }
                    writeStackJson(cfgPath, stackJson);
                }
            }
            String notice = "saved";
            if ("saveApply".equalsIgnoreCase(action) || "apply".equalsIgnoreCase(action)) {
                notice = ss7Apply.apply();
            } else if ("start".equalsIgnoreCase(action)) {
                notice = ss7Apply.start();
            } else if ("stop".equalsIgnoreCase(action)) {
                notice = ss7Apply.stop();
            }
            return planeNotice(notice, "ok");
        } catch (RuntimeException ex) {
            return planeNotice("error: " + nullToEmpty(ex.getMessage()), "error");
        }
    }

    public AdminHttpHandler.HttpReply smppPost(String body) {
        Map<String, String> f = AdminCatalogHandler.parseForm(body);
        String action = f.getOrDefault("action", "save");
        try {
            if ("save".equalsIgnoreCase(action) || "saveApply".equalsIgnoreCase(action)) {
                String json = f.get("smppJson");
                if (json == null || json.isBlank()) {
                    json = f.get("configJson");
                }
                if (json == null || json.isBlank()) {
                    throw new IllegalArgumentException("smppJson required");
                }
                smppConfig.save(json);
            }
            String notice = "saved";
            if ("saveApply".equalsIgnoreCase(action) || "apply".equalsIgnoreCase(action)) {
                notice = smppApply.apply();
            } else if ("start".equalsIgnoreCase(action)) {
                notice = smppApply.start();
            } else if ("stop".equalsIgnoreCase(action)) {
                notice = smppApply.stop();
            }
            return planeNotice(notice, "ok");
        } catch (RuntimeException ex) {
            return planeNotice("error: " + nullToEmpty(ex.getMessage()), "error");
        }
    }

    public AdminHttpHandler.HttpReply diameterPost(String body) {
        Map<String, String> f = AdminCatalogHandler.parseForm(body);
        String action = f.getOrDefault("action", "save");
        try {
            if ("save".equalsIgnoreCase(action) || "saveApply".equalsIgnoreCase(action)) {
                Map<String, String> kv = new LinkedHashMap<>();
                put(kv, RuntimeConfigStore.Keys.DIAMETER_ENABLED, f.get("enabled"));
                put(kv, RuntimeConfigStore.Keys.DIAMETER_HOST, f.get("host"));
                put(kv, RuntimeConfigStore.Keys.DIAMETER_PORT, f.get("port"));
                put(kv, RuntimeConfigStore.Keys.DIAMETER_REALM, f.get("realm"));
                put(kv, RuntimeConfigStore.Keys.DIAMETER_ORIGIN_HOST, f.get("originHost"));
                put(kv, RuntimeConfigStore.Keys.DIAMETER_DEST_REALM, f.get("destinationRealm"));
                put(kv, RuntimeConfigStore.Keys.DIAMETER_DEST_HOST, f.get("destinationHost"));
                store.putAll(kv);
            }
            String notice = "saved";
            if ("saveApply".equalsIgnoreCase(action) || "apply".equalsIgnoreCase(action)) {
                notice = diameterApply.apply();
            } else if ("start".equalsIgnoreCase(action)) {
                notice = diameterApply.start();
            } else if ("stop".equalsIgnoreCase(action)) {
                notice = diameterApply.stop();
            }
            return planeNotice(notice, "ok");
        } catch (RuntimeException ex) {
            return planeNotice("error: " + nullToEmpty(ex.getMessage()), "error");
        }
    }

    public AdminHttpHandler.HttpReply sipPost(String body) {
        Map<String, String> f = AdminCatalogHandler.parseForm(body);
        String action = f.getOrDefault("action", "save");
        try {
            if ("save".equalsIgnoreCase(action) || "saveApply".equalsIgnoreCase(action)) {
                Map<String, String> kv = new LinkedHashMap<>();
                put(kv, RuntimeConfigStore.Keys.SIP_ENABLED, f.get("enabled"));
                put(kv, RuntimeConfigStore.Keys.SIP_HOST, f.get("host"));
                put(kv, RuntimeConfigStore.Keys.SIP_TCP_PORT, f.get("tcpPort"));
                put(kv, RuntimeConfigStore.Keys.SIP_UDP_PORT, f.get("udpPort"));
                put(kv, RuntimeConfigStore.Keys.SIP_FROM_URI, f.get("fromUri"));
                put(kv, RuntimeConfigStore.Keys.SIP_REQUEST_URI, f.get("requestUriTemplate"));
                store.putAll(kv);
            }
            String notice = "saved";
            if ("saveApply".equalsIgnoreCase(action) || "apply".equalsIgnoreCase(action)) {
                notice = sipApply.apply();
            } else if ("start".equalsIgnoreCase(action)) {
                notice = sipApply.start();
            } else if ("stop".equalsIgnoreCase(action)) {
                notice = sipApply.stop();
            }
            return planeNotice(notice, "ok");
        } catch (RuntimeException ex) {
            return planeNotice("error: " + nullToEmpty(ex.getMessage()), "error");
        }
    }

    public AdminHttpHandler.HttpReply hlrPost(String body) {
        return hlrPost(body, null);
    }

    public AdminHttpHandler.HttpReply hlrPost(String body, AdminAuthService.Principal who) {
        if (who != null && who.isTenantScoped()) {
            return AdminHttpHandler.HttpReply.text(403, "forbidden for TENANT role");
        }
        if (!canEditHlr(who)) {
            return AdminHttpHandler.HttpReply.text(403, "HLR face editable by ADMIN/OPS only");
        }
        Map<String, String> f = AdminCatalogHandler.parseForm(body);
        String action = f.getOrDefault("action", "save");
        try {
            if ("save".equalsIgnoreCase(action) || "saveApply".equalsIgnoreCase(action)
                    || "apply".equalsIgnoreCase(action)) {
                if ("save".equalsIgnoreCase(action) || "saveApply".equalsIgnoreCase(action)) {
                    Map<String, String> kv = new LinkedHashMap<>();
                    put(kv, RuntimeConfigStore.Keys.HLR_MODE, f.get("mode"));
                    put(kv, RuntimeConfigStore.Keys.HLR_FAKE_IMSI, f.get("fakeImsi"));
                    put(kv, RuntimeConfigStore.Keys.HLR_FAKE_MSC_GT, f.get("fakeMscGt"));
                    put(kv, RuntimeConfigStore.Keys.HLR_UPPER_GT, f.get("upperGt"));
                    put(kv, RuntimeConfigStore.Keys.HLR_DIAM_DEST_HOST, f.get("diameterDestinationHost"));
                    put(kv, RuntimeConfigStore.Keys.HLR_DIAM_DEST_REALM, f.get("diameterDestinationRealm"));
                    store.putAll(kv);
                }
            }
            String notice = "saved";
            if ("saveApply".equalsIgnoreCase(action) || "apply".equalsIgnoreCase(action)) {
                // HlrFaceService / HlrResolvePolicy read RuntimeConfigStore on each SRI — no RA rewire.
                notice = "saved (HLR face reads RuntimeConfigStore at request time)";
            }
            return planeNotice(notice, "ok");
        } catch (RuntimeException ex) {
            return planeNotice("error: " + nullToEmpty(ex.getMessage()), "error");
        }
    }

    public AdminHttpHandler.HttpReply httpPost(String body) {
        Map<String, String> f = AdminCatalogHandler.parseForm(body);
        String action = f.getOrDefault("action", "save");
        try {
            if ("save".equalsIgnoreCase(action) || "saveApply".equalsIgnoreCase(action)) {
                Map<String, String> kv = new LinkedHashMap<>();
                put(kv, RuntimeConfigStore.Keys.HTTP_CLIENT_ENABLED, f.get("clientEnabled"));
                put(kv, RuntimeConfigStore.Keys.HTTP_SERVER_ENABLED, f.get("serverEnabled"));
                put(kv, RuntimeConfigStore.Keys.HTTP_RA_HOST, f.get("listenHost"));
                put(kv, RuntimeConfigStore.Keys.HTTP_RA_PORT, f.get("listenPort"));
                put(kv, RuntimeConfigStore.Keys.HTTP_CONNECT_MS, f.get("connectTimeoutMs"));
                put(kv, RuntimeConfigStore.Keys.HTTP_REQUEST_MS, f.get("requestTimeoutMs"));
                put(kv, RuntimeConfigStore.Keys.HTTP_CALLBACK_PATH, f.get("callbackPath"));
                put(kv, RuntimeConfigStore.Keys.HTTP_NI_PATH, f.get("niPath"));
                put(kv, RuntimeConfigStore.Keys.HTTP_CLIENT_BRIDGE, f.get("clientBridgeEnabled"));
                store.putAll(kv);
            }
            String notice = "saved";
            if ("saveApply".equalsIgnoreCase(action) || "apply".equalsIgnoreCase(action)) {
                notice = httpApply.apply();
            } else if ("start".equalsIgnoreCase(action)) {
                notice = httpApply.start();
            } else if ("stop".equalsIgnoreCase(action)) {
                notice = httpApply.stop();
            }
            return planeNotice(notice, "ok");
        } catch (RuntimeException ex) {
            return planeNotice("error: " + nullToEmpty(ex.getMessage()), "error");
        }
    }

    public AdminHttpHandler.HttpReply grpcPost(String body) {
        Map<String, String> f = AdminCatalogHandler.parseForm(body);
        String action = f.getOrDefault("action", "save");
        try {
            if ("save".equalsIgnoreCase(action) || "saveApply".equalsIgnoreCase(action)) {
                Map<String, String> kv = new LinkedHashMap<>();
                put(kv, RuntimeConfigStore.Keys.GRPC_CLIENT_ENABLED, f.get("clientEnabled"));
                put(kv, RuntimeConfigStore.Keys.GRPC_SERVER_ENABLED, f.get("serverEnabled"));
                put(kv, RuntimeConfigStore.Keys.GRPC_SERVER_PORT, f.get("listenPort"));
                put(kv, RuntimeConfigStore.Keys.GRPC_INVOKE_MS, f.get("invokeTimeoutMs"));
                put(kv, RuntimeConfigStore.Keys.GRPC_CLIENT_BRIDGE, f.get("clientBridgeEnabled"));
                store.putAll(kv);
            }
            String notice = "saved";
            if ("saveApply".equalsIgnoreCase(action) || "apply".equalsIgnoreCase(action)) {
                notice = grpcApply.apply();
            } else if ("start".equalsIgnoreCase(action)) {
                notice = grpcApply.start();
            } else if ("stop".equalsIgnoreCase(action)) {
                notice = grpcApply.stop();
            }
            return planeNotice(notice, "ok");
        } catch (RuntimeException ex) {
            return planeNotice("error: " + nullToEmpty(ex.getMessage()), "error");
        }
    }

    public AdminHttpHandler.HttpReply bridgePost(String body) {
        Map<String, String> f = AdminCatalogHandler.parseForm(body);
        try {
            Map<String, String> kv = new LinkedHashMap<>();
            put(kv, RuntimeConfigStore.Keys.BRIDGE_ENABLED, f.get("bridgeEnabled"));
            put(kv, RuntimeConfigStore.Keys.ASYNC_GATE_MS, f.get("asyncGateTimeoutMs"));
            put(kv, RuntimeConfigStore.Keys.DIALOG_TIMEOUT_MS, f.get("dialogTimeoutMs"));
            put(kv, RuntimeConfigStore.Keys.ASYNC_WAIT_MSG, f.get("asyncWaitMessage"));
            put(kv, RuntimeConfigStore.Keys.ASYNC_HARD_FAIL_MSG, f.get("asyncHardFailMessage"));
            put(kv, RuntimeConfigStore.Keys.HTTP_CLIENT_BRIDGE, f.get("httpClientBridgeEnabled"));
            put(kv, RuntimeConfigStore.Keys.GRPC_CLIENT_BRIDGE, f.get("grpcClientBridgeEnabled"));
            store.putAll(kv);
            long gate = config.asyncGateTimeoutMs();
            long dialog = config.dialogTimeoutMs();
            String notice = "saved";
            String kind = "ok";
            if (gate > 0 && gate >= dialog) {
                notice = "saved (warn: asyncGate must be < dialogTimeout)";
                kind = "info";
            }
            return bridgeNotice(notice, kind);
        } catch (RuntimeException ex) {
            return bridgeNotice("error: " + nullToEmpty(ex.getMessage()), "error");
        }
    }

    private static AdminHttpHandler.HttpReply bridgeNotice(String message, String kind) {
        return planeNotice(message, kind);
    }

    private static AdminHttpHandler.HttpReply planeNotice(String message, String kind) {
        String html = "<p class=\"admin-notice\">" + esc(message) + "</p>";
        return AdminHttpHandler.HttpReply.html(html)
                .withHeader("HX-Trigger",
                        "{\"ussdToast\":{\"message\":" + jsonStr(message) + ",\"kind\":" + jsonStr(kind) + "}}")
                .withHeader("Vary", "HX-Request");
    }

    private static String jsonStr(String s) {
        if (s == null) {
            return "\"\"";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String ss7Html(String notice, AdminAuthService.Principal who) {
        boolean edit = canEditSs7Stack(who);
        StringBuilder sb = new StringBuilder("<div class=\"plane ss7-panel space-y-4\">");
        if (notice != null) {
            sb.append("<p class=\"admin-notice\">").append(esc(notice)).append("</p>");
        }
        sb.append(linkStatus.htmlPartial("ss7"));
        if (!edit) {
            sb.append("<p class=\"mt-3 text-sm text-ink-mute\">Live SS7 peer state only. ")
                    .append("SCTP/SCCP stack and Apply are <strong class=\"text-slate-200\">ADMIN/OPS</strong> only.</p>");
            sb.append("<p class=\"text-sm text-ink-mute\">Metrics: ")
                    .append("<a class=\"text-signal hover:underline\" href=\"/telemetry/?tab=ss7\">Monitor Hub</a>.</p>");
            sb.append("</div>");
            return sb.toString();
        }
        sb.append(ss7StackEditor());
        sb.append("<h3 class=\"text-sm font-semibold uppercase tracking-wider text-ink-mute\">JSON stack</h3>");
        sb.append("<p class=\"text-sm text-ink-mute mb-3\">Save &amp; Apply rewires ra-jss7 from stackJson + config-file. ")
                .append("Metrics: <a class=\"text-signal hover:underline\" href=\"/telemetry/?tab=ss7\">Monitor Hub</a>.</p>");
        sb.append(formOpen("/admin/ss7/config"));
        sb.append(field("mapEnabled", "enabled", String.valueOf(ss7Apply.mapEnabled()), "true|false"));
        sb.append(field("configFile", "config-file", ss7Apply.configFile(), null));
        sb.append(ss7StackJsonTextarea());
        sb.append(planeButtons());
        sb.append("</form></div>");
        return sb.toString();
    }

    /** ADMIN/OPS only — null who (API-key ADMIN) counts as editable. */
    static boolean canEditSs7Stack(AdminAuthService.Principal who) {
        return who == null || who.isAdminOrOps();
    }

    /** ADMIN/OPS only — null who (API-key ADMIN) counts as editable. */
    static boolean canEditHlr(AdminAuthService.Principal who) {
        return who == null || who.isAdminOrOps();
    }

    private String hlrHtml(String notice, AdminAuthService.Principal who) {
        boolean edit = canEditHlr(who);
        et.restlink.ussdgw.hlr.HlrResolveMode mode = config.hlrMode();
        StringBuilder sb = new StringBuilder("<div class=\"plane hlr-panel space-y-4\">");
        if (notice != null) {
            sb.append("<p class=\"admin-notice\">").append(esc(notice)).append("</p>");
        }
        sb.append("<h2 class=\"text-xl font-semibold text-slate-50\">HLR Face</h2>");
        sb.append("<p class=\"text-sm text-slate-300\">Inbound SRI-SM — mode <code class=\"font-mono text-signal\">")
                .append(esc(mode.name())).append("</code>. Default PROXY_MAP fail-closed. ")
                .append("Separate from SS7 stack JSON.</p>");
        if (!edit) {
            sb.append("<p class=\"mt-3 text-sm text-ink-mute\">HLR face settings are ")
                    .append("<strong class=\"text-slate-200\">ADMIN/OPS</strong> only.</p>");
            sb.append("</div>");
            return sb.toString();
        }
        sb.append(formOpen("/admin/hlr"));
        sb.append("<div><label class=\"block text-xs uppercase tracking-wider text-ink-mute\">mode</label>");
        sb.append("<select name=\"mode\" class=\"mt-1 w-full rounded-md border border-ink-line bg-ink-panel px-3 py-2 text-sm ")
                .append("focus:border-signal focus:outline-none\">");
        for (et.restlink.ussdgw.hlr.HlrResolveMode opt : et.restlink.ussdgw.hlr.HlrResolveMode.values()) {
            sb.append("<option value=\"").append(opt.name()).append("\"");
            if (opt == mode) {
                sb.append(" selected");
            }
            sb.append(">").append(opt.name()).append("</option>");
        }
        sb.append("</select></div>");
        sb.append(field("fakeImsi", "fake IMSI", config.hlrFakeImsi(), null));
        sb.append(field("fakeMscGt", "fake MSC GT", config.hlrFakeMscGt(), null));
        sb.append(field("upperGt", "upper HLR GT", config.hlrUpperGt(),
                "SRI CalledParty; empty→props; no local loop"));
        sb.append(field("diameterDestinationHost", "Diameter destinationHost",
                config.hlrDiamDestinationHost(), null));
        sb.append(field("diameterDestinationRealm", "Diameter destinationRealm",
                config.hlrDiamDestinationRealm(), null));
        sb.append("<div class=\"plane-actions flex flex-wrap gap-2 pt-2\">")
                .append("<button type=\"submit\" name=\"action\" value=\"save\" ")
                .append("class=\"rounded-md border border-ink-line px-3 py-2 text-sm text-ink-mute ")
                .append("hover:border-signal hover:text-signal\">Save</button>")
                .append("<button type=\"submit\" name=\"action\" value=\"saveApply\" ")
                .append("class=\"rounded-md bg-signal px-3 py-2 text-sm font-semibold text-ink ")
                .append("hover:bg-signal-dim\">Save &amp; Apply</button>")
                .append("</div>");
        sb.append("</form></div>");
        return sb.toString();
    }

    /**
     * SCTP/SCCP summary + editable JSON for ADMIN/OPS from {@code ussd.map.config-file}.
     */
    String ss7StackEditor() {
        String cfgPath = ss7Apply.configFile();
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"rounded-lg border border-ink-line bg-ink-panel/60 p-4 mb-4\">");
        sb.append("<h3 class=\"text-sm font-semibold uppercase tracking-wider text-signal\">Stack file (SCTP · SCCP)</h3>");
        sb.append("<p class=\"mt-1 text-xs text-ink-mute\">ADMIN/OPS editable — Save writes JSON to config-file path.</p>");
        if (cfgPath == null || cfgPath.isBlank()) {
            sb.append("<p class=\"mt-2 text-sm text-ink-mute\">No config-file — set path below; stackJson is the source of truth.</p>");
            sb.append("</div>");
            return sb.toString();
        }
        sb.append("<p class=\"mt-1 font-mono text-xs text-ink-mute\">").append(esc(cfgPath)).append("</p>");
        sb.append(ss7StackFileSummary(cfgPath));
        sb.append("</div>");
        return sb.toString();
    }

    private String ss7StackJsonTextarea() {
        String raw = readStackJsonRaw(ss7Apply.configFile());
        return "<div class=\"mt-4\"><label class=\"block text-xs uppercase tracking-wider text-ink-mute\">"
                + "stackJson (SCTP · SCCP)</label>"
                + "<textarea name=\"stackJson\" rows=\"14\" "
                + "class=\"mt-1 w-full rounded-md border border-ink-line bg-ink-panel px-3 py-2 font-mono text-xs "
                + "focus:border-signal focus:outline-none focus:ring-1 focus:ring-signal/40\">"
                + esc(raw) + "</textarea></div>";
    }

    /** Package-visible for tests — SCTP/SCCP summary lines. */
    String ss7StackFilePreview() {
        return ss7StackFileSummary(ss7Apply.configFile());
    }

    private String ss7StackFileSummary(String cfgPath) {
        StringBuilder sb = new StringBuilder();
        if (cfgPath == null || cfgPath.isBlank()) {
            return "<p class=\"mt-2 text-sm text-ink-mute\">No config-file.</p>";
        }
        Path path = resolveConfigPath(cfgPath);
        if (!Files.isRegularFile(path)) {
            return "<p class=\"mt-2 text-sm text-rose-400\">File not found at "
                    + esc(path.toString()) + "</p>";
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
            JsonNode links = root.path("sctp").path("links");
            sb.append("<p class=\"mt-3 text-xs uppercase tracking-wider text-ink-mute\">SCTP links</p>");
            if (!links.isArray() || links.isEmpty()) {
                sb.append("<p class=\"font-mono text-xs text-ink-mute\">(none)</p>");
            } else {
                sb.append("<ul class=\"mt-1 space-y-1 font-mono text-xs text-slate-300\">");
                for (JsonNode link : links) {
                    sb.append("<li>")
                            .append(esc(textOr(link, "name", "?")))
                            .append(" · ").append(esc(textOr(link, "type", "?")))
                            .append(" · ").append(esc(textOr(link, "channel", "?")))
                            .append(" · local ").append(esc(textOr(link, "local", "?")))
                            .append(" → peer ").append(esc(textOr(link, "peer", "?")))
                            .append("</li>");
                }
                sb.append("</ul>");
            }
            JsonNode sccp = root.path("sccp");
            sb.append("<p class=\"mt-3 text-xs uppercase tracking-wider text-ink-mute\">SCCP</p>");
            if (sccp.isMissingNode() || sccp.isNull() || !sccp.isObject()) {
                sb.append("<p class=\"font-mono text-xs text-ink-mute\">(absent)</p>");
            } else {
                JsonNode pts = sccp.path("localPoints");
                sb.append("<ul class=\"mt-1 space-y-1 font-mono text-xs text-slate-300\">");
                if (pts.isArray()) {
                    for (JsonNode pt : pts) {
                        sb.append("<li>pc=").append(esc(textOr(pt, "pc", "?")))
                                .append(" ni=").append(esc(textOr(pt, "networkIndicator", "?")))
                                .append(" netId=").append(esc(textOr(pt, "networkId", "?")))
                                .append("</li>");
                    }
                }
                JsonNode routes = sccp.path("routing");
                if (routes.isArray()) {
                    for (JsonNode r : routes) {
                        sb.append("<li>route ").append(esc(textOr(r, "from", "?")))
                                .append(" match.gt=").append(esc(r.path("match").path("gt").asText("?")))
                                .append(" → pc=").append(esc(r.path("to").path("pc").asText("?")))
                                .append(" mask=").append(esc(textOr(r, "mask", "?")))
                                .append("</li>");
                    }
                }
                if ((!pts.isArray() || pts.isEmpty()) && (!routes.isArray() || routes.isEmpty())) {
                    sb.append("<li>(empty sccp object)</li>");
                }
                sb.append("</ul>");
            }
        } catch (Exception ex) {
            sb.append("<p class=\"mt-2 text-sm text-rose-400\">Parse error: ")
                    .append(esc(ex.getMessage())).append("</p>");
        }
        return sb.toString();
    }

    private static Path resolveConfigPath(String cfgPath) {
        Path path = Path.of(cfgPath);
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir", ".")).resolve(path).normalize();
        }
        return path;
    }

    private String readStackJsonRaw(String cfgPath) {
        if (cfgPath == null || cfgPath.isBlank()) {
            return "";
        }
        Path path = resolveConfigPath(cfgPath);
        try {
            if (!Files.isRegularFile(path)) {
                return "";
            }
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            JsonNode n = JSON.readTree(raw);
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(n);
        } catch (Exception ex) {
            return "";
        }
    }

    private void writeStackJson(String cfgPath, String stackJson) {
        if (cfgPath == null || cfgPath.isBlank()) {
            throw new IllegalArgumentException("config-file required to save stackJson");
        }
        try {
            JsonNode n = JSON.readTree(stackJson);
            if (!n.isObject()) {
                throw new IllegalArgumentException("stackJson must be a JSON object");
            }
            Path path = resolveConfigPath(cfgPath);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(n),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("stackJson write failed: " + ex.getMessage(), ex);
        }
    }

    private static String textOr(JsonNode n, String field, String fallback) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return fallback;
        }
        JsonNode v = n.get(field);
        if (v.isNumber() || v.isBoolean()) {
            return v.asText();
        }
        String t = v.asText();
        return t == null || t.isBlank() ? fallback : t;
    }

    private String smppHtml(String notice) {
        StringBuilder sb = new StringBuilder("<div class=\"plane smpp-panel space-y-4\">");
        if (notice != null) {
            sb.append("<p class=\"admin-notice\">").append(esc(notice)).append("</p>");
        }
        sb.append(linkStatus.htmlPartial("smpp"));
        sb.append("<p class=\"text-sm text-ink-mute\">JSON only (<code class=\"font-mono\">smpp.json</code>). ")
                .append("<a class=\"text-signal hover:underline\" href=\"/telemetry/?tab=smpp\">Monitor Hub</a>.</p>");
        sb.append(formOpen("/admin/smpp/config"));
        sb.append("<div><label class=\"block text-xs uppercase tracking-wider text-ink-mute\">smppJson</label>");
        sb.append("<textarea name=\"smppJson\" rows=\"14\" ")
                .append("class=\"mt-1 w-full rounded-md border border-ink-line bg-ink-panel px-3 py-2 font-mono text-xs ")
                .append("focus:border-signal focus:outline-none focus:ring-1 focus:ring-signal/40\">")
                .append(esc(smppConfig.activeJsonOrLab())).append("</textarea></div>");
        sb.append(planeButtons());
        sb.append("</form></div>");
        return sb.toString();
    }

    private String httpHtml(String notice) {
        String host = httpApply.listenHost();
        int port = httpApply.listenPort();
        String niPath = config.httpNiPath();
        if (niPath == null || niPath.isBlank()) {
            niPath = "/ussd";
        }
        String callback = config.httpCallbackPath();
        String pushUrl = PublicPushUrls.publicNiPushUrl(config.publicBaseUrl(), host, port, niPath);
        if (pushUrl.isEmpty()) {
            pushUrl = "(set ussd.admin.public-base-url)";
        }
        String cbPath = callback == null || callback.isBlank() ? ""
                : (callback.startsWith("/") ? callback : "/" + callback);
        String callbackUrl = PublicPushUrls.publicNiPushUrl(config.publicBaseUrl(), host, port,
                cbPath.isEmpty() ? "/" : cbPath);
        if (callbackUrl.isEmpty()) {
            callbackUrl = "(set ussd.admin.public-base-url)";
        }

        StringBuilder sb = new StringBuilder("<div class=\"plane http-panel space-y-4\">");
        if (notice != null) {
            sb.append("<p class=\"admin-notice\">").append(esc(notice)).append("</p>");
        }
        sb.append(linkStatus.htmlPartial("http"));
        sb.append("<div class=\"rounded-lg border border-signal/40 bg-signal/5 p-4\">");
        sb.append("<h3 class=\"text-sm font-semibold uppercase tracking-wider text-signal\">Push USSD (classic NI)</h3>");
        sb.append("<p class=\"mt-2 text-sm text-slate-300\">AS → GW network-initiated push. Path from ")
                .append("<code class=\"font-mono text-signal\">ussd.http.ni-path</code>")
                .append(" (default <code class=\"font-mono\">/ussd</code>) + listen host:port.</p>");
        sb.append("<p class=\"mt-2 font-mono text-sm text-slate-100\">POST ")
                .append(esc(pushUrl)).append("</p>");
        sb.append("<p class=\"mt-1 font-mono text-xs text-ink-mute\">ni-path=")
                .append(esc(niPath)).append(" · listen=")
                .append(esc(host)).append(':').append(port).append("</p>");
        sb.append("<p class=\"mt-3 text-xs uppercase tracking-wider text-ink-mute\">Callback path</p>");
        sb.append("<p class=\"font-mono text-sm text-slate-200\">")
                .append(esc(callbackUrl.isBlank() ? String.valueOf(callback) : callbackUrl))
                .append("</p>");
        sb.append("</div>");
        sb.append("<p class=\"text-sm text-ink-mute\">Status only — no config form. Modes: <b>SYNC</b> / <b>ASYNC_ACK</b> / <b>BRIDGE</b>. ")
                .append("<a class=\"text-signal hover:underline\" href=\"/telemetry/?tab=http\">Monitor Hub</a>.</p>");
        sb.append("</div>");
        return sb.toString();
    }

    private String grpcHtml(String notice) {
        StringBuilder sb = new StringBuilder("<div class=\"plane grpc-panel space-y-4\">");
        if (notice != null) {
            sb.append("<p class=\"admin-notice\">").append(esc(notice)).append("</p>");
        }
        sb.append(linkStatus.htmlPartial("grpc"));
        sb.append("<p class=\"text-sm text-ink-mute\">Status only — JSON AsRequest/AsResponse over InvokeGrpc. ")
                .append("Same SYNC / ASYNC_ACK / BRIDGE as HTTP.</p>");
        sb.append("</div>");
        return sb.toString();
    }

    private String accessPlaneHtml(String title, String tab, String blurb) {
        StringBuilder sb = new StringBuilder("<div class=\"plane space-y-4\">");
        sb.append("<h2 class=\"text-xl font-semibold text-slate-50\">").append(esc(title)).append("</h2>");
        sb.append("<p class=\"text-sm text-slate-300\">").append(esc(blurb)).append("</p>");
        sb.append(linkStatus.htmlPartial(tab));
        sb.append("<p class=\"text-sm text-ink-mute\">")
                .append("<a class=\"text-signal hover:underline\" href=\"/telemetry/?tab=")
                .append(esc(tab)).append("\">Monitor Hub</a></p>");
        sb.append("</div>");
        return sb.toString();
    }

    private String bridgeHtml(String notice) {
        // Legacy HTMX panel (automation / older shell). Preferred UI is disk bridge.html.
        StringBuilder sb = new StringBuilder("<div class=\"plane bridge-panel space-y-3\">");
        if (notice != null) {
            sb.append("<p class=\"admin-notice\">").append(esc(notice)).append("</p>");
        }
        sb.append("<h2 class=\"text-xl font-semibold text-slate-50\">Virtual Session Bridge</h2>");
        sb.append("<p class=\"text-sm text-ink-mute\">Invariant: 1000 ≤ adaptiveGate ≤ asyncGateTimeoutMs &lt; dialogTimeout. ")
                .append("Per-leg bridge flags arm NI push after gate.</p>");
        sb.append(formOpen("/admin/bridge"));
        sb.append(field("bridgeEnabled", "bridgeEnabled", String.valueOf(config.bridgeEnabled()), "true|false"));
        sb.append(field("asyncGateTimeoutMs", "asyncGateMs", String.valueOf(config.asyncGateTimeoutMs()), null));
        sb.append(field("dialogTimeoutMs", "dialogTimeoutMs", String.valueOf(config.dialogTimeoutMs()), null));
        sb.append(field("asyncWaitMessage", "waitMsg", config.asyncWaitMessage(), null));
        sb.append(field("asyncHardFailMessage", "hardFailMsg", config.asyncHardFailMessage(), null));
        sb.append(field("httpClientBridgeEnabled", "httpBridge", String.valueOf(config.httpClientBridgeEnabled()), "true|false"));
        sb.append(field("grpcClientBridgeEnabled", "grpcBridge", String.valueOf(config.grpcClientBridgeEnabled()), "true|false"));
        sb.append("<button type=\"submit\" name=\"action\" value=\"save\" "
                + "class=\"rounded-md bg-signal px-4 py-2 text-sm font-semibold text-ink hover:bg-signal-dim\">Save</button>");
        sb.append("</form></div>");
        return sb.toString();
    }

    /** Forms target {@code #plane-notice} on Routing-style config shells. */
    private static String formOpen(String path) {
        return "<div id=\"plane-notice\"></div>"
                + "<form method=\"post\" action=\"" + path + "\" accept-charset=\"UTF-8\" "
                + "hx-post=\"" + path + "\" hx-target=\"#plane-notice\" hx-swap=\"innerHTML\" "
                + "class=\"grid-form space-y-3\">";
    }

    private static String field(String name, String label, String value, String hint) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div><label class=\"block text-xs uppercase tracking-wider text-ink-mute\">")
                .append(esc(label));
        if (hint != null) {
            sb.append(" <span class=\"normal-case tracking-normal text-ink-mute/80\">(")
                    .append(esc(hint)).append(")</span>");
        }
        sb.append("</label><input name=\"").append(esc(name)).append("\" value=\"")
                .append(esc(value == null ? "" : value))
                .append("\" class=\"mt-1 w-full rounded-md border border-ink-line bg-ink-panel px-3 py-2 font-mono text-sm ")
                .append("focus:border-signal focus:outline-none focus:ring-1 focus:ring-signal/40\"/></div>");
        return sb.toString();
    }

    private static String planeButtons() {
        return "<div class=\"plane-actions flex flex-wrap gap-2 pt-2\">"
                + "<button type=\"submit\" name=\"action\" value=\"save\" "
                + "class=\"rounded-md border border-ink-line px-3 py-2 text-sm text-ink-mute hover:border-signal hover:text-signal\">Save</button>"
                + "<button type=\"submit\" name=\"action\" value=\"saveApply\" "
                + "class=\"rounded-md bg-signal px-3 py-2 text-sm font-semibold text-ink hover:bg-signal-dim\">Save &amp; Apply</button>"
                + "<button type=\"submit\" name=\"action\" value=\"apply\" "
                + "class=\"rounded-md border border-ink-line px-3 py-2 text-sm text-ink-mute hover:border-signal hover:text-signal\">Apply</button>"
                + "<button type=\"submit\" name=\"action\" value=\"start\" "
                + "class=\"rounded-md border border-ink-line px-3 py-2 text-sm text-ink-mute hover:border-signal hover:text-signal\">Start</button>"
                + "<button type=\"submit\" name=\"action\" value=\"stop\" "
                + "class=\"rounded-md border border-ink-line px-3 py-2 text-sm text-ink-mute hover:border-signal hover:text-signal\">Stop</button>"
                + "</div>";
    }

    private static void put(Map<String, String> kv, String key, String value) {
        if (value != null) kv.put(key, value.trim());
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
