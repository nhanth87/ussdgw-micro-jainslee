package et.restlink.ussdgw.cdr.clickhouse;

import et.restlink.ussdgw.persist.CdrEntity;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * CDR store of record in ClickHouse ({@code ussd.cdr.clickhouse.*}).
 *
 * <p>Boot never waits for ClickHouse: the schema is created by a background thread that retries
 * until the server answers, and rows written meanwhile are spooled and replayed.</p>
 */
@ApplicationScoped
public class CdrClickHouseSink {
    private static final Logger LOG = LogManager.getLogger(CdrClickHouseSink.class);

    @ConfigProperty(name = "ussd.cdr.clickhouse.enabled", defaultValue = "false")
    boolean enabled;
    @ConfigProperty(name = "ussd.cdr.clickhouse.read-enabled", defaultValue = "true")
    boolean readEnabled;
    @ConfigProperty(name = "ussd.cdr.clickhouse.url", defaultValue = "http://127.0.0.1:8123")
    String url;
    @ConfigProperty(name = "ussd.cdr.clickhouse.database", defaultValue = "ussd")
    String database;
    @ConfigProperty(name = "ussd.cdr.clickhouse.user", defaultValue = "ussdgw")
    String user;
    @ConfigProperty(name = "ussd.cdr.clickhouse.password")
    Optional<String> password;
    /** File holding the password (Docker secret); wins over {@code password}. */
    @ConfigProperty(name = "ussd.cdr.clickhouse.password-file")
    Optional<String> passwordFile;
    @ConfigProperty(name = "ussd.cdr.clickhouse.retention-months", defaultValue = "3")
    int retentionMonths;
    @ConfigProperty(name = "ussd.cdr.clickhouse.batch-size", defaultValue = "5000")
    int batchSize;
    @ConfigProperty(name = "ussd.cdr.clickhouse.flush-ms", defaultValue = "500")
    long flushMs;
    @ConfigProperty(name = "ussd.cdr.clickhouse.queue-cap", defaultValue = "500000")
    int queueCap;
    @ConfigProperty(name = "ussd.cdr.clickhouse.spool-dir", defaultValue = "data/cdr-spool")
    String spoolDir;
    @ConfigProperty(name = "ussd.cdr.clickhouse.spool-max-mb", defaultValue = "20480")
    long spoolMaxMb;
    @ConfigProperty(name = "ussd.cdr.clickhouse.connect-timeout-ms", defaultValue = "3000")
    long connectTimeoutMs;
    @ConfigProperty(name = "ussd.cdr.clickhouse.request-timeout-ms", defaultValue = "30000")
    long requestTimeoutMs;
    @ConfigProperty(name = "ussd.cdr.clickhouse.node-id")
    Optional<String> nodeId;

    private volatile ClickHouseHttp ch;
    private volatile CdrClickHouseWriter writer;
    private volatile CdrClickHouseReader reader;
    private volatile boolean schemaReady;
    private volatile String schemaError = "";
    private int effectiveRetention;

    void onStart(@Observes StartupEvent ev) {
        if (!enabled) {
            LOG.info("[cdr-clickhouse] disabled (ussd.cdr.clickhouse.enabled=false) — CDR stays on the file ledger");
            return;
        }
        String db = ClickHouseHttp.requireIdent(database.trim());
        effectiveRetention = CdrClickHouseSchema.effectiveRetentionMonths(retentionMonths);
        if (effectiveRetention != retentionMonths) {
            LOG.warn("[cdr-clickhouse] retention-months={} is below the {}-month minimum — using {}",
                    retentionMonths, CdrClickHouseSchema.MIN_RETENTION_MONTHS, effectiveRetention);
        }
        ch = new ClickHouseHttp(url, user, resolvePassword(), Duration.ofMillis(connectTimeoutMs),
                Duration.ofMillis(requestTimeoutMs));
        String table = db + "." + CdrClickHouseSchema.TABLE;
        String node = nodeId.filter(s -> !s.isBlank())
                .orElseGet(() -> Optional.ofNullable(System.getenv("HOSTNAME")).orElse("ussdgw"));
        writer = new CdrClickHouseWriter(ch, table, queueCap, batchSize, flushMs, Path.of(spoolDir),
                spoolMaxMb * 1024L * 1024L, node);
        reader = new CdrClickHouseReader(ch, table, effectiveRetention);
        try {
            writer.start();
        } catch (IOException e) {
            LOG.error("[cdr-clickhouse] cannot create spool dir {}: {} — ClickHouse CDR disabled",
                    spoolDir, e.toString());
            writer = null;
            return;
        }
        Thread.ofPlatform().daemon().name("cdr-clickhouse-schema").start(() -> ensureSchema(db));
        LOG.info("[cdr-clickhouse] enabled url={} table={} retention={} months node={}",
                url, table, effectiveRetention, node);
    }

