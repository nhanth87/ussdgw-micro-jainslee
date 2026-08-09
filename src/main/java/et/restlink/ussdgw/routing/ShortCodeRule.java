package et.restlink.ussdgw.routing;

/**
 * Short-code → AS routing rule, optionally scoped to tenant / networkId / app user.
 * {@code mark=true} = prefix key (classic {@code exactMatch=false}): dialed strings that
 * {@code startsWith(shortCode)} route here (e.g. {@code *100*} matches {@code *100*123456#};
 * Ethiopia {@code *101} mark matches both {@code *101#} and {@code *101123456#}).
 * Exact (mark=false) wins over mark on equality; longest mark prefix otherwise.
 * One rule model covers short and long dials — no separate short/long rule types.
 *
 * <p>MAP2MAP re-route: when {@link #rerouteEnable()} and {@link #redirectUssdString()} are set,
 * {@code Map2MapSbb} hops MAP UnstructuredSS-Request with the resolved hop USSD (not SCCP GT)
 * before the HTTP/gRPC/SIP AS pull. {@code mark=true} long dials preserve the suffix after the
 * mark prefix ({@code *804*1234#}+redirect {@code *875*} → hop {@code *875*1234#}); exact short
 * stays literal redirect. Default {@code rerouteEnable=false} = classic direct to {@code asUrl}.
 * {@link #bypass()} is derived as {@code !rerouteEnable} (transition only).
 *
 * <p>{@link #hlrMode()} optional override for NI / HLR face (Case 1): {@code null}/{@code INHERIT}
 * → HLR Face global; {@code FAKE} / {@code PROXY_MAP} / …. <strong>Ignored</strong> for MAP2MAP
 * Case 2 hop (upper-gt / hop_dest only).
 *
 * <p>Case 2 hop ({@link #map2mapArmed()}): optional {@link #hopDestGt()} / {@link #hopDestSsn()}
 * (DB {@code hop_dest_*}). When dest GT is set, CalledParty = that GT+SSN. When blank,
 * CalledParty = HLR Face {@code ussd.hlr.upper-gt} + SSN {@link #DEFAULT_HOP_DEST_SSN} (6)
 * (or hopDestSsn alone as SSN override) — <strong>no</strong> SRI/FAKE→MSC. Case 1 NI SRI
 * ({@code SriSbb}) is separate. Stay-on-call = AdaptiveTimeout + Virtual Bridge at ingress.
 * Aliases: {@code map2map_dest_gt}/{@code map2mapDestGt} accepted in admin forms as synonyms.
 */
