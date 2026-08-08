package et.restlink.ussdgw.api;

import et.restlink.ussdgw.api.classic.ClassicDialogXmlCodec;
import et.restlink.ussdgw.api.classic.ClassicNiIngress;
import et.restlink.ussdgw.bridge.GatedSessionMeta;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Dual-mode AS HTTP encode/decode facade (classic XML vs greenfield JSON).
 * gRPC remains JSON via {@link AsWireCodec}; HTTP SBBs will call this later.
 */
@ApplicationScoped
public class AsWireFacade {

    public String encodePullRequest(AsRequest request, AsHttpWireFormat format) {
        AsHttpWireFormat fmt = format == null ? AsHttpWireFormat.XML : format;
        if (fmt == AsHttpWireFormat.JSON) {
            return AsWireCodec.encodeRequestString(request);
        }
        return ClassicDialogXmlCodec.encodePull(request);
    }

    public AsResponse decodePullResponse(String body, AsHttpWireFormat format, String fallbackCorr) {
        AsHttpWireFormat fmt = format == null ? AsHttpWireFormat.XML : format;
        if (fmt == AsHttpWireFormat.JSON) {
            return AsWireCodec.decodeResponse(body, fallbackCorr);
        }
        return ClassicDialogXmlCodec.decodeResponse(body, fallbackCorr);
    }

    public String encodeCallbackAck(AsResponse response) {
        return encodeCallbackAck(response, AsHttpWireFormat.XML);
    }

    public String encodeCallbackAck(AsResponse response, AsHttpWireFormat format) {
        AsHttpWireFormat fmt = format == null ? AsHttpWireFormat.XML : format;
        if (fmt == AsHttpWireFormat.JSON) {
            return new String(AsWireCodec.encodeResponse(response), java.nio.charset.StandardCharsets.UTF_8);
        }
        return ClassicDialogXmlCodec.encodeCallbackAck(response);
    }

    public AsResponse decodeCallback(String body, AsHttpWireFormat format) {
        AsHttpWireFormat fmt = format == null ? AsHttpWireFormat.XML : format;
        if (fmt == AsHttpWireFormat.JSON) {
            return AsWireCodec.decodeResponse(body, null);
        }
        return ClassicDialogXmlCodec.decodeCallback(body);
    }

    public String encodeNiResponse(String correlationId, String text, AsAction action,
                                   boolean emptyHandshake, AsHttpWireFormat format) {
        AsHttpWireFormat fmt = format == null ? AsHttpWireFormat.XML : format;
        if (fmt == AsHttpWireFormat.JSON) {
            AsResponse resp = new AsResponse(correlationId, correlationId, 1,
                    text == null ? "" : text, action, false);
            return new String(AsWireCodec.encodeResponse(resp), java.nio.charset.StandardCharsets.UTF_8);
        }
        return ClassicDialogXmlCodec.encodeNiSnapshot(correlationId, text, action, emptyHandshake);
    }

    /**
     * Parked NI HTTP gate expiry (or bridge-gated notify): ABORT dialog carrying
     * {@code virtualBridgeId}, AdaptiveTimeout fields, and classic {@code jsessionId}.
     */
    public String encodeNiGatedAbort(GatedSessionMeta meta, AsHttpWireFormat format) {
        if (meta == null) {
            return encodeNiResponse(null, "", AsAction.ABORT, false, format);
        }
        AsHttpWireFormat fmt = format == null ? AsHttpWireFormat.XML : format;
        Long gateMs = meta.gateMs() > 0 ? meta.gateMs() : null;
        if (fmt == AsHttpWireFormat.JSON) {
            return AsWireCodec.encodeGatedNotifyString(AsGatedNotify.from(meta));
        }
        return ClassicDialogXmlCodec.encodeNiSnapshot(
                meta.correlationId(), "", AsAction.ABORT, false,
                meta.sessionId(), meta.virtualBridgeId(), gateMs,
                meta.jsessionId(), meta.gateReason(), meta.observedEwmaMs());
    }

    /** Peer MAP Notify RESULT → classic AS XML {@code unstructuredSSNotify_Response}. */
    public String encodeNiNotifyResponse(String correlationId, AsHttpWireFormat format) {
        AsHttpWireFormat fmt = format == null ? AsHttpWireFormat.XML : format;
        if (fmt == AsHttpWireFormat.JSON) {
            AsResponse resp = new AsResponse(correlationId, correlationId, 1, "", AsAction.CONTINUE, false);
            return new String(AsWireCodec.encodeResponse(resp), java.nio.charset.StandardCharsets.UTF_8);
        }
        return ClassicDialogXmlCodec.encodeNiNotifyResponse(correlationId);
    }

    /**
     * AS→GW NI ingress: XML classic dialog or JSON AsRequest-like / AsResponse-like body.
     */
    public ClassicNiIngress decodeNiRequest(String body, AsHttpWireFormat format) {
        AsHttpWireFormat fmt = format == null ? AsHttpWireFormat.XML : format;
        if (fmt == AsHttpWireFormat.JSON) {
            return decodeNiJson(body);
        }
        return ClassicDialogXmlCodec.decodeNiRequest(body);
    }

    private static ClassicNiIngress decodeNiJson(String body) {
        if (body == null || body.isBlank()) {
            return new ClassicNiIngress(null, "", null, false, AsHttpWireFormat.JSON);
        }
        try {
            AsRequest req = AsWireCodec.decodeRequest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new ClassicNiIngress(req.msisdn(), req.ussdString(), req.correlationId(),
                    false, AsHttpWireFormat.JSON, req.networkId());
        } catch (Exception ignored) {
            AsResponse resp = AsWireCodec.decodeResponse(body, null);
            return new ClassicNiIngress(null, resp.text(), resp.correlationId(), false, AsHttpWireFormat.JSON);
        }
    }
}
