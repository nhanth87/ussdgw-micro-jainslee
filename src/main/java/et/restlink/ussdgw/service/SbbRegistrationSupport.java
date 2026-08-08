package et.restlink.ussdgw.service;

import et.restlink.ussdgw.access.DiameterUssdAccessAdapter;
import et.restlink.ussdgw.access.SipUssiAccessAdapter;
import et.restlink.ussdgw.events.GatedAsNotifyEvent;
import et.restlink.ussdgw.events.InboundSriSmEvent;
import et.restlink.ussdgw.events.Map2MapRequestEvent;
import et.restlink.ussdgw.events.NiPushReadyEvent;
import et.restlink.ussdgw.events.NiPushRequestEvent;
import et.restlink.ussdgw.events.PullGrpcEvent;
import et.restlink.ussdgw.events.PullHttpEvent;
import et.restlink.ussdgw.sbbs.DiameterUssdSbb;
import et.restlink.ussdgw.sbbs.GrpcClientSbb;
import et.restlink.ussdgw.sbbs.GrpcServerSbb;
import et.restlink.ussdgw.sbbs.HlrResponderSbb;
import et.restlink.ussdgw.sbbs.HttpClientSbb;
import et.restlink.ussdgw.sbbs.HttpServerSbb;
import et.restlink.ussdgw.sbbs.Map2MapSbb;
import et.restlink.ussdgw.sbbs.MapNiPushSbb;
import et.restlink.ussdgw.sbbs.MapUssdParentSbb;
import et.restlink.ussdgw.sbbs.SipUssiSbb;
import et.restlink.ussdgw.sbbs.SmppAsIngressSbb;
import et.restlink.ussdgw.sbbs.SriSbb;
import et.restlink.ussdgw.ra.smpp.SmppEndpointRegistry;
import et.restlink.ussdgw.ra.smpp.events.SmppSubmitSmEvent;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import com.microjainslee.ra.grpc.events.GrpcInvokeResponseEvent;
import com.microjainslee.ra.grpcserver.events.GrpcRequestEvent;
import com.microjainslee.ra.httpclient.events.HttpCallbackCompletedEvent;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;
import com.microjainslee.ra.jss7.event.Ss7MapEvent;
import com.microjainslee.ra.sipservlet.events.SipMessageEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class SbbRegistrationSupport {
    @Inject MicroSleeContainer container;
    @Inject SbbServices sbbServices;
    @Inject SmppEndpointRegistry smppRegistry;
    @Inject DiameterUssdAccessAdapter diameterAccess;
    @Inject SipUssiAccessAdapter sipAccess;

    public void unregisterAll() {
        for (String n : new String[]{
                "MapUssdParentSbb", "HttpClientSbb", "HttpServerSbb",
                "GrpcClientSbb", "GrpcServerSbb", "SriSbb", "MapNiPushSbb", "Map2MapSbb",
                "SmppAsIngressSbb", "HlrResponderSbb",
                "DiameterUssdSbb", "SipUssiSbb"}) {
            container.getSbbTypeRegistry().unregisterByName(n);
        }
    }

    public void registerAll() {
        container.registerSbbType(MapUssdParentSbb.class, () -> new MapUssdParentSbb(sbbServices));
        container.registerSbbType(HttpClientSbb.class, () -> new HttpClientSbb(sbbServices));
        container.registerSbbType(HttpServerSbb.class, () -> new HttpServerSbb(sbbServices));
        container.registerSbbType(GrpcClientSbb.class, () -> new GrpcClientSbb(sbbServices));
        container.registerSbbType(GrpcServerSbb.class, () -> new GrpcServerSbb(sbbServices));
        container.registerSbbType(SriSbb.class, () -> new SriSbb(sbbServices));
        container.registerSbbType(MapNiPushSbb.class, () -> new MapNiPushSbb(sbbServices));
        container.registerSbbType(Map2MapSbb.class, () -> new Map2MapSbb(sbbServices));
        container.registerSbbType(HlrResponderSbb.class, () -> new HlrResponderSbb(sbbServices));
        container.registerSbbType(SmppAsIngressSbb.class,
                () -> new SmppAsIngressSbb(sbbServices, smppRegistry));
        container.registerSbbType(DiameterUssdSbb.class,
                () -> new DiameterUssdSbb(sbbServices, diameterAccess));
        container.registerSbbType(SipUssiSbb.class,
                () -> new SipUssiSbb(sbbServices, sipAccess));
    }

    public void bindEventMappings() {
        container.mapEventToSbb(Ss7MapEvent.Service.class, "MapUssdParentSbb");
        container.mapEventToSbb(Ss7MapEvent.Dialog.class, "MapUssdParentSbb");
        container.mapEventToSbb(PullHttpEvent.class, "HttpClientSbb");
        container.mapEventToSbb(GatedAsNotifyEvent.class, "HttpClientSbb");
        container.mapEventToSbb(HttpCallbackCompletedEvent.class, "HttpClientSbb");
        container.mapEventToSbb(PullGrpcEvent.class, "GrpcClientSbb");
        container.mapEventToSbb(GrpcInvokeResponseEvent.class, "GrpcClientSbb");
        container.mapEventToSbb(HttpWebRequestEvent.class, "HttpServerSbb");
        container.mapEventToSbb(GrpcRequestEvent.class, "GrpcServerSbb");
        container.mapEventToSbb(NiPushRequestEvent.class, "SriSbb");
        container.mapEventToSbb(NiPushReadyEvent.class, "MapNiPushSbb");
        container.mapEventToSbb(Map2MapRequestEvent.class, "Map2MapSbb");
        container.mapEventToSbb(InboundSriSmEvent.class, "HlrResponderSbb");
        container.mapEventToSbb(SmppSubmitSmEvent.class, "SmppAsIngressSbb");
        container.mapEventToSbb(DiameterRequestEvent.class, "DiameterUssdSbb");
        container.mapEventToSbb(SipMessageEvent.class, "SipUssiSbb");
    }
}
