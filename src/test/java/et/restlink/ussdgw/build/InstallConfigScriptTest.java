package et.restlink.ussdgw.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledOnOs(OS.LINUX)
class InstallConfigScriptTest {

    @TempDir
    Path tmp;

    @Test
    void neverClobbersExistingDest() throws Exception {
        Path script = Path.of("build/install-config.sh").toAbsolutePath().normalize();
        assertThat(script).exists();

        Path src = tmp.resolve("packaged.properties");
        Path dest = tmp.resolve("configs/application.properties");
        Files.createDirectories(dest.getParent());
        Files.writeString(src, "quarkus.datasource.db-kind=h2\n", StandardCharsets.UTF_8);
        Files.writeString(dest, "quarkus.datasource.db-kind=postgresql\n", StandardCharsets.UTF_8);

        Process p = new ProcessBuilder("bash", script.toString(), src.toString(), dest.toString())
                .directory(Path.of(".").toAbsolutePath().normalize().toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isEqualTo(0);
        assertThat(out).contains("KEPT existing");
        assertThat(Files.readString(dest)).contains("postgresql");
        assertThat(tmp.resolve("configs/application.properties.new")).exists();
        assertThat(Files.readString(tmp.resolve("configs/application.properties.new")))
                .contains("db-kind=h2");
    }

    @Test
    void installsWhenAbsent() throws Exception {
        Path script = Path.of("build/install-config.sh").toAbsolutePath().normalize();
        Path src = tmp.resolve("packaged.properties");
        Path dest = tmp.resolve("configs/application.properties");
        Files.writeString(src, "ussd.lab.allow-default-secrets=true\n", StandardCharsets.UTF_8);

        Process p = new ProcessBuilder("bash", script.toString(), src.toString(), dest.toString())
                .redirectErrorStream(true)
                .start();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isEqualTo(0);
        assertThat(Files.readString(dest)).contains("allow-default-secrets=true");
        assertThat(tmp.resolve("configs/application.properties.new")).doesNotExist();
    }
}
