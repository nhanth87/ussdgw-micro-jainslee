package et.restlink.ussdgw.config;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production profile (docker/capacity/stack.prod.yml + ussdgw-prod.env) sizes the gateway
 * through environment variables. A key written literally in configs/application.properties
 * would beat them (measured on digicom-nb: the operator file outranks env), so the sizing keys in
 * the shipped operator config are {@code ${ENV:default}}. This loads that very file with the file
 * OUTRANKING env (worst case) and Quarkus' expression handling, and checks both directions:
 * env set → env value; env absent → the shipped default.
 */
class OperatorConfigEnvOverrideTest {

    private static final Map<String, String> PROD_ENV = Map.of(
            "QUARKUS_DATASOURCE_JDBC_MAX_SIZE", "96",
            "QUARKUS_DATASOURCE_JDBC_MIN_SIZE", "16",
            "USSD_HTTP_CLIENT_MAX_POOL_SIZE", "12288",
            "USSD_HTTP_CLIENT_RA_INSTANCES", "0",
            "USSD_TX_MAX_SESSION_MS", "600000",
            "USSD_CDR_RECENT_EVENTS", "500000");

    @Test
    void prodEnvReachesTheSizingKeys() throws IOException {
        SmallRyeConfig cfg = config(PROD_ENV);
        assertThat(cfg.getValue("quarkus.datasource.jdbc.max-size", Integer.class)).isEqualTo(96);
        assertThat(cfg.getValue("ussd.http.client.max-pool-size", Integer.class)).isEqualTo(12288);
        assertThat(cfg.getValue("ussd.cdr.recent-events", Integer.class)).isEqualTo(500000);
        assertThat(cfg.getValue("ussd.tx.max-session-ms", Long.class)).isEqualTo(600000L);
    }

    @Test
    void withoutEnvTheShippedDefaultsApply() throws IOException {
        SmallRyeConfig cfg = config(Map.of());
        assertThat(cfg.getValue("quarkus.datasource.jdbc.max-size", Integer.class)).isEqualTo(128);
        assertThat(cfg.getValue("ussd.http.client.max-pool-size", Integer.class)).isEqualTo(2048);
        assertThat(cfg.getValue("ussd.http.client.ra-instances", Integer.class)).isZero();
        assertThat(cfg.getValue("ussd.cdr.recent-events", Integer.class)).isEqualTo(50000);
    }

    private static SmallRyeConfig config(Map<String, String> env) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("dist/configs/application.properties"))) {
            p.load(in);
        }
        Map<String, String> file = new java.util.HashMap<>();
        p.forEach((k, v) -> file.put((String) k, (String) v));
        return new SmallRyeConfigBuilder()
                .addDefaultInterceptors()                                         // ${...} expansion, as in Quarkus
                .withSources(new PropertiesConfigSource(file, "operator-file", 400)) // file outranks env
                .withSources(new EnvConfigSource(env, 300))
                .build();
    }
}
