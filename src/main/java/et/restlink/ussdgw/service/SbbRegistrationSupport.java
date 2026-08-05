package et.restlink.ussdgw.service;

import et.restlink.ussdgw.events.NiPushReadyEvent;
import et.restlink.ussdgw.events.NiPushRequestEvent;
import et.restlink.ussdgw.events.PullGrpcEvent;
import et.restlink.ussdgw.events.PullHttpEvent;
import et.restlink.ussdgw.sbbs.GrpcClientSbb;
import et.restlink.ussdgw.sbbs.GrpcServerSbb;
import et.restlink.ussdgw.sbbs.HttpClientSbb;
import et.restlink.ussdgw.sbbs.HttpServerSbb;
import et.restlink.ussdgw.sbbs.MapNiPushSbb;
import et.restlink.ussdgw.sbbs.MapUssdParentSbb;
import et.restlink.ussdgw.sbbs.SmppAsIngressSbb;
import et.restlink.ussdgw.sbbs.SriSbb;
import et.restlink.ussdgw.ra.smpp.SmppEndpointRegistry;
import et.restlink.ussdgw.ra.smpp.events.SmppSubmitSmEvent;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.grpc.events.GrpcInvokeResponseEvent;
import com.microjainslee.ra.grpcserver.events.GrpcRequestEvent;
import com.microjainslee.ra.httpclient.events.HttpCallbackCompletedEvent;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;
import com.microjainslee.ra.jss7.event.Ss7MapEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class SbbRegistrationSupport {
    @Inject MicroSleeContainer container;
    @Inject SbbServices sbbServices;
    @Inject SmppEndpointRegistry smppRegistry;

    public void unregisterAll() {
        for (String n : new String[]{
                "MapUssdParentSbb", "HttpClientSbb", "HttpServerSbb",
                "GrpcClientSbb", "GrpcServerSbb", "SriSbb", "MapNiPushSbb",
                "SmppAsIngressSbb"}) {
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
        container.registerSbbType(SmppAsIngressSbb.class,
                () -> new SmppAsIngressSbb(sbbServices, smppRegistry));
    }

    public void bindEventMappings() {
        container.mapEventToSbb(Ss7MapEvent.Service.class, "MapUssdParentSbb");
        container.mapEventToSbb(Ss7MapEvent.Dialog.class, "MapUssdParentSbb");
        container.mapEventToSbb(PullHttpEvent.class, "HttpClientSbb");
        container.mapEventToSbb(HttpCallbackCompletedEvent.class, "HttpClientSbb");
        container.mapEventToSbb(PullGrpcEvent.class, "GrpcClientSbb");
        container.mapEventToSbb(GrpcInvokeResponseEvent.class, "GrpcClientSbb");
        container.mapEventToSbb(HttpWebRequestEvent.class, "HttpServerSbb");
        container.mapEventToSbb(GrpcRequestEvent.class, "GrpcServerSbb");
        container.mapEventToSbb(NiPushRequestEvent.class, "SriSbb");
        container.mapEventToSbb(NiPushReadyEvent.class, "MapNiPushSbb");
        container.mapEventToSbb(SmppSubmitSmEvent.class, "SmppAsIngressSbb");
    }
}
