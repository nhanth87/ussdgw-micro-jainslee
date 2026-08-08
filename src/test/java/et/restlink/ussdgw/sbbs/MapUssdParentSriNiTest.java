package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.events.NiPushRequestEvent;
import et.restlink.ussdgw.hlr.HlrFaceService;
import et.restlink.ussdgw.hlr.HlrLocationCache;
import et.restlink.ussdgw.service.SbbServices;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restcomm.protocols.ss7.map.api.primitives.IMSI;
import org.restcomm.protocols.ss7.map.api.primitives.ISDNAddressString;
import org.restcomm.protocols.ss7.map.api.primitives.LMSI;
import org.restcomm.protocols.ss7.map.api.service.sms.LocationInfoWithLMSI;
import org.restcomm.protocols.ss7.map.api.service.sms.SendRoutingInfoForSMResponse;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Classic HttpServerSbb.onSRIResult: NI push uses LocationInfoWithLMSI.networkNodeNumber (MSC)
 * and IMSI destReference — never the subscriber MSISDN.
 */
class MapUssdParentSriNiTest {

    private final ConcurrentHashMap<String, VirtualSession> sessions = new ConcurrentHashMap<>();
    private final List<String> failCodes = new ArrayList<>();
    private HlrLocationCache locationCache;
    private MapUssdParentSbb sbb;

    @BeforeEach
    void setUp() throws Exception {
        sessions.clear();
        failCodes.clear();
        locationCache = new HlrLocationCache();

        VirtualSessionStore store = new VirtualSessionStore() {
            @Override public Optional<VirtualSession> get(String corr) {
                return Optional.ofNullable(sessions.get(corr));
            }
            @Override public VirtualSession put(VirtualSession s) {
                if (s != null && s.correlationId() != null) {
                    sessions.put(s.correlationId(), s);
                }
                return s;
            }
            @Override public void remove(String corr) {
                sessions.remove(corr);
            }
        };

        HlrFaceService hlrFace = new HlrFaceService();
        set(hlrFace, "locationCache", locationCache);

        CdrService cdr = new CdrService() {
            @Override
            public void write(String correlationId, CdrPhase phase, String msisdn,
                              String shortCode, String status, String detail) {
                if (status != null) {
                    failCodes.add(status);
                }
            }
        };

        SbbServices services = new SbbServices();
        set(services, "store", store);
        set(services, "hlrFace", hlrFace);
        set(services, "cdr", cdr);
        set(services, "saga", new et.restlink.ussdgw.bridge.UssdSagaCoordinator() {
            @Override public void onNiFailed(String correlationId, String reason) {
                failCodes.add(reason);
            }
        });
        set(services, "campaigns", new et.restlink.ussdgw.campaign.CampaignService() {
            @Override public void onNiDone(String correlationId, boolean ok, String detail) { }
        });
        // container left null — applyNiSriResult skips handoff when container is null
        sbb = new MapUssdParentSbb(services);
    }

    @Test
    void extractKeepsNetworkNodeNumberAsMscNotMsisdn() {
        assertThat(MapUssdParentSbb.extractSriNiRouting(sriResponse(
                "629110000123456", "251971200446", new byte[]{9, 8, 7, 6})))
                .hasValueSatisfying(r -> {
                    assertThat(r.mscGt()).isEqualTo("251971200446");
                    assertThat(r.imsi()).isEqualTo("629110000123456");
                    assertThat(r.lmsi()).containsExactly(9, 8, 7, 6);
                });
    }

    @Test
    void extractEmptyWithoutNetworkNodeNumber() {
        assertThat(MapUssdParentSbb.extractSriNiRouting(
                sriResponse("629110000123456", null, null))).isEmpty();
    }

    @Test
    void sriResultStoresMscAndImsiOnSession() {
        VirtualSession session = new VirtualSession(
                "vs", "corr-1", "req-1", "251911230398", 0, "corr-1", "");
        sessions.put("corr-1", session);

        var ni = new NiPushRequestEvent("corr-1", "251911230398", "Hello", 0);
        String detail = sbb.applyNiSriResult(ni,
                sriResponse("629110000123456", "251971200446", new byte[]{1, 2, 3, 4}));

        assertThat(detail).isEqualTo("sri-ok msc=251971200446");
        assertThat(session.mscGt()).isEqualTo("251971200446");
        assertThat(session.imsi()).isEqualTo("629110000123456");
        assertThat(session.lmsi()).containsExactly(1, 2, 3, 4);
        assertThat(locationCache.get("251911230398")).hasValueSatisfying(loc -> {
            assertThat(loc.mscGt()).isEqualTo("251971200446");
            assertThat(loc.imsi()).isEqualTo("629110000123456");
        });
    }

    @Test
    void sriResultWithoutMscFailsClosed() {
        var ni = new NiPushRequestEvent("corr-2", "251911230398", "Hello", 0);
        String detail = sbb.applyNiSriResult(ni, sriResponse("629110000123456", null, null));

        assertThat(detail).isEqualTo("sri-no-msc");
        assertThat(failCodes).contains("SRI_NO_MSC");
        assertThat(sessions).isEmpty();
    }

    private static SendRoutingInfoForSMResponse sriResponse(String imsi, String msc, byte[] lmsi) {
        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "getIMSI" -> imsi == null ? null : stubImsi(imsi);
            case "getLocationInfoWithLMSI" -> msc == null && lmsi == null ? null : stubLoc(msc, lmsi);
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "toString" -> "sri-rsp";
            default -> null;
        };
        return (SendRoutingInfoForSMResponse) Proxy.newProxyInstance(
                MapUssdParentSriNiTest.class.getClassLoader(),
                new Class<?>[]{SendRoutingInfoForSMResponse.class}, h);
    }

    private static IMSI stubImsi(String data) {
        return (IMSI) Proxy.newProxyInstance(
                MapUssdParentSriNiTest.class.getClassLoader(),
                new Class<?>[]{IMSI.class},
                (p, m, a) -> "getData".equals(m.getName()) ? data : def(p, m, a));
    }

    private static LocationInfoWithLMSI stubLoc(String msc, byte[] lmsi) {
        return (LocationInfoWithLMSI) Proxy.newProxyInstance(
                MapUssdParentSriNiTest.class.getClassLoader(),
                new Class<?>[]{LocationInfoWithLMSI.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getNetworkNodeNumber" -> msc == null ? null : stubIsdn(msc);
                    case "getLMSI" -> lmsi == null ? null : stubLmsi(lmsi);
                    default -> def(p, m, a);
                });
    }

    private static ISDNAddressString stubIsdn(String addr) {
        return (ISDNAddressString) Proxy.newProxyInstance(
                MapUssdParentSriNiTest.class.getClassLoader(),
                new Class<?>[]{ISDNAddressString.class},
                (p, m, a) -> "getAddress".equals(m.getName()) ? addr : def(p, m, a));
    }

    private static LMSI stubLmsi(byte[] data) {
        return (LMSI) Proxy.newProxyInstance(
                MapUssdParentSriNiTest.class.getClassLoader(),
                new Class<?>[]{LMSI.class},
                (p, m, a) -> "getData".equals(m.getName()) ? data : def(p, m, a));
    }

    private static Object def(Object proxy, java.lang.reflect.Method m, Object[] args) {
        return switch (m.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "toString" -> m.getDeclaringClass().getSimpleName();
            default -> null;
        };
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new IllegalStateException("No field " + field);
    }
}
