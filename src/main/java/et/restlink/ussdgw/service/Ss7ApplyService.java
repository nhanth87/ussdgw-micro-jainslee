package et.restlink.ussdgw.service;

import et.restlink.ussdgw.admin.LinkStatusService;
import et.restlink.ussdgw.bridge.UssdSagaCoordinator;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.config.RuntimeConfigStore;
import et.restlink.ussdgw.config.Ss7PersistDirs;
import et.restlink.ussdgw.hlr.PendingHlrProxyRegistry;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.jss7.Ss7RaConfig;
import com.microjainslee.ra.jss7.Ss7RaEndpoint;
import com.microjainslee.ra.jss7.Ss7ResourceAdaptor;
import com.microjainslee.ra.jss7.admin.Ss7AdminBindings;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.restcomm.protocols.ss7.config.Ss7Config;
import org.restcomm.protocols.ss7.config.Ss7ConfigLoader;

@ApplicationScoped
public class Ss7ApplyService {
    private static final Logger LOG = LogManager.getLogger(Ss7ApplyService.class);

    @Inject MicroSleeContainer container;
    @Inject LinkStatusService linkStatus;
    @Inject VirtualSessionBridge bridge;
    @Inject UssdSagaCoordinator saga;
    @Inject PendingHlrProxyRegistry pendingHlrProxy;
    @Inject PendingMap2MapRegistry pendingMap2Map;
    @Inject RuntimeConfigStore store;

    @ConfigProperty(name = "ussd.map.enabled", defaultValue = "false")
    boolean mapEnabledProp;
    @ConfigProperty(name = "ussd.map.config-file")
    Optional<String> mapConfigFileProp;
    @ConfigProperty(name = "ussd.map.host-ip", defaultValue = "127.0.0.1")
    String mapHostIpProp;
    @ConfigProperty(name = "ussd.map.host-port", defaultValue = "8013")
    int mapHostPortProp;
    @ConfigProperty(name = "ussd.map.peer-ip", defaultValue = "127.0.0.1")
    String mapPeerIpProp;
    @ConfigProperty(name = "ussd.map.peer-port", defaultValue = "8014")
    int mapPeerPortProp;
    @ConfigProperty(name = "ussd.map.opc", defaultValue = "1")
    int mapOpcProp;
    @ConfigProperty(name = "ussd.map.dpc", defaultValue = "2")
    int mapDpcProp;
    @ConfigProperty(name = "ussd.map.ip-channel-type", defaultValue = "TCP")
    String mapChannelProp;
    @ConfigProperty(name = "ussd.ss7.persist-dir", defaultValue = "configs/ss7-persist")
    String ss7PersistDirProp;

    private volatile Ss7RaEndpoint ss7Endpoint;
    private volatile Ss7ResourceAdaptor ss7Ra;

    public String apply() {
        return tearDown() + ";" + wireIfConfigured();
    }

    public String stop() {
        linkStatus.markSs7Stopped();
        return tearDown();
    }

    public String start() {
        if (ss7Endpoint != null) return apply();
        return wireIfConfigured();
    }

    public String tearDown() {
        linkStatus.clearSs7();
        bridge.bindSs7(() -> null);
        saga.bindSs7(() -> null);
        pendingHlrProxy.bindSs7(() -> null);
        pendingMap2Map.bindSs7(() -> null);
        if (ss7Endpoint == null) return "ss7-drained=noop";
        try {
            ss7Endpoint.deactivate();
            return "ss7-drained=ok";
        } catch (RuntimeException re) {
            LOG.warn("ra-jss7 deactivate: {}", re.getMessage());
            return "ss7-drained=warn";
        } finally {
            ss7Endpoint = null;
            ss7Ra = null;
            Ss7AdminBindings.clear();
        }
    }

    public boolean mapEnabled() {
        return store.getBool(RuntimeConfigStore.Keys.MAP_ENABLED, mapEnabledProp);
    }