public record ShortCodeRule(
        String shortCode,
        RuleType ruleType,
        String asUrl,
        boolean enabled,
        String tenantId,
        int networkId,
        boolean mark,
        String appUsername,
        boolean rerouteEnable,
        String map2mapGt,
        String hlrMode,
        String hopDestGt,
        Integer hopDestSsn
) {
    /** Default hop SSN when {@link #hopDestGt()} is set and SSN is null (HLR = 6). */
    public static final int DEFAULT_HOP_DEST_SSN = 6;

    /** @deprecated prefer {@link #DEFAULT_HOP_DEST_SSN} */
    public static final int DEFAULT_MAP2MAP_DEST_SSN = DEFAULT_HOP_DEST_SSN;

    public ShortCodeRule(String shortCode, RuleType ruleType, String asUrl, boolean enabled) {
        this(shortCode, ruleType, asUrl, enabled, null, 0, false, null, false, null, null, null, null);
    }

    public ShortCodeRule(String shortCode, RuleType ruleType, String asUrl, boolean enabled,
                         String tenantId, int networkId) {
        this(shortCode, ruleType, asUrl, enabled, tenantId, networkId, false, null, false, null, null,
                null, null);
    }

    public ShortCodeRule(String shortCode, RuleType ruleType, String asUrl, boolean enabled,
                         String tenantId, int networkId, boolean mark) {
        this(shortCode, ruleType, asUrl, enabled, tenantId, networkId, mark, null, false, null, null,
                null, null);
    }

    public ShortCodeRule(String shortCode, RuleType ruleType, String asUrl, boolean enabled,
                         String tenantId, int networkId, boolean mark, String appUsername) {
        this(shortCode, ruleType, asUrl, enabled, tenantId, networkId, mark, appUsername,
                false, null, null, null, null);
    }

    /**
     * Compat ctor: 9th arg historically meant {@code bypass}; callers that still pass
     * {@code bypass=true/false} should migrate to {@link #rerouteEnable()}. Prefer the
     * full record or {@link #ofReroute}.
     *
     * @param legacyBypass when true, re-route is off ({@code rerouteEnable=false})
     */
    public ShortCodeRule(String shortCode, RuleType ruleType, String asUrl, boolean enabled,
                         String tenantId, int networkId, boolean mark, String appUsername,
                         boolean legacyBypass, String map2mapGt) {
        this(shortCode, ruleType, asUrl, enabled, tenantId, networkId, mark, appUsername,
                !legacyBypass, map2mapGt, null, null, null);
    }

    /** Compat: ofReroute without fixed hop dest. */
    public static ShortCodeRule ofReroute(String shortCode, RuleType ruleType, String asUrl,
                                          boolean enabled, String tenantId, int networkId,
                                          boolean mark, String appUsername,
                                          boolean rerouteEnable, String redirectUssd,
                                          String hlrMode) {
        return ofReroute(shortCode, ruleType, asUrl, enabled, tenantId, networkId, mark, appUsername,
                rerouteEnable, redirectUssd, hlrMode, null, null);
    }

    public static ShortCodeRule ofReroute(String shortCode, RuleType ruleType, String asUrl,
                                          boolean enabled, String tenantId, int networkId,
                                          boolean mark, String appUsername,
                                          boolean rerouteEnable, String redirectUssd,
                                          String hlrMode, String hopDestGt,
                                          Integer hopDestSsn) {
        return new ShortCodeRule(shortCode, ruleType, asUrl, enabled, tenantId, networkId,
                mark, appUsername, rerouteEnable, redirectUssd, hlrMode, hopDestGt, hopDestSsn);
    }

    /** Transition alias: {@code true} when re-route is off. Prefer {@link #rerouteEnable()}. */
    public boolean bypass() {
        return !rerouteEnable;
    }

    /**
     * Redirect USSD string sent on outbound MAP UnstructuredSS-Request (per-rule; any code, e.g. {@code *875#}).
     * DB column remains {@code map2map_gt} for compat — not an SCCP GT.
     */
    public String redirectUssdString() {
        return map2mapGt;
    }

    /** MAP2MAP hop armed when re-route enabled and redirect USSD string configured. */
    public boolean map2mapArmed() {
        return rerouteEnable && map2mapGt != null && !map2mapGt.isBlank();
    }

    /**
     * micro-jainslee AS plane for {@code AsPullRouter} (HTTP|GRPC|SIP).
     * Case 2 hop is orthogonal ({@link #map2mapArmed()}); never treat {@link RuleType#RE_ROUTE}
     * as a wire type.
     */
    public RuleType asPullType() {
        return ruleType == null ? RuleType.HTTP : ruleType.asPullPlane();
    }

    /** Explicit Case 2 hop dest GT configured (else upper-gt fallback). */
    public boolean fixedHopArmed() {
        return hopDestGt != null && !hopDestGt.isBlank();
    }

    /** @deprecated use {@link #fixedHopArmed()} */
    public boolean hasFixedHopDest() {
        return fixedHopArmed();
    }

    /** Compat alias for {@link #hopDestGt()}. */
    public String map2mapDestGt() {
        return hopDestGt;
    }

    /** Compat alias for {@link #hopDestSsn()}. */
    public Integer map2mapDestSsn() {
        return hopDestSsn;
    }

    /**
     * Hop CalledParty SSN: explicit {@link #hopDestSsn()} when 1..255, else
     * {@link #DEFAULT_HOP_DEST_SSN} (6) when fixed dest GT is set.
     */
    public int resolvedHopSsn() {
        if (hopDestSsn != null && hopDestSsn >= 1 && hopDestSsn <= 255) {
            return hopDestSsn;
        }
        return DEFAULT_HOP_DEST_SSN;
    }

    /** Digits-only CalledParty GT for fixed hop. */
    public String hopDestGtDigits() {
        return map2mapCalledGtDigits(hopDestGt);
    }

    /** Effective CalledParty SSN for fixed hop (same as {@link #resolvedHopSsn()}). */
    public int effectiveHopDestSsn() {
        return resolvedHopSsn();
    }

    /**
     * LONG when mark matched a dial longer than the rule key; SHORT for exact / equal dial.
     * Ethiopia mark {@code *101} + dial {@code *101#} → SHORT; {@code *101123456#} → LONG.
     */
    public static String codeKind(String dialedUssd, boolean mark, String ruleShortCode) {
        if (!mark) {
            return "SHORT";
        }
        String dialed = dialedUssd == null ? "" : dialedUssd.trim();
        String prefix = ruleShortCode == null ? "" : ruleShortCode.trim();
        if (prefix.isEmpty()) {
            return "SHORT";
        }
        if (dialed.equals(prefix) || dialed.equals(prefix + "#")) {
            return "SHORT";
        }
        return dialed.startsWith(prefix) ? "LONG" : "SHORT";
    }

    public String codeKindForDial(String dialedUssd) {
        return codeKind(dialedUssd, mark, shortCode);
    }

    /** Digits-only extract from redirect string or GT ({@code *875#} → {@code 875}). */
    public static String map2mapCalledGtDigits(String map2mapGt) {
        if (map2mapGt == null || map2mapGt.isBlank()) {
            return "";
        }
        StringBuilder b = new StringBuilder(map2mapGt.length());
        for (int i = 0; i < map2mapGt.length(); i++) {
            char c = map2mapGt.charAt(i);
            if (c >= '0' && c <= '9') {
                b.append(c);
            }
        }
        return b.toString();
    }

    /**
     * USSD string for the outbound UnstructuredSS-Request: keep {@code *…#} form when present,
     * else wrap digits as {@code *digits#}.
     */
    public static String map2mapUssdString(String map2mapGt) {
        if (map2mapGt == null || map2mapGt.isBlank()) {
            return "";
        }
        String s = map2mapGt.trim();
        if (s.indexOf('*') >= 0 || s.indexOf('#') >= 0) {
            return s;
        }
        String digits = map2mapCalledGtDigits(s);
        return digits.isEmpty() ? s : "*" + digits + "#";
    }

    /**
     * Resolve outbound MAP2MAP hop USSD for one rule.
     *
     * <ul>
     *   <li>{@code mark=false} (exact short) → literal {@link #map2mapUssdString(String)} of redirect</li>
     *   <li>{@code mark=true} + short dial ({@code prefix} or {@code prefix#}) → same literal redirect</li>
     *   <li>{@code mark=true} + long dial → replace mark prefix only; keep leftover (incl. trailing
     *       {@code #}). Example: dial {@code *804*1234#}, mark {@code *804*}, redirect {@code *875*}
     *       → {@code *875*1234#}</li>
     * </ul>
     *
     * <p>When redirect ends with {@code #} and leftover is non-empty, the trailing {@code #} on
     * redirect is dropped before concat so leftover can supply the terminator
     * ({@code *875#}+{@code 1234#} → {@code *8751234#}). Prefer redirect shaped as a prefix
     * ({@code *875*}) for {@code *{n}*xxx#} MMI.
     */
    public static String resolveHopUssd(String dialedUssd, boolean mark, String markKey,
                                        String redirectUssd) {
        String redirect = map2mapUssdString(redirectUssd);
        if (redirect.isEmpty()) {
            return "";
        }
        if (!mark) {
            return redirect;
        }
        String dial = dialedUssd == null ? "" : dialedUssd.trim();
        String prefix = markKey == null ? "" : markKey.trim();
        if (prefix.isEmpty() || !dial.startsWith(prefix)) {
            return redirect;
        }
        if (dial.equals(prefix) || dial.equals(prefix + "#")) {
            return redirect;
        }
        String leftover = dial.substring(prefix.length());
        if (leftover.isEmpty()) {
            return redirect;
        }
        String base = redirect.endsWith("#")
                ? redirect.substring(0, redirect.length() - 1)
                : redirect;
        return base + leftover;
    }

    /** Instance form: this rule's mark / shortCode / redirect against {@code dialedUssd}. */
    public String resolveHopUssd(String dialedUssd) {
        return resolveHopUssd(dialedUssd, mark, shortCode, map2mapGt);
    }
}
