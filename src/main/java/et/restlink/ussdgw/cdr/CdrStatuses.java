package et.restlink.ussdgw.cdr;

import java.util.List;

/**
 * CDR {@code status} catalog for AdaptiveTimeout / Virtual bridge, gated AS notify,
 * MAP2MAP / RE_ROUTE Case 2, and related AS-pull failures. Persist via {@link CdrService}
 * → {@link CdrDbFlusher} ({@code gate_ms} / {@code observed_ewma_ms} columns).
 *
 * <p>Admin filter: exact match or trailing {@code *} prefix ({@code MAP2MAP_*}, {@code GATED*}).
 */
public final class CdrStatuses {
    private CdrStatuses() {}

    // ── AdaptiveTimeout / VirtualSessionBridge (MO + NI park) ───────────────
    /** Gate armed; session waiting on AS (or hop). */
    public static final String GATED = "GATED";
    /** Gate won → UE async-wait; AS may still reply (late reconcile). */
    public static final String BRIDGED = "BRIDGED";
    /** NI HTTP park gate fired (ABORT dialog to AS park). */
    public static final String GATE_EXPIRED = "GATE_EXPIRED";
    /** Gate fired while {@code ussd.bridge.enabled=false} (hard-fail). */
    public static final String GATE_NO_BRIDGE = "GATE_NO_BRIDGE";
    /** Late AS after bridge → NI push queued. */
    public static final String QUEUED = "QUEUED";
    /** NI push completed after bridge. */
    public static final String BRIDGED_DONE = "BRIDGED_DONE";

    // ── Gated AS XML notify (GatedAsNotifyService → HttpClientSbb) ───────────
    /** Classic gated XmlMAPDialog POST queued to AS {@code asUrl}. */
    public static final String GATED_AS_NOTIFY = "GATED_AS_NOTIFY";
    /** No HTTP asUrl / circuit / route skip. */
    public static final String GATED_AS_SKIP = "GATED_AS_SKIP";
    /** HTTP 2xx/3xx ack for {@code gated-{corr}} session. */
    public static final String GATED_AS_ACK = "GATED_AS_ACK";
    /** Transport / HTTP fail on gated notify. */
    public static final String GATED_AS_FAIL = "GATED_AS_FAIL";

    // ── MAP2MAP / RE_ROUTE — see {@link Map2MapCdr} ─────────────────────────
    // MAP2MAP_ARMED, MAP2MAP_HOP_START, MAP2MAP_USSD_SENT, MAP2MAP_GATED_HOP,
    // MAP2MAP_OK, MAP2MAP_COMPLETE_AFTER_GATE, MAP2MAP_TIMEOUT, …

    // ── Common AS / saga (filterable) ───────────────────────────────────────
    public static final String AS_EMPTY_BODY = "AS_EMPTY_BODY";
    public static final String AS_PULL_FAIL = "AS_PULL_FAIL";

    /** Admin preset keys → status filter value (trailing {@code *} = prefix). */
    public static final List<StatusPreset> ADMIN_PRESETS = List.of(
            new StatusPreset("", "All statuses"),
            new StatusPreset("GATED*", "Gated / GATED_AS*"),
            new StatusPreset("MAP2MAP_*", "MAP2MAP / re-route"),
            new StatusPreset("BRIDGED*", "Bridged"),
            new StatusPreset("GATE_*", "Gate expired / no-bridge"),
            new StatusPreset("AS_*", "AS pull fails"),
            new StatusPreset("GATED_AS*", "Gated AS notify only"));

    public record StatusPreset(String value, String label) {}

    /** True when status is AdaptiveTimeout / bridge gate family. */
    public static boolean isGateFamily(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String u = status.trim().toUpperCase();
        return u.equals(GATED)
                || u.equals(BRIDGED)
                || u.equals(GATE_EXPIRED)
                || u.equals(GATE_NO_BRIDGE)
                || u.startsWith("GATED_AS")
                || u.startsWith("MAP2MAP_GATED")
                || u.equals(QUEUED)
                || u.equals(BRIDGED_DONE);
    }

    /** True when status is MAP2MAP / RE_ROUTE Case 2. */
    public static boolean isMap2MapFamily(String status) {
        return status != null && status.trim().toUpperCase().startsWith("MAP2MAP_");
    }
}
