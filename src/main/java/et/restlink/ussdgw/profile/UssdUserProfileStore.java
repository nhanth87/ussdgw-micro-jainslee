package et.restlink.ussdgw.profile;

import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.events.Map2MapRequestEvent;

import com.microjainslee.api.ProfileAlreadyExistsException;
import com.microjainslee.api.ProfileFacility;
import com.microjainslee.api.ProfileID;
import com.microjainslee.api.ProfileLocalObject;
import com.microjainslee.api.ProfileNotFoundException;
import com.microjainslee.api.UnrecognizedProfileTableNameException;
import com.microjainslee.core.MicroSleeContainer;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Durable per-MSISDN user profile ({@link UssdUserProfile#TABLE_NAME}) via ProfileFacility.
 * Complements in-flight {@code ussdTx} (PK = correlationId) and temporary
 * {@link AdaptiveTimeout} per-MSISDN EWMA — stores last MAP2MAP TX fields across sessions.
 */
@ApplicationScoped
public class UssdUserProfileStore {
    private static final Logger LOG = LogManager.getLogger(UssdUserProfileStore.class);
    private static final int MAX_PUT_ATTEMPTS = 2;

    /**
     * Snapshot of the latest MAP2MAP TX for one subscriber (digits MSISDN key).
     */
    public record Map2MapTxSnapshot(
            String correlationId,
            String shortCode,
            String redirectUssd,
            String hopDestGt,
            Integer hopDestSsn,
            String hopOutcome,
            Long gateMs,
            Long ewmaMs,
            int networkId,
            String tenantId) {}

    @Inject MicroSleeContainer container;

    private volatile boolean tableReady;

    @PostConstruct
    void init() {
        ensureTable();
    }

    public synchronized void ensureTable() {
        if (tableReady) {
            return;
        }
        ProfileFacility f = facility();
        if (f == null) {
            LOG.warn("ProfileFacility unavailable — ussdUser table not provisioned yet");
            return;
        }
        f.createProfileTable(UssdUserProfile.TABLE_NAME);
        tableReady = true;
        LOG.info("Profile table {} ready (PK=msisdn)", UssdUserProfile.TABLE_NAME);
    }

    public Optional<UssdUserProfile> get(String msisdn) {
        String key = AdaptiveTimeout.normalizeMsisdn(msisdn);
        if (key == null) {
            return Optional.empty();
        }
        ensureTable();
        ProfileFacility f = facility();
        if (f == null) {
            return Optional.empty();
        }
        try {
            ProfileLocalObject plo =
                    f.getProfile(new ProfileID(UssdUserProfile.TABLE_NAME, key));
            if (plo == null) {
                return Optional.empty();
            }
            return Optional.of((UssdUserProfile) plo.getProfile());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Persist / refresh last MAP2MAP TX fields for the subscriber. Increments
     * {@code map2mapTxCount} when {@code hopOutcome} is a terminal hop result (not
     * {@code pending}).
     */
    public void recordMap2Map(String msisdn, Map2MapTxSnapshot snap) {
        if (snap == null) {
            return;
        }
        String key = AdaptiveTimeout.normalizeMsisdn(msisdn);
        if (key == null) {
            return;
        }
        put(key, snap, 0);
    }

    /** Convenience: build snapshot from MAP2MAP request + hop outcome / gate. */
    public void recordMap2Map(Map2MapRequestEvent req, String hopOutcome,
                              Long gateMs, Long ewmaMs) {
        if (req == null) {
            return;
        }
        recordMap2Map(req.msisdn(), new Map2MapTxSnapshot(
                req.correlationId(),
                req.shortCode(),
                req.redirectUssd(),
                req.hopDestGt(),
                req.hopDestSsn(),
                hopOutcome,
                gateMs,
                ewmaMs,
                req.networkId(),
                req.tenantId()));
    }

    private void put(String key, Map2MapTxSnapshot snap, int attempt) {
        ensureTable();
        ProfileFacility f = requireFacility();
        if (f instanceof com.microjainslee.core.ProfileFieldAccess access) {
            com.microjainslee.core.ProfileFieldStoreLocator.set(access);
        }
        if (f.getProfileTable(UssdUserProfile.TABLE_NAME) == null) {
            tableReady = false;
            ensureTable();
            if (f.getProfileTable(UssdUserProfile.TABLE_NAME) == null) {
                throw new IllegalStateException("ussdUser missing on ProfileFacility after ensureTable");
            }
        }
        ProfileID id = new ProfileID(UssdUserProfile.TABLE_NAME, key);
        try {
            ProfileLocalObject plo = f.getProfile(id);
            UssdUserProfile p;
            if (plo == null) {
                plo = f.createProfile(UssdUserProfile.TABLE_NAME, key, UssdUserProfile.class);
                p = (UssdUserProfile) plo.getProfile();
                p.setMap2mapTxCount(0);
            } else {
                p = (UssdUserProfile) plo.getProfile();
            }
            p.setMsisdn(key);
            p.setLastCorrId(snap.correlationId());
            p.setLastShortCode(snap.shortCode());
            p.setLastRedirectUssd(snap.redirectUssd());
            p.setLastHopDestGt(snap.hopDestGt());
            p.setLastHopDestSsn(snap.hopDestSsn());
            p.setLastHopOutcome(snap.hopOutcome());
            p.setLastGateMs(snap.gateMs());
            p.setLastEwmaMs(snap.ewmaMs());
            p.setLastUpdatedAtMs(System.currentTimeMillis());
            p.setNetworkId(snap.networkId());
            p.setTenantId(snap.tenantId());
            if (isCountableOutcome(snap.hopOutcome())) {
                Integer n = p.getMap2mapTxCount();
                p.setMap2mapTxCount(n == null ? 1 : n + 1);
            }
        } catch (ProfileAlreadyExistsException | ProfileNotFoundException e) {
            if (attempt >= MAX_PUT_ATTEMPTS) {
                LOG.warn("put ussdUser {} gave up after {} attempts: {}", key, attempt, e.toString());
                return;
            }
            put(key, snap, attempt + 1);
        } catch (UnrecognizedProfileTableNameException e) {
            tableReady = false;
            ensureTable();
            if (attempt >= MAX_PUT_ATTEMPTS) {
                LOG.warn("put ussdUser {} gave up after {} attempts: {}", key, attempt, e.toString());
                return;
            }
            put(key, snap, attempt + 1);
        }
    }

    /** Terminal hop outcomes count as a completed MAP2MAP TX; {@code pending} does not. */
    static boolean isCountableOutcome(String hopOutcome) {
        if (hopOutcome == null || hopOutcome.isBlank()) {
            return false;
        }
        String o = hopOutcome.trim();
        return !"pending".equalsIgnoreCase(o);
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
