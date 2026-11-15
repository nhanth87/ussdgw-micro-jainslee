package et.restlink.ussdgw.cdr.clickhouse;

import com.microjainslee.api.RaCommandPort;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.clickhouse.ClickHouseCommand;
import com.microjainslee.ra.clickhouse.ClickHouseHttp;
import com.microjainslee.ra.clickhouse.ClickHouseRaEndpoint;
import com.microjainslee.ra.clickhouse.ClickHouseWriter;
import et.restlink.ussdgw.persist.CdrEntity;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
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
 * Wires the CDR store of record ({@code ussd.cdr.clickhouse.*}) — the same role
 * {@code HttpApplyService} / {@code SmppEndpointRegistry} play for their RAs.
 *
 * <ul>
 *   <li><b>Write path</b> (called from SBBs through {@code CdrService}): a
 *       {@link ClickHouseCommand.Store} on the container's {@link RaCommandPort} for
 *       {@code cdr-clickhouse-ra}. All ClickHouse I/O lives in that shared RA
 *       ({@code com.microjainslee:ra-clickhouse}, 3-port contract). What a row contains
 *       stays here ({@link CdrNdjson} codec + {@link CdrClickHouseSchema} DDL).</li>
 *   <li><b>Read path</b> (admin plane only, never an SBB): {@link CdrClickHouseReader}.</li>
 * </ul>
 *
 * <p>Boot never waits for ClickHouse: the RA creates the schema in the background and spools
 * rows until the server answers.</p>
 */
@ApplicationScoped
public class CdrClickHouseSink {
    private static final Logger LOG = LogManager.getLogger(CdrClickHouseSink.class);

    /** RA instance name — kept stable: {@code /admin/status.json} and logs key on it. */
    public static final String RA_NAME = "cdr-clickhouse-ra";

    @Inject MicroSleeContainer container;

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

    private volatile ClickHouseRaEndpoint<CdrEntity> endpoint;
    private volatile RaCommandPort commandPort;
    private volatile CdrClickHouseReader reader;
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
        ClickHouseHttp ch = new ClickHouseHttp(url, user, resolvePassword(),
                Duration.ofMillis(connectTimeoutMs), Duration.ofMillis(requestTimeoutMs));
        String table = db + "." + CdrClickHouseSchema.TABLE;
        String node = nodeId.filter(s -> !s.isBlank())
                .orElseGet(() -> Optional.ofNullable(System.getenv("HOSTNAME")).orElse("ussdgw"));
        int retention = effectiveRetention;
        ClickHouseWriter<CdrEntity> writer = new ClickHouseWriter<>(ch, table, queueCap, batchSize,
                flushMs, Path.of(spoolDir), spoolMaxMb * 1024L * 1024L, node, CdrNdjson::toNdjson);
        ClickHouseRaEndpoint<CdrEntity> ep = new ClickHouseRaEndpoint<>(RA_NAME, ch, writer, schema -> {
            schema.execute(CdrClickHouseSchema.createDatabase(db));
            schema.execute(CdrClickHouseSchema.createTable(db, retention));
            if (CdrClickHouseSchema.ensureTtl(schema, db, retention)) {
                LOG.info("[cdr-clickhouse] TTL set to {} months", retention);
            }
        });
        container.registerRa(ep, ep);
        endpoint = ep;
        // The container's (observed) port, not the endpoint itself: RA command metrics count it.
        RaCommandPort port = container.getRaCommandPort(ep.getRaName());
        commandPort = port != null ? port : ep;
        reader = new CdrClickHouseReader(ch, table, effectiveRetention);
        LOG.info("[cdr-clickhouse] {} registered url={} table={} retention={} months node={}",
                ep.getRaName(), url, table, effectiveRetention, node);
    }

    void onStop(@Observes ShutdownEvent ev) {
        ClickHouseRaEndpoint<CdrEntity> ep = endpoint;
        if (ep != null) {
            ep.deactivate();  // idempotent; flushes the queue to ClickHouse or the spool
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
        return enabled && endpoint != null;
    }

    /** Write path: one command to {@code cdr-clickhouse-ra}. Non-blocking. */
    public void offer(CdrEntity row) {
        RaCommandPort port = commandPort;
        if (port != null && row != null) {
            port.sendCommand(new ClickHouseCommand.Store<>(row));
        }
    }

    /** True when admin reads should come from ClickHouse. */
    public boolean readable() {
        ClickHouseRaEndpoint<CdrEntity> ep = endpoint;
        return enabled && readEnabled && ep != null && ep.isSchemaReady();
    }

    /** Admin plane only. */
    public List<CdrEntity> sessions(int limit, String tenantId, String msisdn, String correlationId,
                                    Predicate<CdrEntity> statusFilter, boolean statusFiltered)
            throws IOException {
        return reader.sessions(limit, tenantId, msisdn, correlationId, statusFilter, statusFiltered);
    }

    /** {@code cdr.clickhouse.*} keys for {@code /admin/status.json}. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cdr.clickhouse.enabled", enabled());
        ClickHouseRaEndpoint<CdrEntity> ep = endpoint;
        if (ep == null) {
            return m;
        }
        ClickHouseWriter<CdrEntity> w = ep.writer();
        m.put("cdr.clickhouse.ra", ep.getRaName() + (ep.isActive() ? " ACTIVE" : " INACTIVE"));
        m.put("cdr.clickhouse.schemaReady", ep.isSchemaReady());
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
        m.put("cdr.clickhouse.lastError", ep.isSchemaReady() ? w.lastError() : ep.schemaError());
        return m;
    }
}
