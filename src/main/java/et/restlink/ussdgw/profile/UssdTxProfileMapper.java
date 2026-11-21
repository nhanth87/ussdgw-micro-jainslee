package et.restlink.ussdgw.profile;

import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionState;

/**
 * Maps {@link VirtualSession} ↔ {@link UssdTxProfile} CMP fields.
 */
public final class UssdTxProfileMapper {
    private UssdTxProfileMapper() {}

    public static void write(UssdTxProfile p, VirtualSession s, long expiresAtMs) {
        if (p == null || s == null) return;
        p.setVirtualSessionId(s.virtualSessionId());
        p.setCorrelationId(s.correlationId());
        p.setRequestId(s.requestId());
        p.setMsisdn(s.msisdn());
        p.setNetworkId(s.networkId());
        p.setDialogId(s.dialogId());
        p.setShortCode(s.shortCode());
        p.setState(s.state() == null ? VirtualSessionState.ACTIVE.name() : s.state().name());
        p.setGeneration(s.generation());
        p.setPendingText(s.pendingText());
        p.setPendingAlphabet(s.pendingAlphabet() == null ? null : s.pendingAlphabet().name());
        p.setCreatedAtMs(s.createdAtMs());
        p.setGateDeadlineMs(s.gateDeadlineMs());
        p.setGateMs(s.gateMs());
        p.setPullStartedAtMs(s.pullStartedAtMs());
        p.setPullStartedAtNanos(s.pullStartedAtNanos());
        p.setInvokeId(s.invokeId());
        // msDigit claim is CAS-owned (VirtualSessionStore.tryClaimMsDigitContinue /
        // releaseMsDigitInFlight): never overwrite a persisted claim with a stale session
        // snapshot (CAS single-field law, like asCookie which the mapper never touches).
        // Initialize defaults only when the row has no claim yet, so a fresh put() carries
        // an explicit hydration value while later puts preserve the winning CAS.
        if (p.getMsDigitInvokeId() == null) {
            long v = s.msDigitInvokeId();
            p.setMsDigitInvokeId(v == Long.MIN_VALUE ? null : v);
        }
        if (p.getMsDigitInFlight() == null) {
            p.setMsDigitInFlight(s.msDigitInFlight() ? Boolean.TRUE : Boolean.FALSE);
        }
        p.setDialogAlive(s.dialogAlive());
        p.setAdaptiveBridgeArm(s.adaptiveBridgeArm());
        p.setMap2mapHopOutstanding(s.map2mapHopOutstanding());
        p.setMscGt(s.mscGt());
        p.setImsi(s.imsi());
        p.setLocalGt(s.localGt());
        p.setTenantId(s.tenantId());
        p.setOriginationType(s.originationType().name());
        p.setOriginatedUssd(s.originatedUssd());
        p.setRedirectUssd(s.redirectUssd());
        p.setHopUssd(s.hopUssd());
        p.setExpiresAtMs(expiresAtMs);
    }

    public static VirtualSession read(UssdTxProfile p) {
        if (p == null) return null;
        String corr = p.getCorrelationId();
        if (corr == null || corr.isBlank()) {
            corr = p.getBoundProfileName();
        }
        VirtualSession s = new VirtualSession(
                nullToEmpty(p.getVirtualSessionId()),
                nullToEmpty(corr),
                nullToEmpty(p.getRequestId()),
                p.getMsisdn(),
                p.getNetworkId() == null ? 0 : p.getNetworkId(),
                nullToEmpty(p.getDialogId()),
                p.getShortCode());
        if (p.getGeneration() != null) {
            s.setGeneration(p.getGeneration());
        }
        if (p.getState() != null) {
            try {
                s.setState(VirtualSessionState.valueOf(p.getState()));
            } catch (IllegalArgumentException ignored) {
                s.setState(VirtualSessionState.ACTIVE);
            }
        }
        s.setPendingText(p.getPendingText());
        if (p.getPendingAlphabet() != null && !p.getPendingAlphabet().isBlank()) {
            s.setPendingAlphabet(et.restlink.ussdgw.api.UssdAlphabet.parse(p.getPendingAlphabet()));
        }
        if (p.getCreatedAtMs() != null) {
            s.setCreatedAtMs(p.getCreatedAtMs());
        }
        if (p.getGateDeadlineMs() != null) {
            s.setGateDeadlineMs(p.getGateDeadlineMs());
        }
        if (p.getGateMs() != null) {
            s.setGateMs(p.getGateMs());
        }
        if (p.getPullStartedAtMs() != null) {
            s.setPullStartedAtMs(p.getPullStartedAtMs());
        }
        if (p.getPullStartedAtNanos() != null) {
            s.setPullStartedAtNanos(p.getPullStartedAtNanos());
        }
        if (p.getInvokeId() != null) {
            s.setInvokeId(p.getInvokeId());
        }
        if (p.getMsDigitInvokeId() != null) {
            s.setMsDigitInvokeId(p.getMsDigitInvokeId());
        }
        if (p.getMsDigitInFlight() != null) {
            s.setMsDigitInFlight(p.getMsDigitInFlight());
        }
        if (p.getDialogAlive() != null) {
            s.setDialogAlive(p.getDialogAlive());
        }
        if (p.getAdaptiveBridgeArm() != null) {
            s.setAdaptiveBridgeArm(p.getAdaptiveBridgeArm());
        }
        if (p.getMap2mapHopOutstanding() != null) {
            s.setMap2mapHopOutstanding(p.getMap2mapHopOutstanding());
        }
        s.setMscGt(p.getMscGt());
        s.setImsi(p.getImsi());
        if (p.getLocalGt() != null) {
            s.setLocalGt(p.getLocalGt());
        }
        s.setTenantId(p.getTenantId());
        s.setOriginationType(OriginationType.parse(p.getOriginationType()));
        s.setOriginatedUssd(p.getOriginatedUssd());
        s.setRedirectUssd(p.getRedirectUssd());
        s.setHopUssd(p.getHopUssd());
        return s;
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }
}
