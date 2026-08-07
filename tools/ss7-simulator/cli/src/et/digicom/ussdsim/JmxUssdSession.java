package et.digicom.ussdsim;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

/**
 * Thin JMX driver for jSS7 {@code USSD_TEST_CLIENT} (TestUssdClientMan + TesterHost).
 */
public final class JmxUssdSession implements AutoCloseable {

    private static final Pattern NETWORK_TEXT = Pattern.compile("Rcvd: unstrSsReq=\"([^\"]*)\"");
    private static final Pattern END_TEXT = Pattern.compile("procUnstrSsResp=\"([^\"]*)\"");

    private final String jmxUrl;
    private final ObjectName hostName;
    private final ObjectName ussdName;

    private JMXConnector connector;
    private MBeanServerConnection mbsc;
    private String msisdn = "251911000001";

    public JmxUssdSession(String jmxUrl, String hostObjectName, String ussdObjectName) {
        this.jmxUrl = jmxUrl;
        try {
            this.hostName = ObjectName.getInstance(hostObjectName);
            this.ussdName = ObjectName.getInstance(ussdObjectName);
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad JMX object name: " + e.getMessage(), e);
        }
    }

    public void connect() throws IOException {
        if (connector != null) {
            return;
        }
        JMXServiceURL url = new JMXServiceURL(jmxUrl);
        connector = JMXConnectorFactory.connect(url);
        mbsc = connector.getMBeanServerConnection();
    }

    public boolean isConnected() {
        return mbsc != null;
    }

    public boolean isSimStarted() throws Exception {
        requireConnected();
        Object v = mbsc.getAttribute(hostName, "Started");
        return Boolean.TRUE.equals(v);
    }

    public void startSim() throws Exception {
        requireConnected();
        mbsc.invoke(hostName, "start", null, null);
    }

    public void stopSim() throws Exception {
        requireConnected();
        mbsc.invoke(hostName, "stop", null, null);
    }

    public String getL1State() throws Exception {
        requireConnected();
        return String.valueOf(mbsc.getAttribute(hostName, "L1State"));
    }

    public void setMsisdn(String msisdn) throws Exception {
        requireConnected();
        this.msisdn = msisdn == null ? "" : msisdn.trim();
        mbsc.setAttribute(ussdName, new javax.management.Attribute("MsisdnAddress", this.msisdn));
    }

    public String getMsisdn() {
        return msisdn;
    }

    public void setAutoDigits(String sequence, boolean enabled) throws Exception {
        requireConnected();
        if (sequence != null) {
            mbsc.setAttribute(ussdName, new javax.management.Attribute("AutoResponseString", sequence));
        }
        mbsc.setAttribute(ussdName,
                new javax.management.Attribute("AutoResponseOnUnstructuredSSRequests", enabled));
    }

    public String dial(String code) throws Exception {
        requireConnected();
        mbsc.setAttribute(ussdName, new javax.management.Attribute("MsisdnAddress", msisdn));
        Object res = mbsc.invoke(ussdName, "performProcessUnstructuredRequest",
                new Object[] { code }, new String[] { String.class.getName() });
        return res == null ? "" : res.toString();
    }

    public String dt(String digitsOrText) throws Exception {
        requireConnected();
        Object res = mbsc.invoke(ussdName, "performUnstructuredResponse",
                new Object[] { digitsOrText }, new String[] { String.class.getName() });
        return res == null ? "" : res.toString();
    }

    public String closeDialog() throws Exception {
        requireConnected();
        Object res = mbsc.invoke(ussdName, "closeCurrentDialog", null, null);
        return res == null ? "" : res.toString();
    }

    public String currentRequestDef() throws Exception {
        requireConnected();
        Object v = mbsc.getAttribute(ussdName, "CurrentRequestDef");
        return v == null ? "" : v.toString();
    }

    /**
     * Poll {@code CurrentRequestDef} until network menu text appears, END text, or timeout.
     *
     * @return last seen network text (may be empty)
     */
    public String waitNetworkText(long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        String last = "";
        String lastDef = "";
        while (System.nanoTime() < deadline) {
            String def = currentRequestDef();
            if (!def.equals(lastDef)) {
                lastDef = def;
                Matcher m = NETWORK_TEXT.matcher(def);
                String found = null;
                while (m.find()) {
                    found = m.group(1);
                }
                if (found != null) {
                    last = found;
                    // Give auto-digit a moment; return immediately for manual DT UX.
                    return last;
                }
                Matcher end = END_TEXT.matcher(def);
                if (end.find()) {
                    return end.group(1);
                }
            }
            Thread.sleep(80);
        }
        return last;
    }

    public String extractLatestNetworkText() throws Exception {
        String def = currentRequestDef();
        Matcher m = NETWORK_TEXT.matcher(def);
        String found = null;
        while (m.find()) {
            found = m.group(1);
        }
        return found == null ? "" : found;
    }

    private void requireConnected() {
        if (mbsc == null) {
            throw new IllegalStateException("Not connected — run 'connect' first");
        }
    }

    @Override
    public void close() {
        if (connector != null) {
            try {
                connector.close();
            } catch (IOException ignored) {
                // best-effort
            }
            connector = null;
            mbsc = null;
        }
    }
}
