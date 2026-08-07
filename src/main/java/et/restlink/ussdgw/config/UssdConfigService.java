package et.restlink.ussdgw.config;

import et.restlink.ussdgw.api.UssdAlphabet;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Effective runtime config = MicroProfile boot props overlaid by {@link RuntimeConfigStore} (admin Save).
 */
@ApplicationScoped
public class UssdConfigService {
    @Inject RuntimeConfigStore store;

    @ConfigProperty(name = "ussd.admin.api-key", defaultValue = "ussd-admin")
    String adminApiKey;
    @ConfigProperty(name = "ussd.bridge.enabled", defaultValue = "true")
    boolean bridgeEnabledProp;
    @ConfigProperty(name = "ussd.bridge.async-gate-timeout-ms", defaultValue = "7000")
    long asyncGateTimeoutMsProp;
    @ConfigProperty(name = "ussd.dialog-timeout-ms", defaultValue = "60000")
    long dialogTimeoutMsProp;
    @ConfigProperty(name = "ussd.bridge.async-wait-message", defaultValue = "Please wait...")
    String asyncWaitMessageProp;
    @ConfigProperty(name = "ussd.bridge.async-hard-fail-message",
            defaultValue = "Service temporarily unavailable. Please try again.")
    String asyncHardFailMessageProp;
    @ConfigProperty(name = "ussd.bridge.http-client-enabled", defaultValue = "true")
    boolean httpClientBridgeEnabledProp;
    @ConfigProperty(name = "ussd.bridge.grpc-client-enabled", defaultValue = "true")
    boolean grpcClientBridgeEnabledProp;
    @ConfigProperty(name = "ussd.alphabet.default", defaultValue = "AUTO")
    String alphabetDefault;

    @ConfigProperty(name = "ussd.map.ussd-gt", defaultValue = "100")
    String ussdGtProp;
    @ConfigProperty(name = "ussd.map.ussd-ssn", defaultValue = "8")
    int ussdSsnProp;
    @ConfigProperty(name = "ussd.map.hlr-ssn", defaultValue = "6")
    int hlrSsnProp;
    @ConfigProperty(name = "ussd.map.msc-ssn", defaultValue = "8")
    int mscSsnProp;
    @ConfigProperty(name = "ussd.map.max-version", defaultValue = "3")
    int maxMapVersionProp;

    @ConfigProperty(name = "ussd.http.client.enabled", defaultValue = "true")
    boolean httpClientEnabledProp;
    @ConfigProperty(name = "ussd.http.server.enabled", defaultValue = "true")
    boolean httpServerEnabledProp;
    @ConfigProperty(name = "ussd.http.client.connect-timeout-ms", defaultValue = "5000")
    int httpConnectMsProp;
    @ConfigProperty(name = "ussd.http.client.request-timeout-ms", defaultValue = "15000")
    int httpRequestMsProp;
    @ConfigProperty(name = "ussd.http.client.max-pool-size", defaultValue = "2048")
    int httpClientMaxPoolProp;
    @ConfigProperty(name = "ussd.http.callback-path", defaultValue = "/as/callback")
    String httpCallbackPathProp;
    @ConfigProperty(name = "ussd.as.http.wire-format", defaultValue = "xml")
    String asHttpWireFormatProp;
    @ConfigProperty(name = "ussd.http.ni-path", defaultValue = "/ussd")
    String httpNiPathProp;

    @ConfigProperty(name = "ussd.grpc.client.enabled", defaultValue = "true")
    boolean grpcClientEnabledProp;
    @ConfigProperty(name = "ussd.grpc.server.enabled", defaultValue = "true")
    boolean grpcServerEnabledProp;
    @ConfigProperty(name = "ussd.grpc.client.invoke-timeout-ms", defaultValue = "15000")
    long grpcInvokeMsProp;

    @ConfigProperty(name = "ussd.diameter.enabled", defaultValue = "false")
    boolean diameterEnabledProp;

    @ConfigProperty(name = "ussd.sip.enabled", defaultValue = "false")
    boolean sipEnabledProp;

