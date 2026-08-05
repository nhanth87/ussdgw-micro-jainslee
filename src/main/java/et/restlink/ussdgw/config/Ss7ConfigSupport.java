package et.restlink.ussdgw.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SS7 hub JSON ↔ {@link RuntimeConfigStore} KV (props / MAP addressing).
 * Full SS7 stack XML still via {@code ussd.map.config-file} when set.
 */
@ApplicationScoped
public class Ss7ConfigSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject RuntimeConfigStore store;
    @Inject UssdConfigService config;

    public record Result(boolean ok, List<String> errors) {
        public static Result success() { return new Result(true, List.of()); }
        public static Result fail(String... m) { return new Result(false, List.of(m)); }
    }

    public Result validate(String json) {
        if (json == null || json.isBlank()) return Result.fail("empty body");
        try {
            JsonNode n = JSON.readTree(json);
            if (!n.isObject()) return Result.fail("expected object");
            return Result.success();
        } catch (Exception ex) {
            return Result.fail("invalid JSON: " + ex.getMessage());
        }
    }

    public String activeJsonOrProps() {
        return store.get(RuntimeConfigStore.Keys.SS7_JSON).orElseGet(this::propsAsJson);
    }

    public String propsAsJson() {
        ObjectNode n = JSON.createObjectNode();
        n.put("mapEnabled", store.getBool(RuntimeConfigStore.Keys.MAP_ENABLED, false));
        n.put("hostIp", store.getOr(RuntimeConfigStore.Keys.MAP_HOST_IP, "127.0.0.1"));
        n.put("hostPort", store.getInt(RuntimeConfigStore.Keys.MAP_HOST_PORT, 8013));
        n.put("peerIp", store.getOr(RuntimeConfigStore.Keys.MAP_PEER_IP, "127.0.0.1"));
        n.put("peerPort", store.getInt(RuntimeConfigStore.Keys.MAP_PEER_PORT, 8014));
        n.put("opc", store.getInt(RuntimeConfigStore.Keys.MAP_OPC, 1));
        n.put("dpc", store.getInt(RuntimeConfigStore.Keys.MAP_DPC, 2));
        n.put("ipChannelType", store.getOr(RuntimeConfigStore.Keys.MAP_CHANNEL, "TCP"));
        n.put("configFile", store.get(RuntimeConfigStore.Keys.MAP_CONFIG_FILE).orElse(""));
        n.put("persistDir", store.getOr(RuntimeConfigStore.Keys.SS7_PERSIST, "configs/ss7-persist"));
        n.put("ussdGt", config.ussdGt());
        n.put("ussdSsn", config.ussdSsn());
        n.put("hlrSsn", config.hlrSsn());
        n.put("mscSsn", config.mscSsn());
        n.put("maxMapVersion", config.maxMapVersion());
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(n);
        } catch (Exception e) {
            return "{}";
        }
    }

    public String validateHookJson(String json) {
        Result r = validate(json == null ? "" : json);
        try {
            if (!r.ok()) {
                return JSON.writeValueAsString(Map.of("ok", false, "errors", r.errors()));
            }
            return "{\"ok\":true,\"errors\":[]}";
        } catch (Exception ex) {
            return "{\"ok\":false,\"error\":\"validate failed\"}";
        }
    }

    public String saveHookJson(String json) {
        Result r = validate(json == null ? "" : json);
        if (!r.ok()) {
            try {
                return JSON.writeValueAsString(Map.of("ok", false, "errors", r.errors()));
            } catch (Exception ex) {
                return "{\"ok\":false,\"error\":\"validation failed\"}";
            }
        }
        applyJsonToStore(json);
        store.put(RuntimeConfigStore.Keys.SS7_JSON, json.trim());
        return "{\"ok\":true}";
    }

    public void saveFromForm(Map<String, String> kv) {
        store.putAll(kv);
        store.put(RuntimeConfigStore.Keys.SS7_JSON, propsAsJson());
    }

    private void applyJsonToStore(String json) {
        try {
            JsonNode n = JSON.readTree(json);
            Map<String, String> kv = new LinkedHashMap<>();
            put(kv, RuntimeConfigStore.Keys.MAP_ENABLED, text(n, "mapEnabled"));
            put(kv, RuntimeConfigStore.Keys.MAP_HOST_IP, text(n, "hostIp"));
            put(kv, RuntimeConfigStore.Keys.MAP_HOST_PORT, text(n, "hostPort"));
            put(kv, RuntimeConfigStore.Keys.MAP_PEER_IP, text(n, "peerIp"));
            put(kv, RuntimeConfigStore.Keys.MAP_PEER_PORT, text(n, "peerPort"));
            put(kv, RuntimeConfigStore.Keys.MAP_OPC, text(n, "opc"));
            put(kv, RuntimeConfigStore.Keys.MAP_DPC, text(n, "dpc"));
            put(kv, RuntimeConfigStore.Keys.MAP_CHANNEL, text(n, "ipChannelType"));
            put(kv, RuntimeConfigStore.Keys.MAP_CONFIG_FILE, text(n, "configFile"));
            put(kv, RuntimeConfigStore.Keys.SS7_PERSIST, text(n, "persistDir"));
            put(kv, RuntimeConfigStore.Keys.USSD_GT, text(n, "ussdGt"));
            put(kv, RuntimeConfigStore.Keys.USSD_SSN, text(n, "ussdSsn"));
            put(kv, RuntimeConfigStore.Keys.HLR_SSN, text(n, "hlrSsn"));
            put(kv, RuntimeConfigStore.Keys.MSC_SSN, text(n, "mscSsn"));
            put(kv, RuntimeConfigStore.Keys.MAX_MAP_V, text(n, "maxMapVersion"));
            store.putAll(kv);
        } catch (Exception ex) {
            throw new IllegalArgumentException("ss7 json apply: " + ex.getMessage(), ex);
        }
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) return null;
        JsonNode v = n.get(field);
        if (v.isBoolean()) return String.valueOf(v.asBoolean());
        return v.asText();
    }

    private static void put(Map<String, String> kv, String key, String value) {
        if (value != null) kv.put(key, value.trim());
    }
}
