package et.restlink.ussdgw.ra.cdr;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;
import et.restlink.ussdgw.cdr.clickhouse.CdrClickHouseSchema;
import et.restlink.ussdgw.cdr.clickhouse.CdrClickHouseWriter;
import et.restlink.ussdgw.cdr.clickhouse.ClickHouseHttp;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * {@code cdr-clickhouse-ra} — the only place that talks to ClickHouse on the CDR write path.
 *
 * <p>3-port contract (micro-jainslee README § "How RAs work"):</p>
 * <ol>
 *   <li><b>Lifecycle</b> ({@link RaEndpointPort}) — {@link #activate} starts the batching writer and
 *       the schema bootstrap; {@link #deactivate} flushes (to ClickHouse, else to the spool).</li>
 *   <li><b>Commands</b> ({@link RaCommandPort}) — {@link CdrStoreCommand.Record}: non-blocking
 *       hand-off from the CDR service the SBBs call. SBBs never open the socket.</li>
 *   <li><b>Events</b> ({@link RaBootstrapPort}) — held per contract; the store raises no SLEE
 *       events today (its health is on {@code /admin/status.json}).</li>
 * </ol>
 *
 * <p>No business logic here: what a CDR row contains and how sessions roll up stay in
 * {@code CdrService} / {@code CdrFileLedger}. This RA only moves rows to the store.</p>
 */
public final class CdrClickHouseRaEndpoint implements RaEndpointPort, RaCommandPort {
    public static final String RA_NAME = "cdr-clickhouse-ra";
    private static final Logger LOG = LogManager.getLogger(CdrClickHouseRaEndpoint.class);

    private final ClickHouseHttp ch;
    private final String database;
    private final int retentionMonths;
    private final CdrClickHouseWriter writer;

    private volatile RaBootstrapPort bootstrap;
    private volatile boolean active;
    private volatile boolean schemaReady;
    private volatile String schemaError = "";

    public CdrClickHouseRaEndpoint(ClickHouseHttp ch, String database, int retentionMonths,
                                   CdrClickHouseWriter writer) {
        this.ch = ch;
        this.database = ClickHouseHttp.requireIdent(database);
        this.retentionMonths = retentionMonths;
        this.writer = writer;
    }

    @Override
    public String getRaName() {
        return RA_NAME;
    }

    // ---------------------------------------------------------------- port 1: lifecycle

    @Override
    public synchronized void activate(RaBootstrapPort bootstrap) {
        if (active) {
            return;
        }
        this.bootstrap = bootstrap;
        try {
            writer.start();
        } catch (IOException e) {
            throw new IllegalStateException("cdr-clickhouse-ra: cannot create the spool directory: " + e, e);
        }
        active = true;
        Thread.ofPlatform().daemon().name("cdr-clickhouse-schema").start(this::ensureSchema);
        LOG.info("{} activated (database={}, retention={} months)", RA_NAME, database, retentionMonths);
    }

    @Override
    public synchronized void deactivate() {
        if (!active) {
            return;
        }
        active = false;
        writer.stop(10_000L);
        LOG.info("{} deactivated", RA_NAME);
    }

    private void ensureSchema() {
        long delay = 1_000L;
        while (active) {
            try {
                ch.execute(CdrClickHouseSchema.createDatabase(database));
                ch.execute(CdrClickHouseSchema.createTable(database, retentionMonths));
                if (CdrClickHouseSchema.ensureTtl(ch, database, retentionMonths)) {
                    LOG.info("[cdr-clickhouse] TTL set to {} months", retentionMonths);
                }
                schemaReady = true;
                schemaError = "";
                LOG.info("[cdr-clickhouse] schema ready {}.{}", database, CdrClickHouseSchema.TABLE);
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

    // ---------------------------------------------------------------- port 2: commands

    @Override
    public void sendCommand(OutboundCommand command) {
        if (command instanceof CdrStoreCommand.Record r) {
            writer.offer(r.row());
        } else if (command != null) {
            LOG.debug("{} ignoring unknown command {}", RA_NAME, command.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------- state (admin plane)

    public boolean isActive() { return active; }
    public boolean isSchemaReady() { return schemaReady; }
    public String schemaError() { return schemaError; }
    public CdrClickHouseWriter writer() { return writer; }
    RaBootstrapPort bootstrapPort() { return bootstrap; }
}
