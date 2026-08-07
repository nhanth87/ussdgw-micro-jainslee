package et.restlink.ussdgw.hlr;

import et.restlink.ussdgw.config.RuntimeConfigStore;
import et.restlink.ussdgw.config.UssdConfigService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Resolves HLR face mode + fake/upper addresses from config.
 * Default mode {@link HlrResolveMode#PROXY_MAP} — FAKE only when ops explicitly set.
 */
@ApplicationScoped
public class HlrResolvePolicy {
    @Inject UssdConfigService config;

    public HlrResolveMode mode() {
        return config.hlrMode();
    }

    public HlrResolveMode modeFor(int networkId, String msisdn) {
        // Per-network override: ussd.hlr.network.<id>.mode
        String key = RuntimeConfigStore.Keys.HLR_MODE_NETWORK_PREFIX + networkId;
        return config.store().get(key).map(HlrResolveMode::parse).orElseGet(this::mode);
    }

    public String fakeImsi() {
        return config.hlrFakeImsi();
    }

    public String fakeMscGt() {
        return config.hlrFakeMscGt();
    }

    /**
     * Resolved upper HLR GT for outbound SRI-SM CalledParty.
     * Non-blank admin KV overlay wins; blank/missing overlay falls back to
     * {@code application.properties} / {@code @ConfigProperty ussd.hlr.upper-gt}.
     */
    public String upperHlrGt() {
        return config.hlrUpperGt();
    }

    /**
     * True when the <em>resolved</em> upper GT is blank/unusable or equals local USSD GT
     * (or configured fake MSC) — loop risk. Empty admin overlay alone is not a fail if props
     * supply a usable default.
     */
    public boolean upperWouldLoop(String upperGt) {
        if (upperGt == null || upperGt.isBlank()) {
            return true;
        }
        String u = digits(upperGt);
        if (u.isEmpty()) {
            return true;
        }
        String local = digits(config.ussdGt());
        String fakeMsc = digits(fakeMscGt());
        return (!local.isEmpty() && u.equals(local))
                || (!fakeMsc.isEmpty() && u.equals(fakeMsc));
    }

    public boolean canFake() {
        String imsi = fakeImsi();
        String msc = fakeMscGt();
        return imsi != null && !imsi.isBlank() && msc != null && !msc.isBlank();
    }

    private static String digits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') b.append(c);
        }
        return b.toString();
    }
}
