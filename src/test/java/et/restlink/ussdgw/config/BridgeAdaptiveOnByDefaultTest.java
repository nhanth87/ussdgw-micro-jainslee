package et.restlink.ussdgw.config;

import et.restlink.ussdgw.service.BridgeGateScheduler;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AdaptiveTimeout + the virtual session bridge are ON by default in code: with no line about them
 * in any configuration file, every MO / MAP2MAP / NI session is armed and the gate ticks every
 * 100 ms. These are the defaults the gateway falls back to; flipping one to "off" must be a
 * deliberate, reviewed change, so this test fails on it.
 */
class BridgeAdaptiveOnByDefaultTest {

    @Test
    void bridgeAndAdaptiveDefaultsAreOn() {
        Map<String, String> d = new HashMap<>();
        d.putAll(defaults(UssdConfigService.class));
        d.putAll(defaults(BridgeGateScheduler.class));

        assertThat(d).containsEntry("ussd.bridge.enabled", "true")
                .containsEntry("ussd.bridge.http-client-enabled", "true")
                .containsEntry("ussd.bridge.grpc-client-enabled", "true")
                .containsEntry("ussd.bridge.async-gate-timeout-ms", "25000")
                .containsEntry("ussd.dialog-timeout-ms", "60000")
                .containsEntry("ussd.bridge.gate-tick-ms", "100")
                // the AS keeps its 30 s budget past the gate; the late answer goes through the bridge
                .containsEntry("ussd.http.client.request-timeout-ms", "30000")
                .containsEntry("ussd.grpc.client.invoke-timeout-ms", "30000");
        assertThat(Long.parseLong(d.get("ussd.bridge.async-gate-timeout-ms")))
                .as("gate fires before the dialog would time out")
                .isLessThan(Long.parseLong(d.get("ussd.dialog-timeout-ms")));
    }

    private static Map<String, String> defaults(Class<?> type) {
        Map<String, String> m = new HashMap<>();
        for (Field f : type.getDeclaredFields()) {
            ConfigProperty cp = f.getAnnotation(ConfigProperty.class);
            if (cp != null && !ConfigProperty.UNCONFIGURED_VALUE.equals(cp.defaultValue())) {
                m.put(cp.name(), cp.defaultValue());
            }
        }
        return m;
    }
}
