package et.restlink.ussdgw.api.classic;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.api.AsResponse;

/**
 * Lightweight classic-compatible {@code <dialog>} XML codec (no org.mobicents.ussdgateway).
 * Attribute style matches javolution / RestComm fixtures (string= on MAP message elements).
 */
public final class ClassicDialogXmlCodec {
    private static final XmlMapper XML = XmlMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    private static final String APP_CTX = "networkUnstructuredSsContext";
    private static final int DCS = 15;

    private ClassicDialogXmlCodec() {}

    /** MO first pull (generation == 0) → processUnstructuredSSRequest_Request. */
    public static String encodePull(AsRequest req) {
        if (req == null) {
            return "<dialog/>";
        }
        if (req.generation() > 0) {
            return encodeContinue(req);
        }
        StringBuilder sb = new StringBuilder(256);
        openDialog(sb, req, false);
        sb.append("<processUnstructuredSSRequest_Request dataCodingScheme=\"").append(DCS)
                .append("\" string=\"").append(xmlAttr(req.ussdString())).append("\">");
        appendMsisdn(sb, req.msisdn());
        sb.append("</processUnstructuredSSRequest_Request>");
        sb.append("</dialog>");
        return sb.toString();
    }

    /** User continue pull → unstructuredSSRequest_Request. */
    public static String encodeContinue(AsRequest req) {
        if (req == null) {
            return "<dialog/>";
        }
        StringBuilder sb = new StringBuilder(256);
        openDialog(sb, req, false);
        sb.append("<unstructuredSSRequest_Request dataCodingScheme=\"").append(DCS)
                .append("\" string=\"").append(xmlAttr(req.ussdString())).append("\">");
        appendMsisdn(sb, req.msisdn());
        sb.append("</unstructuredSSRequest_Request>");
        sb.append("</dialog>");
        return sb.toString();
    }

    /**
     * Decode AS pull/callback response body.
     * Abort → ABORT; empty USSD string → END; otherwise CONTINUE.
     * RestLink extension: {@code async="true"} on {@code <dialog>} for ASYNC_ACK (classic omits).
     */
    public static AsResponse decodeResponse(String xml, String fallbackCorr) {
        String corr = blankTo(fallbackCorr, "unknown");
        if (xml == null || xml.isBlank()) {
            return new AsResponse(corr, corr, 1, "", AsAction.END, false);
        }
        try {
            JsonNode root = XML.readTree(xml.trim());
            if (root == null || root.isMissingNode()) {
                return new AsResponse(corr, corr, 1, "", AsAction.END, false);
            }
            String sessionId = firstNonBlank(attr(root, "sessionId"), textChild(root, "sessionId"));
            String bridgeId = firstNonBlank(
                    attr(root, "virtualBridgeId"), textChild(root, "virtualBridgeId"));
            Long gateMs = parseLong(firstNonBlank(
                    attr(root, "adaptiveTimeoutMs"), textChild(root, "adaptiveTimeoutMs")));
            corr = firstNonBlank(
                    attr(root, "localId"),
                    textChild(root, "localId"),
                    attr(root, "correlationId"),
                    bridgeId,
                    sessionId,
                    corr);
            boolean async = boolAttr(root, "async")
                    || "true".equalsIgnoreCase(textChild(root, "async"));

            if (isAbort(root)) {
                return new AsResponse(corr, corr, 1, "", AsAction.ABORT, async,
                        null, sessionId, bridgeId, gateMs);
            }

            String text = extractResponseString(root);
            AsAction action = (text == null || text.isEmpty()) ? AsAction.END : AsAction.CONTINUE;
            return new AsResponse(corr, corr, 1, text == null ? "" : text, action, async,
                    null, sessionId, bridgeId, gateMs);
        } catch (Exception e) {
            throw new IllegalArgumentException("decode classic dialog XML", e);
        }
    }

    public static String encodeCallbackAck(AsResponse resp) {
        return encodeNiSnapshot(
                resp == null ? null : resp.correlationId(),
                resp == null ? null : resp.text(),
                resp == null ? AsAction.END : resp.action(),
                false,
                resp == null ? null : resp.sessionId(),
                resp == null ? null : resp.virtualBridgeId(),
                resp == null ? null : resp.adaptiveTimeoutMs());
    }

    public static AsResponse decodeCallback(String xml) {
        return decodeResponse(xml, null);
    }

