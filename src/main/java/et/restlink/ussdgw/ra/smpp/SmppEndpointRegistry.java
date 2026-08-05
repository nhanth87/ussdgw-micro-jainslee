package et.restlink.ussdgw.ra.smpp;

import et.restlink.ussdgw.config.SmppConfigDocument;

import com.microjainslee.core.MicroSleeContainer;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Multi SMPP client endpoints + one ESME server with allowlist/TPS.
 */
@ApplicationScoped
public class SmppEndpointRegistry {

    private static final Logger LOG = LogManager.getLogger(SmppEndpointRegistry.class);

    private final Map<String, SmppRaEndpoint> clients = new ConcurrentHashMap<>();
    private volatile SmppServerRaEndpoint server;
    private volatile EsmePolicy esmePolicy = EsmePolicy.open();

    public synchronized void apply(MicroSleeContainer container, SmppConfigDocument doc,
                                   FallbackProps fallback) {
        teardown(container);

        List<SmppConfigDocument.Client> list = doc != null && doc.clients() != null
                ? doc.clients() : List.of();
        boolean wantFallbackClient = list.isEmpty() && fallback != null && fallback.clientEnabled();
        if (list.isEmpty() && fallback != null && !fallback.clientEnabled()) {
            LOG.info("[smpp-registry] SMPP client skipped (smpp.client.enabled=false) — ESME server only");
        }
        if (wantFallbackClient) {
            SmppRaEndpoint ep = new SmppRaEndpoint()
                    .setHost(fallback.host())
                    .setPort(fallback.port())
                    .setSystemId(fallback.systemId())
                    .setPassword(fallback.password())
                    .setSystemType(fallback.systemType() == null ? "" : fallback.systemType())
                    .setSourceAddr(fallback.sourceAddr())
                    .setNetworkId(fallback.clientNetworkId());
            container.registerRa(ep, ep);
            clients.put("default", ep);
            LOG.info("[smpp-registry] client default → {}:{} networkId={}",
                    fallback.host(), fallback.port(), fallback.clientNetworkId());
        } else if (!list.isEmpty()) {
            for (SmppConfigDocument.Client c : list) {
                if (Boolean.FALSE.equals(c.enabled())) {
                    continue;
                }
                String name = c.name() == null || c.name().isBlank() ? c.systemId() : c.name();
                String raName = clients.isEmpty() ? "smpp-ra" : ("smpp-client-" + name);
                SmppRaEndpoint ep = new SmppRaEndpoint(raName)
                        .setHost(c.host())
                        .setPort(c.port())
                        .setSystemId(c.systemId())
                        .setPassword(c.password())
                        .setSystemType(c.systemType() == null ? "" : c.systemType())
                        .setSourceAddr(c.sourceAddr() == null ? "RESTLINK" : c.sourceAddr())
                        .setNetworkId(c.networkId());
                container.registerRa(ep, ep);
                clients.put(name, ep);
                LOG.info("[smpp-registry] client {} ra={} → {}:{} networkId={}",
                        name, raName, c.host(), c.port(), c.networkId());
            }
        }

        boolean serverOn = doc != null && doc.server() != null
                ? !Boolean.FALSE.equals(doc.server().enabled())
                : (fallback != null && fallback.serverEnabled());
        if (serverOn) {
            int port = doc != null && doc.server() != null ? doc.server().port()
                    : fallback.serverPort();
            String sid = doc != null && doc.server() != null ? doc.server().systemId()
                    : fallback.serverSystemId();
            String pw = doc != null && doc.server() != null ? doc.server().password()
                    : fallback.serverPassword();
            int networkId = doc != null && doc.server() != null ? doc.server().networkId()
                    : fallback.serverNetworkId();
            server = new SmppServerRaEndpoint()
                    .setPort(port)
                    .setSystemId(sid)
                    .setPassword(pw)
                    .setNetworkId(networkId)
                    .setRequirePassword(ConfigProvider.getConfig()
                            .getOptionalValue("smpp.server.require-password", Boolean.class)
                            .orElse(true));
            esmePolicy = EsmePolicy.from(doc != null ? doc.server() : null);
            if (esmePolicy.allowlistSize() > 0) {
                server.setBindAuthenticator(esmePolicy::authenticate);
            }
            container.registerRa(server, server);
            LOG.info("[smpp-registry] server :{} systemId={} networkId={} allowlist={}",
                    port, sid, networkId, esmePolicy.allowlistSize());
        }
    }

    public synchronized void teardown(MicroSleeContainer container) {
        for (SmppRaEndpoint ep : new ArrayList<>(clients.values())) {
            try {
                ep.deactivate();
            } catch (RuntimeException re) {
                LOG.warn("smpp client deactivate: {}", re.getMessage());
            }
        }
        clients.clear();
        if (server != null) {
            try {
                server.deactivate();
            } catch (RuntimeException re) {
                LOG.warn("smpp server deactivate: {}", re.getMessage());
            }
            server = null;
        }
    }

    public SmppRaEndpoint client(String name) {
        return clients.get(name);
    }

    public SmppRaEndpoint anyClient() {
        return clients.values().stream().findFirst().orElse(null);
    }

