package et.restlink.ussdgw.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persist / validate SMPP JSON ({@link SmppConfigDocument}) in {@code ussd_config}
 * under {@link RuntimeConfigStore.Keys#SMPP_JSON} — OTA-style hub config.
 */
@ApplicationScoped
public class SmppConfigSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject RuntimeConfigStore store;

    public record Result(boolean ok, List<String> errors) {
        public static Result success() { return new Result(true, List.of()); }
        public static Result fail(String... msgs) { return new Result(false, List.of(msgs)); }
    }

    public Result validate(String json) {
        if (json == null || json.isBlank()) {
            return Result.fail("empty body");
        }
        try {
            SmppConfigDocument doc = JSON.readValue(json, SmppConfigDocument.class);
            if (doc.server() == null && (doc.clients() == null || doc.clients().isEmpty())) {
                return Result.fail("need server and/or clients");
            }
            if (doc.server() != null && doc.server().port() < 0) {
                return Result.fail("server.port invalid");
            }
            return Result.success();
        } catch (Exception ex) {
            return Result.fail("invalid JSON: " + ex.getMessage());
        }
    }

    public void save(String json) {
        Result r = validate(json);
        if (!r.ok()) {
            throw new IllegalArgumentException(String.join("; ", r.errors()));
        }
        store.put(RuntimeConfigStore.Keys.SMPP_JSON, json.trim());
        syncKvFromJson(json);
    }

    public String activeJsonOrLab() {
        return store.get(RuntimeConfigStore.Keys.SMPP_JSON).orElse(labTemplate());
    }

    public SmppConfigDocument loadActiveOrNull() {
        return store.get(RuntimeConfigStore.Keys.SMPP_JSON).map(this::parseQuiet).orElse(null);
    }

    /** Mirror form/KV fields into a JSON doc (Save from HTMX form). */
    public void saveFromFallback(et.restlink.ussdgw.ra.smpp.SmppEndpointRegistry.FallbackProps fb,
                                 boolean ussdOverSmpp) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Map<String, Object>> clients = new ArrayList<>();
        if (fb.clientEnabled()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", "default");
            c.put("host", fb.host());
            c.put("port", fb.port());
            c.put("systemId", fb.systemId());
            c.put("password", fb.password());
            c.put("systemType", fb.systemType() == null ? "" : fb.systemType());
            c.put("sourceAddr", fb.sourceAddr());
            c.put("networkId", fb.clientNetworkId());
            c.put("enabled", true);
            clients.add(c);
        }
        root.put("clients", clients);
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("enabled", fb.serverEnabled());
        server.put("port", fb.serverPort());
        server.put("systemId", fb.serverSystemId());
        server.put("password", fb.serverPassword());
        server.put("networkId", fb.serverNetworkId());
        server.put("esmeAllowlist", List.of());
        root.put("server", server);
        root.put("ussdOverSmpp", ussdOverSmpp);
        try {
            store.put(RuntimeConfigStore.Keys.SMPP_JSON, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (Exception ex) {
            throw new IllegalStateException("smpp json serialize: " + ex.getMessage(), ex);
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
            return "{\"ok\":false,\"error\":\"" + esc(ex.getMessage()) + "\"}";
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
        store.put(RuntimeConfigStore.Keys.SMPP_JSON, json.trim());
        syncKvFromJson(json);
        return "{\"ok\":true}";
    }

    private void syncKvFromJson(String json) {
        try {
            SmppConfigDocument doc = JSON.readValue(json, SmppConfigDocument.class);
            Map<String, String> kv = new LinkedHashMap<>();
            if (doc.server() != null) {
                kv.put(RuntimeConfigStore.Keys.SMPP_SERVER_ENABLED,
                        String.valueOf(!Boolean.FALSE.equals(doc.server().enabled())));
                kv.put(RuntimeConfigStore.Keys.SMPP_SERVER_PORT, String.valueOf(doc.server().port()));
                if (doc.server().systemId() != null) {
                    kv.put(RuntimeConfigStore.Keys.SMPP_SERVER_SYSTEM_ID, doc.server().systemId());
                }
                if (doc.server().password() != null && !doc.server().password().isBlank()) {
                    kv.put(RuntimeConfigStore.Keys.SMPP_SERVER_PASSWORD, doc.server().password());
                }
            }
            SmppConfigDocument.Client first = firstEnabledClient(doc);
            if (first != null) {
                kv.put(RuntimeConfigStore.Keys.SMPP_CLIENT_ENABLED, "true");
                kv.put(RuntimeConfigStore.Keys.SMPP_HOST, first.host() == null ? "" : first.host());
                kv.put(RuntimeConfigStore.Keys.SMPP_PORT, String.valueOf(first.port()));
                kv.put(RuntimeConfigStore.Keys.SMPP_SYSTEM_ID, first.systemId() == null ? "" : first.systemId());
                if (first.password() != null && !first.password().isBlank()) {
                    kv.put(RuntimeConfigStore.Keys.SMPP_PASSWORD, first.password());
                }
                if (first.sourceAddr() != null) {
                    kv.put(RuntimeConfigStore.Keys.SMPP_SOURCE, first.sourceAddr());
                }
            } else {
                kv.put(RuntimeConfigStore.Keys.SMPP_CLIENT_ENABLED, "false");
            }
            JsonNode tree = JSON.readTree(json);
            if (tree.has("ussdOverSmpp")) {
                kv.put(RuntimeConfigStore.Keys.SMPP_USSD_ENABLED, tree.get("ussdOverSmpp").asText("false"));
            }
            store.putAll(kv);
        } catch (Exception ignored) {
            // best-effort mirror
        }
    }

    private static SmppConfigDocument.Client firstEnabledClient(SmppConfigDocument doc) {
        if (doc.clients() == null) return null;
        for (SmppConfigDocument.Client c : doc.clients()) {
            if (c != null && !Boolean.FALSE.equals(c.enabled())) return c;
        }
        return null;
    }

    private SmppConfigDocument parseQuiet(String json) {
        try {
            return JSON.readValue(json, SmppConfigDocument.class);
        } catch (Exception e) {
            return null;
        }
    }

    public static String labTemplate() {
        return """
                {
                  "clients": [
                    { "name": "lab-smsc", "host": "127.0.0.1", "port": 2775,
                      "systemId": "restlink", "password": "password", "role": "lab",
                      "networkId": 0, "enabled": false }
                  ],
                  "server": {
                    "enabled": true, "port": 2776, "systemId": "ussdgw", "password": "password",
                    "networkId": 0,
                    "esmeAllowlist": []
                  },
                  "ussdOverSmpp": false
                }
                """;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
