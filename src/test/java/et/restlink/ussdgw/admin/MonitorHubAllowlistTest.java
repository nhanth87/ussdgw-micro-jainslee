package et.restlink.ussdgw.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MonitorHubAllowlistTest {

    @Test
    void staticAssetsRemainPublic() {
        assertThat(AdminHttpHandler.isPublicMonitorStatic("GET", "/telemetry/app.js")).isTrue();
        assertThat(AdminHttpHandler.isPublicMonitorStatic("GET", "/telemetry/style.css")).isTrue();
        assertThat(AdminHttpHandler.isPublicMonitorStatic("GET", "/admin/ra/panel.js")).isTrue();
    }

    @Test
    void livePartialsRequireAuth() {
        assertThat(AdminHttpHandler.isPublicMonitorStatic("GET", "/telemetry/partial/ss7")).isFalse();
        assertThat(AdminHttpHandler.isPublicMonitorStatic("GET", "/telemetry/")).isFalse();
        assertThat(AdminHttpHandler.isPublicMonitorStatic("GET", "/telemetry")).isFalse();
        assertThat(AdminHttpHandler.isPublicMonitorStatic("POST", "/telemetry/app.js")).isFalse();
    }
}
