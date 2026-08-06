package et.restlink.ussdgw.bootstrap;

import et.restlink.ussdgw.admin.AdminHttpHandler;
import et.restlink.ussdgw.admin.LinkStatusService;
import et.restlink.ussdgw.bridge.UssdSagaCoordinator;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.service.GrpcApplyService;
import et.restlink.ussdgw.service.HttpApplyService;
import et.restlink.ussdgw.service.SbbRegistrationSupport;
import et.restlink.ussdgw.service.SmppApplyService;
import et.restlink.ussdgw.service.DiameterApplyService;
import et.restlink.ussdgw.service.SipApplyService;
import et.restlink.ussdgw.service.Ss7ApplyService;
import et.restlink.ussdgw.telemetry.AppTelemetry;

import com.microjainslee.core.MicroSleeContainer;

import io.quarkus.runtime.StartupEvent;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class UssdGatewayBootstrap {
    private static final Logger LOG = LogManager.getLogger(UssdGatewayBootstrap.class);

    @Inject MicroSleeContainer container;
    @Inject SbbRegistrationSupport sbbRegistration;
    @Inject AppTelemetry appTelemetry;
    @Inject LinkStatusService linkStatus;
    @Inject Ss7ApplyService ss7Apply;
    @Inject SmppApplyService smppApply;
    @Inject HttpApplyService httpApply;
    @Inject GrpcApplyService grpcApply;
    @Inject DiameterApplyService diameterApply;
    @Inject SipApplyService sipApply;
    @Inject AdminHttpHandler adminHttp;
    @Inject VirtualSessionBridge bridge;
    @Inject UssdSagaCoordinator saga;
    @Inject VirtualSessionStore sessionStore;

    @ConfigProperty(name = "ussd.map.auto-apply-on-boot", defaultValue = "true")
    boolean mapAutoApplyOnBoot;
    @ConfigProperty(name = "smpp.auto-apply-on-boot", defaultValue = "true")
    boolean smppAutoApplyOnBoot;

    void onStart(@Observes StartupEvent ev) {
        teardownPlanes();
        sbbRegistration.unregisterAll();
        if (container.getState() != MicroSleeContainer.State.STARTED) {
            container.start();
        }
        sessionStore.ensureTable();
        appTelemetry.install(container);
        linkStatus.clearSs7();
        linkStatus.clearHttp();
        linkStatus.clearGrpc();
        linkStatus.clearSmpp();
        linkStatus.clearDiameter();
        linkStatus.clearSip();
        bridge.bindSs7(() -> null);
        saga.bindSs7(() -> null);

        sbbRegistration.registerAll();
        httpApply.wire();
        grpcApply.wire();
        wireSs7OnBoot();
        wireSmppOnBoot();
        wireDiameterOnBoot();
        wireSipOnBoot();
        adminHttp.wireRaAdminHub();
        container.createIesDispatcher();
        sbbRegistration.bindEventMappings();

        LOG.info("USSD GW bootstrap complete");
    }

    private void wireSs7OnBoot() {
        if (!mapAutoApplyOnBoot) {
            LOG.info("SS7 auto-apply on boot disabled");
            return;
        }
        try {
            String detail = ss7Apply.wireIfConfigured();
            if (ss7Apply.endpoint() != null) {
                bridge.bindSs7(ss7Apply::endpoint);
                saga.bindSs7(ss7Apply::endpoint);
            }
            LOG.info("SS7 boot: {}", detail);
        } catch (RuntimeException ex) {
            LOG.warn("SS7 boot wire failed (lab may run without MAP): {}", ex.getMessage());
            bridge.bindSs7(() -> null);
            saga.bindSs7(() -> null);
        }
    }

    private void wireDiameterOnBoot() {
        if (!diameterApply.autoApplyOnBoot()) {
            LOG.info("Diameter auto-apply on boot disabled");
            return;
        }
        try {
            LOG.info("Diameter boot: {}", diameterApply.wireIfEnabled());
        } catch (RuntimeException ex) {
            LOG.warn("Diameter boot wire failed: {}", ex.getMessage());
        }
    }

    private void wireSipOnBoot() {
        if (!sipApply.autoApplyOnBoot()) {
            LOG.info("SIP auto-apply on boot disabled");
            return;
        }
        try {
            LOG.info("SIP boot: {}", sipApply.wireIfEnabled());
        } catch (RuntimeException ex) {
            LOG.warn("SIP boot wire failed: {}", ex.getMessage());
        }
    }

    private void wireSmppOnBoot() {
        if (!smppAutoApplyOnBoot) {
            LOG.info("SMPP auto-apply on boot disabled");
            return;
        }
        try {
            smppApply.wireOnBoot();
        } catch (RuntimeException ex) {
            LOG.warn("SMPP boot wire failed: {}", ex.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        adminHttp.clearRaAdminHub();
        appTelemetry.close();
        teardownPlanes();
        bridge.bindSs7(() -> null);
        saga.bindSs7(() -> null);
    }

    private void teardownPlanes() {
        httpApply.tearDown();
        grpcApply.tearDown();
        smppApply.teardown();
        ss7Apply.tearDown();
        diameterApply.tearDown();
        sipApply.tearDown();
    }
}
