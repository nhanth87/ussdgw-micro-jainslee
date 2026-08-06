package et.restlink.ussdgw.admin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPageRendererTest {
    @Test
    void stripsLeftoverTokensAndSeedsNav() throws Exception {
        Path tmp = Files.createTempDirectory("ussd-admin-ui");
        Path admin = tmp.resolve("admin");
        Files.createDirectories(admin);
        Files.writeString(admin.resolve("index.html"),
                "<html>{{NAV_LINKS}}{{NOTICE}}{{MISSING}}</html>");
        AdminPageRenderer r = new AdminPageRenderer();
        set(r, "uiDir", tmp.toString());
        AdminNavRenderer nav = new AdminNavRenderer();
        var reply = r.pageWith("index.html", nav.adminPageVars(true, Map.of()));
        String html = new String(reply.body());
        assertThat(html).contains("Dashboard").contains("Logout");
        assertThat(html).doesNotContain("{{");
    }

    @Test
    void staticResourceServesCss() throws Exception {
        Path tmp = Files.createTempDirectory("ussd-admin-static");
        Path staticDir = tmp.resolve("admin/static");
        Files.createDirectories(staticDir);
        Files.writeString(staticDir.resolve("admin.css"), "body{}");
        AdminPageRenderer r = new AdminPageRenderer();
        set(r, "uiDir", tmp.toString());
        var reply = r.staticResource("admin.css");
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.contentType()).contains("text/css");
        assertThat(new String(reply.body())).contains("body{}");
    }

    private static void set(Object target, String field, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