    /**
     * Encode NI / callback snapshot toward AS.
     * ABORT → mapUserAbortChoice; END → processUnstructuredSSRequest_Response;
     * CONTINUE → unstructuredSSRequest_Request; emptyHandshake sets emptyDialogHandshake.
     */
    /**
     * Classic XmlMAPDialog after peer {@code unstructuredSS-Notify} RESULT: wrap Notify_Response
     * so the parked AS HTTP can END / continue without waiting for AdaptiveTimeout alone.
     */
    public static String encodeNiNotifyResponse(String correlationId) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("<dialog");
        appendIdentityAttrs(sb, correlationId, null, null, null, null);
        sb.append(" mapMessagesSize=\"1\">");
        sb.append("<unstructuredSSNotify_Response/>");
        sb.append("</dialog>");
        return sb.toString();
    }

    public static String encodeNiSnapshot(String correlationId, String text, AsAction action,
                                          boolean emptyHandshake) {
        return encodeNiSnapshot(correlationId, text, action, emptyHandshake, null, null, null);
    }

    public static String encodeNiSnapshot(String correlationId, String text, AsAction action,
                                          boolean emptyHandshake, String sessionId,
                                          String virtualBridgeId, Long adaptiveTimeoutMs) {
        AsAction act = action == null ? AsAction.END : action;
        StringBuilder sb = new StringBuilder(256);
        if (act == AsAction.ABORT) {
            sb.append("<dialog");
            appendIdentityAttrs(sb, correlationId, sessionId, virtualBridgeId, adaptiveTimeoutMs, null);
            sb.append(" mapMessagesSize=\"0\" mapUserAbortChoice=\"isUserSpecificReason\"");
            if (emptyHandshake) {
                sb.append(" emptyDialogHandshake=\"true\"");
            }
            sb.append("/>");
            return sb.toString();
        }
        openDialog(sb, correlationId, -1, emptyHandshake, sessionId, virtualBridgeId,
                adaptiveTimeoutMs, null, null);
        String safe = text == null ? "" : text;
        if (act == AsAction.CONTINUE) {
            sb.append("<unstructuredSSRequest_Request dataCodingScheme=\"").append(DCS)
                    .append("\" string=\"").append(xmlAttr(safe)).append("\"/>");
        } else {
            sb.append("<processUnstructuredSSRequest_Response dataCodingScheme=\"").append(DCS)
                    .append("\" string=\"").append(xmlAttr(safe)).append("\"/>");
        }
        sb.append("</dialog>");
        return sb.toString();
    }

    /** AS→GW NI body: msisdn + USSD text + emptyDialogHandshake + localId. */
    public static ClassicNiIngress decodeNiRequest(String body) {
        if (body == null || body.isBlank()) {
            return new ClassicNiIngress(null, "", null, false, et.restlink.ussdgw.api.AsHttpWireFormat.XML);
        }
        try {
            JsonNode root = XML.readTree(body.trim());
            String corr = firstNonBlank(attr(root, "localId"), textChild(root, "localId"));
            boolean handshake = boolAttr(root, "emptyDialogHandshake")
                    || "true".equalsIgnoreCase(textChild(root, "emptyDialogHandshake"));
            String notifyText = extractNamedString(root, "unstructuredSSNotify_Request");
            String requestText = extractNamedString(root, "unstructuredSSRequest_Request");
            String processText = extractNamedString(root, "processUnstructuredSSRequest_Request");
            // Element present ⇒ notify (classic NI push); request tag alone ⇒ UnstructuredSS-Request.
            boolean notifyOnly = notifyText != null && requestText == null;
            String text = firstNonBlank(notifyText, requestText, processText, "");
            String msisdn = extractMsisdn(root);
            Integer networkId = parseInt(firstNonBlank(attr(root, "networkId"),
                    textChild(root, "networkId")));
            return new ClassicNiIngress(msisdn, text == null ? "" : text, corr, handshake,
                    et.restlink.ussdgw.api.AsHttpWireFormat.XML, networkId, notifyOnly);
        } catch (Exception e) {
            throw new IllegalArgumentException("decode classic NI dialog XML", e);
        }
    }

    // --- helpers ---

    private static void openDialog(StringBuilder sb, AsRequest req, boolean emptyHandshake) {
        openDialog(sb, req.correlationId(), req.networkId(), emptyHandshake,
                req.sessionId(), req.virtualBridgeId(), req.adaptiveTimeoutMs(),
                req.asMode(), null);
    }

    private static void openDialog(StringBuilder sb, String localId, int networkId,
                                   boolean emptyHandshake, String sessionId,
                                   String virtualBridgeId, Long adaptiveTimeoutMs,
                                   String asMode, Boolean async) {
        sb.append("<dialog appCntx=\"").append(APP_CTX).append('"');
        appendIdentityAttrs(sb, localId, sessionId, virtualBridgeId, adaptiveTimeoutMs, asMode);
        if (networkId >= 0) {
            sb.append(" networkId=\"").append(networkId).append('"');
        }
        if (emptyHandshake) {
            sb.append(" emptyDialogHandshake=\"true\"");
        }
        if (async != null && async) {
            sb.append(" async=\"true\"");
        }
        sb.append('>');
    }

    private static void appendIdentityAttrs(StringBuilder sb, String localId, String sessionId,
                                            String virtualBridgeId, Long adaptiveTimeoutMs,
                                            String asMode) {
        if (notBlank(localId)) {
            sb.append(" localId=\"").append(xmlAttr(localId)).append('"');
        }
        if (notBlank(sessionId)) {
            sb.append(" sessionId=\"").append(xmlAttr(sessionId)).append('"');
        }
        if (notBlank(virtualBridgeId)) {
            sb.append(" virtualBridgeId=\"").append(xmlAttr(virtualBridgeId)).append('"');
        }
        if (adaptiveTimeoutMs != null && adaptiveTimeoutMs > 0) {
            sb.append(" adaptiveTimeoutMs=\"").append(adaptiveTimeoutMs).append('"');
        }
        if (notBlank(asMode)) {
            sb.append(" asMode=\"").append(xmlAttr(asMode)).append('"');
        }
    }

    private static Integer parseInt(String s) {
        if (!notBlank(s)) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLong(String s) {
        if (!notBlank(s)) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void appendMsisdn(StringBuilder sb, String msisdn) {
        if (!notBlank(msisdn)) {
            return;
        }
        sb.append("<msisdn nai=\"international_number\" npi=\"ISDN\" number=\"")
                .append(xmlAttr(msisdn)).append("\"/>");
    }

    private static boolean isAbort(JsonNode root) {
        if (root.has("mapUserAbortChoice") || root.has("mapAbortProviderReason")) {
            return true;
        }
        String type = attr(root, "type");
        return type != null && "abort".equalsIgnoreCase(type);
    }

    private static String extractResponseString(JsonNode root) {
        String s = extractNamedString(root, "processUnstructuredSSRequest_Response");
        if (s != null) return s;
        s = extractNamedString(root, "unstructuredSSRequest_Response");
        if (s != null) return s;
        s = extractNamedString(root, "unstructuredSSNotify_Response");
        if (s != null) return s;
        // CONTINUE menus sometimes arrive as Request from AS
        s = extractNamedString(root, "unstructuredSSRequest_Request");
        if (s != null) return s;
        s = extractNamedString(root, "unstructuredSSNotify_Request");
        return s;
    }

    private static String extractNamedString(JsonNode root, String element) {
        JsonNode n = root.get(element);
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        String attr = attr(n, "string");
        if (attr != null) {
            return attr;
        }
        String child = textChild(n, "string");
        if (child != null) {
            return child;
        }
        String ussd = textChild(n, "ussdString");
        return ussd;
    }

    private static String extractMsisdn(JsonNode root) {
        String direct = attr(root, "msisdn");
        if (notBlank(direct)) {
            return direct;
        }
        JsonNode msisdn = root.get("msisdn");
        if (msisdn != null && !msisdn.isNull()) {
            String num = firstNonBlank(attr(msisdn, "number"), textChild(msisdn, "number"),
                    msisdn.isTextual() ? msisdn.asText() : null);
            if (notBlank(num)) {
                return num;
            }
        }
        // Classic XmlMAPDialog carries the subscriber as destinationReference AddressString
        // (number=…). NI AS bodies often omit a nested <msisdn> and rely on this alone.
        JsonNode destRef = root.get("destinationReference");
        if (destRef != null && !destRef.isNull()) {
            String num = firstNonBlank(attr(destRef, "number"), textChild(destRef, "number"),
                    destRef.isTextual() ? destRef.asText() : null);
            if (notBlank(num)) {
                return num;
            }
        }
        String destAttr = attr(root, "destinationReference");
        if (notBlank(destAttr)) {
            return destAttr;
        }
        for (String el : new String[] {
                "unstructuredSSNotify_Request",
                "unstructuredSSRequest_Request",
                "processUnstructuredSSRequest_Request"
        }) {
            JsonNode msg = root.get(el);
            if (msg == null || msg.isNull()) {
                continue;
            }
            JsonNode nested = msg.get("msisdn");
            if (nested != null && !nested.isNull()) {
                String num = firstNonBlank(attr(nested, "number"), textChild(nested, "number"),
                        nested.isTextual() ? nested.asText() : null);
                if (notBlank(num)) {
                    return num;
                }
            }
            String flat = attr(msg, "msisdn");
            if (notBlank(flat)) {
                return flat;
            }
        }
        return null;
    }

    private static String attr(JsonNode n, String name) {
        if (n == null) return null;
        JsonNode a = n.get(name);
        if (a != null && !a.isNull() && a.isValueNode()) {
            return a.asText();
        }
        // Jackson XML sometimes keeps attributes under ""
        return null;
    }

    private static boolean boolAttr(JsonNode n, String name) {
        String v = attr(n, name);
        return v != null && ("true".equalsIgnoreCase(v) || "1".equals(v));
    }

    private static String textChild(JsonNode n, String name) {
        if (n == null) return null;
        JsonNode c = n.get(name);
        if (c == null || c.isNull() || c.isMissingNode()) {
            return null;
        }
        if (c.isValueNode()) {
            return c.asText();
        }
        return null;
    }

    private static String xmlAttr(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankTo(String s, String def) {
        return notBlank(s) ? s.trim() : def;
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (notBlank(v)) {
                return v.trim();
            }
        }
        return null;
    }
}