    public String hostIp() { return store.getOr(RuntimeConfigStore.Keys.MAP_HOST_IP, mapHostIpProp); }
    public int hostPort() { return store.getInt(RuntimeConfigStore.Keys.MAP_HOST_PORT, mapHostPortProp); }
    public String peerIp() { return store.getOr(RuntimeConfigStore.Keys.MAP_PEER_IP, mapPeerIpProp); }
    public int peerPort() { return store.getInt(RuntimeConfigStore.Keys.MAP_PEER_PORT, mapPeerPortProp); }
    public int opc() { return store.getInt(RuntimeConfigStore.Keys.MAP_OPC, mapOpcProp); }
    public int dpc() { return store.getInt(RuntimeConfigStore.Keys.MAP_DPC, mapDpcProp); }
    public String channel() { return store.getOr(RuntimeConfigStore.Keys.MAP_CHANNEL, mapChannelProp); }
    public String persistDir() { return store.getOr(RuntimeConfigStore.Keys.SS7_PERSIST, ss7PersistDirProp); }

    public String configFile() {
        return store.get(RuntimeConfigStore.Keys.MAP_CONFIG_FILE)
                .orElse(mapConfigFileProp.orElse(""));
    }

    public String wireIfConfigured() {
        String cfgFile = configFile();
        boolean haveFile = cfgFile != null && !cfgFile.isBlank();
        if (!mapEnabled() && !haveFile) {
            linkStatus.setSs7AppliedDetail("ss7=skipped(no-config)");
            return "ss7=skipped(no-config;set-ussd.map.enabled-or-config-file)";
        }
        try {
            Ss7Config full = resolveSs7Config();
            if (full != null) {
                warnSctpIpspRoleMismatch(full);
            }
            wireSs7Ra(full);
            if (ss7Ra == null || !ss7Ra.isActive()) {
                throw new IllegalStateException("ra-jss7 registered but not active");
            }
            String detail = formatWiredDetail(
                    haveFile,
                    full,
                    hostIp(), hostPort(), peerIp(), peerPort());
            linkStatus.setSs7AppliedDetail(detail);
            return detail;
        } catch (RuntimeException ex) {
            linkStatus.setSs7AppliedDetail("ss7=error:" + ex.getMessage());
            throw ex;
        }
    }

    /**
     * Applied-detail for {@code ss7.detail} / admin plane.
     * When a config-file loads, summarize real SCTP endpoints + M3UA AS (ipsp + RC) —
     * never the props fallback {@code 127.0.0.1:8013→8014}.
     * Arrow: {@code local←peer} = SCTP server (peer INIT); {@code local→peer} = SCTP client.
     */
    static String formatWiredDetail(boolean configFileConfigured, Ss7Config cfg,
                                    String hostIp, int hostPort, String peerIp, int peerPort) {
        String propsLinks = hostIp + ":" + hostPort + "→" + peerIp + ":" + peerPort;
        if (configFileConfigured && cfg == null) {
            return "ss7=wired;source=file-missing;links=[" + propsLinks + "]";
        }
        if (cfg == null || cfg.sctp() == null || cfg.sctp().links() == null
                || cfg.sctp().links().isEmpty()) {
            return "ss7=wired;source=props;links=[" + propsLinks + "]";
        }
        List<String> sctpParts = new ArrayList<>();
        for (Ss7Config.Link link : cfg.sctp().links()) {
            if (link == null) {
                continue;
            }
            String type = link.type() == null ? "?" : link.type().toLowerCase(Locale.ROOT);
            String arrow = "server".equals(type) ? "←" : "→";
            String name = link.name() == null ? "?" : link.name();
            String local = link.local() == null ? "?" : link.local();
            String peer = link.peer() == null ? "?" : link.peer();
            sctpParts.add(name + ":" + type + ":" + local + arrow + peer);
        }
        List<String> m3uaParts = new ArrayList<>();
        if (cfg.m3ua() != null && cfg.m3ua().as() != null) {
            for (Ss7Config.As as : cfg.m3ua().as()) {
                if (as == null) {
                    continue;
                }
                String ipsp = as.ipsp() == null ? "?" : as.ipsp().toLowerCase(Locale.ROOT);
                String rc = as.routingContext() == null ? "?" : String.valueOf(as.routingContext());
                String name = as.name() == null ? "?" : as.name();
                m3uaParts.add(name + ":ipsp/" + ipsp + "/rc=" + rc);
            }
        }
        StringBuilder sb = new StringBuilder("ss7=wired;source=file;sctp=[")
                .append(String.join(",", sctpParts)).append("]");
        if (!m3uaParts.isEmpty()) {
            sb.append(";m3ua=[").append(String.join(",", m3uaParts)).append("]");
        }
        return sb.toString();
    }

