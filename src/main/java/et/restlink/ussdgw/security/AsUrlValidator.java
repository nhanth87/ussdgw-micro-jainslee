package et.restlink.ussdgw.security;

import jakarta.enterprise.context.ApplicationScoped;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Guards the operator-supplied {@code asUrl} on a short-code rule against SSRF.
 *
 * <p>A TENANT-role principal can create routing rules, and {@code AsPullClient} later fetches
 * whatever they name and returns the body to the handset as the USSD reply. Without this check
 * that is a read primitive against cloud metadata endpoints ({@code 169.254.169.254}) and any
 * internal service reachable from the gateway.
 *
 * <p>Literal IPs are classified without touching the network so the check never blocks. DNS is
 * only consulted when {@code ussd.as.url.resolve-dns=true}, which an operator may enable to also
 * catch hostnames that resolve into private space.
 */
@ApplicationScoped
public class AsUrlValidator {

    /** Hostnames that mean "this machine" without needing a resolver. */
    private static final Set<String> LOCAL_HOST_NAMES = Set.of(
            "localhost", "localhost.localdomain", "ip6-localhost", "ip6-loopback");

    /** Cloud metadata service names — the classic SSRF target. */
    private static final Set<String> METADATA_HOST_NAMES = Set.of(
            "metadata", "metadata.google.internal", "metadata.goog",
            "instance-data", "instance-data.ec2.internal");

    /**
     * Hosts the tenant may point at even though they are private. The Digicom lab AS runs on
     * {@code http://127.0.0.1:8090/ussd/pull}, so the shipped properties list it here.
     */
    @ConfigProperty(name = "ussd.as.url.host-allowlist", defaultValue = "")
    String hostAllowlist = "";

    /** Blanket opt-out for an isolated lab network. */
    @ConfigProperty(name = "ussd.as.url.allow-private-hosts", defaultValue = "false")
    boolean allowPrivateHosts = false;

    /** Off by default: a resolver lookup on the admin request thread is blocking IO. */
    @ConfigProperty(name = "ussd.as.url.resolve-dns", defaultValue = "false")
    boolean resolveDns = false;

    /** @return the rejection reason, or empty when the URL is acceptable. */
    public Optional<String> reject(String asUrl) {
        return reject(asUrl, parseAllowlist(hostAllowlist), allowPrivateHosts, resolveDns);
    }

    /**
     * SSRF guard for SIP trunk {@code peerHost} (hostname or literal IP — no scheme).
     * Same private / loopback / metadata policy as HTTP {@code asUrl}.
     */
    public Optional<String> rejectSipPeerHost(String peerHost) {
        return rejectSipPeerHost(
                peerHost, parseAllowlist(hostAllowlist), allowPrivateHosts, resolveDns);
    }

    /**
     * SSRF guard for SIP {@code requestUriTemplate} ({@code sip:} / {@code sips:}).
     * Blank is allowed (runtime falls back to peer host). {@code {msisdn}} may appear only in
     * the user part — never in the host.
     */
    public Optional<String> rejectSipRequestUriTemplate(String template) {
        return rejectSipRequestUriTemplate(
                template, parseAllowlist(hostAllowlist), allowPrivateHosts, resolveDns);
    }

    static Optional<String> reject(String asUrl, Set<String> allowlist, boolean allowPrivate,
                                   boolean resolveDns) {
        if (asUrl == null || asUrl.isBlank()) {
            return Optional.of("asUrl required");
        }
        URI uri;
        try {
            uri = URI.create(asUrl.trim());
        } catch (IllegalArgumentException ex) {
            return Optional.of("asUrl is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Optional.of("asUrl scheme must be http or https (got "
                    + (scheme.isEmpty() ? "none" : scheme) + ")");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Optional.of("asUrl has no host");
        }
        return rejectHost(host, allowlist, allowPrivate, resolveDns, "asUrl");
    }

    static Optional<String> rejectSipPeerHost(String peerHost, Set<String> allowlist,
                                              boolean allowPrivate, boolean resolveDns) {
        if (peerHost == null || peerHost.isBlank()) {
            return Optional.of("peerHost required");
        }
        String raw = peerHost.trim();
        if (raw.contains("://") || raw.contains("/") || raw.contains("@") || raw.contains(" ")) {
            return Optional.of("peerHost must be a bare hostname or IP (no URI)");
        }
        String host = extractSipHost(raw);
        if (host.isBlank()) {
            return Optional.of("peerHost required");
        }
        return rejectHost(host, allowlist, allowPrivate, resolveDns, "peerHost");
    }

    static Optional<String> rejectSipRequestUriTemplate(String template, Set<String> allowlist,
                                                        boolean allowPrivate, boolean resolveDns) {
        if (template == null || template.isBlank()) {
            return Optional.empty();
        }
        String tpl = template.trim();
        String lower = tpl.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("sip:") && !lower.startsWith("sips:")) {
            return Optional.of("requestUriTemplate scheme must be sip or sips");
        }
        int at = tpl.indexOf('@');
        if (at < 0 || at + 1 >= tpl.length()) {
            return Optional.of("requestUriTemplate must be sip:user@host (got no host)");
        }
        String afterAt = tpl.substring(at + 1);
        if (afterAt.toLowerCase(Locale.ROOT).contains("{msisdn}")) {
            return Optional.of("requestUriTemplate must not put {msisdn} in the host");
        }
        String host = extractSipHost(afterAt);
        if (host.isBlank()) {
            return Optional.of("requestUriTemplate has no host");
        }
        return rejectHost(host, allowlist, allowPrivate, resolveDns, "requestUriTemplate");
    }

