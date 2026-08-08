package et.restlink.ussdgw.events;

import com.microjainslee.api.SleeEvent;

import et.restlink.ussdgw.bridge.GatedSessionMeta;

/**
 * Fire-and-forget GW→AS HTTP POST when AdaptiveTimeout / bridge gate fires.
 * Body is classic XmlMAPDialog XML (see {@code ClassicDialogXmlCodec.encodeGatedPush}).
 * Completion uses session id {@code gated-{correlationId}} so it never collides with
 * an in-flight AS pull on the same correlation.
 */
public record GatedAsNotifyEvent(
        String asUrl,
        String xmlBody,
        GatedSessionMeta meta
) implements SleeEvent {
    public static String httpSessionId(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return "gated-unknown";
        }
        return "gated-" + correlationId.trim();
    }

    public boolean isValid() {
        return asUrl != null && !asUrl.isBlank()
                && xmlBody != null && !xmlBody.isBlank()
                && meta != null && meta.correlationId() != null && !meta.correlationId().isBlank();
    }
}
