package et.restlink.ussdgw.service;

import et.restlink.ussdgw.admin.AdminHttpHandler;
import et.restlink.ussdgw.admin.LinkStatusService;
import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.api.WireFormatResolver;
import et.restlink.ussdgw.api.classic.ClassicNiHttpPark;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.UssdSagaCoordinator;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.profile.UssdUserProfileStore;
import et.restlink.ussdgw.routing.ShortCodeRoutingService;
import et.restlink.ussdgw.tenant.CallbackAuthService;
import et.restlink.ussdgw.tenant.TenantGuard;
import et.restlink.ussdgw.campaign.CampaignService;
import et.restlink.ussdgw.hlr.DiameterLocationClient;
import et.restlink.ussdgw.hlr.HlrFaceService;
import et.restlink.ussdgw.hlr.HlrResolvePolicy;
import et.restlink.ussdgw.hlr.PendingHlrProxyRegistry;
import et.restlink.ussdgw.access.DiameterUssdAccessAdapter;
import et.restlink.ussdgw.access.SipUssiAccessAdapter;
import et.restlink.ussdgw.sip.SipTrunkService;
import et.restlink.ussdgw.telemetry.Map2MapTelemetry;

import com.microjainslee.core.MicroSleeContainer;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class SbbServices {
    private static volatile SbbServices INSTANCE;

    @Inject MicroSleeContainer container;
    @Inject ShortCodeRoutingService routing;
    @Inject VirtualSessionBridge bridge;
    @Inject VirtualSessionStore store;
    @Inject UssdUserProfileStore userProfiles;
    @Inject AdaptiveTimeout adaptive;
    @Inject CdrService cdr;
    @Inject UssdConfigService config;
    @Inject LinkStatusService linkStatus;
    @Inject AdminHttpHandler adminHttp;
    @Inject PendingSriRegistry pendingSri;
    @Inject PendingMap2MapRegistry pendingMap2Map;
    @Inject Map2MapCompletionService map2MapCompletion;
    @Inject PendingHlrProxyRegistry pendingHlrProxy;
    @Inject HlrFaceService hlrFace;
    @Inject HlrResolvePolicy hlrPolicy;
    @Inject DiameterLocationClient diameterLocation;
    @Inject DiameterUssdAccessAdapter diameterAccess;
    @Inject SipUssiAccessAdapter sipAccess;
    @Inject SipTrunkService sipTrunks;
    @Inject et.restlink.ussdgw.ra.smpp.SmppEndpointRegistry smppRegistry;
    @Inject TenantGuard tenantGuard;
    @Inject AsPullClient asPull;
    @Inject AsPullStateRegistry asPullState;
    @Inject UssdSagaCoordinator saga;
    @Inject CallbackAuthService callbackAuth;
    @Inject CampaignService campaigns;
    @Inject AsPullRouter asPullRouter;
    @Inject AsWireFacade wireFacade;
    @Inject WireFormatResolver wireFormatResolver;
    @Inject ClassicNiHttpPark niHttpPark;
    @Inject Map2MapTelemetry map2MapTelemetry;
    @Inject Ss7PeerRouteService peerRoutes;

    @PostConstruct
    void install() { INSTANCE = this; }

    public static SbbServices get() {
        SbbServices s = INSTANCE;
        if (s == null) throw new IllegalStateException("SbbServices not initialized");
        return s;
    }

    public MicroSleeContainer container() { return container; }
    public ShortCodeRoutingService routing() { return routing; }
    public VirtualSessionBridge bridge() { return bridge; }
    public VirtualSessionStore store() { return store; }
    public UssdUserProfileStore userProfiles() { return userProfiles; }
    public AdaptiveTimeout adaptive() { return adaptive; }
    public CdrService cdr() { return cdr; }
    public UssdConfigService config() { return config; }
    public LinkStatusService linkStatus() { return linkStatus; }
    public AdminHttpHandler adminHttp() { return adminHttp; }
    public PendingSriRegistry pendingSri() { return pendingSri; }
    public PendingMap2MapRegistry pendingMap2Map() { return pendingMap2Map; }
    public Map2MapCompletionService map2MapCompletion() { return map2MapCompletion; }
    public PendingHlrProxyRegistry pendingHlrProxy() { return pendingHlrProxy; }
    public HlrFaceService hlrFace() { return hlrFace; }
    public HlrResolvePolicy hlrPolicy() { return hlrPolicy; }
    public DiameterLocationClient diameterLocation() { return diameterLocation; }
    public DiameterUssdAccessAdapter diameterAccess() { return diameterAccess; }
    public SipUssiAccessAdapter sipAccess() { return sipAccess; }
    public SipTrunkService sipTrunks() { return sipTrunks; }
    public et.restlink.ussdgw.ra.smpp.SmppEndpointRegistry smppRegistry() { return smppRegistry; }
    public TenantGuard tenantGuard() { return tenantGuard; }
    public AsPullClient asPull() { return asPull; }
    public AsPullStateRegistry asPullState() { return asPullState; }
    public UssdSagaCoordinator saga() { return saga; }
    public CallbackAuthService callbackAuth() { return callbackAuth; }
    public CampaignService campaigns() { return campaigns; }
    public AsPullRouter asPullRouter() { return asPullRouter; }
    public AsWireFacade wireFacade() { return wireFacade; }
    public WireFormatResolver wireFormatResolver() { return wireFormatResolver; }
    public ClassicNiHttpPark niHttpPark() { return niHttpPark; }
    public Map2MapTelemetry map2MapTelemetry() { return map2MapTelemetry; }
    public Ss7PeerRouteService peerRoutes() { return peerRoutes; }

    /**
     * N–N LB pick for new outbound (NI / MAP2MAP) when no ingress ASP.
     * @return preferredAsp + remotePc, or nulls when LB inactive / single candidate unused
     */
    public record RoutePin(String preferredAspName, int remotePc) {
        public static final RoutePin NONE = new RoutePin(null, -1);
    }

    public RoutePin pickPeerRoute(int networkId, String affinityKey) {
        if (peerRoutes == null) {
            return RoutePin.NONE;
        }
        var r = peerRoutes.pickForNewSession(networkId, affinityKey);
        if (r == null) {
            return RoutePin.NONE;
        }
        return new RoutePin(r.aspName(), r.peerPc());
    }
}
