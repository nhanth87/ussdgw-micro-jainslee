package et.restlink.ussdgw.service;

import et.restlink.ussdgw.admin.LinkStatusService;
import et.restlink.ussdgw.config.UssdConfigService;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.sipservlet.SipRaConfig;
import com.microjainslee.ra.sipservlet.SipServletRaEndpoint;
import com.microjainslee.ra.sipservlet.SipServletResourceAdaptor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Wire {@code ra-sip-servlet} when {@code ussd.sip.enabled}.
 * Link truth = RA active (listen) for lab; NI still requires active RA to send MESSAGE.
 */
@ApplicationScoped
public class SipApplyService {
    private static final Logger LOG = LogManager.getLogger(SipApplyService.class);

    @Inject MicroSleeContainer container;
    @Inject LinkStatusService linkStatus;
    @Inject UssdConfigService config;

    @ConfigProperty(name = "ussd.sip.host", defaultValue = "0.0.0.0")
    String host;
    @ConfigProperty(name = "ussd.sip.tcp-port", defaultValue = "5060")
    int tcpPort;
    @ConfigProperty(name = "ussd.sip.udp-port", defaultValue = "5060")
    int udpPort;
    @ConfigProperty(name = "ussd.sip.from-uri", defaultValue = "sip:ussdgw@restlink.local")
    String fromUri;
    @ConfigProperty(name = "ussd.sip.auto-apply-on-boot", defaultValue = "true")
    boolean autoApplyOnBoot;

    private volatile SipServletRaEndpoint endpoint;

    public boolean autoApplyOnBoot() {
        return autoApplyOnBoot;
    }

    public String fromUri() {
        return fromUri;
    }

    public String apply() {
        return tearDown() + ";" + wire();
    }

    public String start() {
        if (endpoint != null) return "sip=already";
        return wire();
    }

    public String stop() {
        return tearDown();
    }

    public String tearDown() {
        if (endpoint == null) {
            linkStatus.clearSip();
            return "sip=noop";
        }
        try {
            endpoint.deactivate();
        } catch (RuntimeException e) {
            LOG.warn("sip deactivate: {}", e.toString());
        } finally {
            endpoint = null;
            linkStatus.clearSip();
        }
        return "sip=drained";
    }

    public String wire() {
        if (!config.sipEnabled()) {
            linkStatus.clearSip();
            return "sip=off";
        }
        SipServletResourceAdaptor ra = new SipServletResourceAdaptor();
        SipRaConfig cfg = new SipRaConfig();
        cfg.setHost(host);
        cfg.setTcpPort(tcpPort);
        cfg.setUdpPort(udpPort);
        cfg.setClientEnabled(true);
        SipServletRaEndpoint ep = new SipServletRaEndpoint(ra);
        ep.setConfig(cfg);
        container.registerRa(ep, ep);
        this.endpoint = ep;
        linkStatus.bindSip(ep);
        String d = "sip=wired;listen=" + host + ":" + tcpPort + "/" + udpPort
                + ";active=" + ra.isActive();
        linkStatus.setSipDetail(d);
        LOG.info("SIP apply: {}", d);
        return d;
    }

    public String wireIfEnabled() {
        if (!config.sipEnabled()) {
            return "sip=skipped-disabled";
        }
        return wire();
    }

    public SipServletRaEndpoint endpoint() {
        return endpoint;
    }

    public boolean raActive() {
        SipServletRaEndpoint ep = endpoint;
        return ep != null && ep.delegate() != null && ep.delegate().isActive();
    }
}
