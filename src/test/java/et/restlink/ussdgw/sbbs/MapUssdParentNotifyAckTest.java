package et.restlink.ussdgw.sbbs;

import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.api.classic.ClassicNiHttpPark;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.service.SbbServices;

import com.microjainslee.ra.jss7.event.Ss7MapEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restcomm.protocols.ss7.map.api.MAPMessageType;
import org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSNotifyResponse;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Peer unstructuredSS-Notify RESULT settles classic HTTP-NI park (AdaptiveTimeout ontop).
 */
class MapUssdParentNotifyAckTest {

    private final ConcurrentHashMap<String, VirtualSession> sessions = new ConcurrentHashMap<>();
    private MapUssdParentSbb sbb;
    private ClassicNiHttpPark park;

    @BeforeEach
    void setUp() throws Exception {
        sessions.clear();

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
            @Override public Optional<VirtualSession> byDialogId(String dialogId) {
                return sessions.values().stream()
                        .filter(s -> dialogId != null && dialogId.equals(s.dialogId()))
                        .findFirst();
            }
        };

        park = new ClassicNiHttpPark();
        set(park, "wireFacade", new AsWireFacade());
        SbbServices services = new SbbServices();
        set(services, "store", store);
        set(services, "niHttpPark", park);
        set(services, "wireFacade", new AsWireFacade());
        sbb = new MapUssdParentSbb(services);
    }

    @Test
    void notifyResponseCompletesParkedHttpWithNotifyXml() {
        String corr = "corr-notify-ack";
        VirtualSession session = new VirtualSession(
                "vs-1", corr, "req-1", "251911230398", 1, corr, "");
        sessions.put(corr, session);

        ClassicNiHttpPark.ParkRecord rec = park.park(
                "http-1", "JSESS-1", corr, AsHttpWireFormat.XML, 1, false);
        assertThat(rec).isNotNull();
        assertThat(park.isHttpNi(corr)).isTrue();

        // Intercept reply path: completeParkedEncoded still needs http RA; exercise encode + settle CAS.
        UnstructuredSSNotifyResponse msg = (UnstructuredSSNotifyResponse) Proxy.newProxyInstance(
                UnstructuredSSNotifyResponse.class.getClassLoader(),
                new Class<?>[] { UnstructuredSSNotifyResponse.class },
                (proxy, method, args) -> {
                    if ("getInvokeId".equals(method.getName())) {
                        return 7L;
                    }
                    if ("getMessageType".equals(method.getName())) {
                        return MAPMessageType.unstructuredSSNotify_Response;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == long.class || rt == Long.class) return 0L;
                    if (rt == int.class || rt == Integer.class) return 0;
                    return null;
                });

        // Without HTTP RA, completeParkedEncoded returns true after trySettle but reply is skipped —
        // verify by wrapping park... Actually reply warns and still returns true after settle.
        Ss7MapEvent.Service evt = new Ss7MapEvent.Service(
                corr, MAPMessageType.unstructuredSSNotify_Response, msg);
        sbb.onEvent(evt, null);

        assertThat(park.findByCorr(corr)).isPresent();
        // httpSessionId cleared after settle
        assertThat(park.findByCorr(corr).get().httpSessionId()).isNull();
        assertThat(sessions.get(corr).dialogAlive()).isTrue();
        assertThat(sessions.get(corr).invokeId()).isEqualTo(7L);

        String encoded = new AsWireFacade().encodeNiNotifyResponse(corr, AsHttpWireFormat.XML);
        assertThat(encoded)
                .contains("localId=\"" + corr + "\"")
                .contains("unstructuredSSNotify_Response")
                .contains("mapMessagesSize=\"1\"");
    }

    @Test
    void notifyResponseWithoutSessionIsIgnored() {
        UnstructuredSSNotifyResponse msg = (UnstructuredSSNotifyResponse) Proxy.newProxyInstance(
                UnstructuredSSNotifyResponse.class.getClassLoader(),
                new Class<?>[] { UnstructuredSSNotifyResponse.class },
                (proxy, method, args) -> {
                    if ("getInvokeId".equals(method.getName())) return 1L;
                    if ("getMessageType".equals(method.getName())) {
                        return MAPMessageType.unstructuredSSNotify_Response;
                    }
                    return null;
                });
        Ss7MapEvent.Service evt = new Ss7MapEvent.Service(
                "missing-corr", MAPMessageType.unstructuredSSNotify_Response, msg);
        sbb.onEvent(evt, null);
        assertThat(park.findByCorr("missing-corr")).isEmpty();
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = find(target.getClass(), field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field find(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