    /** Host-only check shared by HTTP asUrl and SIP requestUriTemplate. */
    static Optional<String> rejectHost(String host, Set<String> allowlist, boolean allowPrivate,
                                       boolean resolveDns, String label) {
        String normalized = normalizeHost(host);
        if (allowlist.contains(normalized)) {
            return Optional.empty();
        }
        if (allowPrivate) {
            return Optional.empty();
        }
        if (METADATA_HOST_NAMES.contains(normalized)) {
            return Optional.of(deny(label, host, "cloud metadata endpoint", allowlist));
        }
        if (LOCAL_HOST_NAMES.contains(normalized)) {
            return Optional.of(deny(label, host, "loopback", allowlist));
        }
        Optional<InetAddress> literal = parseLiteralAddress(normalized);
        if (literal.isPresent()) {
            String why = classify(literal.get());
            return why == null ? Optional.empty() : Optional.of(deny(label, host, why, allowlist));
        }
        if (resolveDns) {
            try {
                for (InetAddress addr : InetAddress.getAllByName(normalized)) {
                    String why = classify(addr);
                    if (why != null) {
                        return Optional.of(deny(label, host,
                                why + " (" + addr.getHostAddress() + ")", allowlist));
                    }
                }
            } catch (UnknownHostException ex) {
                return Optional.of(label + " host " + host + " does not resolve");
            }
        }
        return Optional.empty();
    }

    /** Host from {@code host[:port][;params]} after the {@code @} in a SIP URI. */
    static String extractSipHost(String hostPortParams) {
        if (hostPortParams == null || hostPortParams.isBlank()) {
            return "";
        }
        String h = hostPortParams.trim();
        int semi = h.indexOf(';');
        if (semi > 0) {
            h = h.substring(0, semi);
        }
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            if (close > 1) {
                return h.substring(1, close);
            }
        }
        int colon = h.indexOf(':');
        if (colon > 0) {
            return h.substring(0, colon);
        }
        return h;
    }

    /** @return why the address is off-limits, or null when it is a normal routable host. */
    private static String classify(InetAddress addr) {
        if (addr.isLoopbackAddress()) return "loopback";
        if (addr.isLinkLocalAddress()) return "link-local (cloud metadata range)";
        if (addr.isSiteLocalAddress()) return "private / RFC1918";
        if (addr.isAnyLocalAddress()) return "wildcard";
        if (addr.isMulticastAddress()) return "multicast";
        if (isUniqueLocalIpv6(addr)) return "IPv6 unique-local";
        if (isCarrierGradeNat(addr)) return "carrier-grade NAT (100.64.0.0/10)";
        return null;
    }

    private static boolean isUniqueLocalIpv6(InetAddress addr) {
        byte[] b = addr.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;
    }

    private static boolean isCarrierGradeNat(InetAddress addr) {
        byte[] b = addr.getAddress();
        return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xFF) >= 64 && (b[1] & 0xFF) <= 127;
    }

    private static String deny(String label, String host, String why, Set<String> allowlist) {
        return label + " host " + host + " is " + why
                + " — refused as SSRF. Allow it with ussd.as.url.host-allowlist"
                + (allowlist.isEmpty() ? "" : " (current: " + String.join(",", allowlist) + ")")
                + " or ussd.as.url.allow-private-hosts=true.";
    }

    /** Literal-IP parse only — never a resolver call. */
    private static Optional<InetAddress> parseLiteralAddress(String host) {
        String h = host;
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        try {
            return Optional.of(InetAddress.ofLiteral(h));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static String normalizeHost(String host) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return h;
    }

    static Set<String> parseAllowlist(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        for (String part : csv.split(",")) {
            String p = normalizeHost(part);
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return Set.copyOf(out);
    }
}
