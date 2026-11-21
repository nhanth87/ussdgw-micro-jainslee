package et.restlink.ussdgw.bootstrap;

import et.restlink.ussdgw.profile.UssdTxProfile;
import et.restlink.ussdgw.profile.UssdUserProfile;

import com.microjainslee.cluster.ClusterManager;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * G9 — feature-flagged REPL_SYNC Profile CMP cluster.
 *
 * <ul>
 *   <li>{@code ussd.cluster.enabled=false} → stub, {@code clusterManager()} null, inactive logged.</li>
 *   <li>{@code enabled=true} + simulated construct failure → no throw, stub + WARN blocker.</li>
 *   <li>{@code enabled=true} + real construct (when the classpath Infinispan API allows) →
 *       non-null manager, {@code REPL_SYNC} caches; otherwise the fail-safe branch is asserted.</li>
 * </ul>
 */
class ClusterBootstrapTest {

    @TempDir
    Path tempDir;

    private CapturingAppender appender;
    private LoggerContext ctx;
    private Configuration config;
    private ClusterBootstrap bootstrap;

    @BeforeEach
    void attachAppender() {
        ctx = (LoggerContext) LogManager.getContext(false);
        config = ctx.getConfiguration();
        appender = new CapturingAppender("cluster-bootstrap-test");
        appender.start();
        config.addAppender(appender);
        config.getLoggerConfig(ClusterBootstrap.class.getName()).addAppender(appender, Level.INFO, null);
        ctx.updateLoggers();
    }

    @AfterEach
    void detachAppenderAndStopCluster() {
        if (bootstrap != null) {
            bootstrap.shutdown();
            bootstrap = null;
        }
        if (config != null && appender != null) {
            config.getLoggerConfig(ClusterBootstrap.class.getName()).removeAppender(appender.getName());
            config.getAppenders().remove(appender.getName());
            appender.stop();
            ctx.updateLoggers();
        }
    }

    @Test
    void disabledKeepsStubAndLogsInactive() {
        bootstrap = newInstance();
        bootstrap.enabled = false;

        assertThatCode(() -> bootstrap.onStart(null)).doesNotThrowAnyException();

        assertThat(bootstrap.clusterManager()).isNull();
        assertThat(bootstrap.clusterActive()).isFalse();
        assertThat(bootstrap.clusterMode()).isEqualTo("OFF");
        assertThat(bootstrap.blocker()).isNull();
        assertThat(bootstrap.status()).containsEntry("cluster.enabled", false)
                .containsEntry("cluster.active", false)
                .containsEntry("cluster.mode", "OFF")
                .containsEntry("cluster.nodeId", "ussd-g9-test");
        assertThat(appender.saw("ClusterBootstrap inactive")).isTrue();
        assertThat(appender.saw("ussd.cluster.enabled=false")).isTrue();
    }

    @Test
    void enabledWithSimulatedConstructFailureFallsBackToStub() {
        bootstrap = new ClusterBootstrap() {
            @Override
            ClusterManager buildManager() {
                throw new IllegalStateException("simulated Infinispan Attribute.set mismatch");
            }
        };
        bootstrap.enabled = true;
        bootstrap.nodeId = "ussd-g9-fail";
        bootstrap.initialHosts = "127.0.0.1[7800]";
        bootstrap.profileDir = tempDir.resolve("ussd-profiles").toString();
        bootstrap.mode = "REPL_SYNC";

        assertThatCode(() -> bootstrap.onStart(null)).doesNotThrowAnyException();

        assertThat(bootstrap.clusterManager()).isNull();
        assertThat(bootstrap.clusterActive()).isFalse();
        assertThat(bootstrap.clusterMode()).isEqualTo("OFF");
        assertThat(bootstrap.blocker()).contains("simulated Infinispan Attribute.set mismatch");
        assertThat(appender.saw("cluster requested but ISPN API mismatch")).isTrue();
        assertThat(appender.saw("staying inactive")).isTrue();
    }

    @Test
    void enabledConstructsReplSyncManagerWhenIspnApiAllows() {
        bootstrap = newInstance();
        bootstrap.enabled = true;
        bootstrap.nodeId = "ussd-g9-live";
        bootstrap.initialHosts = "127.0.0.1[7800]";
        bootstrap.profileDir = tempDir.resolve("ussd-profiles").toString();
        bootstrap.mode = "REPL_SYNC";

        assertThatCode(() -> bootstrap.onStart(null)).doesNotThrowAnyException();

        ClusterManager manager = bootstrap.clusterManager();
        if (manager != null) {
            // Constructed: the REPL_SYNC Profile CMP caches must really exist on the manager.
            assertThat(manager.isClustered()).isTrue();
            assertThat(bootstrap.clusterActive()).isTrue();
            assertThat(bootstrap.clusterMode()).isEqualTo("REPL_SYNC");
            assertThat(bootstrap.blocker()).isNull();

            Cache<Object, Object> tx = manager.getCache(UssdTxProfile.TABLE_NAME, CacheMode.REPL_SYNC);
            assertThat(tx.getCacheConfiguration().clustering().cacheMode()).isEqualTo(CacheMode.REPL_SYNC);
            Cache<Object, Object> user = manager.getCache(UssdUserProfile.TABLE_NAME, CacheMode.REPL_SYNC);
            assertThat(user.getCacheConfiguration().clustering().cacheMode()).isEqualTo(CacheMode.REPL_SYNC);
            assertThat(manager.getCacheManager().getMembers()).isNotNull();
            assertThat(bootstrap.status()).containsEntry("cluster.enabled", true)
                    .containsEntry("cluster.active", true)
                    .containsEntry("cluster.mode", "REPL_SYNC");
            assertThat(appender.saw("ClusterBootstrap active")).isTrue();
        } else {
            // Offline / Digicom ISPN API mismatch: the required fail-safe branch must hold.
            assertThat(bootstrap.blocker()).isNotNull();
            assertThat(bootstrap.clusterActive()).isFalse();
            assertThat(bootstrap.clusterMode()).isEqualTo("OFF");
            assertThat(appender.saw("cluster requested but ISPN API mismatch")).isTrue();
        }
    }

    private ClusterBootstrap newInstance() {
        ClusterBootstrap b = new ClusterBootstrap();
        b.enabled = false;
        b.nodeId = "ussd-g9-test";
        b.initialHosts = "127.0.0.1[7800]";
        b.profileDir = tempDir.resolve("ussd-profiles").toString();
        b.mode = "REPL_SYNC";
        return b;
    }

    /** Minimal Log4j2 appender so the test can assert the inactive / blocker log lines. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name) {
            super(name, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }

        boolean saw(String needle) {
            return messages.stream().anyMatch(m -> m != null && m.contains(needle));
        }
    }
}
