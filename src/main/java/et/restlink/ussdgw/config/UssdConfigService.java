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
    /** Operator public base (e.g. http://127.0.0.1:8088) — never publish bind 0.0.0.0. */
    @ConfigProperty(name = "ussd.admin.public-base-url", defaultValue = "")
    Optional<String> publicBaseUrlProp;
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
    /**
     * Classic NI ingress auth. Field initialiser mirrors {@code defaultValue} so a non-CDI
     * instantiation is fail-closed too.
     */
    @ConfigProperty(name = "ussd.http.ni.auth-required", defaultValue = "true")
    boolean httpNiAuthRequiredProp = true;
    @ConfigProperty(name = "ussd.http.ni.default-network-id", defaultValue = "0")
    int httpNiDefaultNetworkIdProp;

    @ConfigProperty(name = "ussd.sri.pending-ttl-ms", defaultValue = "30000")
    long sriPendingTtlMsProp = 30_000L;
    @ConfigProperty(name = "ussd.hlr.proxy.pending-ttl-ms", defaultValue = "15000")
    long hlrProxyPendingTtlMsProp = 15_000L;

    @ConfigProperty(name = "ussd.map.enabled", defaultValue = "false")
    boolean mapEnabledProp;

    @ConfigProperty(name = "ussd.grpc.client.enabled", defaultValue = "true")
    boolean grpcClientEnabledProp;
    @ConfigProperty(name = "ussd.grpc.server.enabled", defaultValue = "true")
    boolean grpcServerEnabledProp;
    @ConfigProperty(name = "ussd.grpc.client.invoke-timeout-ms", defaultValue = "15000")
    long grpcInvokeMsProp;

    @ConfigProperty(name = "ussd.diameter.enabled", defaultValue = "false")
    boolean diameterEnabledProp;
    @ConfigProperty(name = "ussd.diameter.host", defaultValue = "0.0.0.0")
    String diameterHostProp;
    @ConfigProperty(name = "ussd.diameter.port", defaultValue = "3868")
    int diameterPortProp;
    @ConfigProperty(name = "ussd.diameter.realm", defaultValue = "restlink.local")
    String diameterRealmProp;
    @ConfigProperty(name = "ussd.diameter.origin-host", defaultValue = "ussdgw.restlink.local")
    String diameterOriginHostProp;
    @ConfigProperty(name = "ussd.diameter.destination-realm", defaultValue = "restlink.local")
    String diameterDestRealmProp;
    @ConfigProperty(name = "ussd.diameter.destination-host")
    java.util.Optional<String> diameterDestHostProp;

    @ConfigProperty(name = "ussd.sip.enabled", defaultValue = "false")
    boolean sipEnabledProp;
    @ConfigProperty(name = "ussd.sip.host", defaultValue = "0.0.0.0")
    String sipHostProp;
    @ConfigProperty(name = "ussd.sip.tcp-port", defaultValue = "5060")
    int sipTcpPortProp;
    @ConfigProperty(name = "ussd.sip.udp-port", defaultValue = "5060")
    int sipUdpPortProp;
    @ConfigProperty(name = "ussd.sip.from-uri", defaultValue = "sip:ussdgw@restlink.local")
    String sipFromUriProp;
    @ConfigProperty(name = "ussd.sip.request-uri-template",
            defaultValue = "sip:{msisdn}@ussd.restlink.local")
    String sipRequestUriProp;

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

    /** Operator-facing base URL for NI/gRPC push advertise (may be empty). */
    public String publicBaseUrl() {
        return str(RuntimeConfigStore.Keys.ADMIN_PUBLIC_BASE_URL, opt(publicBaseUrlProp));
    }

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

    /**
     * Classic NI ingress ({@link #httpNiPath()}) requires a tenant {@code httpApiKey} or the
     * global admin key. Default {@code true} — an open {@code /ussd} lets anyone push USSD at
     * any subscriber.
     */
    public boolean httpNiAuthRequired() {
        return bool(RuntimeConfigStore.Keys.HTTP_NI_AUTH_REQUIRED, httpNiAuthRequiredProp);
    }

    /** networkId for NI ingress when the authenticated principal carries none (admin key). */
    public int httpNiDefaultNetworkId() {
        return integer(RuntimeConfigStore.Keys.HTTP_NI_DEFAULT_NETWORK_ID, httpNiDefaultNetworkIdProp);
    }

    /** TTL for a NI push awaiting its own SRI-SM Response before the saga fails. */
    public long sriPendingTtlMs() {
        return lng(RuntimeConfigStore.Keys.SRI_PENDING_TTL_MS, sriPendingTtlMsProp);
    }

    /** TTL for an inbound HLR dialog awaiting an upper SRI-SM resolve before abort. */
    public long hlrProxyPendingTtlMs() {
        return lng(RuntimeConfigStore.Keys.HLR_PROXY_PENDING_TTL_MS, hlrProxyPendingTtlMsProp);
    }

    /** MAP plane enabled (same key as SS7 apply {@code ussd.map.enabled}). */
    public boolean mapEnabled() {
        return bool(RuntimeConfigStore.Keys.MAP_ENABLED, mapEnabledProp);
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

    public String diameterHost() {
        return str(RuntimeConfigStore.Keys.DIAMETER_HOST, diameterHostProp);
    }

    public int diameterPort() {
        return integer(RuntimeConfigStore.Keys.DIAMETER_PORT, diameterPortProp);
    }

    public String diameterRealm() {
        return str(RuntimeConfigStore.Keys.DIAMETER_REALM, diameterRealmProp);
    }

    public String diameterOriginHost() {
        return str(RuntimeConfigStore.Keys.DIAMETER_ORIGIN_HOST, diameterOriginHostProp);
    }

    public String diameterDestinationRealm() {
        return str(RuntimeConfigStore.Keys.DIAMETER_DEST_REALM, diameterDestRealmProp);
    }

    public String diameterDestinationHost() {
        return str(RuntimeConfigStore.Keys.DIAMETER_DEST_HOST, opt(diameterDestHostProp));
    }

    public boolean sipEnabled() {
        return bool(RuntimeConfigStore.Keys.SIP_ENABLED, sipEnabledProp);
    }

    public String sipHost() {
        return str(RuntimeConfigStore.Keys.SIP_HOST, sipHostProp);
    }

    public int sipTcpPort() {
        return integer(RuntimeConfigStore.Keys.SIP_TCP_PORT, sipTcpPortProp);
    }

    public int sipUdpPort() {
        return integer(RuntimeConfigStore.Keys.SIP_UDP_PORT, sipUdpPortProp);
    }

    public String sipFromUri() {
        return str(RuntimeConfigStore.Keys.SIP_FROM_URI, sipFromUriProp);
    }

    public String sipRequestUriTemplate() {
        return str(RuntimeConfigStore.Keys.SIP_REQUEST_URI, sipRequestUriProp);
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

    /** Constant-time compare — {@code String.equals} short-circuits on the first differing byte. */
    public boolean adminKeyOk(String key) {
        if (key == null || key.isBlank() || adminApiKey == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                adminApiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
