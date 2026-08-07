package et.restlink.ussdgw.access;

import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.service.SipApplyService;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.sipservlet.command.SendMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SipUssiNiLiveTest {
    private final List<String> cdrStatus = new ArrayList<>();
    private final List<OutboundCommand> sent = new ArrayList<>();
    private UssdConfigService cfg;
    private SipUssiAccessAdapter sip;

    @BeforeEach
    void setUp() {
        cfg = new UssdConfigService();
        set(cfg, "sipEnabledProp", true);
        cdrStatus.clear();
        sent.clear();

        sip = new SipUssiAccessAdapter() {
            @Override
            protected void sendMessage(RaCommandPort port, SendMessage cmd) {
                sent.add(cmd);
            }
        };
        set(sip, "config", cfg);
        set(cfg, "sipRequestUriProp", "sip:{msisdn}@ussd.restlink.local");
        set(sip, "cdr", new CdrService() {
            @Override
            public void write(String correlationId, CdrPhase phase,
                              String msisdn, String shortCode, String status, String detail,
                              int networkId, String tenantId, String originationType) {
                cdrStatus.add(status);
            }
        });
        SipApplyService apply = new SipApplyService();
        set(apply, "config", cfg);
        set(cfg, "sipFromUriProp", "sip:ussdgw@restlink.local");
        set(sip, "sipApply", apply);
    }

    @Test
    void disabledUsesStubQueued() {
        set(cfg, "sipEnabledProp", false);
        sip.setSipPortSupplier(() -> cmd -> sent.add(cmd));
        sip.requestNiPush(session(), "hello");
        assertThat(cdrStatus).containsExactly("STUB_QUEUED");
        assertThat(sent).isEmpty();
    }

    @Test
    void enabledWithCapturingPortSendsMessage() {
        sip.setSipPortSupplier(() -> cmd -> { });
        sip.requestNiPush(session(), "hello");
        assertThat(cdrStatus).containsExactly("SIP_SENT");
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()).isInstanceOf(SendMessage.class);
        SendMessage msg = (SendMessage) sent.getFirst();
        assertThat(msg.toUri()).isEqualTo("sip:251911000000@ussd.restlink.local");
        assertThat(msg.body()).isEqualTo("hello");
    }

    private static VirtualSession session() {
        VirtualSession s = new VirtualSession(
                "vs", "corr-s", "req", "251911000000", 1, "sip-1", "*123#");
        s.setOriginationType(OriginationType.SIP);
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
