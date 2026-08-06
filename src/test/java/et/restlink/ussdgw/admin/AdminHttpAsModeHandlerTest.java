package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.service.HttpApplyService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AdminHttpAsModeHandlerTest {
    private AdminHttpAsModeHandler handler;
    private final List<AsResponse> injected = new ArrayList<>();
    private VirtualSession session;

    @BeforeEach
    void setUp() {
        injected.clear();
        UssdConfigService cfg = new UssdConfigService();
        set(cfg, "httpClientEnabledProp", true);
        set(cfg, "httpConnectMsProp", 2000);
        set(cfg, "httpRequestMsProp", 5000);
        set(cfg, "asyncGateTimeoutMsProp", 7000L);
        set(cfg, "dialogTimeoutMsProp", 60_000L);
        set(cfg, "asyncWaitMessageProp", "wait");
        set(cfg, "asyncHardFailMessageProp", "fail");
        set(cfg, "httpClientBridgeEnabledProp", true);
        set(cfg, "httpServerEnabledProp", true);
        set(cfg, "httpCallbackPathProp", "/as/callback");

        session = new VirtualSession("vs", "corr-lab", "req-1", "2519", 1, "d1", "*123#");
        session.setTenantId("bank-a");
        session.setGeneration(2);

        VirtualSessionStore sessions = new VirtualSessionStore() {
            @Override public Optional<VirtualSession> get(String correlationId) {
                return "corr-lab".equals(correlationId) ? Optional.of(session) : Optional.empty();
            }
        };
        VirtualSessionBridge bridge = new VirtualSessionBridge() {
            @Override public void onAsResponse(AsResponse response, long latencyMs) {
                injected.add(response);
            }
        };
        HttpApplyService httpApply = new HttpApplyService() {
            @Override public String apply() { return "http-applied"; }
            @Override public String start() { return "http-started"; }
            @Override public String stop() { return "http-stopped"; }
            @Override public String listenHost() { return "0.0.0.0"; }
            @Override public int listenPort() { return 8088; }
        };

        handler = new AdminHttpAsModeHandler();
        set(handler, "config", cfg);
        set(handler, "store", new et.restlink.ussdgw.config.RuntimeConfigStore() {
            private final java.util.Map<String, String> m = new java.util.concurrent.ConcurrentHashMap<>();
            @Override public synchronized void putAll(java.util.Map<String, String> kv) {
                if (kv != null) m.putAll(kv);
            }
        });
        set(handler, "httpApply", httpApply);
        set(handler, "bridge", bridge);
        set(handler, "sessions", sessions);
        set(handler, "labMo", null);
    }

    @Test
    void syncGetShowsClientConfig() {
        AdminHttpHandler.HttpReply r = handler.get("sync", new AdminAuthService.Principal("ADMIN", null));
        assertThat(r.status()).isEqualTo(200);
        String html = new String(r.body());
        assertThat(html).contains("HTTP AS · Sync").contains("clientEnabled");
    }

    @Test
    void tenantCannotPostConfig() {
        AdminHttpHandler.HttpReply r = handler.post("sync",
                "action=save&clientEnabled=false",
                new AdminAuthService.Principal("TENANT", "bank-a"));
        assertThat(r.status()).isEqualTo(403);
    }

    @Test
    void tenantCanLabInjectOwnSession() {
        AdminHttpHandler.HttpReply r = handler.post("sync",
                "action=labInject&correlationId=corr-lab&generation=2&text=hi&asAction=END",
                new AdminAuthService.Principal("TENANT", "bank-a"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(new String(r.body())).contains("injected");
        assertThat(injected).hasSize(1);
        assertThat(injected.getFirst().async()).isFalse();
        assertThat(injected.getFirst().text()).isEqualTo("hi");
        assertThat(injected.getFirst().action()).isEqualTo(AsAction.END);
    }

    @Test
    void asyncLabInjectSetsAsyncTrue() {
        handler.post("async",
                "action=labInject&correlationId=corr-lab&generation=2",
                new AdminAuthService.Principal("ADMIN", null));
        assertThat(injected).hasSize(1);
        assertThat(injected.getFirst().async()).isTrue();
    }

    @Test
    void tenantLabInjectOtherTenantForbidden() {
        AdminHttpHandler.HttpReply r = handler.post("callback",
                "action=labInject&correlationId=corr-lab&text=x",
                new AdminAuthService.Principal("TENANT", "other"));
        assertThat(new String(r.body())).contains("error:");
        assertThat(injected).isEmpty();
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
