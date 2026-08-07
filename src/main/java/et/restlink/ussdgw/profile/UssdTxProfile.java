package et.restlink.ussdgw.profile;

import com.microjainslee.api.ProfileAbstractCmp;
import com.microjainslee.api.ProfileAccessorInvoker;

import java.lang.reflect.Method;

/**
 * In-flight USSD virtual-session saga row (micro-jainslee ProfileFacility).
 * Primary key = {@code correlationId}. Enums stored as {@link String} (C7 whitelist).
 */
public final class UssdTxProfile extends ProfileAbstractCmp {

    public static final String TABLE_NAME = "ussdTx";

    public String getVirtualSessionId() {
        return str(g("virtualSessionId"));
    }

    public void setVirtualSessionId(String v) {
        set(s("virtualSessionId"), v);
    }

    public String getCorrelationId() {
        return str(g("correlationId"));
    }

    public void setCorrelationId(String v) {
        set(s("correlationId"), v);
    }

    public String getRequestId() {
        return str(g("requestId"));
    }

    public void setRequestId(String v) {
        set(s("requestId"), v);
    }

    public String getMsisdn() {
        return str(g("msisdn"));
    }

    public void setMsisdn(String v) {
        set(s("msisdn"), v);
    }

    public Integer getNetworkId() {
        return (Integer) ProfileAccessorInvoker.getValue(this, g("networkId"));
    }

    public void setNetworkId(Integer v) {
        set(s("networkId", Integer.class), v);
    }

    public String getDialogId() {
        return str(g("dialogId"));
    }

    public void setDialogId(String v) {
        set(s("dialogId"), v);
    }

    public String getShortCode() {
        return str(g("shortCode"));
    }

    public void setShortCode(String v) {
        set(s("shortCode"), v);
    }

    public String getState() {
        return str(g("state"));
    }

    public void setState(String v) {
        set(s("state"), v);
    }

    public Integer getGeneration() {
        return (Integer) ProfileAccessorInvoker.getValue(this, g("generation"));
    }

    public void setGeneration(Integer v) {
        set(s("generation", Integer.class), v);
    }

    public String getPendingText() {
        return str(g("pendingText"));
    }

    public void setPendingText(String v) {
        set(s("pendingText"), v);
    }

    public String getPendingAlphabet() {
        return str(g("pendingAlphabet"));
    }

    public void setPendingAlphabet(String v) {
        set(s("pendingAlphabet"), v);
    }

    public Long getCreatedAtMs() {
        return (Long) ProfileAccessorInvoker.getValue(this, g("createdAtMs"));
    }

    public void setCreatedAtMs(Long v) {
        set(s("createdAtMs", Long.class), v);
    }

    public Long getGateDeadlineMs() {
        return (Long) ProfileAccessorInvoker.getValue(this, g("gateDeadlineMs"));
    }

    public void setGateDeadlineMs(Long v) {
        set(s("gateDeadlineMs", Long.class), v);
    }

    public Long getGateMs() {
        return (Long) ProfileAccessorInvoker.getValue(this, g("gateMs"));
    }

    public void setGateMs(Long v) {
        set(s("gateMs", Long.class), v);
    }

    public Long getPullStartedAtMs() {
        return (Long) ProfileAccessorInvoker.getValue(this, g("pullStartedAtMs"));
    }

    public void setPullStartedAtMs(Long v) {
        set(s("pullStartedAtMs", Long.class), v);
    }

    public Long getPullStartedAtNanos() {
        return (Long) ProfileAccessorInvoker.getValue(this, g("pullStartedAtNanos"));
    }

    public void setPullStartedAtNanos(Long v) {
        set(s("pullStartedAtNanos", Long.class), v);
    }

    public Long getInvokeId() {
        return (Long) ProfileAccessorInvoker.getValue(this, g("invokeId"));
    }

    public void setInvokeId(Long v) {
        set(s("invokeId", Long.class), v);
    }

    public Boolean getDialogAlive() {
        return (Boolean) ProfileAccessorInvoker.getValue(this, g("dialogAlive"));
    }

    public void setDialogAlive(Boolean v) {
        set(s("dialogAlive", Boolean.class), v);
    }

    public Boolean getAdaptiveBridgeArm() {
        return (Boolean) ProfileAccessorInvoker.getValue(this, g("adaptiveBridgeArm"));
    }

    public void setAdaptiveBridgeArm(Boolean v) {
        set(s("adaptiveBridgeArm", Boolean.class), v);
    }

    public String getMscGt() {
        return str(g("mscGt"));
    }

    public void setMscGt(String v) {
        set(s("mscGt"), v);
    }

    public String getLocalGt() {
        return str(g("localGt"));
    }

    public void setLocalGt(String v) {
        set(s("localGt"), v);
    }

    public String getTenantId() {
        return str(g("tenantId"));
    }

    public void setTenantId(String v) {
        set(s("tenantId"), v);
    }

    public String getOriginationType() {
        return str(g("originationType"));
    }

    public void setOriginationType(String v) {
        set(s("originationType"), v);
    }

    /** Absolute expiry for TTL reclaim (dialog timeout ceiling). */
    public Long getExpiresAtMs() {
        return (Long) ProfileAccessorInvoker.getValue(this, g("expiresAtMs"));
    }

    public void setExpiresAtMs(Long v) {
        set(s("expiresAtMs", Long.class), v);
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
            return UssdTxProfile.class.getDeclaredMethod(
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
            return UssdTxProfile.class.getDeclaredMethod(
                    "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1), type);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
