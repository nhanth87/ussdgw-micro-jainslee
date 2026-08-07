package et.restlink.ussdgw.config;

import et.restlink.ussdgw.persist.ConfigEntity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KV overlay on {@code ussd_config}. Admin Save writes here; Apply/readers prefer store over boot props.
 */
@ApplicationScoped
public class RuntimeConfigStore {
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    @jakarta.annotation.PostConstruct
    void load() {
        refresh();
    }

    public synchronized void refresh() {
        cache.clear();
        for (ConfigEntity e : ConfigEntity.<ConfigEntity>listAll()) {
            if (e.configKey != null && e.configValue != null) {
                cache.put(e.configKey, e.configValue);
            }
        }
    }

    public Optional<String> get(String key) {
        if (key == null) return Optional.empty();
        String v = cache.get(key);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v);
    }

    public String getOr(String key, String def) {
        return get(key).orElse(def);
    }

    public boolean getBool(String key, boolean def) {
        return get(key).map(s -> "true".equalsIgnoreCase(s.trim()) || "1".equals(s.trim())
                || "yes".equalsIgnoreCase(s.trim())).orElse(def);
    }

    public int getInt(String key, int def) {
        return get(key).map(s -> {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
        }).orElse(def);
    }

    public long getLong(String key, long def) {
        return get(key).map(s -> {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return def; }
        }).orElse(def);
    }

    @Transactional
    public void put(String key, String value) {
        if (key == null || key.isBlank()) return;
        String k = key.trim();
        String v = value == null ? "" : value;
        ConfigEntity e = ConfigEntity.findById(k);
        if (e == null) {
            e = new ConfigEntity();
            e.configKey = k;
        }
        e.configValue = v;
        e.persist();
        cache.put(k, v);
    }

    @Transactional
    public void putAll(Map<String, String> entries) {
        if (entries == null) return;
        for (Map.Entry<String, String> en : entries.entrySet()) {
            put(en.getKey(), en.getValue());
        }
    }

    public Map<String, String> snapshotPrefixed(String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : cache.entrySet()) {
            if (prefix == null || e.getKey().startsWith(prefix)) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** Well-known admin keys. */
    public static final class Keys {
        private Keys() {}
        public static final String BRIDGE_ENABLED = "ussd.bridge.enabled";
        public static final String ASYNC_GATE_MS = "ussd.bridge.async-gate-timeout-ms";
        public static final String DIALOG_TIMEOUT_MS = "ussd.dialog-timeout-ms";
        public static final String ASYNC_WAIT_MSG = "ussd.bridge.async-wait-message";
        public static final String ASYNC_HARD_FAIL_MSG = "ussd.bridge.async-hard-fail-message";
        public static final String HTTP_CLIENT_BRIDGE = "ussd.bridge.http-client-enabled";
        public static final String GRPC_CLIENT_BRIDGE = "ussd.bridge.grpc-client-enabled";

        public static final String MAP_ENABLED = "ussd.map.enabled";
        public static final String MAP_HOST_IP = "ussd.map.host-ip";
        public static final String MAP_HOST_PORT = "ussd.map.host-port";
        public static final String MAP_PEER_IP = "ussd.map.peer-ip";
        public static final String MAP_PEER_PORT = "ussd.map.peer-port";
        public static final String MAP_OPC = "ussd.map.opc";
        public static final String MAP_DPC = "ussd.map.dpc";
        public static final String MAP_CHANNEL = "ussd.map.ip-channel-type";
        public static final String MAP_CONFIG_FILE = "ussd.map.config-file";
        public static final String SS7_PERSIST = "ussd.ss7.persist-dir";
        public static final String USSD_GT = "ussd.map.ussd-gt";
        public static final String USSD_SSN = "ussd.map.ussd-ssn";
        public static final String HLR_SSN = "ussd.map.hlr-ssn";
        public static final String MSC_SSN = "ussd.map.msc-ssn";
        public static final String MAX_MAP_V = "ussd.map.max-version";

        public static final String HTTP_CLIENT_ENABLED = "ussd.http.client.enabled";
        public static final String HTTP_SERVER_ENABLED = "ussd.http.server.enabled";
        public static final String HTTP_RA_HOST = "http.ra.host";
        public static final String HTTP_RA_PORT = "http.ra.port";
        public static final String HTTP_RA_EVENT_LOOP = "http.ra.event-loop-threads";
        public static final String HTTP_RA_WORKER_POOL = "http.ra.worker-pool-size";
        public static final String HTTP_RA_ACCEPT_BACKLOG = "http.ra.accept-backlog";
        public static final String HTTP_CONNECT_MS = "ussd.http.client.connect-timeout-ms";
        public static final String HTTP_REQUEST_MS = "ussd.http.client.request-timeout-ms";
        public static final String HTTP_CLIENT_MAX_POOL = "ussd.http.client.max-pool-size";
        public static final String HTTP_CALLBACK_PATH = "ussd.http.callback-path";
        /** AS HTTP wire: XML (classic dialog) or JSON (greenfield). */
        public static final String AS_HTTP_WIRE_FORMAT = "ussd.as.http.wire-format";
        /** HTTP NI push path appended to AS base URL. */
        public static final String HTTP_NI_PATH = "ussd.http.ni-path";

        public static final String GRPC_CLIENT_ENABLED = "ussd.grpc.client.enabled";
        public static final String GRPC_SERVER_ENABLED = "ussd.grpc.server.enabled";
        public static final String GRPC_SERVER_PORT = "ussd.grpc.server.port";
        public static final String GRPC_INVOKE_MS = "ussd.grpc.client.invoke-timeout-ms";

        public static final String SMPP_CLIENT_ENABLED = "smpp.client.enabled";
        public static final String SMPP_HOST = "smpp.host";
        public static final String SMPP_PORT = "smpp.port";
        public static final String SMPP_SYSTEM_ID = "smpp.system-id";
        public static final String SMPP_PASSWORD = "smpp.password";
        public static final String SMPP_SOURCE = "smpp.source-addr";
        public static final String SMPP_SERVER_ENABLED = "smpp.server.enabled";
        public static final String SMPP_SERVER_PORT = "smpp.server.port";
        public static final String SMPP_SERVER_SYSTEM_ID = "smpp.server.system-id";
        public static final String SMPP_SERVER_PASSWORD = "smpp.server.password";
        public static final String SMPP_USSD_ENABLED = "ussd.smpp.ussd.enabled";
        /** Full SMPP JSON document for Monitor Hub (OTA-style). */
        public static final String SMPP_JSON = "smpp.json";
        /** Optional SS7 hub JSON (props form mirrored); apply still uses KV / config-file. */
        public static final String SS7_JSON = "ss7.json";

        /** Feature flags only — peer/listen config belongs to micro-jainslee RAs. */
        public static final String DIAMETER_ENABLED = "ussd.diameter.enabled";
        public static final String SIP_ENABLED = "ussd.sip.enabled";

        /** HLR face (inbound SRI-SM): FAKE | PROXY_MAP | PROXY_DIAMETER | FAKE_THEN_RESOLVE */
        public static final String HLR_MODE = "ussd.hlr.mode";
        public static final String HLR_MODE_NETWORK_PREFIX = "ussd.hlr.network.";
        public static final String HLR_FAKE_IMSI = "ussd.hlr.fake.imsi";
        public static final String HLR_FAKE_MSC_GT = "ussd.hlr.fake.msc-gt";
        public static final String HLR_UPPER_GT = "ussd.hlr.upper-gt";
        public static final String HLR_DIAM_DEST_HOST = "ussd.hlr.diameter.destination-host";
        public static final String HLR_DIAM_DEST_REALM = "ussd.hlr.diameter.destination-realm";
    }
}
