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
        openDialog(sb, req.correlationId(), req.networkId(), false);
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
        openDialog(sb, req.correlationId(), req.networkId(), false);
        sb.append("<unstructuredSSRequest_Request dataCodingScheme=\"").append(DCS)
                .append("\" string=\"").append(xmlAttr(req.ussdString())).append("\">");
        appendMsisdn(sb, req.msisdn());
        sb.append("</unstructuredSSRequest_Request>");
        sb.append("</dialog>");
        return sb.toString();
    }

    /**
     * Decode AS pull/callback response body.
     * Abort → ABORT; empty USSD string → END; otherwise CONTINUE. Always async=false.
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
            corr = firstNonBlank(
                    attr(root, "localId"),
                    textChild(root, "localId"),
                    corr);

            if (isAbort(root)) {
                return new AsResponse(corr, corr, 1, "", AsAction.ABORT, false);
            }

            String text = extractResponseString(root);
            AsAction action = (text == null || text.isEmpty()) ? AsAction.END : AsAction.CONTINUE;
            return new AsResponse(corr, corr, 1, text == null ? "" : text, action, false);
        } catch (Exception e) {
            throw new IllegalArgumentException("decode classic dialog XML", e);
        }
    }

    public static String encodeCallbackAck(AsResponse resp) {
        return encodeNiSnapshot(
                resp == null ? null : resp.correlationId(),
                resp == null ? null : resp.text(),
                resp == null ? AsAction.END : resp.action(),
                false);
    }

    public static AsResponse decodeCallback(String xml) {
        return decodeResponse(xml, null);
    }

    /**
     * Encode NI / callback snapshot toward AS.
     * ABORT → mapUserAbortChoice; END → processUnstructuredSSRequest_Response;
     * CONTINUE → unstructuredSSRequest_Request; emptyHandshake sets emptyDialogHandshake.
     */
    public static String encodeNiSnapshot(String correlationId, String text, AsAction action,
                                          boolean emptyHandshake) {
        AsAction act = action == null ? AsAction.END : action;
        StringBuilder sb = new StringBuilder(256);
        if (act == AsAction.ABORT) {
            sb.append("<dialog");
            if (notBlank(correlationId)) {
                sb.append(" localId=\"").append(xmlAttr(correlationId)).append('"');
            }
            sb.append(" mapMessagesSize=\"0\" mapUserAbortChoice=\"isUserSpecificReason\"");
            if (emptyHandshake) {
                sb.append(" emptyDialogHandshake=\"true\"");
            }
            sb.append("/>");
            return sb.toString();
        }
        openDialog(sb, correlationId, -1, emptyHandshake);
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
            String text = firstNonBlank(
                    extractNamedString(root, "unstructuredSSNotify_Request"),
                    extractNamedString(root, "unstructuredSSRequest_Request"),
                    extractNamedString(root, "processUnstructuredSSRequest_Request"),
                    "");
            String msisdn = extractMsisdn(root);
            return new ClassicNiIngress(msisdn, text == null ? "" : text, corr, handshake,
                    et.restlink.ussdgw.api.AsHttpWireFormat.XML);
        } catch (Exception e) {
            throw new IllegalArgumentException("decode classic NI dialog XML", e);
        }
    }

    // --- helpers ---

    private static void openDialog(StringBuilder sb, String localId, int networkId, boolean emptyHandshake) {
        sb.append("<dialog appCntx=\"").append(APP_CTX).append('"');
        if (notBlank(localId)) {
            sb.append(" localId=\"").append(xmlAttr(localId)).append('"');
        }
        if (networkId >= 0) {
            sb.append(" networkId=\"").append(networkId).append('"');
        }
        if (emptyHandshake) {
            sb.append(" emptyDialogHandshake=\"true\"");
        }
        sb.append('>');
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