    /** RFC/SP: SCTP association role and M3UA IPSP role must agree per linked ASP. */
    static void warnSctpIpspRoleMismatch(Ss7Config cfg) {
        if (cfg == null || cfg.sctp() == null || cfg.m3ua() == null) {
            return;
        }
        Map<String, String> linkType = new HashMap<>();
        if (cfg.sctp().links() != null) {
            for (Ss7Config.Link link : cfg.sctp().links()) {
                if (link != null && link.name() != null) {
                    linkType.put(link.name(), link.type() == null ? "" : link.type());
                }
            }
        }
        if (cfg.m3ua().as() == null) {
            return;
        }
        for (Ss7Config.As as : cfg.m3ua().as()) {
            if (as == null || as.links() == null) {
                continue;
            }
            String ipsp = as.ipsp() == null ? "" : as.ipsp().toLowerCase(Locale.ROOT);
            if (ipsp.isBlank()) {
                continue;
            }
            for (String linkName : as.links()) {
                String type = linkType.getOrDefault(linkName, "").toLowerCase(Locale.ROOT);
                if (type.isBlank()) {
                    continue;
                }
                if (!type.equals(ipsp)) {
                    LOG.warn("SCTP/M3UA role mismatch: AS {} ipsp={} but link {} type={} "
                                    + "(RFC 4666 IPSP + RFC 4960 association initiator must align)",
                            as.name(), ipsp, linkName, type);
                }
            }
        }
    }

    private void wireSs7Ra(Ss7Config full) {
        Path persist = Ss7PersistDirs.ensureConfigured(persistDir());
        LOG.info("jSS7 persist dir={}", persist);

        Ss7ResourceAdaptor ra = new Ss7ResourceAdaptor();
        if (full != null) {
            ra.setSs7Config(full);
        } else {
            if (!mapEnabled()) {
                throw new IllegalStateException("No SS7 config file and ussd.map.enabled=false");
            }
            Ss7RaConfig cfg = new Ss7RaConfig()
                    .hostIp(hostIp()).hostPort(hostPort())
                    .peerIp(peerIp()).peerPort(peerPort())
                    .originatingPointCode(opc()).destinationPointCode(dpc())
                    .mapEnabled(true).capEnabled(false)
                    .ipChannelType(channel());
            ra.setConfig(cfg);
        }
        ss7Ra = ra;
        ss7Endpoint = new Ss7RaEndpoint(ra);
        container.registerRa(ss7Endpoint, ss7Endpoint);
        if (!ra.isActive()) {
            ss7Endpoint = null;
            ss7Ra = null;
            linkStatus.clearSs7();
            Ss7AdminBindings.clear();
            throw new IllegalStateException("ra-jss7 activate failed (SCTP/M3UA)");
        }
        linkStatus.bindSs7(ra);
        bridge.bindSs7(this::endpoint);
        saga.bindSs7(this::endpoint);
        pendingHlrProxy.bindSs7(this::endpoint);
        pendingMap2Map.bindSs7(this::endpoint);
        Ss7AdminBindings.bind(ss7Endpoint);
    }

    private Ss7Config resolveSs7Config() {
        try {
            String cfgFile = configFile();
            if (cfgFile != null && !cfgFile.isBlank()) {
                Path p = Path.of(cfgFile);
                if (Files.isRegularFile(p)) {
                    return Ss7ConfigLoader.load(p);
                }
                LOG.warn("ussd.map.config-file not found: {}", cfgFile);
            }
        } catch (RuntimeException ex) {
            LOG.error("Ss7Config load failed: {}", ex.getMessage());
        }
        return null;
    }

    public Ss7RaEndpoint endpoint() { return ss7Endpoint; }
}
