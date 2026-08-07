package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.ra.smpp.SmppEndpointRegistry;

import com.microjainslee.ra.diameter.DiameterRaEndpoint;
import com.microjainslee.ra.jss7.Ss7ResourceAdaptor;
import com.microjainslee.ra.sipservlet.SipServletRaEndpoint;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class LinkStatusService {
    private volatile Ss7ResourceAdaptor ss7Ra;
    private volatile boolean ss7IntentionallyStopped;
    private volatile String ss7AppliedDetail = "down";
    private volatile boolean httpListen;
    private volatile String httpDetail = "down";
    private volatile boolean grpcListen;
    private volatile String grpcDetail = "down";
    private volatile SmppEndpointRegistry smppRegistry;
    private volatile String smppDetail = "down";
    private volatile DiameterRaEndpoint diameterEp;
    private volatile String diameterDetail = "down";
    private volatile SipServletRaEndpoint sipEp;
    private volatile String sipDetail = "down";

    public void bindSs7(Ss7ResourceAdaptor ra) {
        this.ss7Ra = ra;
        this.ss7IntentionallyStopped = false;
    }

    public void clearSs7() {
        this.ss7Ra = null;
        this.ss7AppliedDetail = "cleared";
    }

    public void markSs7Stopped() {
        ss7IntentionallyStopped = true;
        ss7Ra = null;
        ss7AppliedDetail = "stopped";
    }

    public void setSs7AppliedDetail(String detail) {
        this.ss7AppliedDetail = detail == null ? "" : detail;
    }

    public boolean isM3uaRouteReady() {
        Ss7ResourceAdaptor ra = ss7Ra;
        return !ss7IntentionallyStopped && ra != null && ra.isM3uaRouteReady();
    }

    public boolean ss7Live() {
        return isM3uaRouteReady();
    }

    public void markHttpListen(int port) {
        httpListen = true;
        httpDetail = "listen:" + port;
    }
    public void clearHttp() { httpListen = false; httpDetail = "down"; }
    public void setHttpDetail(String detail) { httpDetail = detail == null ? "" : detail; }

    public void markGrpcListen(int port) {
        grpcListen = true;
        grpcDetail = "listen:" + port;
    }
    public void clearGrpc() { grpcListen = false; grpcDetail = "down"; }
    public void setGrpcDetail(String detail) { grpcDetail = detail == null ? "" : detail; }

    public void bindSmpp(SmppEndpointRegistry registry, String detail) {
        this.smppRegistry = registry;
        this.smppDetail = detail == null ? "wired" : detail;
    }

    public void clearSmpp() {
        smppRegistry = null;
        smppDetail = "down";
    }

    public void bindDiameter(DiameterRaEndpoint ep) {
        this.diameterEp = ep;
    }

    public void clearDiameter() {
        diameterEp = null;
        diameterDetail = "down";
    }

    public void setDiameterDetail(String detail) {
        diameterDetail = detail == null ? "" : detail;
    }

    public void bindSip(SipServletRaEndpoint ep) {
        this.sipEp = ep;
    }

    public void clearSip() {
        sipEp = null;
        sipDetail = "down";
    }

    public void setSipDetail(String detail) {
        sipDetail = detail == null ? "" : detail;
    }

    public boolean diameterLive() {
        DiameterRaEndpoint ep = diameterEp;
        return ep != null && ep.isPeerReady();
    }

    public boolean sipLive() {
        SipServletRaEndpoint ep = sipEp;
        return ep != null && ep.delegate() != null && ep.delegate().isActive();
    }

    public boolean smppServerUp() {
        return smppRegistry != null && smppRegistry.server() != null;
    }

    public int smppBoundSessions() {
        if (smppRegistry == null || smppRegistry.server() == null) return 0;
        try {
            return smppRegistry.server().sessions().snapshot().size();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean routeReady = isM3uaRouteReady();
        boolean raActive = ss7Ra != null && ss7Ra.isActive();
        m.put("ss7.live", routeReady);
        m.put("ss7.raActive", raActive);
        m.put("ss7.detail", synthesizeSs7Detail(routeReady, raActive));
        m.put("http.listen", httpListen);
        m.put("http.detail", httpDetail);
        m.put("grpc.listen", grpcListen);
        m.put("grpc.detail", grpcDetail);
        m.put("smpp.server", smppServerUp());
        m.put("smpp.boundSessions", smppBoundSessions());
        m.put("smpp.clients", smppRegistry == null ? 0 : smppRegistry.clients().size());
        m.put("smpp.detail", smppDetail);
        int bound = smppBoundSessions();
        int clients = smppRegistry == null ? 0 : smppRegistry.clients().size();
        m.put("smpp.live", bound > 0 || clients > 0);
        boolean diamLive = diameterLive();
        m.put("diameter.live", diamLive);
        m.put("diameter.peerConnected", diameterEp != null && diameterEp.isPeerConnected());
        m.put("diameter.detail", diamLive
                ? (diameterDetail == null || diameterDetail.isBlank() ? "diameter=peer-ready" : diameterDetail)
                : (diameterEp == null ? "diameter=down" : "diameter=peer-down;" + diameterDetail));
        boolean sipUp = sipLive();
        m.put("sip.live", sipUp);
        m.put("sip.detail", sipUp
                ? (sipDetail == null || sipDetail.isBlank() ? "sip=active" : sipDetail)
                : (sipEp == null ? "sip=down" : "sip=inactive;" + sipDetail));
        return m;
    }

    public String htmlPartial(String tab) {
        Map<String, Object> s = snapshot();
        StringBuilder sb = new StringBuilder();
        boolean wantSs7 = tab == null || tab.isBlank() || "all".equals(tab) || "ss7".equals(tab);
        boolean wantSmpp = tab == null || tab.isBlank() || "all".equals(tab) || "smpp".equals(tab);
        boolean wantHttp = tab == null || tab.isBlank() || "all".equals(tab) || "http".equals(tab);
        boolean wantGrpc = tab == null || tab.isBlank() || "all".equals(tab) || "grpc".equals(tab);
        boolean wantDiam = tab == null || tab.isBlank() || "all".equals(tab) || "diameter".equals(tab);
        boolean wantSip = tab == null || tab.isBlank() || "all".equals(tab) || "sip".equals(tab);
        sb.append("<div class=\"grid gap-3 lg:grid-cols-2\">");
        if (wantSs7) {
            boolean live = Boolean.TRUE.equals(s.get("ss7.live"));
            sb.append(planeCard("SS7", live,
                    "raActive=" + s.get("ss7.raActive") + " · " + s.get("ss7.detail"),
                    "/admin/ss7", "/telemetry/?tab=ss7"));
        }
        if (wantHttp) {
            boolean listen = Boolean.TRUE.equals(s.get("http.listen"));
            sb.append(planeCard("HTTP", listen,
                    String.valueOf(s.get("http.detail")),
                    "/admin/http", "/telemetry/?tab=http"));
        }
        if (wantGrpc) {
            boolean listen = Boolean.TRUE.equals(s.get("grpc.listen"));
            sb.append(planeCard("gRPC", listen,
                    String.valueOf(s.get("grpc.detail")),
                    "/admin/grpc", "/telemetry/?tab=http"));
        }
        if (wantDiam) {
            boolean live = Boolean.TRUE.equals(s.get("diameter.live"));
            sb.append(planeCard("Diameter", live,
                    String.valueOf(s.get("diameter.detail")),
                    "/admin/diameter", "/telemetry/?tab=ss7"));
        }
        if (wantSip) {
            boolean live = Boolean.TRUE.equals(s.get("sip.live"));
            sb.append(planeCard("SIP", live,
                    String.valueOf(s.get("sip.detail")),
                    "/admin/sip", "/telemetry/?tab=ss7"));
        }
        if (wantSmpp) {
            int bound = s.get("smpp.boundSessions") instanceof Number n ? n.intValue() : 0;
            int clients = s.get("smpp.clients") instanceof Number n ? n.intValue() : 0;
            boolean live = Boolean.TRUE.equals(s.get("smpp.live"))
                    || bound > 0 || clients > 0;
            sb.append(planeCard("SMPP", live,
                    "server=" + s.get("smpp.server")
                            + " · bound=" + bound
                            + " · clients=" + clients
                            + " · " + s.get("smpp.detail"),
                    "/admin/smpp", "/telemetry/?tab=smpp"));
        }
        sb.append("</div>");
        return sb.toString();
    }

    /** Dashboard plane card — Open → config form; secondary Monitor Hub. */
    private static String planeCard(String name, boolean live, String detail,
                                    String configHref, String hubHref) {
        String badge = live
                ? "<span class=\"link-status-badge link-status-badge--ok\">LIVE</span>"
                : "<span class=\"link-status-badge link-status-badge--mute\">DOWN</span>";
        return "<div class=\"form-card rounded-lg border border-ink-line bg-ink-panel/80 p-4 link-status-panel\">"
                + "<div class=\"link-status-head\"><h3 class=\"text-sm font-semibold tracking-wide text-slate-100\">"
                + esc(name) + "</h3>" + badge + "</div>"
                + "<p class=\"link-status-detail mt-2\">" + esc(detail) + "</p>"
                + planeOpenLinks(configHref, hubHref)
                + "</div>";
    }

    private static String planeOpenLinks(String configHref, String hubHref) {
        StringBuilder b = new StringBuilder("<p class=\"mt-3 text-xs\">");
        if (configHref != null && !configHref.isBlank()) {
            b.append("<a class=\"text-signal hover:underline\" href=\"")
                    .append(esc(configHref)).append("\">Open</a>");
        }
        if (hubHref != null && !hubHref.isBlank()) {
            if (configHref != null && !configHref.isBlank()) {
                b.append(" · ");
            }
            b.append("<a class=\"text-ink-mute hover:text-signal hover:underline\" href=\"")
                    .append(esc(hubHref)).append("\">Monitor Hub</a>");
        }
        b.append("</p>");
        return b.toString();
    }

    private static String esc(Object o) {
        if (o == null) return "";
        return o.toString()
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String synthesizeSs7Detail(boolean routeReady, boolean raActive) {
        if (ss7IntentionallyStopped) return "ss7=stopped";
        if (routeReady) return ss7AppliedDetail.contains("ss7=") ? ss7AppliedDetail : "ss7=route-ready";
        if (raActive) return "ss7=listening;peer=down";
        return ss7AppliedDetail == null || ss7AppliedDetail.isBlank() ? "ss7=down" : ss7AppliedDetail;
    }
}
