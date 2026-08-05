package et.restlink.ussdgw.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * Greenfield AS wire format: UTF-8 JSON for both HTTP and gRPC payload bytes.
 * Content-Type for HTTP is application/json; gRPC fullMethod carries service/method.
 */
public final class AsWireCodec {
    public static final String CONTENT_TYPE = "application/json; charset=utf-8";
    public static final String GRPC_CONTENT_TYPE = "application/json";
    private static final ObjectMapper JSON = new ObjectMapper();

    private AsWireCodec() {}

    public static byte[] encodeRequest(AsRequest req) {
        try {
            return JSON.writeValueAsBytes(req);
        } catch (Exception e) {
            throw new IllegalStateException("encode AsRequest", e);
        }
    }

    public static String encodeRequestString(AsRequest req) {
        return new String(encodeRequest(req), StandardCharsets.UTF_8);
    }

    public static byte[] encodeResponse(AsResponse resp) {
        try {
            return JSON.writeValueAsBytes(resp);
        } catch (Exception e) {
            throw new IllegalStateException("encode AsResponse", e);
        }
    }

    public static AsResponse decodeResponse(byte[] payload, String fallbackCorr) {
        if (payload == null || payload.length == 0) {
            return new AsResponse(fallbackCorr, fallbackCorr, 1, "", AsAction.END, false);
        }
        String body = new String(payload, StandardCharsets.UTF_8).trim();
        return decodeResponse(body, fallbackCorr);
    }

    public static AsResponse decodeResponse(String body, String fallbackCorr) {
        if (body == null || body.isBlank()) {
            return new AsResponse(fallbackCorr, fallbackCorr, 1, "", AsAction.END, false);
        }
        try {
            AsResponse resp = JSON.readValue(body, AsResponse.class);
            if (resp.correlationId() == null || resp.correlationId().isBlank()) {
                return new AsResponse(fallbackCorr, resp.requestId(), resp.generation(),
                        resp.text(), resp.action(), resp.async(), resp.alphabet());
            }
            return resp;
        } catch (Exception e) {
            // Non-JSON body treated as END menu text (lab AS sim)
            return new AsResponse(fallbackCorr, fallbackCorr, 1, body, AsAction.END, false);
        }
    }

    public static AsRequest decodeRequest(byte[] payload) {
        try {
            return JSON.readValue(payload, AsRequest.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("decode AsRequest", e);
        }
    }
}
