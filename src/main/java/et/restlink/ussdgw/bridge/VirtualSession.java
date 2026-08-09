package et.restlink.ussdgw.bridge;

import et.restlink.ussdgw.access.OriginationType;

import java.util.concurrent.atomic.AtomicInteger;

public final class VirtualSession {
    private final String virtualSessionId;
    private final String correlationId;
    private final String requestId;
    private final AtomicInteger generation = new AtomicInteger(1);
    private final String msisdn;
    private final int networkId;
    private final String dialogId;
    private final String shortCode;
    private volatile VirtualSessionState state = VirtualSessionState.ACTIVE;
    private volatile String pendingText;
    private volatile et.restlink.ussdgw.api.UssdAlphabet pendingAlphabet =
            et.restlink.ussdgw.api.UssdAlphabet.AUTO;
    private volatile long createdAtMs = System.currentTimeMillis();
    private volatile long gateDeadlineMs;
    private volatile long gateMs;
    private volatile long pullStartedAtMs;
    /** Monotonic twin of {@link #pullStartedAtMs}; only valid inside the JVM that set it. */
    private volatile long pullStartedAtNanos;
    private volatile long invokeId;
    private volatile boolean dialogAlive = true;
    private volatile boolean adaptiveBridgeArm = true;
    /**
     * MAP2MAP Case 2: outbound hop UnstructuredSS is on the wire and has not yet
     * terminated (Response / Abort / Reject / Timeout). While true, MO
     * {@code replyAndEnd} from AS/saga must not run — hop outcome first.
     */
    private volatile boolean map2mapHopOutstanding;
    /**
     * MAP2MAP: first AS pull after hop (RESULT text or empty CLOSE/REJECT) won.
     * Prevents CLOSE-after-RESULT from posting a second {@code hlr none} pull.
     */
    private final java.util.concurrent.atomic.AtomicBoolean map2mapAsRouted =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile String mscGt;
    /** IMSI from SRI-SM — MAP NI destReference (land_mobile). */
    private volatile String imsi;
    /** Optional LMSI octets from SRI LocationInfoWithLMSI (stored; USSD NI uses MSC+IMSI). */
    private volatile byte[] lmsi;
    private volatile String localGt = "100";
    private volatile String tenantId;
    private volatile OriginationType originationType = OriginationType.MAP;
    /**
     * Full UE dialed string from MO ingress (e.g. {@code *100#} / {@code *804#}).
     * Survives digit continues — AS pull {@code ussdString} is the digit; originated stays dialed.
     */
    private volatile String originatedUssd;
    /** MAP2MAP rule redirect code (e.g. {@code *875#}); optional on continue pulls. */
    private volatile String redirectUssd;
    /** MAP2MAP resolved hop USSD sent to upper HLR; optional on continue pulls. */
    private volatile String hopUssd;

    public VirtualSession(String virtualSessionId, String correlationId, String requestId,
                          String msisdn, int networkId, String dialogId, String shortCode) {
        this.virtualSessionId = virtualSessionId;
        this.correlationId = correlationId;
        this.requestId = requestId;
        this.msisdn = msisdn;
        this.networkId = networkId;
        this.dialogId = dialogId;
        this.shortCode = shortCode;
    }

    public String virtualSessionId() { return virtualSessionId; }
    public String correlationId() { return correlationId; }
    public String requestId() { return requestId; }
    public int generation() { return generation.get(); }
    public int nextGeneration() { return generation.incrementAndGet(); }
    public String msisdn() { return msisdn; }
    public int networkId() { return networkId; }
    public String dialogId() { return dialogId; }
    public String shortCode() { return shortCode; }
    public VirtualSessionState state() { return state; }
    public void setState(VirtualSessionState state) { this.state = state; }
    public String pendingText() { return pendingText; }
    public void setPendingText(String pendingText) { this.pendingText = pendingText; }
    public et.restlink.ussdgw.api.UssdAlphabet pendingAlphabet() {
        return pendingAlphabet == null ? et.restlink.ussdgw.api.UssdAlphabet.AUTO : pendingAlphabet;
    }
    public void setPendingAlphabet(et.restlink.ussdgw.api.UssdAlphabet pendingAlphabet) {
        this.pendingAlphabet = pendingAlphabet == null
                ? et.restlink.ussdgw.api.UssdAlphabet.AUTO : pendingAlphabet;
    }
    public long createdAtMs() { return createdAtMs; }
    public long gateDeadlineMs() { return gateDeadlineMs; }
    public void setGateDeadlineMs(long gateDeadlineMs) { this.gateDeadlineMs = gateDeadlineMs; }
    public long gateMs() { return gateMs; }
    public void setGateMs(long gateMs) { this.gateMs = gateMs; }
    public long pullStartedAtMs() { return pullStartedAtMs; }
    public void setPullStartedAtMs(long pullStartedAtMs) { this.pullStartedAtMs = pullStartedAtMs; }
    public long pullStartedAtNanos() { return pullStartedAtNanos; }
    public void setPullStartedAtNanos(long pullStartedAtNanos) {
        this.pullStartedAtNanos = pullStartedAtNanos;
    }
    public long invokeId() { return invokeId; }
    public void setInvokeId(long invokeId) { this.invokeId = invokeId; }
    public boolean dialogAlive() { return dialogAlive; }
    public void setDialogAlive(boolean dialogAlive) { this.dialogAlive = dialogAlive; }
    public boolean adaptiveBridgeArm() { return adaptiveBridgeArm; }
    public void setAdaptiveBridgeArm(boolean adaptiveBridgeArm) { this.adaptiveBridgeArm = adaptiveBridgeArm; }
    public boolean map2mapHopOutstanding() { return map2mapHopOutstanding; }
    public void setMap2mapHopOutstanding(boolean map2mapHopOutstanding) {
        this.map2mapHopOutstanding = map2mapHopOutstanding;
    }
    /** @return true if this caller owns the MAP2MAP AS pull (first wins). */
    public boolean tryClaimMap2MapAsRoute() {
        return map2mapAsRouted.compareAndSet(false, true);
    }
    public boolean map2mapAsRouted() {
        return map2mapAsRouted.get();
    }
    public String mscGt() { return mscGt; }
    public void setMscGt(String mscGt) { this.mscGt = mscGt; }
    public String imsi() { return imsi; }
    public void setImsi(String imsi) { this.imsi = imsi; }
    public byte[] lmsi() { return lmsi; }
    public void setLmsi(byte[] lmsi) { this.lmsi = lmsi; }
    public String localGt() { return localGt; }
    public void setLocalGt(String localGt) { this.localGt = localGt; }
    public void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }
    public void setGeneration(int generation) { this.generation.set(Math.max(1, generation)); }
    public String tenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public OriginationType originationType() {
        return originationType == null ? OriginationType.MAP : originationType;
    }
    public void setOriginationType(OriginationType originationType) {
        this.originationType = originationType == null ? OriginationType.MAP : originationType;
    }
    public String originatedUssd() { return originatedUssd; }
    public void setOriginatedUssd(String originatedUssd) { this.originatedUssd = originatedUssd; }
    public String redirectUssd() { return redirectUssd; }
    public void setRedirectUssd(String redirectUssd) { this.redirectUssd = redirectUssd; }
    public String hopUssd() { return hopUssd; }
    public void setHopUssd(String hopUssd) { this.hopUssd = hopUssd; }
}
