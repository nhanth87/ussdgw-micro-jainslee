package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.config.RuntimeConfigStore;
import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.service.GrpcApplyService;
import et.restlink.ussdgw.service.HttpApplyService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPlaneSaveApplyTest {
    private AdminPlaneHandler planes;
    private final AtomicBoolean httpApplied = new AtomicBoolean();
    private final AtomicBoolean grpcApplied = new AtomicBoolean();
    private final AtomicReference<Map<String, String>> lastKv = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        planes = new AdminPlaneHandler();
        UssdConfigService cfg = new UssdConfigService();
        set(planes, "config", cfg);
        set(planes, "linkStatus", new LinkStatusService());
        RuntimeConfigStore store = new RuntimeConfigStore() {
            @Override
            public synchronized void putAll(Map<String, String> entries) {
                lastKv.set(entries == null ? Map.of() : Map.copyOf(entries));
            }

            @Override
            public void put(String key, String value) {
                // no JPA in unit test
            }
        };
        set(planes, "store", store);
        set(planes, "httpApply", new HttpApplyService() {
            @Override
            public String apply() {
                httpApplied.set(true);
                return "http-applied-ok";
            }

            @Override
            public String listenHost() {
                return "127.0.0.1";
            }

            @Override
            public int listenPort() {
                return 8088;
            }
        });
        set(planes, "grpcApply", new GrpcApplyService() {
            @Override
            public String apply() {
                grpcApplied.set(true);
                return "grpc-applied-ok";
            }

            @Override
            public int listenPort() {
                return 9099;
            }
        });
    }

    @Test
    void httpSaveApplyCallsApplyAndReturnsNotice() {
        AdminHttpHandler.HttpReply r = planes.httpPost(
                "action=saveApply&clientEnabled=true&serverEnabled=false&listenHost=127.0.0.1&listenPort=8088");
        assertThat(httpApplied).isTrue();
        assertThat(lastKv.get()).isNotNull().containsKey(RuntimeConfigStore.Keys.HTTP_CLIENT_ENABLED);
        String html = new String(r.body());
        assertThat(html).contains("http-applied-ok").contains("http-panel");
    }

    @Test
    void grpcSaveApplyCallsApplyAndReturnsNotice() {
        AdminHttpHandler.HttpReply r = planes.grpcPost(
                "action=saveApply&clientEnabled=true&serverEnabled=true&listenPort=9099");
        assertThat(grpcApplied).isTrue();
        assertThat(lastKv.get()).isNotNull().containsKey(RuntimeConfigStore.Keys.GRPC_CLIENT_ENABLED);
        String html = new String(r.body());
        assertThat(html).contains("grpc-applied-ok").contains("grpc-panel");
    }

    @Test
    void shellMapsHttpConfig() {
        assertThat(AdminHttpHandler.shellTemplateName("/admin/http/config")).isEqualTo("http.html");
        assertThat(AdminHttpHandler.shellTemplateName("/admin/ss7/config")).isEqualTo("ss7.html");
        assertThat(AdminHttpHandler.shellTemplateName("/admin/smpp/config")).isEqualTo("smpp.html");
        assertThat(AdminHttpHandler.shellTemplateName("/admin/grpc")).isEqualTo("grpc.html");
        assertThat(AdminHttpHandler.shellTemplateName("/admin/routing")).isEqualTo("routing.html");
    }

    @Test
    void navPointsPlanesAtConfigShells() {
        String nav = AdminNavRenderer.adminNavLinks(true);
        assertThat(nav).contains("href=\"/admin/ss7/config\"")
                .contains("href=\"/admin/smpp/config\"")
                .contains("href=\"/admin/http/config\"")
                .contains("href=\"/admin/grpc\"")
                .contains("href=\"/admin/routing\"")
                .contains("href=\"/telemetry/\"");
        assertThat(nav).doesNotContain("href=\"/telemetry/?tab=ss7\"");
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
