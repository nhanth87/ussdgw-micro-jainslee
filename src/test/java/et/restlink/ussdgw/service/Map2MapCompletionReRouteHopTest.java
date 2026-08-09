package et.restlink.ussdgw.service;

import et.restlink.ussdgw.access.OriginationType;
import et.restlink.ussdgw.api.AsRequest;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionState;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.cdr.Map2MapCdr;
import et.restlink.ussdgw.events.Map2MapRequestEvent;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.telemetry.Map2MapTelemetry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RE_ROUTE only: hop REJECT/empty → AS {@code hlr reject}/empty {@code string=}
 * ({@code hlrResult=none}), no second GATED.
 */
class Map2MapCompletionReRouteHopTest {

    private Map2MapCompletionService completion;
    private AtomicInteger gateArms;
    private AtomicReference<AsRequest> lastAs;
    private VirtualSession session;

    @BeforeEach
    void setUp() throws Exception {
        gateArms = new AtomicInteger();
        lastAs = new AtomicReference<>();
        session = new VirtualSession("vs1", "corr1", "req1", "251911000001", 0, "dlg-in", "*804#");
        session.setState(VirtualSessionState.AWAITING_AS);
        session.setGateMs(7000L);
        session.setDialogAlive(true);
        session.setOriginationType(OriginationType.MAP);

        VirtualSessionStore store = new VirtualSessionStore() {
            @Override public Optional<VirtualSession> get(String corr) {
                return Optional.of(session);
            }
            @Override public VirtualSession put(VirtualSession s) {
                session = s;
                return s;
            }
        };
        VirtualSessionBridge bridge = new VirtualSessionBridge() {
            @Override
            public void startAwaitingAs(VirtualSession s) {
                gateArms.incrementAndGet();
                s.setState(VirtualSessionState.AWAITING_AS);
            }
        };
        AsPullRouter router = new AsPullRouter() {
            @Override
            public String route(ShortCodeRule rule, AsRequest req, String corr) {
                lastAs.set(req);
                return "http-ok";
            }
        };

        completion = new Map2MapCompletionService();
        set(completion, "store", store);
        set(completion, "bridge", bridge);
        set(completion, "asPullRouter", router);
        set(completion, "cdr", new CdrService() {
            @Override
            public void write(String correlationId, et.restlink.ussdgw.cdr.CdrPhase phase,
                              String msisdn, String shortCode, String status, String detail,
                              int networkId, String tenantId, String originationType,
                              Long gateMs, Long observedEwmaMs) {
                // no-op
            }
        });
        set(completion, "map2MapTelemetry", new Map2MapTelemetry());
    }

    @Test
    void reject_noRearm_sendsHlrReject() {
        Map2MapRequestEvent req = sample();
        String out = completion.onMap2MapResponse(req, "", Map2MapCdr.OUTCOME_REJECT);
        assertThat(out).startsWith("map2map-ok");
        assertThat(gateArms.get()).isZero();
        assertThat(lastAs.get().ussdString()).isEqualTo("hlr reject");
        assertThat(lastAs.get().originatedUssd()).isEqualTo("*804#");
    }

    @Test
    void empty_noRearm_sendsEmptyStringWithHlrNoneAttr() {
        completion.onMap2MapResponse(sample(), "", Map2MapCdr.OUTCOME_EMPTY);
        assertThat(gateArms.get()).isZero();
        assertThat(lastAs.get().ussdString()).isEmpty();
        assertThat(lastAs.get().redirectUssd()).isEqualTo("*875#");
        assertThat(lastAs.get().hopUssd()).isEqualTo("*875#");
        assertThat(lastAs.get().originatedUssd()).isEqualTo("*804#");
        String xml = et.restlink.ussdgw.api.classic.ClassicDialogXmlCodec.encodePull(lastAs.get());
        assertThat(xml)
                .contains("hlrResult=\"none\"")
                .contains("string=\"\"")
                .doesNotContain("string=\"hlr none\"");
    }

    @Test
    void hopText_rearms_andSendsText() {
        completion.onMap2MapResponse(sample(), "Balance: 10", Map2MapCdr.OUTCOME_TEXT);
        assertThat(gateArms.get()).isEqualTo(1);
        assertThat(lastAs.get().ussdString()).isEqualTo("Balance: 10");
        assertThat(lastAs.get().redirectUssd()).isEqualTo("*875#");
        assertThat(lastAs.get().hopUssd()).isEqualTo("*875#");
    }

    @Test
    void hopAmharicText_encodesInXmlString() {
        String amharic = "ውድ ደንበኛ ፣ ውጤቱ በአጭር መልእክት ተልኳል። ኢትዮ ቴሌኮም";
        completion.onMap2MapResponse(sample(), amharic, Map2MapCdr.OUTCOME_TEXT);
        assertThat(lastAs.get().ussdString()).isEqualTo(amharic);
        String xml = et.restlink.ussdgw.api.classic.ClassicDialogXmlCodec.encodePull(lastAs.get());
        assertThat(xml)
                .contains("hlrResult=\"responded\"")
                .contains("string=\"" + amharic + "\"")
                .contains("redirectUssd=\"*875#\"")
                .doesNotContain("string=\"hlr none\"");
    }

    @Test
    void secondComplete_isIdempotent_keepsFirstHopText() {
        completion.onMap2MapResponse(sample(), "first hop", Map2MapCdr.OUTCOME_TEXT);
        String second = completion.onMap2MapResponse(sample(), "", Map2MapCdr.OUTCOME_CLOSE);
        assertThat(second).isEqualTo("map2map-already-routed");
        assertThat(lastAs.get().ussdString()).isEqualTo("first hop");
        assertThat(gateArms.get()).isEqualTo(1);
    }

    @Test
    void close_noRearm_stillExposesRedirectAndHopCodes() {
        completion.onMap2MapResponse(sample(), "", Map2MapCdr.OUTCOME_CLOSE);
        assertThat(gateArms.get()).isZero();
        assertThat(lastAs.get().ussdString()).isEmpty();
        assertThat(lastAs.get().redirectUssd()).isEqualTo("*875#");
        assertThat(lastAs.get().shortCode()).isEqualTo("*804#");
    }

    private static Map2MapRequestEvent sample() {
        return new Map2MapRequestEvent(
                "corr1", "m2m-corr1", "dlg-in", 7L, "251911000001", "*804#", "*804#",
                "*875#", "https://as.example/ussd", RuleType.HTTP, 0, null,
                "vs1", "req1", false, null, "251971200201", 6);
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
