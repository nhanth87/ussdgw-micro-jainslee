package et.restlink.ussdgw.service;

import et.restlink.ussdgw.admin.AdminHttpHandler;
import et.restlink.ussdgw.admin.LinkStatusService;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.UssdSagaCoordinator;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.routing.ShortCodeRoutingService;
import et.restlink.ussdgw.tenant.CallbackAuthService;
import et.restlink.ussdgw.tenant.TenantGuard;
import et.restlink.ussdgw.campaign.CampaignService;
import et.restlink.ussdgw.hlr.DiameterLocationClient;
import et.restlink.ussdgw.hlr.HlrFaceService;
import et.restlink.ussdgw.hlr.PendingHlrProxyRegistry;
import et.restlink.ussdgw.access.DiameterUssdAccessAdapter;
import et.restlink.ussdgw.access.SipUssiAccessAdapter;

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
    @Inject AdaptiveTimeout adaptive;
    @Inject CdrService cdr;
    @Inject UssdConfigService config;
    @Inject LinkStatusService linkStatus;
    @Inject AdminHttpHandler adminHttp;
    @Inject PendingSriRegistry pendingSri;
    @Inject PendingHlrProxyRegistry pendingHlrProxy;
    @Inject HlrFaceService hlrFace;
    @Inject DiameterLocationClient diameterLocation;
    @Inject DiameterUssdAccessAdapter diameterAccess;
    @Inject SipUssiAccessAdapter sipAccess;
    @Inject et.restlink.ussdgw.ra.smpp.SmppEndpointRegistry smppRegistry;
    @Inject TenantGuard tenantGuard;
    @Inject AsPullClient asPull;
    @Inject UssdSagaCoordinator saga;
    @Inject CallbackAuthService callbackAuth;
    @Inject CampaignService campaigns;
    @Inject AsPullRouter asPullRouter;

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
    public AdaptiveTimeout adaptive() { return adaptive; }
    public CdrService cdr() { return cdr; }
    public UssdConfigService config() { return config; }
    public LinkStatusService linkStatus() { return linkStatus; }
    public AdminHttpHandler adminHttp() { return adminHttp; }
    public PendingSriRegistry pendingSri() { return pendingSri; }
    public PendingHlrProxyRegistry pendingHlrProxy() { return pendingHlrProxy; }
    public HlrFaceService hlrFace() { return hlrFace; }
    public DiameterLocationClient diameterLocation() { return diameterLocation; }
    public DiameterUssdAccessAdapter diameterAccess() { return diameterAccess; }
    public SipUssiAccessAdapter sipAccess() { return sipAccess; }
    public et.restlink.ussdgw.ra.smpp.SmppEndpointRegistry smppRegistry() { return smppRegistry; }
    public TenantGuard tenantGuard() { return tenantGuard; }
    public AsPullClient asPull() { return asPull; }
    public UssdSagaCoordinator saga() { return saga; }
    public CallbackAuthService callbackAuth() { return callbackAuth; }
    public CampaignService campaigns() { return campaigns; }
    public AsPullRouter asPullRouter() { return asPullRouter; }
}