    void onStop(@Observes ShutdownEvent ev) {
        CdrClickHouseWriter w = writer;
        if (w != null) {
            w.stop(10_000L);
        }
    }

    private void ensureSchema(String db) {
        long delay = 1_000L;
        while (writer != null) {
            try {
                ch.execute(CdrClickHouseSchema.createDatabase(db));
                ch.execute(CdrClickHouseSchema.createTable(db, effectiveRetention));
                if (CdrClickHouseSchema.ensureTtl(ch, db, effectiveRetention)) {
                    LOG.info("[cdr-clickhouse] TTL set to {} months", effectiveRetention);
                }
                schemaReady = true;
                schemaError = "";
                LOG.info("[cdr-clickhouse] schema ready {}.{}", db, CdrClickHouseSchema.TABLE);
                return;
            } catch (IOException | RuntimeException e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                if (!msg.equals(schemaError)) {
                    LOG.warn("[cdr-clickhouse] schema not ready ({}), retrying — CDR is spooled meanwhile", msg);
                }
                schemaError = msg;
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                delay = Math.min(30_000L, delay * 2);
            }
        }
    }

    private String resolvePassword() {
        Optional<String> file = passwordFile.filter(s -> !s.isBlank());
        if (file.isPresent()) {
            try {
                return Files.readString(Path.of(file.get()), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                LOG.error("[cdr-clickhouse] cannot read password file {}: {}", file.get(), e.toString());
            }
        }
        return password.orElse("");
    }

    // ------------------------------------------------------------------ API

    public boolean enabled() {
        return enabled && writer != null;
    }

    /** Hot path: non-blocking hand-off. */
    public void offer(CdrEntity row) {
        CdrClickHouseWriter w = writer;
        if (w != null) {
            w.offer(row);
        }
    }

    /** True when admin reads should come from ClickHouse. */
    public boolean readable() {
        return enabled() && readEnabled && schemaReady;
    }

    public List<CdrEntity> sessions(int limit, String tenantId, String msisdn, String correlationId,
                                    Predicate<CdrEntity> statusFilter, boolean statusFiltered)
            throws IOException {
        return reader.sessions(limit, tenantId, msisdn, correlationId, statusFilter, statusFiltered);
    }

    /** {@code cdr.clickhouse.*} keys for {@code /admin/status.json}. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cdr.clickhouse.enabled", enabled());
        if (!enabled()) {
            return m;
        }
        CdrClickHouseWriter w = writer;
        m.put("cdr.clickhouse.schemaReady", schemaReady);
        m.put("cdr.clickhouse.retentionMonths", effectiveRetention);
        m.put("cdr.clickhouse.queueDepth", w.queueDepth());
        m.put("cdr.clickhouse.enqueued", w.enqueued());
        m.put("cdr.clickhouse.inserted", w.inserted());
        m.put("cdr.clickhouse.spooledRows", w.spooledRows());
        m.put("cdr.clickhouse.spoolBytes", w.spoolBytes());
        m.put("cdr.clickhouse.replayedBatches", w.replayedBatches());
        m.put("cdr.clickhouse.insertFailures", w.insertFailures());
        m.put("cdr.clickhouse.droppedQueueFull", w.droppedQueueFull());
        m.put("cdr.clickhouse.droppedSpoolFull", w.droppedSpoolFull());
        m.put("cdr.clickhouse.lastInsertAtMs", w.lastInsertAtMs());
        m.put("cdr.clickhouse.lastError", schemaReady ? w.lastError() : schemaError);
        return m;
    }
}
