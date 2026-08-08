package et.restlink.ussdgw.cdr;

import et.restlink.ussdgw.persist.CdrEntity;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.scheduler.Scheduled;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.LongAdder;

import javax.sql.DataSource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Async CDR DB path (OTA {@code CdrDbFlusher} pattern): bounded queue + scheduled JDBC batch.
 * Hot MAP/SBB path only {@link #enqueue}; never waits on DB. Queue full → drop + warn (file CDR kept).
 * Works on H2 (PostgreSQL mode) and PostgreSQL.
 */
@ApplicationScoped
public class CdrDbFlusher {
    private static final Logger LOG = LogManager.getLogger(CdrDbFlusher.class);

    static final String INSERT_SQL = """
            INSERT INTO ussd_cdr (
                id, recorded_at, correlation_id, phase, status, msisdn, short_code, detail,
                network_id, tenant_id, origination_type, gate_ms, observed_ewma_ms, csv_line
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?
            )
            """;

    private final DataSource dataSource;
    private final EntityManager em;
    private final ArrayBlockingQueue<CdrEntity> queue;
    private final int batchSize;
    private final int queueCap;
    private final LongAdder dropped = new LongAdder();
    private final LongAdder flushed = new LongAdder();

    public CdrDbFlusher(
            DataSource dataSource,
            EntityManager em,
            @ConfigProperty(name = "ussd.cdr.db.batch-size", defaultValue = "2000") int batchSize,
            @ConfigProperty(name = "ussd.cdr.db.queue-cap", defaultValue = "100000") int queueCap) {
        this.dataSource = dataSource;
        this.em = em;
        this.batchSize = Math.max(1, batchSize);
        this.queueCap = Math.max(1, queueCap);
        this.queue = new ArrayBlockingQueue<>(this.queueCap);
    }

    /** Non-blocking offer. Returns {@code false} when dropped (queue full). */
    public boolean enqueue(CdrEntity row) {
        if (row == null) return false;
        if (queue.offer(row)) return true;
        dropped.increment();
        LOG.warn("[cdr-db] queue full (cap={}); dropping CDR corr={} phase={} (file log kept)",
                queueCap, row.correlationId, row.phase);
        return false;
    }

    /** Sync path for tests / {@code ussd.cdr.db.async=false}. */
    @Transactional
    public void persistSync(CdrEntity row) {
        em.persist(row);
    }

    public int queueSize() { return queue.size(); }
    public long droppedCount() { return dropped.sum(); }
    public long flushedCount() { return flushed.sum(); }

    @Scheduled(every = "${ussd.cdr.db.flush-every:100ms}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduledFlush() {
        flushOnce();
    }

    void onStop(@Observes ShutdownEvent ev) {
        int left;
        do {
            left = flushOnce();
        } while (left > 0);
        if (!queue.isEmpty()) {
            LOG.warn("[cdr-db] shutdown with {} CDR rows still queued", queue.size());
        }
    }

    int flushOnce() {
        if (queue.isEmpty()) return 0;
        List<CdrEntity> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        if (batch.isEmpty()) return 0;
        try {
            insertBatch(batch);
            flushed.add(batch.size());
            return batch.size();
        } catch (SQLException e) {
            LOG.error("[cdr-db] batch insert failed ({} rows); re-queue best-effort", batch.size(), e);
            for (int i = batch.size() - 1; i >= 0; i--) {
                if (!queue.offer(batch.get(i))) {
                    dropped.increment();
                }
            }
            return 0;
        }
    }

    void insertBatch(List<CdrEntity> batch) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(INSERT_SQL)) {
            boolean prevAuto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                for (CdrEntity row : batch) {
                    bind(ps, row);
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                try { c.rollback(); } catch (SQLException ignored) { }
                throw e;
            } finally {
                c.setAutoCommit(prevAuto);
            }
        }
    }

    static void bind(PreparedStatement ps, CdrEntity row) throws SQLException {
        ps.setObject(1, row.id);
        ps.setTimestamp(2, Timestamp.from(row.recordedAt));
        ps.setString(3, row.correlationId);
        ps.setString(4, row.phase);
        ps.setString(5, row.status);
        setNullableString(ps, 6, row.msisdn);
        setNullableString(ps, 7, row.shortCode);
        setNullableString(ps, 8, row.detail);
        setNullableInt(ps, 9, row.networkId);
        setNullableString(ps, 10, row.tenantId);
        setNullableString(ps, 11, row.originationType);
        setNullableLong(ps, 12, row.gateMs);
        setNullableLong(ps, 13, row.observedEwmaMs);
        ps.setString(14, row.csvLine);
    }

    private static void setNullableString(PreparedStatement ps, int idx, String v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.VARCHAR);
        else ps.setString(idx, v);
    }

    private static void setNullableInt(PreparedStatement ps, int idx, Integer v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.INTEGER);
        else ps.setInt(idx, v);
    }

    private static void setNullableLong(PreparedStatement ps, int idx, Long v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.BIGINT);
        else ps.setLong(idx, v);
    }
}
