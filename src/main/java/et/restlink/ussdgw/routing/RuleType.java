package et.restlink.ussdgw.routing;

/**
 * Short-code rule type.
 *
 * <p><strong>micro-jainslee AS plane</strong> (SLEE events via {@code AsPullRouter}):
 * {@link #HTTP} → {@code PullHttpEvent}/{@code HttpClientSbb};
 * {@link #GRPC} → {@code PullGrpcEvent}/{@code GrpcClientSbb};
 * {@link #SIP} → SIP MESSAGE / {@code SipUssiSbb}.
 *
 * <p>{@link #RE_ROUTE} is an admin/UX alias for MAP2MAP Case 2 (hop then AS). It is
 * <em>not</em> an AS transport — {@link #asPullPlane()} maps legacy {@code RE_ROUTE} rows
 * to {@link #HTTP}. Prefer persist {@code HTTP|GRPC|SIP} + {@code reroute_enable=true}.
 */
public enum RuleType {
    HTTP,
    GRPC,
    SIP,
    /**
     * Legacy / form alias: MAP2MAP Case 2. Implies {@code rerouteEnable}.
     * AS plane = {@link #asPullPlane()} (default HTTP unless admin sent {@code asPullType}).
     */
    RE_ROUTE;

    /** True when this type encodes MAP2MAP Case 2 (forces {@code rerouteEnable}). */
    public boolean impliesReroute() {
        return this == RE_ROUTE;
    }

    /**
     * SLEE AS pull plane only — never {@link #RE_ROUTE}.
     * Legacy {@code RE_ROUTE} DB rows → {@link #HTTP}.
     */
    public RuleType asPullPlane() {
        return this == RE_ROUTE ? HTTP : this;
    }

    /** AS pull after hop (or direct) uses HTTP RA / wire ({@code PullHttpEvent}). */
    public boolean usesHttpAsPull() {
        return asPullPlane() == HTTP;
    }

    /**
     * Parse admin / DB form values. Accepts {@code RE_ROUTE}, {@code re-route}, {@code REROUTE}.
     *
     * @throws IllegalArgumentException when unknown
     */
    public static RuleType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return HTTP;
        }
        String n = raw.trim().toUpperCase().replace('-', '_');
        if ("REROUTE".equals(n) || "RE_ROUTE".equals(n) || "MAP2MAP".equals(n)) {
            return RE_ROUTE;
        }
        return RuleType.valueOf(n);
    }
}
