package et.restlink.ussdgw.profile;

import com.microjainslee.api.ProfileAbstractCmp;
import com.microjainslee.api.ProfileAccessorInvoker;

import java.lang.reflect.Method;

/**
 * Durable per-subscriber (MSISDN) profile row — last MAP2MAP TX snapshot + last multimenu
 * snapshot + counters. Primary key = digits-only MSISDN. Distinct from in-flight
 * {@link UssdTxProfile} (PK = correlationId). JVM-local until clustering.
 */
public final class UssdUserProfile extends ProfileAbstractCmp {

    public static final String TABLE_NAME = "ussdUser";

    public String getMsisdn() {
        return str(g("msisdn"));
    }

    public void setMsisdn(String v) {
        set(s("msisdn"), v);
    }

    public String getLastCorrId() {
        return str(g("lastCorrId"));
    }

    public void setLastCorrId(String v) {
        set(s("lastCorrId"), v);
    }

    public String getLastShortCode() {
        return str(g("lastShortCode"));
    }

    public void setLastShortCode(String v) {
        set(s("lastShortCode"), v);
    }

    public String getLastRedirectUssd() {
        return str(g("lastRedirectUssd"));
    }

    public void setLastRedirectUssd(String v) {
        set(s("lastRedirectUssd"), v);
    }

    public String getLastHopDestGt() {
        return str(g("lastHopDestGt"));
    }

    public void setLastHopDestGt(String v) {
        set(s("lastHopDestGt"), v);
    }

    public Integer getLastHopDestSsn() {
        return (Integer) ProfileAccessorInvoker.getValue(this, g("lastHopDestSsn"));
    }

    public void setLastHopDestSsn(Integer v) {
        set(s("lastHopDestSsn", Integer.class), v);
    }

    public String getLastHopOutcome() {
        return str(g("lastHopOutcome"));
    }

    public void setLastHopOutcome(String v) {
        set(s("lastHopOutcome"), v);
    }

    public Long getLastGateMs() {
        return (Long) ProfileAccessorInvoker.getValue(this, g("lastGateMs"));
    }

    public void setLastGateMs(Long v) {
        set(s("lastGateMs", Long.class), v);
    }

    public Long getLastEwmaMs() {
        return (Long) ProfileAccessorInvoker.getValue(this, g("lastEwmaMs"));
    }

    public void setLastEwmaMs(Long v) {
        set(s("lastEwmaMs", Long.class), v);
    }

    public Long getLastUpdatedAtMs() {
        return (Long) ProfileAccessorInvoker.getValue(this, g("lastUpdatedAtMs"));
    }

    public void setLastUpdatedAtMs(Long v) {
        set(s("lastUpdatedAtMs", Long.class), v);
    }

    public Integer getMap2mapTxCount() {
        return (Integer) ProfileAccessorInvoker.getValue(this, g("map2mapTxCount"));
    }

    public void setMap2mapTxCount(Integer v) {
        set(s("map2mapTxCount", Integer.class), v);
    }

    public Integer getNetworkId() {
        return (Integer) ProfileAccessorInvoker.getValue(this, g("networkId"));
    }

    public void setNetworkId(Integer v) {
        set(s("networkId", Integer.class), v);
    }

    public String getTenantId() {
        return str(g("tenantId"));
    }

    public void setTenantId(String v) {
        set(s("tenantId"), v);
    }

    /** Last multimenu generation (MS digit / AS CONTINUE stamp). */
    public Integer getLastGeneration() {
        return (Integer) ProfileAccessorInvoker.getValue(this, g("lastGeneration"));
    }

    public void setLastGeneration(Integer v) {
        set(s("lastGeneration", Integer.class), v);
    }

    /** Last MS digit (short). */
    public String getLastDigit() {
        return str(g("lastDigit"));
    }

    public void setLastDigit(String v) {
        set(s("lastDigit"), v);
    }

    /** Last AS→UE menu text snippet (≤50). */
    public String getLastMenuAsUssd() {
        return str(g("lastMenuAsUssd"));
    }

    public void setLastMenuAsUssd(String v) {
        set(s("lastMenuAsUssd"), v);
    }

    /** Last AS action: CONTINUE / END / ABORT / pending. */
    public String getLastAsAction() {
        return str(g("lastAsAction"));
    }

    public void setLastAsAction(String v) {
        set(s("lastAsAction"), v);
    }

    /** Optional MAP dialog id for ops. */
    public String getLastDialogId() {
        return str(g("lastDialogId"));
    }

    public void setLastDialogId(String v) {
        set(s("lastDialogId"), v);
    }

    private String str(Method getter) {
        Object v = ProfileAccessorInvoker.getValue(this, getter);
        return v == null ? null : v.toString();
    }

    private void set(Method setter, Object v) {
        ProfileAccessorInvoker.setValue(this, setter, v);
    }

    private static Method g(String field) {
        try {
            return UssdUserProfile.class.getDeclaredMethod(
                    "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Method s(String field) {
        return s(field, String.class);
    }

    private static Method s(String field, Class<?> type) {
        try {
            return UssdUserProfile.class.getDeclaredMethod(
                    "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1), type);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
