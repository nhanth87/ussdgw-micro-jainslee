package et.restlink.ussdgw.service;

import et.restlink.ussdgw.admin.LinkStatusService;
import et.restlink.ussdgw.config.RuntimeConfigStore;
import et.restlink.ussdgw.config.SmppConfigDocument;
import et.restlink.ussdgw.config.SmppConfigSupport;
import et.restlink.ussdgw.ra.smpp.SmppEndpointRegistry;

import com.microjainslee.core.MicroSleeContainer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/** SMPP Apply — drain/reload local ESME server (+ optional SMSC client). */
@ApplicationScoped
public class SmppApplyService {
    private static final Logger LOG = LogManager.getLogger(SmppApplyService.class);

    @Inject MicroSleeContainer container;
    @Inject SmppEndpointRegistry smppRegistry;
    @Inject LinkStatusService linkStatus;
    @Inject RuntimeConfigStore store;
    @Inject SmppConfigSupport smppConfig;

    @ConfigProperty(name = "smpp.host", defaultValue = "127.0.0.1")
    String smppHost;
    @ConfigProperty(name = "smpp.port", defaultValue = "2775")
    int smppPort;
    @ConfigProperty(name = "smpp.system-id", defaultValue = "restlink")
    String smppSystemId;
    @ConfigProperty(name = "smpp.password", defaultValue = "password")
    String smppPassword;
    @ConfigProperty(name = "smpp.system-type")
    Optional<String> smppSystemType;
    @ConfigProperty(name = "smpp.source-addr", defaultValue = "RESTLINK")
    String smppSourceAddr;
    @ConfigProperty(name = "smpp.client.enabled", defaultValue = "false")
    boolean smppClientEnabled;
    @ConfigProperty(name = "smpp.server.enabled", defaultValue = "true")
    boolean smppServerEnabled;
    @ConfigProperty(name = "smpp.server.port", defaultValue = "2776")
    int smppServerPort;
    @ConfigProperty(name = "smpp.server.system-id", defaultValue = "ussdgw")
    String smppServerSystemId;
    @ConfigProperty(name = "smpp.server.password", defaultValue = "password")
    String smppServerPassword;

    public String apply() {
        if (smppRegistry.server() != null) {
            try {
                smppRegistry.server().sessions().clear();
            } catch (RuntimeException ignore) { }
        }
        var fb = fallback();
        SmppConfigDocument doc = smppConfig.loadActiveOrNull();
        smppRegistry.apply(container, doc, fb);
        String detail = "smpp=wired;clients=" + smppRegistry.clients().size()
                + ";server=" + (smppRegistry.server() != null ? "on:" + fb.serverPort() : "off")
                + ";source=" + (doc != null ? "json" : "props");
        linkStatus.bindSmpp(smppRegistry, detail);
        LOG.info("SMPP apply: {}", detail);
        return detail;
    }

    public void wireOnBoot() {
        apply();
    }

    public String start() {
        return apply();
    }

    public String stop() {
        return teardown();
    }

    public String teardown() {
        smppRegistry.teardown(container);
        linkStatus.clearSmpp();
        return "smpp-drained=ok";
    }

    public SmppEndpointRegistry.FallbackProps fallback() {
        return new SmppEndpointRegistry.FallbackProps(
                store.getOr(RuntimeConfigStore.Keys.SMPP_HOST, smppHost),
                store.getInt(RuntimeConfigStore.Keys.SMPP_PORT, smppPort),
                store.getOr(RuntimeConfigStore.Keys.SMPP_SYSTEM_ID, smppSystemId),
                store.getOr(RuntimeConfigStore.Keys.SMPP_PASSWORD, smppPassword),
                smppSystemType.orElse(""),
                store.getOr(RuntimeConfigStore.Keys.SMPP_SOURCE, smppSourceAddr),
                store.getBool(RuntimeConfigStore.Keys.SMPP_CLIENT_ENABLED, smppClientEnabled),
                store.getBool(RuntimeConfigStore.Keys.SMPP_SERVER_ENABLED, smppServerEnabled),
                store.getInt(RuntimeConfigStore.Keys.SMPP_SERVER_PORT, smppServerPort),
                store.getOr(RuntimeConfigStore.Keys.SMPP_SERVER_SYSTEM_ID, smppServerSystemId),
                store.getOr(RuntimeConfigStore.Keys.SMPP_SERVER_PASSWORD, smppServerPassword));
    }

    public SmppEndpointRegistry registry() {
        return smppRegistry;
    }
}
