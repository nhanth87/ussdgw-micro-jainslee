package et.restlink.ussdgw.profile;

import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.cdr.CdrUssdSnippet;
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
 * {@link AdaptiveTimeout} per-MSISDN EWMA — stores last MAP2MAP TX + last multimenu snapshot.
 * JVM-local until clustering — not Digicom JDBC.
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

    /**
     * Last multimenu / AS→UE stamp for one subscriber. Does not bump {@code map2mapTxCount}.
     * In-flight CAS stays on {@code ussdTx}; this is best-effort ops snapshot only.
     */
    public record MenuStateSnapshot(
            String correlationId,
            String shortCode,
            int generation,
            String digit,
            String menuAsUssd,
            String asAction,
            String dialogId,
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
        putMap2Map(key, snap, 0);
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

    /**
     * Best-effort multimenu stamp (digit / AS CONTINUE|END). Merges menu fields onto the
     * existing row without wiping hop GT/outcome or bumping {@code map2mapTxCount}.
     */
    public void recordMenuState(String msisdn, MenuStateSnapshot snap) {
        if (snap == null) {
            return;
        }
        String key = AdaptiveTimeout.normalizeMsisdn(msisdn);
        if (key == null) {
            return;
        }
        try {
            putMenuState(key, snap, 0);
            LOG.info(
                    "ussdUser menu-state msisdn={} gen={} digit={} asAction={} menu={} corr={} dialogId={} sc={}",
                    key,
                    snap.generation(),
                    CdrUssdSnippet.of(snap.digit(), 16),
                    snap.asAction() == null ? "-" : snap.asAction(),
                    CdrUssdSnippet.of(snap.menuAsUssd()),
                    snap.correlationId() == null ? "-" : snap.correlationId(),
                    snap.dialogId() == null ? "-" : snap.dialogId(),
                    snap.shortCode() == null ? "-" : snap.shortCode());
        } catch (Throwable t) {
            LOG.info("ussdUser menu-state FAILED msisdn={} gen={} reason={}",
                    key, snap.generation(), t.toString());
        }
    }

    /**
     * Seed AdaptiveTimeout per-MSISDN EWMA from profile {@code lastEwmaMs} once on MO.
     * Does not overwrite correlationId / AS session. Returns whether seed applied.
     */
    public boolean seedAdaptiveFromProfile(AdaptiveTimeout adaptive, String msisdn, int networkId) {
        if (adaptive == null) {
            LOG.info("ussdUser EWMA-seed skip msisdn={} reason=no-adaptive net={}",
                    AdaptiveTimeout.normalizeMsisdn(msisdn), networkId);
            return false;
        }
        String key = AdaptiveTimeout.normalizeMsisdn(msisdn);
        if (key == null) {
            LOG.info("ussdUser EWMA-seed skip msisdn=- reason=blank-msisdn net={}", networkId);
            return false;
        }
        if (adaptive.isMsisdnSeeded(key)) {
            LOG.info("ussdUser EWMA-seed skip msisdn={} reason=already-seeded net={} observedMs={}",
                    key, networkId, Math.round(adaptive.observedLatencyMs(networkId, key)));
            return false;
        }
        Optional<UssdUserProfile> opt = get(key);
        if (opt.isEmpty()) {
            LOG.info("ussdUser EWMA-seed skip msisdn={} reason=no-profile net={}", key, networkId);
            return false;
        }
        Long ewma = opt.get().getLastEwmaMs();
        if (ewma == null || ewma <= 0L) {
            LOG.info("ussdUser EWMA-seed skip msisdn={} reason=no-lastEwmaMs net={}", key, networkId);
            return false;
        }
        boolean seeded = adaptive.seedObservedMs(key, ewma);
        if (seeded) {
            LOG.info("ussdUser EWMA-seed applied msisdn={} lastEwmaMs={} net={} gen={} digit={}",
                    key, ewma, networkId,
                    opt.get().getLastGeneration(),
                    CdrUssdSnippet.of(opt.get().getLastDigit(), 16));
        } else {
            LOG.info("ussdUser EWMA-seed skip msisdn={} reason=seed-lost-race net={} lastEwmaMs={}",
                    key, networkId, ewma);
        }
        return seeded;
    }

    private void putMap2Map(String key, Map2MapTxSnapshot snap, int attempt) {
        ensureTable();
        ProfileFacility f = requireFacility();
        rebindLocator(f);
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
            putMap2Map(key, snap, attempt + 1);
        } catch (UnrecognizedProfileTableNameException e) {
            tableReady = false;
            ensureTable();
            if (attempt >= MAX_PUT_ATTEMPTS) {
                LOG.warn("put ussdUser {} gave up after {} attempts: {}", key, attempt, e.toString());
                return;
            }
            putMap2Map(key, snap, attempt + 1);
        }
    }

    private void putMenuState(String key, MenuStateSnapshot snap, int attempt) {
        ensureTable();
        ProfileFacility f = requireFacility();
        rebindLocator(f);
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
            if (snap.correlationId() != null && !snap.correlationId().isBlank()) {
                p.setLastCorrId(snap.correlationId());
            }
            if (snap.shortCode() != null && !snap.shortCode().isBlank()) {
                p.setLastShortCode(snap.shortCode());
            }
            p.setLastGeneration(snap.generation());
            if (snap.digit() != null) {
                p.setLastDigit(CdrUssdSnippet.of(snap.digit(), 32));
            }
            if (snap.menuAsUssd() != null) {
                p.setLastMenuAsUssd(CdrUssdSnippet.of(snap.menuAsUssd()));
            }
            if (snap.asAction() != null && !snap.asAction().isBlank()) {
                p.setLastAsAction(snap.asAction().trim());
            }
            if (snap.dialogId() != null && !snap.dialogId().isBlank()) {
                p.setLastDialogId(snap.dialogId());
            }
            if (snap.gateMs() != null) {
                p.setLastGateMs(snap.gateMs());
            }
            if (snap.ewmaMs() != null) {
                p.setLastEwmaMs(snap.ewmaMs());
            }
            p.setLastUpdatedAtMs(System.currentTimeMillis());
            p.setNetworkId(snap.networkId());
            if (snap.tenantId() != null && !snap.tenantId().isBlank()) {
                p.setTenantId(snap.tenantId());
            }
            // Do not bump map2mapTxCount; do not clear hop GT/outcome when omitted.
        } catch (ProfileAlreadyExistsException | ProfileNotFoundException e) {
            if (attempt >= MAX_PUT_ATTEMPTS) {
                LOG.warn("put ussdUser menu {} gave up after {} attempts: {}",
                        key, attempt, e.toString());
                return;
            }
            putMenuState(key, snap, attempt + 1);
        } catch (UnrecognizedProfileTableNameException e) {
            tableReady = false;
            ensureTable();
            if (attempt >= MAX_PUT_ATTEMPTS) {
                LOG.warn("put ussdUser menu {} gave up after {} attempts: {}",
                        key, attempt, e.toString());
                return;
            }
            putMenuState(key, snap, attempt + 1);
        }
    }

    private static void rebindLocator(ProfileFacility f) {
        if (f instanceof com.microjainslee.core.ProfileFieldAccess access) {
            com.microjainslee.core.ProfileFieldStoreLocator.set(access);
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
