package demo.ussd;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A three-level USSD menu ("My Ethio") behind ussdgw, written as a plain Jakarta Servlet.
 *
 * <h2>MAP2MAP (USSD proxy to the upper platform)</h2>
 * With a RE_ROUTE rule the gateway first proxies the dial to the upper USSD platform
 * (e.g. {@code *804#} → {@code *875#}) and then calls this AS with the upper platform's answer:
 * {@code hlrResult="responded"} and the upper text in {@code string=}. {@code none} /
 * {@code reject} / {@code pending} mean there is no usable upper text — the AS shows its own
 * menu and never echoes those sentinels to the handset. A rule without re-route sends a plain
 * dial (no {@code hlrResult}); this servlet handles both.
 *
 * <h2>How the conversation is kept: the Java HttpSession</h2>
 * ussdgw sends one HTTP POST per USSD turn and, like the classic ussdgateway, returns the
 * AS's cookies on every later POST of the same USSD dialog. So the servlet container's
 * {@code JSESSIONID} identifies the dialog and {@link HttpSession} holds where the subscriber
 * is in the menu:
 * <pre>
 *   turn 1  *804#        request.getSession(true)   → new session, Set-Cookie: JSESSIONID=…
 *   turn 2  "2"          request.getSession(false)  → same session (Cookie: JSESSIONID=…)
 *   turn 3  "1"          same session
 *   turn 4  "1" (end)    session.invalidate()       → dialog closed on both sides
 * </pre>
 *
 * <h2>What the AS answers</h2>
 * <ul>
 *   <li>{@code <unstructuredSSRequest_Request string="…"/>} — show a menu and wait for the
 *       subscriber's choice (the USSD dialog stays open);</li>
 *   <li>{@code <processUnstructuredSSRequest_Response string="…"/>} — final text; ussdgw sends
 *       it and closes the dialog.</li>
 * </ul>
 */
public final class MyEthioMenuServlet extends HttpServlet {

    // Session attribute names
    private static final String LEVEL = "menuLevel";
    private static final String BUNDLE = "chosenBundle";
    private static final String MSISDN = "msisdn";
    private static final String CORRELATION = "correlationId";

    private static final String MAIN_MENU = "My Ethio\n1. Check balance\n2. Buy bundle\n3. Exit";
    private static final String BUNDLE_MENU =
            "Bundles\n1. Daily 50MB - 5 ETB\n2. Weekly 500MB - 25 ETB\n0. Back";

    private record Bundle(String name, int price) {}
    private static final Map<String, Bundle> BUNDLES = Map.of(
            "1", new Bundle("Daily 50MB", 5),
            "2", new Bundle("Weekly 500MB", 25));

    /** Demo "billing": balance per subscriber, 50 ETB to start. */
    private static final Map<String, Integer> BALANCE = new ConcurrentHashMap<>();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        UssdTurn turn = UssdTurn.parse(req.getInputStream());

        String reply;
        if (turn.isFirst()) {
            // Turn 1: the subscriber dialed the short code. Start a fresh session for this dialog.
            HttpSession old = req.getSession(false);
            if (old != null) {
                old.invalidate();
            }
            HttpSession session = req.getSession(true);
            session.setAttribute(MSISDN, turn.msisdn());
            session.setAttribute(CORRELATION, turn.correlationId());   // gateway localId: log key
            session.setAttribute(LEVEL, "MAIN");
            reply = menu(firstScreen(turn) + MAIN_MENU);
        } else {
            // Later turns: the same dialog comes back with its JSESSIONID.
            HttpSession session = req.getSession(false);
            if (session == null) {
                reply = end("Session expired. Please dial again.");
            } else {
                reply = nextStep(session, turn.text());
            }
        }

        resp.setStatus(200);
        resp.setContentType("text/xml;charset=UTF-8");
        resp.getOutputStream().write(reply.getBytes(StandardCharsets.UTF_8));
        log("corr=" + turn.correlationId() + " msisdn=" + turn.msisdn()
                + (turn.hlrResult() == null ? "" : " hlrResult=" + turn.hlrResult() + " hop=" + turn.hopUssd())
                + " in='" + turn.text() + "' session="
                + (req.getSession(false) == null ? "-" : req.getSession(false).getId()) + " -> " + reply);
    }

    /**
     * Turn 1 header. MAP2MAP: show the upper platform's answer only when it really answered;
     * a plain dial (no re-route rule) has no hlrResult and needs no header.
     */
    private static String firstScreen(UssdTurn turn) {
        if (turn.hlrResult() == null) {
            return "";
        }
        return switch (turn.hlrResult()) {
            case "responded" -> turn.text() + "\n";
            case "pending" -> "Your request is being processed.\n";
            default -> "Upper service not available now.\n";   // none / reject
        };
    }

    /** The menu tree. Returns the XML answer and moves the session to the next level. */
    private String nextStep(HttpSession session, String choice) {
        String msisdn = (String) session.getAttribute(MSISDN);
        int balance = BALANCE.computeIfAbsent(msisdn, k -> 50);
        String level = (String) session.getAttribute(LEVEL);

        switch (level) {
            case "MAIN" -> {                                      // level 1
                switch (choice) {
                    case "1" -> { return finish(session, "Your balance is " + balance + " ETB."); }
                    case "2" -> { session.setAttribute(LEVEL, "BUNDLES"); return menu(BUNDLE_MENU); }
                    case "3" -> { return finish(session, "Thank you for using My Ethio."); }
                    default -> { return menu("Invalid choice.\n" + MAIN_MENU); }
                }
            }
            case "BUNDLES" -> {                                   // level 2
                if ("0".equals(choice)) {
                    session.setAttribute(LEVEL, "MAIN");
                    return menu(MAIN_MENU);
                }
                Bundle b = BUNDLES.get(choice);
                if (b == null) {
                    return menu("Invalid choice.\n" + BUNDLE_MENU);
                }
                session.setAttribute(BUNDLE, choice);
                session.setAttribute(LEVEL, "CONFIRM");
                return menu("Buy " + b.name() + " for " + b.price() + " ETB?\n1. Yes\n2. No");
            }
            case "CONFIRM" -> {                                   // level 3
                Bundle b = BUNDLES.get((String) session.getAttribute(BUNDLE));
                if (!"1".equals(choice)) {
                    return finish(session, "Cancelled. No charge.");
                }
                if (balance < b.price()) {
                    return finish(session, "Insufficient balance (" + balance + " ETB).");
                }
                BALANCE.put(msisdn, balance - b.price());
                return finish(session, b.name() + " is active. New balance "
                        + (balance - b.price()) + " ETB.");
            }
            default -> { return finish(session, "Session expired. Please dial again."); }
        }
    }

    /** Final text: end the USSD dialog and drop the Java session. */
    private static String finish(HttpSession session, String text) {
        session.invalidate();
        return end(text);
    }

    // ------------------------------------------------------------------ classic XML

    /** Show a menu and keep the USSD dialog open. */
    static String menu(String text) {
        return "<dialog mapMessagesSize=\"1\"><unstructuredSSRequest_Request dataCodingScheme=\"15\" string=\""
                + xml(text) + "\"/></dialog>";
    }

    /** Final text; ussdgw closes the USSD dialog after sending it. */
    static String end(String text) {
        return "<dialog mapMessagesSize=\"1\"><processUnstructuredSSRequest_Response dataCodingScheme=\"15\" string=\""
                + xml(text) + "\"/></dialog>";
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\n", "&#10;");
    }

    /**
     * One USSD turn as ussdgw sends it:
     * {@code processUnstructuredSSRequest_Request} = the dial (turn 1),
     * {@code unstructuredSSRequest_Request} = the subscriber's answer to a menu.
     * Text and MSISDN may come as attributes or as child elements (both classic styles).
     */
    record UssdTurn(boolean isFirst, String text, String msisdn,
                    String correlationId, String hlrResult, String hopUssd) {
        static UssdTurn parse(InputStream body) throws IOException {
            try {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // no XXE
                Document doc = f.newDocumentBuilder().parse(body);
                Element first = firstElement(doc, "processUnstructuredSSRequest_Request");
                Element op = first != null ? first : firstElement(doc, "unstructuredSSRequest_Request");
                Element dialog = doc.getDocumentElement();
                String corr = attrOrNull(dialog, "localId");
                String hlr = attrOrNull(dialog, "hlrResult");
                String hop = attrOrNull(dialog, "hopUssd");
                if (op == null) {
                    return new UssdTurn(false, "", "unknown", corr, hlr, hop);
                }
                String text = op.hasAttribute("string") ? op.getAttribute("string") : childText(op, "ussdString");
                Element m = firstElement(op, "msisdn");
                String msisdn = m == null ? "unknown" : m.getAttribute("number");
                return new UssdTurn(first != null, text == null ? "" : text.trim(), msisdn, corr, hlr, hop);
            } catch (Exception e) {
                throw new IOException("not a ussdgw <dialog> body: " + e.getMessage(), e);
            }
        }

        private static Element firstElement(Node scope, String name) {
            NodeList list = scope instanceof Document d ? d.getElementsByTagName(name)
                    : ((Element) scope).getElementsByTagName(name);
            return list.getLength() == 0 ? null : (Element) list.item(0);
        }

        private static String attrOrNull(Element e, String name) {
            return e.hasAttribute(name) && !e.getAttribute(name).isBlank() ? e.getAttribute(name) : null;
        }

        private static String childText(Element e, String name) {
            Element c = firstElement(e, name);
            return c == null ? null : c.getTextContent();
        }
    }
}