    public Map<String, SmppRaEndpoint> clients() {
        return Map.copyOf(clients);
    }

    public SmppServerRaEndpoint server() {
        return server;
    }

    public EsmePolicy esmePolicy() {
        return esmePolicy;
    }

    /** Returns false if ESME is denied or over TPS. */
    public boolean admitEsme(String systemId) {
        return esmePolicy.admit(systemId);
    }

    public Optional<EsmeBinding> esmeBinding(String systemId) {
        return esmePolicy.binding(systemId);
    }

    public record EsmeBinding(String systemId, String tenantId, int networkId, int maxTps,
                              String password) {}

    /**
     * Constant-time compare for bind credentials. {@code String.equals} short-circuits on the
     * first differing byte, which leaks the shared secret one character at a time to a peer
     * that can time its binds.
     */
    public static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    public record FallbackProps(
            String host, int port, String systemId, String password,
            String systemType, String sourceAddr, int clientNetworkId,
            boolean clientEnabled,
            boolean serverEnabled, int serverPort, String serverSystemId, String serverPassword,
            int serverNetworkId) {

        /** Backward-compatible ctor — client on, networkId defaults to 0. */
        public FallbackProps(
                String host, int port, String systemId, String password,
                String systemType, String sourceAddr,
                boolean serverEnabled, int serverPort, String serverSystemId, String serverPassword) {
            this(host, port, systemId, password, systemType, sourceAddr, 0, true,
                    serverEnabled, serverPort, serverSystemId, serverPassword, 0);
        }

        public FallbackProps(
                String host, int port, String systemId, String password,
                String systemType, String sourceAddr,
                boolean clientEnabled,
                boolean serverEnabled, int serverPort, String serverSystemId, String serverPassword) {
            this(host, port, systemId, password, systemType, sourceAddr, 0, clientEnabled,
                    serverEnabled, serverPort, serverSystemId, serverPassword, 0);
        }
    }

    public static final class EsmePolicy {
        private final Map<String, EsmeBinding> bindings; // empty = open
        private final Map<String, Window> windows = new ConcurrentHashMap<>();

        private EsmePolicy(Map<String, EsmeBinding> bindings) {
            this.bindings = bindings;
        }

        static EsmePolicy open() {
            return new EsmePolicy(Map.of());
        }

        static EsmePolicy from(SmppConfigDocument.Server server) {
            if (server == null || server.esmeAllowlist() == null || server.esmeAllowlist().isEmpty()) {
                return open();
            }
            int serverNet = server.networkId();
            Map<String, EsmeBinding> m = new ConcurrentHashMap<>();
            for (SmppConfigDocument.Esme e : server.esmeAllowlist()) {
                if (e.systemId() != null) {
                    String tid = e.tenantId() == null || e.tenantId().isBlank()
                            ? e.systemId() : e.tenantId();
                    int net = e.networkId() != null ? e.networkId() : serverNet;
                    int tps = Math.max(1, e.maxTps() <= 0 ? 100 : e.maxTps());
                    m.put(e.systemId(), new EsmeBinding(e.systemId(), tid, net, tps, e.password()));
                }
            }
            return new EsmePolicy(Map.copyOf(m));
        }

        int allowlistSize() {
            return bindings.size();
        }

        /**
         * Empty allowlist = open (any systemId, server credentials still apply). With an
         * allowlist the {@code systemId} must be listed <em>and</em> present the configured
         * password.
         *
         * <p>A blank allowlist password used to mean "accept anything", which made the whole
         * bind fail-open for that ESME — and a bind is what decides whose delivery receipts a
         * peer receives. {@code OtaConfigValidator} rejects blank passwords at save/apply time;
         * this is the runtime half of the same rule, for documents that predate it.
         */
        static boolean authenticate(Map<String, EsmeBinding> bindings, String systemId,
                                    String password) {
            if (bindings.isEmpty()) {
                return true;
            }
            EsmeBinding b = bindings.get(systemId);
            if (b == null) {
                return false;
            }
            if (b.password() == null || b.password().isBlank()) {
                LOG.warn("[smpp-registry] refusing bind systemId={} — allowlist entry has no "
                        + "password (fix the SMPP config document)", systemId);
                return false;
            }
            return constantTimeEquals(b.password(), password);
        }

        boolean authenticate(String systemId, String password) {
            return authenticate(bindings, systemId, password);
        }

        Optional<EsmeBinding> binding(String systemId) {
            if (systemId == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(bindings.get(systemId));
        }

        boolean admit(String systemId) {
            if (bindings.isEmpty()) {
                return true;
            }
            EsmeBinding b = bindings.get(systemId);
            if (b == null) {
                return false;
            }
            Window w = windows.computeIfAbsent(systemId, k -> new Window());
            return w.tryAcquire(b.maxTps());
        }

        private static final class Window {
            private final AtomicInteger count = new AtomicInteger();
            private volatile long second = System.currentTimeMillis() / 1000L;

            synchronized boolean tryAcquire(int limit) {
                long now = System.currentTimeMillis() / 1000L;
                if (now != second) {
                    second = now;
                    count.set(0);
                }
                if (count.get() >= limit) {
                    return false;
                }
                count.incrementAndGet();
                return true;
            }
        }
    }
}