    @ConfigProperty(name = "ussd.smpp.ussd.enabled", defaultValue = "false")
    boolean smppUssdEnabledProp;

    @ConfigProperty(name = "ussd.hlr.mode", defaultValue = "PROXY_MAP")
    String hlrModeProp;
    /** Optional blanks via Optional — empty defaultValue="" breaks SmallRye String load. */
    @ConfigProperty(name = "ussd.hlr.fake.imsi")
    java.util.Optional<String> hlrFakeImsiProp;
    @ConfigProperty(name = "ussd.hlr.fake.msc-gt")
    java.util.Optional<String> hlrFakeMscGtProp;
    @ConfigProperty(name = "ussd.hlr.upper-gt")
    java.util.Optional<String> hlrUpperGtProp;
    @ConfigProperty(name = "ussd.hlr.diameter.destination-host")
    java.util.Optional<String> hlrDiamHostProp;
    @ConfigProperty(name = "ussd.hlr.diameter.destination-realm")
    java.util.Optional<String> hlrDiamRealmProp;

    /** Exposed for per-network HLR overrides. */
    public RuntimeConfigStore store() {
        return store;
    }

    public String adminApiKey() { return adminApiKey; }

    public boolean bridgeEnabled() {
        return bool(RuntimeConfigStore.Keys.BRIDGE_ENABLED, bridgeEnabledProp);
    }

    public long asyncGateTimeoutMs() {
        return lng(RuntimeConfigStore.Keys.ASYNC_GATE_MS, asyncGateTimeoutMsProp);
    }

    public long dialogTimeoutMs() {
        return lng(RuntimeConfigStore.Keys.DIALOG_TIMEOUT_MS, dialogTimeoutMsProp);
    }

    public String asyncWaitMessage() {
        return str(RuntimeConfigStore.Keys.ASYNC_WAIT_MSG, asyncWaitMessageProp);
    }

    public String asyncHardFailMessage() {
        return str(RuntimeConfigStore.Keys.ASYNC_HARD_FAIL_MSG, asyncHardFailMessageProp);
    }

    public boolean httpClientBridgeEnabled() {
        return bool(RuntimeConfigStore.Keys.HTTP_CLIENT_BRIDGE, httpClientBridgeEnabledProp);
    }

    public boolean grpcClientBridgeEnabled() {
        return bool(RuntimeConfigStore.Keys.GRPC_CLIENT_BRIDGE, grpcClientBridgeEnabledProp);
    }

    public String ussdGt() {
        return str(RuntimeConfigStore.Keys.USSD_GT, ussdGtProp);
    }

    public int ussdSsn() {
        return integer(RuntimeConfigStore.Keys.USSD_SSN, ussdSsnProp);
    }

    public int hlrSsn() {
        return integer(RuntimeConfigStore.Keys.HLR_SSN, hlrSsnProp);
    }

    public int mscSsn() {
        return integer(RuntimeConfigStore.Keys.MSC_SSN, mscSsnProp);
    }

    public int maxMapVersion() {
        return integer(RuntimeConfigStore.Keys.MAX_MAP_V, maxMapVersionProp);
    }

    public boolean httpClientEnabled() {
        return bool(RuntimeConfigStore.Keys.HTTP_CLIENT_ENABLED, httpClientEnabledProp);
    }

    public boolean httpServerEnabled() {
        return bool(RuntimeConfigStore.Keys.HTTP_SERVER_ENABLED, httpServerEnabledProp);
    }

    public int httpConnectTimeoutMs() {
        return integer(RuntimeConfigStore.Keys.HTTP_CONNECT_MS, httpConnectMsProp);
    }

    public int httpRequestTimeoutMs() {
        return integer(RuntimeConfigStore.Keys.HTTP_REQUEST_MS, httpRequestMsProp);
    }

    /** Vert.x WebClient max pool size for AS pull / callbacks. */
    public int httpClientMaxPoolSize() {
        return integer(RuntimeConfigStore.Keys.HTTP_CLIENT_MAX_POOL, httpClientMaxPoolProp);
    }

