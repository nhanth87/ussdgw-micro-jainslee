package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.config.RuntimeConfigStore;
import et.restlink.ussdgw.config.SmppConfigSupport;
import et.restlink.ussdgw.config.Ss7ConfigSupport;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.service.GrpcApplyService;
import et.restlink.ussdgw.service.HttpApplyService;
import et.restlink.ussdgw.service.SmppApplyService;
import et.restlink.ussdgw.service.Ss7ApplyService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTMX plane panels: jSS7, SMPP, HTTP, gRPC, Bridge — Save to {@link RuntimeConfigStore} then Apply.
 * Diameter / SIP RA config lives in micro-jainslee ({@code ra-diameter}, {@code ra-sip-servlet}), not here.
 */
@ApplicationScoped
public class AdminPlaneHandler {
    @Inject RuntimeConfigStore store;
    @Inject UssdConfigService config;
    @Inject LinkStatusService linkStatus;
    @Inject Ss7ApplyService ss7Apply;
    @Inject SmppApplyService smppApply;
    @Inject HttpApplyService httpApply;
    @Inject GrpcApplyService grpcApply;
    @Inject SmppConfigSupport smppConfig;
    @Inject Ss7ConfigSupport ss7Config;

    public AdminHttpHandler.HttpReply ss7Get() {
        return AdminHttpHandler.HttpReply.html(ss7Html(null));
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

    public AdminHttpHandler.HttpReply ss7Post(String body) {
        Map<String, String> f = AdminCatalogHandler.parseForm(body);
        String action = f.getOrDefault("action", "save");
        try {
            if ("save".equalsIgnoreCase(action) || "saveApply".equalsIgnoreCase(action)) {
                Map<String, String> kv = new LinkedHashMap<>();
                put(kv, RuntimeConfigStore.Keys.MAP_ENABLED, f.get("mapEnabled"));
                put(kv, RuntimeConfigStore.Keys.MAP_HOST_IP, f.get("hostIp"));
                put(kv, RuntimeConfigStore.Keys.MAP_HOST_PORT, f.get("hostPort"));
                put(kv, RuntimeConfigStore.Keys.MAP_PEER_IP, f.get("peerIp"));
                put(kv, RuntimeConfigStore.Keys.MAP_PEER_PORT, f.get("peerPort"));
                put(kv, RuntimeConfigStore.Keys.MAP_OPC, f.get("opc"));
                put(kv, RuntimeConfigStore.Keys.MAP_DPC, f.get("dpc"));
                put(kv, RuntimeConfigStore.Keys.MAP_CHANNEL, f.get("ipChannelType"));
                put(kv, RuntimeConfigStore.Keys.MAP_CONFIG_FILE, f.get("configFile"));
                put(kv, RuntimeConfigStore.Keys.SS7_PERSIST, f.get("persistDir"));
                put(kv, RuntimeConfigStore.Keys.USSD_GT, f.get("ussdGt"));
                put(kv, RuntimeConfigStore.Keys.USSD_SSN, f.get("ussdSsn"));
                put(kv, RuntimeConfigStore.Keys.HLR_SSN, f.get("hlrSsn"));
                put(kv, RuntimeConfigStore.Keys.MSC_SSN, f.get("mscSsn"));
                put(kv, RuntimeConfigStore.Keys.MAX_MAP_V, f.get("maxMapVersion"));
                ss7Config.saveFromForm(kv);
            }
            String notice = "saved";
            if ("saveApply".equalsIgnoreCase(action) || "apply".equalsIgnoreCase(action)) {
                notice = ss7Apply.apply();
            } else if ("start".equalsIgnoreCase(action)) {
                notice = ss7Apply.start();
            } else if ("stop".equalsIgnoreCase(action)) {
                notice = ss7Apply.stop();
            }
            return AdminHttpHandler.HttpReply.html(ss7Html(notice));
        } catch (RuntimeException ex) {
            return AdminHttpHandler.HttpReply.html(ss7Html("error: " + esc(ex.getMessage())));
        }
    }

    public AdminHttpHandler.HttpReply smppPost(String body) {
        Map<String, String> f = AdminCatalogHandler.parseForm(body);
        String action = f.getOrDefault("action", "save");
        try {
            if ("save".equalsIgnoreCase(action) || "saveApply".equalsIgnoreCase(action)) {
                Map<String, String> kv = new LinkedHashMap<>();
                put(kv, RuntimeConfigStore.Keys.SMPP_CLIENT_ENABLED, f.get("clientEnabled"));
                put(kv, RuntimeConfigStore.Keys.SMPP_HOST, f.get("host"));
                put(kv, RuntimeConfigStore.Keys.SMPP_PORT, f.get("port"));
                put(kv, RuntimeConfigStore.Keys.SMPP_SYSTEM_ID, f.get("systemId"));
                if (f.get("password") != null && !f.get("password").isBlank()) {
                    put(kv, RuntimeConfigStore.Keys.SMPP_PASSWORD, f.get("password"));
                }
                put(kv, RuntimeConfigStore.Keys.SMPP_SOURCE, f.get("sourceAddr"));
                put(kv, RuntimeConfigStore.Keys.SMPP_SERVER_ENABLED, f.get("serverEnabled"));
                put(kv, RuntimeConfigStore.Keys.SMPP_SERVER_PORT, f.get("serverPort"));
                put(kv, RuntimeConfigStore.Keys.SMPP_SERVER_SYSTEM_ID, f.get("serverSystemId"));
                if (f.get("serverPassword") != null && !f.get("serverPassword").isBlank()) {
                    put(kv, RuntimeConfigStore.Keys.SMPP_SERVER_PASSWORD, f.get("serverPassword"));
                }
                put(kv, RuntimeConfigStore.Keys.SMPP_USSD_ENABLED, f.get("ussdOverSmpp"));
                store.putAll(kv);
                smppConfig.saveFromFallback(smppApply.fallback(),
                        store.getBool(RuntimeConfigStore.Keys.SMPP_USSD_ENABLED, false));
            }
            String notice = "saved";
            if ("saveApply".equalsIgnoreCase(action) || "apply".equalsIgnoreCase(action)) {
                notice = smppApply.apply();
            } else if ("start".equalsIgnoreCase(action)) {
                notice = smppApply.start();
            } else if ("stop".equalsIgnoreCase(action)) {
                notice = smppApply.stop();
            }
            return AdminHttpHandler.HttpReply.html(smppHtml(notice));
        } catch (RuntimeException ex) {
            return AdminHttpHandler.HttpReply.html(smppHtml("error: " + esc(ex.getMessage())));
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
            return AdminHttpHandler.HttpReply.html(httpHtml(notice));
        } catch (RuntimeException ex) {
            return AdminHttpHandler.HttpReply.html(httpHtml("error: " + esc(ex.getMessage())));
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
            return AdminHttpHandler.HttpReply.html(grpcHtml(notice));
        } catch (RuntimeException ex) {
            return AdminHttpHandler.HttpReply.html(grpcHtml("error: " + esc(ex.getMessage())));
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
            if (gate > 0 && gate >= dialog) {
                notice = "saved (warn: asyncGate must be < dialogTimeout)";
            }
            return AdminHttpHandler.HttpReply.html(bridgeHtml(notice));
        } catch (RuntimeException ex) {
            return AdminHttpHandler.HttpReply.html(bridgeHtml("error: " + esc(ex.getMessage())));
        }
    }

    private String ss7Html(String notice) {
        StringBuilder sb = new StringBuilder("<div class=\"plane ss7-panel\">");
        if (notice != null) sb.append("<p class=\"notice\">").append(esc(notice)).append("</p>");
        sb.append("<h2>jSS7 / MAP (TS 29.002 USSD)</h2>");
        sb.append(linkStatus.htmlPartial("ss7"));
        sb.append("<p class=\"hint\">Primary UI: <a href=\"/telemetry/?tab=ss7\">Monitor Hub SS7</a>. ")
                .append("Form writes ussd_config; Save&amp;Apply rewires ra-jss7.</p>");
        sb.append(formOpen("/admin/ss7/config"));
        sb.append(field("mapEnabled", "enabled", String.valueOf(ss7Apply.mapEnabled()), "true|false"));
        sb.append(field("hostIp", "hostIp", ss7Apply.hostIp(), null));
        sb.append(field("hostPort", "hostPort", String.valueOf(ss7Apply.hostPort()), null));
        sb.append(field("peerIp", "peerIp", ss7Apply.peerIp(), null));
        sb.append(field("peerPort", "peerPort", String.valueOf(ss7Apply.peerPort()), null));
        sb.append(field("opc", "opc", String.valueOf(ss7Apply.opc()), null));
        sb.append(field("dpc", "dpc", String.valueOf(ss7Apply.dpc()), null));
        sb.append(field("ipChannelType", "channel", ss7Apply.channel(), "TCP|SCTP"));
        sb.append(field("configFile", "config-file", ss7Apply.configFile(), null));
        sb.append(field("persistDir", "persist-dir", ss7Apply.persistDir(), null));
        sb.append("<h3>MAP addressing</h3>");
        sb.append(field("ussdGt", "ussdGt", config.ussdGt(), null));
        sb.append(field("ussdSsn", "ussdSsn", String.valueOf(config.ussdSsn()), null));
        sb.append(field("hlrSsn", "hlrSsn", String.valueOf(config.hlrSsn()), null));
        sb.append(field("mscSsn", "mscSsn", String.valueOf(config.mscSsn()), null));
        sb.append(field("maxMapVersion", "maxMapV", String.valueOf(config.maxMapVersion()), null));
        sb.append(planeButtons("ss7"));
        sb.append("</form></div>");
        return sb.toString();
    }

    private String smppHtml(String notice) {
        var fb = smppApply.fallback();
        StringBuilder sb = new StringBuilder("<div class=\"plane smpp-panel\">");
        if (notice != null) sb.append("<p class=\"notice\">").append(esc(notice)).append("</p>");
        sb.append("<h2>SMPP (local RA)</h2>");
        sb.append(linkStatus.htmlPartial("smpp"));
        sb.append("<p class=\"hint\">Primary UI: <a href=\"/telemetry/?tab=smpp\">Monitor Hub SMPP</a> ")
                .append("(JSON + Apply). Form below mirrors KV + smpp.json.</p>");
        sb.append(formOpen("/admin/smpp/config"));
        sb.append("<h3>ESME server (AS → GW)</h3>");
        sb.append(field("serverEnabled", "serverEnabled", String.valueOf(fb.serverEnabled()), "true|false"));
        sb.append(field("serverPort", "serverPort", String.valueOf(fb.serverPort()), null));
        sb.append(field("serverSystemId", "serverSystemId", fb.serverSystemId(), null));
        sb.append(field("serverPassword", "serverPassword", "", "write-only"));
        sb.append("<h3>SMSC client (optional)</h3>");
        sb.append(field("clientEnabled", "clientEnabled", String.valueOf(fb.clientEnabled()), "true|false"));
        sb.append(field("host", "host", fb.host(), null));
        sb.append(field("port", "port", String.valueOf(fb.port()), null));
        sb.append(field("systemId", "systemId", fb.systemId(), null));
        sb.append(field("password", "password", "", "write-only"));
        sb.append(field("sourceAddr", "sourceAddr", fb.sourceAddr(), null));
        sb.append("<h3>USSD over SMPP (skeleton)</h3>");
        sb.append("<p class=\"hint\">When enabled, MO SUBMIT_SM with USSD service_op TLV maps to VirtualSessionBridge ")
                .append("(origination=SMPP). NI push sends plain-text submit_sm via SMSC client when bound; ")
                .append("full service_op TLV dialog is not required for NI text.</p>");
        sb.append(field("ussdOverSmpp", "ussdOverSmpp", String.valueOf(config.smppUssdEnabled()), "true|false"));
        sb.append(planeButtons("smpp"));
        sb.append("</form></div>");
        return sb.toString();
    }

    private String httpHtml(String notice) {
        StringBuilder sb = new StringBuilder("<div class=\"plane http-panel\">");
        if (notice != null) sb.append("<p class=\"notice\">").append(esc(notice)).append("</p>");
        sb.append("<h2>HTTP AS plane</h2>");
        sb.append(linkStatus.htmlPartial("http"));
        sb.append("<p class=\"hint\">Primary UI: <a href=\"/telemetry/?tab=http\">Monitor Hub HTTP</a>. ")
                .append("Modes: <b>SYNC</b> / <b>ASYNC_ACK</b> / <b>BRIDGE</b>.</p>");
        sb.append(formOpen("/admin/http/config"));
        sb.append("<h3>Pull client (http-callback-ra)</h3>");
        sb.append(field("clientEnabled", "clientEnabled", String.valueOf(config.httpClientEnabled()), "true|false"));
        sb.append(field("connectTimeoutMs", "connectMs", String.valueOf(config.httpConnectTimeoutMs()), null));
        sb.append(field("requestTimeoutMs", "requestMs", String.valueOf(config.httpRequestTimeoutMs()), null));
        sb.append(field("clientBridgeEnabled", "clientBridge", String.valueOf(config.httpClientBridgeEnabled()), "true|false"));
        sb.append("<h3>Callback server (http-server-ra)</h3>");
        sb.append(field("serverEnabled", "serverEnabled", String.valueOf(config.httpServerEnabled()), "true|false"));
        sb.append(field("listenHost", "listenHost", httpApply.listenHost(), null));
        sb.append(field("listenPort", "listenPort", String.valueOf(httpApply.listenPort()), null));
        sb.append(field("callbackPath", "callbackPath", config.httpCallbackPath(), null));
        sb.append(planeButtons("http"));
        sb.append("</form></div>");
        return sb.toString();
    }

    private String grpcHtml(String notice) {
        StringBuilder sb = new StringBuilder("<div class=\"plane grpc-panel\">");
        if (notice != null) sb.append("<p class=\"notice\">").append(esc(notice)).append("</p>");
        sb.append("<h2>gRPC AS plane</h2>");
        sb.append(linkStatus.htmlPartial("grpc"));
        sb.append("<p class=\"hint\">JSON AsRequest/AsResponse over InvokeGrpc. Same SYNC / ASYNC_ACK / BRIDGE modes as HTTP.</p>");
        sb.append(formOpen("/admin/grpc"));
        sb.append("<h3>Pull client (grpc-client-ra)</h3>");
        sb.append(field("clientEnabled", "clientEnabled", String.valueOf(config.grpcClientEnabled()), "true|false"));
        sb.append(field("invokeTimeoutMs", "invokeMs", String.valueOf(config.grpcInvokeTimeoutMs()), null));
        sb.append(field("clientBridgeEnabled", "clientBridge", String.valueOf(config.grpcClientBridgeEnabled()), "true|false"));
        sb.append("<h3>Callback server (grpc-server-ra)</h3>");
        sb.append(field("serverEnabled", "serverEnabled", String.valueOf(config.grpcServerEnabled()), "true|false"));
        sb.append(field("listenPort", "listenPort", String.valueOf(grpcApply.listenPort()), null));
        sb.append(planeButtons("grpc"));
        sb.append("</form></div>");
        return sb.toString();
    }

    private String bridgeHtml(String notice) {
        StringBuilder sb = new StringBuilder("<div class=\"plane bridge-panel\">");
        if (notice != null) sb.append("<p class=\"notice\">").append(esc(notice)).append("</p>");
        sb.append("<h2>Virtual Session Bridge</h2>");
        sb.append("<p class=\"hint\">Invariant: 1000 ≤ adaptiveGate ≤ asyncGateTimeoutMs &lt; dialogTimeout. ")
                .append("Per-leg bridge flags arm NI push after gate.</p>");
        sb.append(formOpen("/admin/bridge"));
        sb.append(field("bridgeEnabled", "bridgeEnabled", String.valueOf(config.bridgeEnabled()), "true|false"));
        sb.append(field("asyncGateTimeoutMs", "asyncGateMs", String.valueOf(config.asyncGateTimeoutMs()), null));
        sb.append(field("dialogTimeoutMs", "dialogTimeoutMs", String.valueOf(config.dialogTimeoutMs()), null));
        sb.append(field("asyncWaitMessage", "waitMsg", config.asyncWaitMessage(), null));
        sb.append(field("asyncHardFailMessage", "hardFailMsg", config.asyncHardFailMessage(), null));
        sb.append(field("httpClientBridgeEnabled", "httpBridge", String.valueOf(config.httpClientBridgeEnabled()), "true|false"));
        sb.append(field("grpcClientBridgeEnabled", "grpcBridge", String.valueOf(config.grpcClientBridgeEnabled()), "true|false"));
        sb.append("<button type=\"submit\" name=\"action\" value=\"save\">Save</button>");
        sb.append("</form></div>");
        return sb.toString();
    }

    private static String formOpen(String path) {
        return "<form hx-post=\"" + path + "\" hx-target=\"#panel\" hx-swap=\"innerHTML\" "
                + "hx-headers='{\"X-USSD-Admin-Key\":\"ussd-admin\"}' class=\"grid-form\">";
    }

    private static String field(String name, String label, String value, String hint) {
        StringBuilder sb = new StringBuilder();
        sb.append("<label>").append(esc(label));
        if (hint != null) sb.append(" <span class=\"hint\">(").append(esc(hint)).append(")</span>");
        sb.append(" <input name=\"").append(esc(name)).append("\" value=\"")
                .append(esc(value == null ? "" : value)).append("\"/></label>");
        return sb.toString();
    }

    private static String planeButtons(String plane) {
        return "<div class=\"plane-actions\">"
                + "<button type=\"submit\" name=\"action\" value=\"save\">Save</button> "
                + "<button type=\"submit\" name=\"action\" value=\"saveApply\">Save &amp; Apply</button> "
                + "<button type=\"submit\" name=\"action\" value=\"apply\">Apply</button> "
                + "<button type=\"submit\" name=\"action\" value=\"start\">Start</button> "
                + "<button type=\"submit\" name=\"action\" value=\"stop\">Stop</button>"
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
