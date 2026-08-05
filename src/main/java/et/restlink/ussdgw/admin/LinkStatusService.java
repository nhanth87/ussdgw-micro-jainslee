package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.admin.smpp.SmppAdminController;
import et.restlink.ussdgw.ra.smpp.SmppEndpointRegistry;

import com.microjainslee.ra.jss7.Ss7ResourceAdaptor;

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
        return m;
    }

    public String htmlPartial(String tab) {
        Map<String, Object> s = snapshot();
        StringBuilder sb = new StringBuilder();
        boolean wantSs7 = tab == null || tab.isBlank() || "all".equals(tab) || "ss7".equals(tab);
        boolean wantSmpp = tab == null || tab.isBlank() || "all".equals(tab) || "smpp".equals(tab);
        boolean wantHttp = tab == null || tab.isBlank() || "all".equals(tab) || "http".equals(tab);
        boolean wantGrpc = tab == null || tab.isBlank() || "all".equals(tab) || "grpc".equals(tab);
        if (wantSs7 || wantHttp || wantGrpc) {
            sb.append("<pre>");
            if (wantSs7) {
                sb.append("=== SS7 ===\n");
                sb.append("live=").append(s.get("ss7.live")).append('\n');
                sb.append("raActive=").append(s.get("ss7.raActive")).append('\n');
                sb.append("detail=").append(s.get("ss7.detail")).append('\n');
            }
            if (wantHttp) {
                sb.append("=== HTTP ===\n");
                sb.append("listen=").append(s.get("http.listen")).append('\n');
                sb.append("detail=").append(s.get("http.detail")).append('\n');
            }
            if (wantGrpc) {
                sb.append("=== gRPC ===\n");
                sb.append("listen=").append(s.get("grpc.listen")).append('\n');
                sb.append("detail=").append(s.get("grpc.detail")).append('\n');
            }
            sb.append("</pre>");
        }
        if (wantSmpp) {
            try {
                sb.append(new SmppAdminController().statusHtml(null).bodyAsString());
            } catch (RuntimeException ex) {
                sb.append("<pre>=== SMPP ===\n");
                sb.append("server=").append(s.get("smpp.server")).append('\n');
                sb.append("boundSessions=").append(s.get("smpp.boundSessions")).append('\n');
                sb.append("clients=").append(s.get("smpp.clients")).append('\n');
                sb.append("detail=").append(s.get("smpp.detail")).append('\n');
                sb.append("</pre>");
            }
        }
        return sb.toString();
    }

    private String synthesizeSs7Detail(boolean routeReady, boolean raActive) {
        if (ss7IntentionallyStopped) return "ss7=stopped";
        if (routeReady) return ss7AppliedDetail.contains("ss7=") ? ss7AppliedDetail : "ss7=route-ready";
        if (raActive) return "ss7=listening;peer=down";
        return ss7AppliedDetail == null || ss7AppliedDetail.isBlank() ? "ss7=down" : ss7AppliedDetail;
    }
}