    public String httpCallbackPath() {
        return str(RuntimeConfigStore.Keys.HTTP_CALLBACK_PATH, httpCallbackPathProp);
    }

    /** Global AS HTTP wire format (xml|json); tenant may override via TenantEntity.httpAsWireFormat. */
    public String asHttpWireFormat() {
        return str(RuntimeConfigStore.Keys.AS_HTTP_WIRE_FORMAT, asHttpWireFormatProp);
    }

    /** HTTP NI push path on the AS (default {@code /ussd}). */
    public String httpNiPath() {
        return str(RuntimeConfigStore.Keys.HTTP_NI_PATH, httpNiPathProp);
    }

    /** MAP plane enabled (same key as SS7 apply {@code ussd.map.enabled}). */
    public boolean mapEnabled() {
        return bool(RuntimeConfigStore.Keys.MAP_ENABLED, false);
    }

    public boolean grpcClientEnabled() {
        return bool(RuntimeConfigStore.Keys.GRPC_CLIENT_ENABLED, grpcClientEnabledProp);
    }

    public boolean grpcServerEnabled() {
        return bool(RuntimeConfigStore.Keys.GRPC_SERVER_ENABLED, grpcServerEnabledProp);
    }

    public long grpcInvokeTimeoutMs() {
        return lng(RuntimeConfigStore.Keys.GRPC_INVOKE_MS, grpcInvokeMsProp);
    }

    public boolean diameterEnabled() {
        return bool(RuntimeConfigStore.Keys.DIAMETER_ENABLED, diameterEnabledProp);
    }

    public boolean sipEnabled() {
        return bool(RuntimeConfigStore.Keys.SIP_ENABLED, sipEnabledProp);
    }

    public boolean smppUssdEnabled() {
        return bool(RuntimeConfigStore.Keys.SMPP_USSD_ENABLED, smppUssdEnabledProp);
    }

    public et.restlink.ussdgw.hlr.HlrResolveMode hlrMode() {
        return et.restlink.ussdgw.hlr.HlrResolveMode.parse(
                str(RuntimeConfigStore.Keys.HLR_MODE, hlrModeProp));
    }

    public String hlrFakeImsi() {
        return str(RuntimeConfigStore.Keys.HLR_FAKE_IMSI, opt(hlrFakeImsiProp));
    }

    public String hlrFakeMscGt() {
        return str(RuntimeConfigStore.Keys.HLR_FAKE_MSC_GT, opt(hlrFakeMscGtProp));
    }

    public String hlrUpperGt() {
        return str(RuntimeConfigStore.Keys.HLR_UPPER_GT, opt(hlrUpperGtProp));
    }

    public String hlrDiamDestinationHost() {
        return str(RuntimeConfigStore.Keys.HLR_DIAM_DEST_HOST, opt(hlrDiamHostProp));
    }

    public String hlrDiamDestinationRealm() {
        return str(RuntimeConfigStore.Keys.HLR_DIAM_DEST_REALM, opt(hlrDiamRealmProp));
    }

    private static String opt(java.util.Optional<String> o) {
        return o == null ? "" : o.filter(s -> !s.isBlank()).orElse("");
    }

    public UssdAlphabet defaultAlphabet() {
        return UssdAlphabet.parse(alphabetDefault);
    }

    public boolean adminKeyOk(String key) {
        return key != null && !key.isBlank() && key.equals(adminApiKey);
    }

    private boolean bool(String key, boolean def) {
        RuntimeConfigStore s = store();
        return s == null ? def : s.getBool(key, def);
    }

    private int integer(String key, int def) {
        RuntimeConfigStore s = store();
        return s == null ? def : s.getInt(key, def);
    }

    private long lng(String key, long def) {
        RuntimeConfigStore s = store();
        return s == null ? def : s.getLong(key, def);
    }

    private String str(String key, String def) {
        RuntimeConfigStore s = store();
        if (s == null) return def;
        Optional<String> v = s.get(key);
        return v.filter(x -> !x.isBlank()).orElse(def);
    }
}
