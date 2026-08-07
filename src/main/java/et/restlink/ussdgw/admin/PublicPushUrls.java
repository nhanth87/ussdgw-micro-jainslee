package et.restlink.ussdgw.admin;

/**
 * Builds operator-facing HTTP NI and gRPC push endpoints from
 * {@code ussd.admin.public-base-url} — never publishes bind address {@code 0.0.0.0}.
 */
public final class PublicPushUrls {
    private PublicPushUrls() {}

    /**
     * Normalize public base: trim, strip trailing slash, reject blank / 0.0.0.0 hosts.
     * Returns empty when unusable.
     */
    public static String normalizePublicBase(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            return "";
        }
        String lower = s.toLowerCase();
        if (lower.contains("0.0.0.0") || lower.contains("[::]") || lower.contains("[::0]")) {
            return "";
        }
        // Strip accidental path so we can append ni-path cleanly.
        int scheme = s.indexOf("://");
        if (scheme > 0) {
            int pathStart = s.indexOf('/', scheme + 3);
            if (pathStart > 0) {
                s = s.substring(0, pathStart);
            }
        }
        return s;
    }

    /**
     * HTTP base for AS clients: prefer {@code publicBase}; else {@code http://host:port}
     * when host is a real advertise address (not wildcard).
     */
    public static String publicHttpBase(String publicBase, String listenHost, int listenPort) {
        String base = normalizePublicBase(publicBase);
        if (!base.isEmpty()) {
            return ensureScheme(base);
        }
        String host = listenHost == null ? "" : listenHost.trim();
        if (host.isEmpty() || isWildcard(host) || "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)) {
            return "";
        }
        // Host may already include :port (misconfig) — do not double-append.
        if (hostContainsPort(host)) {
            return "http://" + stripBrackets(host);
        }
        if (listenPort <= 0) {
            return "http://" + stripBrackets(host);
        }
        return "http://" + stripBrackets(host) + ":" + listenPort;
    }

    public static String publicNiPushUrl(String publicBase, String listenHost, int listenPort,
                                         String niPath) {
        String base = publicHttpBase(publicBase, listenHost, listenPort);
        if (base.isEmpty()) {
            return "";
        }
        String path = niPath == null || niPath.isBlank() ? "/ussd" : niPath.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        // If public base already carries a non-default port (or any :port), append path only.
        // If public base has no port and we fell back to listen host without port in base,
        // publicHttpBase already included listenPort when needed.
        return base + path;
    }

    /**
     * gRPC push advertise endpoint: {@code host:grpcPort} (no scheme), or empty when unknown.
     * Host comes from {@code publicBase}; when that URL carries an HTTP listen port it is
     * stripped — gRPC must advertise {@code grpcPort} (default 9099), never the HTTP port.
     */
    public static String publicGrpcPushEndpoint(String publicBase, int grpcPort) {
        String base = normalizePublicBase(publicBase);
        if (base.isEmpty() || grpcPort <= 0) {
            return "";
        }
        String withScheme = ensureScheme(base);
        int scheme = withScheme.indexOf("://");
        String hostPort = scheme > 0 ? withScheme.substring(scheme + 3) : withScheme;
        String host = stripPort(hostPort);
        if (host.isEmpty()) {
            return "";
        }
        return host + ":" + grpcPort;
    }

    /** Host only — drop {@code :port} from {@code host:port} or {@code [ipv6]:port}. */
    static String stripPort(String hostPort) {
        if (hostPort == null || hostPort.isBlank()) {
            return "";
        }
        String h = hostPort.trim();
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            if (end > 1) {
                return h.substring(0, end + 1);
            }
            return h;
        }
        int colon = h.indexOf(':');
        if (colon > 0 && h.indexOf(':', colon + 1) < 0) {
            return h.substring(0, colon);
        }
        return h;
    }

    private static String ensureScheme(String base) {
        String lower = base.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("grpc://")) {
            return base;
        }
        return "http://" + base;
    }

    private static boolean isWildcard(String host) {
        String h = host;
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return "0.0.0.0".equals(h) || "::".equals(h) || "::0".equals(h);
    }

    private static boolean hostContainsPort(String host) {
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end > 0 && end + 1 < host.length() && host.charAt(end + 1) == ':';
        }
        int colon = host.indexOf(':');
        return colon > 0 && host.indexOf(':', colon + 1) < 0;
    }

    private static String stripBrackets(String host) {
        return host;
    }
}
