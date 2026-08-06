package et.restlink.ussdgw.access;

import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.service.DiameterApplyService;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiameterUssdNiLiveTest {
    private final List<String> cdrStatus = new ArrayList<>();
    private final List<OutboundCommand> sent = new ArrayList<>();
    private UssdConfigService cfg;
    private DiameterUssdAccessAdapter diameter;

    @BeforeEach
    void setUp() {
        cfg = new UssdConfigService();
        set(cfg, "diameterEnabledProp", true);
        cdrStatus.clear();
        sent.clear();

        diameter = new DiameterUssdAccessAdapter() {
            @Override
            protected void sendDiameter(RaCommandPort port, SendDiameterRequest cmd) {
                sent.add(cmd);
            }
        };
        set(diameter, "config", cfg);
        set(diameter, "destHost", "peer.restlink.local");
        set(diameter, "destRealm", "restlink.local");
        set(diameter, "cdr", new CdrService() {
            @Override
            public void write(String correlationId, CdrPhase phase,
                              String msisdn, String shortCode, String status, String detail,
                              int networkId, String tenantId, String originationType) {
                cdrStatus.add(status);
            }
        });
        DiameterApplyService apply = new DiameterApplyService();
        set(diameter, "diameterApply", apply);
    }

    @Test
    void disabledUsesStubQueued() {
        set(cfg, "diameterEnabledProp", false);
        diameter.setDiameterPortSupplier(() -> cmd -> sent.add(cmd));
        diameter.requestNiPush(session(), "hello");
        assertThat(cdrStatus).containsExactly("STUB_QUEUED");
        assertThat(sent).isEmpty();
    }

    @Test
    void enabledWithCapturingPortSendsDiameterRequest() {
        diameter.setDiameterPortSupplier(() -> cmd -> { /* overridden by sendDiameter */ });
        diameter.requestNiPush(session(), "hello");
        assertThat(cdrStatus).containsExactly("DIAMETER_SENT");
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()).isInstanceOf(SendDiameterRequest.class);
        SendDiameterRequest req = (SendDiameterRequest) sent.getFirst();
        assertThat(req.applicationId()).isEqualTo(DiameterUssdCodes.USSD_APP_ID);
        assertThat(req.commandCode()).isEqualTo(DiameterUssdCodes.USSD_REQUEST);
        assertThat(req.avps().get(DiameterUssdCodes.AVP_USER_NAME)).isEqualTo("251911000000");
        assertThat(req.avps().get(DiameterUssdCodes.AVP_USSD_STRING)).isEqualTo("hello");
    }

    private static VirtualSession session() {
        VirtualSession s = new VirtualSession(
                "vs", "corr-d", "req", "251911000000", 1, "dia-1", "*123#");
        s.setOriginationType(OriginationType.DIAMETER);
        return s;
    }

    private static void set(Object target, String field, Object value) {
        try {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    var f = c.getDeclaredField(field);
                    f.setAccessible(true);
                    f.set(target, value);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            throw new NoSuchFieldException(field);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
