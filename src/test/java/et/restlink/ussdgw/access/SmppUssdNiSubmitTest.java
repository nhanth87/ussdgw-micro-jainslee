package et.restlink.ussdgw.access;

import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.ra.smpp.SmppEndpointRegistry;
import et.restlink.ussdgw.ra.smpp.SmppRaEndpoint;
import et.restlink.ussdgw.ra.smpp.command.SmppCommand;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SmppUssdNiSubmitTest {
    private final List<String> cdrStatus = new ArrayList<>();
    private final List<SmppCommand> sent = new ArrayList<>();
    private UssdConfigService cfg;
    private SmppUssdAccessAdapter smpp;

    @BeforeEach
    void setUp() {
        cfg = new UssdConfigService();
        set(cfg, "smppUssdEnabledProp", true);
        cdrStatus.clear();
        sent.clear();

        smpp = new SmppUssdAccessAdapter() {
            @Override
            protected void sendSubmitSm(SmppRaEndpoint client, SmppCommand.SubmitSm cmd) {
                sent.add(cmd);
            }
        };
        set(smpp, "config", cfg);
        set(smpp, "cdr", new CdrService() {
            @Override
            public void write(String correlationId, CdrPhase phase,
                              String msisdn, String shortCode, String status, String detail,
                              int networkId, String tenantId, String originationType) {
                cdrStatus.add(status);
            }
        });
    }

    @Test
    void disabledUsesStubQueuedWithoutSubmit() {
        set(cfg, "smppUssdEnabledProp", false);
        set(smpp, "smppRegistry", registryWithClient());
        smpp.requestNiPush(session(), "hello");
        assertThat(cdrStatus).containsExactly("STUB_QUEUED");
        assertThat(sent).isEmpty();
        assertThat(smpp.niCount()).isEqualTo(1);
    }

    @Test
    void enabledWithoutClientWritesNoSmppClient() {
        set(smpp, "smppRegistry", new SmppEndpointRegistry());
        smpp.requestNiPush(session(), "hello");
        assertThat(cdrStatus).containsExactly("NO_SMPP_CLIENT");
        assertThat(sent).isEmpty();
    }

    @Test
    void enabledWithClientSubmitsSm() {
        set(smpp, "smppRegistry", registryWithClient());
        smpp.requestNiPush(session(), "hello");
        assertThat(cdrStatus).containsExactly("SUBMITTED");
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()).isInstanceOf(SmppCommand.SubmitSm.class);
        SmppCommand.SubmitSm sm = (SmppCommand.SubmitSm) sent.getFirst();
        assertThat(sm.destAddr()).isEqualTo("251911000000");
        assertThat(sm.tpUd()).isNotEmpty();
    }

    private static SmppEndpointRegistry registryWithClient() {
        SmppEndpointRegistry reg = new SmppEndpointRegistry();
        try {
            var f = SmppEndpointRegistry.class.getDeclaredField("clients");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, SmppRaEndpoint> clients = (Map<String, SmppRaEndpoint>) f.get(reg);
            clients.put("test", new SmppRaEndpoint("test-client"));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return reg;
    }

    private static VirtualSession session() {
        VirtualSession s = new VirtualSession(
                "vs", "corr-ni", "req", "251911000000", 1, "smpp-1", "*123#");
        s.setOriginationType(OriginationType.SMPP);
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
