package et.restlink.ussdgw.service;

import et.restlink.ussdgw.admin.LinkStatusService;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.hlr.DiameterLocationClient;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.diameter.DiameterRaConfig;
import com.microjainslee.ra.diameter.DiameterRaEndpoint;
import com.microjainslee.ra.diameter.DiameterResourceAdaptor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Wire {@code ra-diameter} when {@code ussd.diameter.enabled}.
 * Link truth = {@link DiameterRaEndpoint#isPeerReady()} (CER/CEA), never LISTEN alone.
 * Listen/peer props overlay boot {@code @ConfigProperty} via {@link UssdConfigService}.
 */
@ApplicationScoped
public class DiameterApplyService {
    private static final Logger LOG = LogManager.getLogger(DiameterApplyService.class);

    @Inject MicroSleeContainer container;
    @Inject LinkStatusService linkStatus;
    @Inject UssdConfigService config;
    @Inject DiameterLocationClient diameterLocation;

    @ConfigProperty(name = "ussd.diameter.auto-apply-on-boot", defaultValue = "true")
    boolean autoApplyOnBoot;

    private volatile DiameterRaEndpoint endpoint;

    public boolean autoApplyOnBoot() {
        return autoApplyOnBoot;
    }

    public String host() {
        return config.diameterHost();
    }

    public int port() {
        return config.diameterPort();
    }

    public String realm() {
        return config.diameterRealm();
    }

    public String originHost() {
        return config.diameterOriginHost();
    }

    public String apply() {
        return tearDown() + ";" + wire();
    }

    public String start() {
        if (endpoint != null) return "diameter=already";
        return wire();
    }

    public String stop() {
        return tearDown();
    }

    public String tearDown() {
        if (endpoint == null) {
            linkStatus.clearDiameter();
            diameterLocation.setDiameterPortSupplier(() -> null);
            return "diameter=noop";
        }
        try {
            endpoint.deactivate();
        } catch (RuntimeException e) {
            LOG.warn("diameter deactivate: {}", e.toString());
        } finally {
            endpoint = null;
            linkStatus.clearDiameter();
            diameterLocation.setDiameterPortSupplier(() -> null);
        }
        return "diameter=drained";
    }

    public String wire() {
        if (!config.diameterEnabled()) {
            linkStatus.clearDiameter();
            diameterLocation.setDiameterPortSupplier(() -> null);
            return "diameter=off";
        }
        String host = config.diameterHost();
        int port = config.diameterPort();
        String realm = config.diameterRealm();
        String originHost = config.diameterOriginHost();
        DiameterResourceAdaptor ra = new DiameterResourceAdaptor();
        DiameterRaConfig cfg = new DiameterRaConfig();
        cfg.setHost(host);
        cfg.setPort(port);
        cfg.setRealm(realm);
        cfg.setOriginHost(originHost);
        DiameterRaEndpoint ep = new DiameterRaEndpoint(ra);
        ep.setConfig(cfg);
        container.registerRa(ep, ep);
        this.endpoint = ep;
        diameterLocation.setDiameterPortSupplier(this::endpoint);
        linkStatus.bindDiameter(ep);
        String d = "diameter=wired;listen=" + host + ":" + port + ";realm=" + realm
                + ";peerReady=" + ep.isPeerReady();
        linkStatus.setDiameterDetail(d);
        LOG.info("Diameter apply: {}", d);
        return d;
    }

    public String wireIfEnabled() {
        if (!config.diameterEnabled()) {
            return "diameter=skipped-disabled";
        }
        return wire();
    }

    public DiameterRaEndpoint endpoint() {
        return endpoint;
    }

    public boolean peerReady() {
        DiameterRaEndpoint ep = endpoint;
        return ep != null && ep.isPeerReady();
    }
}
