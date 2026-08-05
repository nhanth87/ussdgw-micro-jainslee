package et.restlink.ussdgw.admin.smpp;

import et.restlink.ussdgw.ra.smpp.SmppEndpointRegistry;
import et.restlink.ussdgw.ra.smpp.SmppRaEndpoint;
import et.restlink.ussdgw.ra.smpp.SmppServerRaEndpoint;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Bindings for the local SMPP RA admin pack — apply/start/stop hooks into
 * {@code SmppApplyService} plus live registry for status (OTA pattern).
 */
public final class SmppAdminBindings {

    private static volatile SmppEndpointRegistry registry;
    private static volatile Supplier<String> applyHook;
    private static volatile Supplier<String> startHook;
    private static volatile Supplier<String> stopHook;
    private static volatile Function<String, String> validateHook;
    private static volatile Supplier<String> configJsonHook;
    private static volatile Function<String, String> saveConfigHook;

    private SmppAdminBindings() {
    }

    public static void bindRegistry(SmppEndpointRegistry reg) {
        registry = reg;
    }

    public static void bindHooks(Supplier<String> apply,
                                 Supplier<String> start,
                                 Supplier<String> stop,
                                 Function<String, String> validate,
                                 Supplier<String> configJson,
                                 Function<String, String> saveConfig) {
        applyHook = apply;
        startHook = start;
        stopHook = stop;
        validateHook = validate;
        configJsonHook = configJson;
        saveConfigHook = saveConfig;
    }

    public static void clear() {
        registry = null;
        applyHook = null;
        startHook = null;
        stopHook = null;
        validateHook = null;
        configJsonHook = null;
        saveConfigHook = null;
    }

    public static SmppEndpointRegistry registry() {
        return registry;
    }

    public static SmppRaEndpoint primaryClient() {
        SmppEndpointRegistry r = registry;
        if (r == null || r.clients().isEmpty()) {
            return null;
        }
        SmppRaEndpoint def = r.clients().get("default");
        if (def != null) {
            return def;
        }
        return r.clients().values().iterator().next();
    }

    public static SmppServerRaEndpoint server() {
        SmppEndpointRegistry r = registry;
        return r == null ? null : r.server();
    }

    public static Supplier<String> applyHook() {
        return applyHook;
    }

    public static Supplier<String> startHook() {
        return startHook;
    }

    public static Supplier<String> stopHook() {
        return stopHook;
    }

    public static Function<String, String> validateHook() {
        return validateHook;
    }

    public static Supplier<String> configJsonHook() {
        return configJsonHook;
    }

    public static Function<String, String> saveConfigHook() {
        return saveConfigHook;
    }
}
