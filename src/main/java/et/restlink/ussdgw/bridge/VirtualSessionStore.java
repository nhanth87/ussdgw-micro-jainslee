package et.restlink.ussdgw.bridge;

import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.profile.UssdTxProfile;
import et.restlink.ussdgw.profile.UssdTxProfileMapper;

import com.microjainslee.api.ProfileAlreadyExistsException;
import com.microjainslee.api.ProfileFacility;
import com.microjainslee.api.ProfileID;
import com.microjainslee.api.ProfileLocalObject;
import com.microjainslee.api.ProfileNotFoundException;
import com.microjainslee.api.ProfileTable;
import com.microjainslee.api.UnrecognizedProfileTableNameException;
import com.microjainslee.core.MicroSleeContainer;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Durable in-flight USSD saga store via micro-jainslee {@link ProfileFacility}
 * table {@link UssdTxProfile#TABLE_NAME} (PK = correlationId).
 */
@ApplicationScoped
public class VirtualSessionStore {
    private static final Logger LOG = LogManager.getLogger(VirtualSessionStore.class);

    /** Upper bound on sessions handed to a single gate tick, so one tick stays bounded. */
    static final int MAX_GATE_BATCH = 5000;
    /** Grace added on top of the gate deadline before TTL reclaim may drop the row. */
    private static final long GATE_TTL_GRACE_MS = 30_000L;
    /** Bounded retries when a write races a concurrent create/remove of the same row. */
    private static final int MAX_PUT_ATTEMPTS = 2;

    @Inject MicroSleeContainer container;
    @Inject UssdConfigService config;

    @ConfigProperty(name = "ussd.tx.profile-ttl-ms", defaultValue = "120000")
    long profileTtlMs;

    private volatile boolean tableReady;

    /**
     * Deadline-ordered hint of the {@code AWAITING_AS} sessions with an armed gate, so the
     * 10 Hz tick is O(due) instead of deserializing every awaiting row. The Profile table
     * stays the source of truth: every entry is re-read and re-validated before use, and a
     * stale entry is simply dropped.
     */
    private final ConcurrentSkipListSet<GateKey> gateIndex = new ConcurrentSkipListSet<>();
    private final ConcurrentHashMap<String, Long> gateDeadlines = new ConcurrentHashMap<>();

    private record GateKey(long deadlineMs, String correlationId) implements Comparable<GateKey> {
        @Override
        public int compareTo(GateKey o) {
            int c = Long.compare(deadlineMs, o.deadlineMs);
            return c != 0 ? c : correlationId.compareTo(o.correlationId);
        }
    }

    /**
     * Exclusive claim on a session for one AS content response.
     *
     * @param session  detached snapshot, its {@code state()} restored to {@code previous}
     * @param previous state the claim was won from — decides sync reply vs late NI reconcile
     */
    public record AsResponseClaim(VirtualSession session, VirtualSessionState previous) {}

    @PostConstruct
    void init() {
        ensureTable();
    }

    public synchronized void ensureTable() {
        if (tableReady) return;
        ProfileFacility f = facility();
        if (f == null) {
            LOG.warn("ProfileFacility unavailable — ussdTx table not provisioned yet");
            return;
        }
        f.createProfileTable(UssdTxProfile.TABLE_NAME);
        try {
            f.registerIndex(UssdTxProfile.TABLE_NAME, "requestId");
            f.registerIndex(UssdTxProfile.TABLE_NAME, "dialogId");
            f.registerIndex(UssdTxProfile.TABLE_NAME, "state");
            f.registerIndex(UssdTxProfile.TABLE_NAME, "msisdn");
        } catch (UnrecognizedProfileTableNameException e) {
            throw new IllegalStateException("ussdTx table missing after create", e);
        }
        tableReady = true;
        LOG.info("Profile table {} ready (indexes requestId,dialogId,state,msisdn)",
                UssdTxProfile.TABLE_NAME);
    }

    /** Persist or update session (source of truth = Profile). */
    public VirtualSession put(VirtualSession session) {
        return put(session, 0);
    }

    private VirtualSession put(VirtualSession session, int attempt) {
        if (session == null || session.correlationId() == null || session.correlationId().isBlank()) {
            return session;
        }
        ensureTable();
        ProfileFacility f = requireFacility();
        String corr = session.correlationId();
        ProfileID id = new ProfileID(UssdTxProfile.TABLE_NAME, corr);
        long expires = expiresAt(session);
        try {
            ProfileLocalObject plo = f.getProfile(id);
            UssdTxProfile p;
            if (plo == null) {
                plo = f.createProfile(UssdTxProfile.TABLE_NAME, corr, UssdTxProfile.class);
                p = (UssdTxProfile) plo.getProfile();
            } else {
                p = (UssdTxProfile) plo.getProfile();
            }
            UssdTxProfileMapper.write(p, session, expires);
            indexGate(session);
            return session;
        } catch (ProfileAlreadyExistsException | ProfileNotFoundException e) {
            // Raced a concurrent create or remove; the local object we resolved is stale.
            if (attempt >= MAX_PUT_ATTEMPTS) {
                LOG.warn("put ussdTx {} gave up after {} attempts: {}", corr, attempt, e.toString());
                return session;
            }
            return put(session, attempt + 1);
        } catch (UnrecognizedProfileTableNameException e) {
            tableReady = false;
            ensureTable();
            if (attempt >= MAX_PUT_ATTEMPTS) {
                LOG.warn("put ussdTx {} gave up after {} attempts: {}", corr, attempt, e.toString());
                return session;
            }
            return put(session, attempt + 1);
        }
    }

    public Optional<VirtualSession> get(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) return Optional.empty();
        ensureTable();
        ProfileFacility f = facility();
        if (f == null) return Optional.empty();
        try {
            ProfileLocalObject plo =
                    f.getProfile(new ProfileID(UssdTxProfile.TABLE_NAME, correlationId));
            if (plo == null) return Optional.empty();
            return Optional.ofNullable(UssdTxProfileMapper.read((UssdTxProfile) plo.getProfile()));
        } catch (RuntimeException e) {
            // The row was removed between the lookup and the read, which invalidates the local
            // object (micro-jainslee C8). "Gone" is the honest answer — never let a plain read
            // throw into a SLEE event handler.
            return Optional.empty();
        }
    }

    public Optional<VirtualSession> byRequestId(String requestId) {
        return findOneByAttribute("requestId", requestId);
    }

    public Optional<VirtualSession> byDialogId(String dialogId) {
        return findOneByAttribute("dialogId", dialogId);
    }

    public void remove(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) return;
        ensureTable();
        unindexGate(correlationId);
        ProfileFacility f = facility();
        if (f == null) return;
        try {
            f.removeProfile(new ProfileID(UssdTxProfile.TABLE_NAME, correlationId));
        } catch (RuntimeException e) {
            LOG.warn("remove ussdTx {}: {}", correlationId, e.toString());
        }
    }

    /**
     * CAS state transition (classic {@code compareAndTransition} parity) via
     * {@link ProfileFacility#compareAndSetField}.
     * <p>
     * The CAS is the whole point, so the winner must NOT follow it with a
     * read-modify-write of the full row: {@code UssdTxProfileMapper.write} republishes all
     * CMP fields from a detached snapshot and would silently revert any concurrent
     * single-field update (notably {@code dialogAlive}) taken between the CAS and the read.
     */
    public Optional<VirtualSession> compareAndTransition(String correlationId,
                                                         VirtualSessionState expected,
                                                         VirtualSessionState next) {
        if (correlationId == null || expected == null || next == null) return Optional.empty();
        ensureTable();
        if (!casState(correlationId, expected, next)) return Optional.empty();
        Optional<VirtualSession> opt = get(correlationId);
        opt.ifPresent(s -> s.setState(next));
        return opt;
    }

    /**
     * Atomically take the session out of the running for one AS content response, mirroring
     * classic {@code BridgeReconciler}: the read-only checks are a fast path, the CAS is the
     * authority. Exactly one of {pull response, {@code /as/callback} POST, gate scheduler}
     * can win, so a correlation can never produce two MAP replies or two NI pushes.
     *
     * @return the claim (with the state it was won from), or empty when the session is gone,
     *         stale by generation, already terminal, or another thread got there first
     */
    public Optional<AsResponseClaim> claimForAsResponse(String correlationId, int generation) {
        Optional<VirtualSession> opt = acceptAsResponse(correlationId, generation);
        if (opt.isEmpty()) return Optional.empty();
        VirtualSession s = opt.get();
        VirtualSessionState seen = s.state();
        if (seen != VirtualSessionState.AWAITING_AS && seen != VirtualSessionState.S1_RELEASED) {
            return Optional.empty();
        }
        VirtualSessionState from = seen;
        if (!casState(correlationId, from, VirtualSessionState.RESPONDING)) {
            // Lost to the gate scheduler, which moves AWAITING_AS -> S1_RELEASED. That is not
            // a duplicate: the MO leg is bridged and this response is still owed to the
            // subscriber over NI. Re-try the CAS once from the bridged state.
            if (seen != VirtualSessionState.AWAITING_AS
                    || !casState(correlationId, VirtualSessionState.S1_RELEASED,
                                 VirtualSessionState.RESPONDING)) {
                return Optional.empty();
            }
            from = VirtualSessionState.S1_RELEASED;
        }
        s.setState(from);
        return Optional.of(new AsResponseClaim(s, from));
    }

    /**
     * Atomic single-field write. Use instead of {@link #put(VirtualSession)} whenever the
     * caller only owns one field, so concurrent writers do not lose updates.
     */
    public void setDialogAlive(String correlationId, boolean alive) {
        if (correlationId == null || correlationId.isBlank()) return;
        ProfileFacility f = facility();
        if (f == null) return;
        try {
            f.updateField(new ProfileID(UssdTxProfile.TABLE_NAME, correlationId),
                    "dialogAlive", old -> alive);
        } catch (RuntimeException e) {
            LOG.debug("dialogAlive update skipped corr={}: {}", correlationId, e.toString());
        }
    }

    /**
     * Sessions whose adaptive gate is due. Walks the deadline-ordered index and stops at the
     * first entry in the future, so cost is proportional to the number of due sessions rather
     * than to the number of awaiting ones.
     */
    public List<VirtualSession> awaitingPastDeadline(long nowMs) {
        List<VirtualSession> due = new ArrayList<>();
        ensureTable();
        if (facility() == null) return due;
        for (GateKey key : gateIndex) {
            if (key.deadlineMs() > nowMs || due.size() >= MAX_GATE_BATCH) break;
            Optional<VirtualSession> opt = get(key.correlationId());
            if (opt.isEmpty()) {
                unindexGate(key.correlationId());
                continue;
            }
            VirtualSession s = opt.get();
            if (s.state() != VirtualSessionState.AWAITING_AS || s.gateDeadlineMs() <= 0) {
                unindexGate(key.correlationId());
                continue;
            }
            if (s.gateDeadlineMs() > nowMs) {
                gateIndex.remove(key);
                indexGate(s);
                continue;
            }
            due.add(s);
        }
        return due;
    }

    /** Drop expired / terminal rows (TTL reclaim). */
    public int reclaimExpired(long nowMs) {
        ensureTable();
        ProfileFacility f = facility();
        if (f == null) return 0;
        ProfileTable table = f.getProfileTable(UssdTxProfile.TABLE_NAME);
        if (table == null) return 0;
        // Collect first: removing while iterating the table would race the scan itself, and a
        // row that vanishes concurrently invalidates its local object (micro-jainslee C8).
        List<String> doomed = new ArrayList<>();
        for (ProfileLocalObject plo : table.getProfiles()) {
            try {
                UssdTxProfile p = (UssdTxProfile) plo.getProfile();
                Long exp = p.getExpiresAtMs();
                VirtualSessionState st = parseState(p.getState());
                boolean expired = exp != null && exp > 0 && exp <= nowMs;
                if ((st != null && st.terminal()) || expired) {
                    String name = plo.getProfileID() == null ? p.getCorrelationId()
                            : plo.getProfileID().getProfileName();
                    if (name != null && !name.isBlank()) {
                        doomed.add(name);
                    }
                }
            } catch (RuntimeException e) {
                LOG.debug("reclaim scan skipped a row: {}", e.toString());
            }
        }
        int removed = 0;
        for (String name : doomed) {
            try {
                remove(name);
                removed++;
            } catch (RuntimeException e) {
                LOG.warn("reclaim ussdTx {}: {}", name, e.toString());
            }
        }
        return removed;
    }

    public int size() {
        ensureTable();
        ProfileFacility f = facility();
        if (f == null) return 0;
        ProfileTable t = f.getProfileTable(UssdTxProfile.TABLE_NAME);
        return t == null ? 0 : t.getProfileCount();
    }

    /** Number of sessions currently carrying an armed gate (telemetry / tests). */
    public int armedGateCount() {
        return gateIndex.size();
    }

    /**
     * Read-only admissibility check for an AS response. Never mutates — callers that are
     * about to emit a MAP reply or an NI push must use {@link #claimForAsResponse} instead.
     */
    public Optional<VirtualSession> acceptAsResponse(String correlationId, int generation) {
        Optional<VirtualSession> opt = get(correlationId);
        if (opt.isEmpty()) return Optional.empty();
        VirtualSession s = opt.get();
        VirtualSessionState st = s.state();
        // PUSH_PENDING = S2 already queued, RESPONDING = another thread owns the response;
        // both make further AS replies duplicates.
        if (st.terminal()
                || st == VirtualSessionState.PUSH_PENDING
                || st == VirtualSessionState.RESPONDING) {
            return Optional.empty();
        }
        if (generation > 0 && generation != s.generation()) {
            return Optional.empty();
        }
        return Optional.of(s);
    }

    private boolean casState(String correlationId, VirtualSessionState expected,
                             VirtualSessionState next) {
        ProfileFacility f = facility();
        if (f == null) return false;
        boolean ok;
        try {
            ok = f.compareAndSetField(new ProfileID(UssdTxProfile.TABLE_NAME, correlationId),
                    "state", expected.name(), next.name());
        } catch (RuntimeException e) {
            // Row removed / table dropped between the read and the CAS: lost the race.
            LOG.debug("state CAS {}->{} failed corr={}: {}", expected, next, correlationId,
                    e.toString());
            return false;
        }
        if (ok) {
            if (next == VirtualSessionState.AWAITING_AS) {
                get(correlationId).ifPresent(this::indexGate);
            } else {
                unindexGate(correlationId);
            }
        }
        return ok;
    }

    private static VirtualSessionState parseState(String raw) {
        if (raw == null) return null;
        try {
            return VirtualSessionState.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void indexGate(VirtualSession s) {
        if (s == null || s.correlationId() == null || s.correlationId().isBlank()) return;
        String corr = s.correlationId();
        boolean armed = s.state() == VirtualSessionState.AWAITING_AS && s.gateDeadlineMs() > 0;
        if (!armed) {
            unindexGate(corr);
            return;
        }
        long deadline = s.gateDeadlineMs();
        Long prev = gateDeadlines.put(corr, deadline);
        if (prev != null && prev != deadline) {
            gateIndex.remove(new GateKey(prev, corr));
        }
        gateIndex.add(new GateKey(deadline, corr));
    }

    private void unindexGate(String correlationId) {
        if (correlationId == null) return;
        Long prev = gateDeadlines.remove(correlationId);
        if (prev != null) {
            gateIndex.remove(new GateKey(prev, correlationId));
        }
    }

    private Optional<VirtualSession> findOneByAttribute(String attr, String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        ensureTable();
        ProfileFacility f = facility();
        if (f == null) return Optional.empty();
        try {
            Collection<ProfileLocalObject> rows =
                    f.findProfilesByAttribute(UssdTxProfile.TABLE_NAME, attr, value);
            if (rows.isEmpty()) return Optional.empty();
            return Optional.ofNullable(
                    UssdTxProfileMapper.read((UssdTxProfile) rows.iterator().next().getProfile()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * TTL anchor is {@code createdAtMs}: anchoring on "now" would slide the expiry forward on
     * every write, so a frequently updated session would never expire (leaked saga row).
     */
    private long expiresAt(VirtualSession s) {
        long created = s.createdAtMs() > 0 ? s.createdAtMs() : System.currentTimeMillis();
        long dialog = config == null ? profileTtlMs : Math.max(profileTtlMs, config.dialogTimeoutMs());
        long gate = s.gateDeadlineMs() > 0 ? s.gateDeadlineMs() + GATE_TTL_GRACE_MS : 0L;
        return Math.max(created + dialog, gate);
    }

    private ProfileFacility facility() {
        return container == null ? null : container.getProfileFacility();
    }

    private ProfileFacility requireFacility() {
        ProfileFacility f = facility();
        if (f == null) {
            throw new IllegalStateException("MicroSleeContainer ProfileFacility not available");
        }
        return f;
    }
}
