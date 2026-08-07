package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.bridge.VirtualSessionBridge;
import et.restlink.ussdgw.bridge.VirtualSessionStore;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.config.UssdConfigService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AdminHttpHandlerRoutingTest {
    private AdminHttpHandler handler;

    @BeforeEach
    void setUp() {
        handler = new AdminHttpHandler();
        UssdConfigService cfg = new UssdConfigService();
        set(cfg, "adminApiKey", "ussd-admin");
        set(cfg, "bridgeEnabledProp", true);
        set(cfg, "asyncGateTimeoutMsProp", 7000L);
        set(cfg, "asyncWaitMessageProp", "Please wait...");
        set(cfg, "asyncHardFailMessageProp", "unavailable");
        set(handler, "config", cfg);
        AdminAuthService adminAuth = new AdminAuthService();
        set(adminAuth, "config", cfg);
        set(adminAuth, "tenantGuard", new et.restlink.ussdgw.tenant.TenantGuard());
        set(adminAuth, "users", new et.restlink.ussdgw.tenant.AdminUserService());
        set(handler, "adminAuth", adminAuth);
        set(handler, "bridgeGate", new et.restlink.ussdgw.service.BridgeGateScheduler());
        set(handler, "asPull", new et.restlink.ussdgw.service.AsPullClient());
        set(handler, "saga", new et.restlink.ussdgw.bridge.UssdSagaCoordinator());
        set(handler, "linkStatus", new LinkStatusService());
        set(handler, "cdr", new CdrService() {
            @Override
            public java.util.List<et.restlink.ussdgw.cdr.CdrRecord> listRecords(int limit) {
                return java.util.List.of();
            }
            @Override
            public java.util.List<et.restlink.ussdgw.cdr.CdrRecord> listRecords(int limit, String tenantId) {
                return java.util.List.of();
            }
        });
        set(handler, "bridge", new VirtualSessionBridge());
        set(handler, "store", new VirtualSessionStore());
        set(handler, "adaptive", new AdaptiveTimeout());
        AdminCatalogHandler catalog = new AdminCatalogHandler() {
            @Override public AdminHttpHandler.HttpReply routingGet() {
                return AdminHttpHandler.HttpReply.html("<div>routing-ok</div>");
            }
            @Override public AdminHttpHandler.HttpReply routingGet(AdminAuthService.Principal who) {
                return routingGet();
            }
            @Override public AdminHttpHandler.HttpReply tenantsGet() {
                return AdminHttpHandler.HttpReply.html("<div>tenants-ok</div>");
            }
            @Override public AdminHttpHandler.HttpReply tenantsGet(AdminAuthService.Principal who) {
                return tenantsGet();
            }
            @Override public AdminHttpHandler.HttpReply usersGet() {
                return AdminHttpHandler.HttpReply.html("<div>users-ok</div>");
            }
            @Override public AdminHttpHandler.HttpReply usersGet(AdminAuthService.Principal who) {
                return usersGet();
            }
        };
        set(handler, "catalog", catalog);
        AdminPlaneHandler planes = new AdminPlaneHandler() {
            @Override public AdminHttpHandler.HttpReply ss7Get() {
                return AdminHttpHandler.HttpReply.html("<div class=\"ss7-panel\">jSS7 form mapEnabled</div>");
            }
            @Override public AdminHttpHandler.HttpReply ss7Post(String body) {
                return AdminHttpHandler.HttpReply.html("<pre>ss7=test-apply</pre><div class=\"ss7-panel\">ok</div>");
            }
            @Override public AdminHttpHandler.HttpReply smppGet() {
                return AdminHttpHandler.HttpReply.html("<div class=\"smpp-panel\">SMPP</div>");
            }
            @Override public AdminHttpHandler.HttpReply smppPost(String body) {
                return AdminHttpHandler.HttpReply.html("<pre>smpp=test-apply</pre>");
            }
            @Override public AdminHttpHandler.HttpReply httpGet() {
                return AdminHttpHandler.HttpReply.html(
                        "<div class=\"http-panel\">SYNC ASYNC_ACK BRIDGE clientBridgeEnabled</div>");
            }
            @Override public AdminHttpHandler.HttpReply grpcGet() {
                return AdminHttpHandler.HttpReply.html(
                        "<div class=\"grpc-panel\">SYNC ASYNC clientBridgeEnabled</div>");
            }
            @Override public AdminHttpHandler.HttpReply bridgeGet() {
                return AdminHttpHandler.HttpReply.html("<div class=\"bridge-panel\">asyncGateTimeoutMs</div>");
            }
        };
        set(handler, "planes", planes);
        set(handler, "campaigns", new AdminCampaignHandler() {
            @Override public AdminHttpHandler.HttpReply get(AdminAuthService.Principal who) {
                return AdminHttpHandler.HttpReply.html("<div>campaigns-ok</div>");
            }
        });
        set(handler, "labMo", new AdminLabMoHandler() {
            @Override public AdminHttpHandler.HttpReply get(AdminAuthService.Principal who) {
                return AdminHttpHandler.HttpReply.html("<div class=\"lab-mo-panel\">lab-mo-ok</div>");
            }
        });
        set(handler, "httpAsModes", new AdminHttpAsModeHandler() {
            @Override public AdminHttpHandler.HttpReply get(String panel, AdminAuthService.Principal who) {
                return AdminHttpHandler.HttpReply.html(
                        "<div class=\"catalog http-as-" + panel + "-panel\">" + panel + "-ok</div>");
            }
        });
    }

    @Test
    void healthIsPublic() {
        Optional<AdminHttpHandler.HttpReply> r = handler.tryHandle(
                "GET", "/health", Map.of(), Map.of(), null);
        assertThat(r).isPresent();
        assertThat(r.get().status()).isEqualTo(200);
    }

    @Test
    void rootRedirectsToLoginWithoutSession() {
        Optional<AdminHttpHandler.HttpReply> r = handler.tryHandle(
                "GET", "/", Map.of(), Map.of(), null);
        assertThat(r).isPresent();
        assertThat(r.get().status()).isEqualTo(302);
        assertThat(r.get().headers().get("Location")).isEqualTo("/admin/login");
    }

    @Test
    void rootIgnoresApiKeyForBrowserRedirect() {
        Optional<AdminHttpHandler.HttpReply> r = handler.tryHandle(
                "GET", "/", Map.of("X-USSD-Admin-Key", "ussd-admin"), Map.of(), null);
        assertThat(r).isPresent();
        assertThat(r.get().status()).isEqualTo(302);
        assertThat(r.get().headers().get("Location")).isEqualTo("/admin/login");
    }

    @Test
    void dashboardShellRequiresSessionNotJustApiKey() {
        Optional<AdminHttpHandler.HttpReply> r = handler.tryHandle(
                "GET", "/admin", Map.of("X-USSD-Admin-Key", "ussd-admin"), Map.of(), null);
        assertThat(r).isPresent();
        assertThat(r.get().status()).isEqualTo(302);
        assertThat(r.get().headers().get("Location")).isEqualTo("/admin/login");
    }

    @Test
    void adminRequiresKey() {
        Optional<AdminHttpHandler.HttpReply> r = handler.tryHandle(
                "GET", "/admin/status", Map.of(), Map.of(), null);
        assertThat(r).isPresent();
        assertThat(r.get().status()).isEqualTo(401);
    }

    @Test
    void ss7GetRedirectsToHub() {
        Optional<AdminHttpHandler.HttpReply> r = handler.tryHandle(
                "GET", "/admin/ss7",
                Map.of("X-USSD-Admin-Key", "ussd-admin"), Map.of(), null);
        assertThat(r).isPresent();
        assertThat(r.get().status()).isEqualTo(302);
        assertThat(r.get().headers().get("Location")).contains("/telemetry/?tab=ss7");
    }

    @Test
    void smppGetRedirectsToHub() {
        Optional<AdminHttpHandler.HttpReply> r = handler.tryHandle(
                "GET", "/admin/smpp",
                Map.of("X-USSD-Admin-Key", "ussd-admin"), Map.of("key", "ussd-admin"), null);
        assertThat(r).isPresent();
        assertThat(r.get().status()).isEqualTo(302);
        assertThat(r.get().headers().get("Location")).contains("tab=smpp").contains("key=ussd-admin");
    }

    @Test
    void httpConfigPanelHasModes() {
        Optional<AdminHttpHandler.HttpReply> r = handler.tryHandle(
                "GET", "/admin/http/config",
                authHx(), Map.of(), null);
        assertThat(r).isPresent();
        assertThat(new String(r.get().body())).contains("SYNC").contains("BRIDGE");
    }

    @Test
    void ss7ApplyPost() {
        Optional<AdminHttpHandler.HttpReply> r = handler.tryHandle(
                "POST", "/admin/ss7/apply",
                Map.of("X-USSD-Admin-Key", "ussd-admin"), Map.of(), null);
        assertThat(r).isPresent();
        assertThat(new String(r.get().body())).contains("ss7=test-apply");
        assertThat(r.get().contentType()).contains("text/html");
    }

    @Test
    void smppApplyPost() {
        Optional<AdminHttpHandler.HttpReply> r = handler.tryHandle(
                "POST", "/admin/smpp/apply",
                Map.of("X-USSD-Admin-Key", "ussd-admin"), Map.of(), null);
        assertThat(r).isPresent();
        assertThat(new String(r.get().body())).contains("smpp=test-apply");
    }

    @Test
    void routingPanel() {
        Optional<AdminHttpHandler.HttpReply> r = handler.tryHandle(
                "GET", "/admin/routing",
                authHx(), Map.of(), null);
        assertThat(r).isPresent();
        assertThat(new String(r.get().body())).contains("routing-ok");
    }

    @Test
    void campaignsPanel() {
        Optional<AdminHttpHandler.HttpReply> r = handler.tryHandle(
                "GET", "/admin/campaigns",
                authHx(), Map.of(), null);
        assertThat(r).isPresent();
        assertThat(new String(r.get().body())).contains("campaigns-ok");
    }

    @Test
    void labMoPanel() {
        Optional<AdminHttpHandler.HttpReply> r = handler.tryHandle(
                "GET", "/admin/lab/mo",
                authHx(), Map.of(), null);
        assertThat(r).isPresent();
        assertThat(new String(r.get().body())).contains("lab-mo-ok");
    }

    @Test
    void httpSyncAsyncCallbackPanels() {
        for (String path : new String[]{"/admin/http/sync", "/admin/http/async", "/admin/http/callback"}) {
            Optional<AdminHttpHandler.HttpReply> r = handler.tryHandle(
                    "GET", path,
                    authHx(), Map.of(), null);
            assertThat(r).as(path).isPresent();
            assertThat(new String(r.get().body())).contains("-ok");
        }
    }

    @Test
    void tenantsPanel() {
        Optional<AdminHttpHandler.HttpReply> r = handler.tryHandle(
                "GET", "/admin/tenants",
                authHx(), Map.of(), null);
        assertThat(r).isPresent();
        assertThat(new String(r.get().body())).contains("tenants-ok");
    }

    @Test
    void hubPartialSs7() {
        Optional<AdminHttpHandler.HttpReply> r = handler.tryHandle(
                "GET", "/admin/hub",
                authHx(),
                Map.of("tab", "ss7"), null);
        assertThat(r).isPresent();
        assertThat(new String(r.get().body())).contains("SS7");
    }

    @Test
    void monitorHubPathDetection() {
        assertThat(AdminHttpHandler.isMonitorHubPath("/telemetry/")).isTrue();
        assertThat(AdminHttpHandler.isMonitorHubPath("/api/ra/smpp-ra/status")).isTrue();
        assertThat(AdminHttpHandler.isPublicMonitorStatic("GET", "/telemetry/")).isTrue();
    }

    private static Map<String, String> authHx() {
        return Map.of("X-USSD-Admin-Key", "ussd-admin", "HX-Request", "true");
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
