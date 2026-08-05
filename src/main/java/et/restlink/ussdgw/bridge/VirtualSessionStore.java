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

    @Inject MicroSleeContainer container;
    @Inject UssdConfigService config;

    @ConfigProperty(name = "ussd.tx.profile-ttl-ms", defaultValue = "120000")
    long profileTtlMs;

    private volatile boolean tableReady;

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
            return session;
        } catch (ProfileAlreadyExistsException e) {
            ProfileLocalObject plo = f.getProfile(id);
            if (plo != null) {
                UssdTxProfileMapper.write((UssdTxProfile) plo.getProfile(), session, expires);
            }
            return session;
        } catch (UnrecognizedProfileTableNameException e) {
            tableReady = false;
            ensureTable();
            return put(session);
        }
    }

    public Optional<VirtualSession> get(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) return Optional.empty();
        ensureTable();
        ProfileFacility f = facility();
        if (f == null) return Optional.empty();
        ProfileLocalObject plo = f.getProfile(new ProfileID(UssdTxProfile.TABLE_NAME, correlationId));
        if (plo == null) return Optional.empty();
        return Optional.ofNullable(UssdTxProfileMapper.read((UssdTxProfile) plo.getProfile()));
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
        ProfileFacility f = facility();
        if (f == null) return;
        try {
            f.removeProfile(new ProfileID(UssdTxProfile.TABLE_NAME, correlationId));
        } catch (UnrecognizedProfileTableNameException e) {
            LOG.warn("remove ussdTx {}: {}", correlationId, e.toString());
        }
    }

    /**
     * CAS state transition (classic compareAndTransition parity) via
     * {@link ProfileFacility#compareAndSetField}.
     */
    public Optional<VirtualSession> compareAndTransition(String correlationId,
                                                         VirtualSessionState expected,
                                                         VirtualSessionState next) {
        if (correlationId == null || expected == null || next == null) return Optional.empty();
        ensureTable();
        ProfileFacility f = requireFacility();
        ProfileID id = new ProfileID(UssdTxProfile.TABLE_NAME, correlationId);
        try {
            boolean ok = f.compareAndSetField(id, "state", expected.name(), next.name());
            if (!ok) return Optional.empty();
            Optional<VirtualSession> opt = get(correlationId);
            opt.ifPresent(s -> {
                s.setState(next);
                put(s);
            });
            return opt;
        } catch (ProfileNotFoundException | UnrecognizedProfileTableNameException e) {
            return Optional.empty();
        }
    }

    public List<VirtualSession> awaitingPastDeadline(long nowMs) {
        List<VirtualSession> due = new ArrayList<>();
        ensureTable();
        ProfileFacility f = facility();
        if (f == null) return due;
        try {
            Collection<ProfileLocalObject> rows = f.findProfilesByAttribute(
                    UssdTxProfile.TABLE_NAME, "state", VirtualSessionState.AWAITING_AS.name());
            for (ProfileLocalObject plo : rows) {
                VirtualSession s = UssdTxProfileMapper.read((UssdTxProfile) plo.getProfile());
                if (s != null && s.gateDeadlineMs() > 0 && s.gateDeadlineMs() <= nowMs) {
                    due.add(s);
                }
            }
        } catch (UnrecognizedProfileTableNameException | IllegalStateException e) {
            // index may not be ready — fall back to full table scan
            scanAwaiting(due, nowMs);
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
        int removed = 0;
        for (ProfileLocalObject plo : table.getProfiles()) {
            UssdTxProfile p = (UssdTxProfile) plo.getProfile();
            Long exp = p.getExpiresAtMs();
            String st = p.getState();
            boolean terminal = VirtualSessionState.COMPLETED.name().equals(st)
                    || VirtualSessionState.ABORTED.name().equals(st)
                    || VirtualSessionState.ZOMBIE.name().equals(st);
            boolean expired = exp != null && exp > 0 && exp <= nowMs;
            if (terminal || expired) {
                String name = plo.getProfileID() == null ? p.getCorrelationId()
                        : plo.getProfileID().getProfileName();
                remove(name);
                removed++;
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

    public Optional<VirtualSession> acceptAsResponse(String correlationId, int generation) {
        Optional<VirtualSession> opt = get(correlationId);
        if (opt.isEmpty()) return Optional.empty();
        VirtualSession s = opt.get();
        VirtualSessionState st = s.state();
        // PUSH_PENDING = S2 already queued; further AS replies are duplicates.
        if (st == VirtualSessionState.ABORTED || st == VirtualSessionState.ZOMBIE
                || st == VirtualSessionState.COMPLETED
                || st == VirtualSessionState.PUSH_PENDING) {
            return Optional.empty();
        }
        if (generation > 0 && generation != s.generation()) {
            return Optional.empty();
        }
        return Optional.of(s);
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
        } catch (UnrecognizedProfileTableNameException | IllegalStateException e) {
            return Optional.empty();
        }
    }

    private void scanAwaiting(List<VirtualSession> due, long nowMs) {
        ProfileFacility f = facility();
        if (f == null) return;
        ProfileTable table = f.getProfileTable(UssdTxProfile.TABLE_NAME);
        if (table == null) return;
        for (ProfileLocalObject plo : table.getProfiles()) {
            VirtualSession s = UssdTxProfileMapper.read((UssdTxProfile) plo.getProfile());
            if (s != null
                    && s.state() == VirtualSessionState.AWAITING_AS
                    && s.gateDeadlineMs() > 0
                    && s.gateDeadlineMs() <= nowMs) {
                due.add(s);
            }
        }
    }

    private long expiresAt(VirtualSession s) {
        long base = Math.max(s.createdAtMs(), System.currentTimeMillis());
        long dialog = config == null ? profileTtlMs : Math.max(profileTtlMs, config.dialogTimeoutMs());
        long gate = s.gateDeadlineMs() > 0 ? s.gateDeadlineMs() + 30_000L : 0L;
        return Math.max(base + dialog, gate);
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
