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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.LongAdder;

import javax.sql.DataSource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Async CDR DB path: bounded queue + scheduled coalesce-by-corr + UPSERT into
 * {@code ussd_cdr_session}. Hot MAP/SBB path only {@link #enqueue}; never waits on DB.
 * Queue full → drop + warn (file CDR kept). Works on H2 (PostgreSQL mode) and PostgreSQL.
 */
@ApplicationScoped
public class CdrDbFlusher {
    private static final Logger LOG = LogManager.getLogger(CdrDbFlusher.class);

    static final String UPDATE_SQL = """
            UPDATE ussd_cdr_session SET
                recorded_at = ?, phase = ?, status = ?, msisdn = ?, short_code = ?, detail = ?,
                network_id = ?, tenant_id = ?, origination_type = ?, gate_ms = ?, observed_ewma_ms = ?,
                hop_outcome = ?, refuse_reason = ?, as_ussd = ?, csv_line = ?,
                updated_at = ?, event_count = ?, events_json = ?
            WHERE correlation_id = ?
            """;

    static final String INSERT_SQL = """
            INSERT INTO ussd_cdr_session (
                id, recorded_at, correlation_id, phase, status, msisdn, short_code, detail,
                network_id, tenant_id, origination_type, gate_ms, observed_ewma_ms,
                hop_outcome, refuse_reason, as_ussd, csv_line,
                started_at, updated_at, event_count, events_json
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?, ?
            )
            """;

    private final DataSource dataSource;
    private final EntityManager em;
    private final ArrayBlockingQueue<CdrEntity> queue;
    private final int batchSize;
    private final int queueCap;
    private final LongAdder dropped = new LongAdder();
    private final LongAdder flushed = new LongAdder();
    private final LongAdder upserted = new LongAdder();

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

    /** Sync path for tests / {@code ussd.cdr.db.async=false} — coalesce+upsert one row. */
    @Transactional
    public void persistSync(CdrEntity row) {
        if (row == null) {
            return;
        }
        CdrEntity session = CdrSessionRollup.seed(row);
        try {
            upsertBatch(List.of(session));
            upserted.increment();
        } catch (SQLException e) {
            throw new IllegalStateException("CDR session upsert failed corr=" + row.correlationId, e);
        }
    }

    public int queueSize() { return queue.size(); }
    public long droppedCount() { return dropped.sum(); }
    public long flushedCount() { return flushed.sum(); }
    public long upsertedCount() { return upserted.sum(); }

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

    /**
     * Drain up to {@code batchSize} deltas, coalesce by correlationId, UPSERT sessions.
     * Return value = raw deltas drained (not merged session count) for queue accounting.
     */
    int flushOnce() {
        if (queue.isEmpty()) return 0;
        List<CdrEntity> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        if (batch.isEmpty()) return 0;
        List<CdrEntity> sessions = CdrSessionRollup.coalesceByCorrelation(batch);
        try {
            upsertBatch(sessions);
            flushed.add(batch.size());
            upserted.add(sessions.size());
            return batch.size();
        } catch (SQLException e) {
            LOG.error("[cdr-db] session upsert failed ({} deltas → {} sessions); re-queue best-effort",
                    batch.size(), sessions.size(), e);
            for (int i = batch.size() - 1; i >= 0; i--) {
                if (!queue.offer(batch.get(i))) {
                    dropped.increment();
                }
            }
            return 0;
        }
    }

    void upsertBatch(List<CdrEntity> sessions) throws SQLException {
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            boolean prevAuto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT id, recorded_at, correlation_id, phase, status, msisdn, short_code, detail, "
                            + "network_id, tenant_id, origination_type, gate_ms, observed_ewma_ms, "
                            + "hop_outcome, refuse_reason, as_ussd, csv_line, started_at, updated_at, "
                            + "event_count, events_json FROM ussd_cdr_session WHERE correlation_id = ?");
                 PreparedStatement upd = c.prepareStatement(UPDATE_SQL);
                 PreparedStatement ins = c.prepareStatement(INSERT_SQL)) {
                for (CdrEntity incoming : sessions) {
                    CdrEntity row = prepareForUpsert(sel, incoming);
                    bindUpdate(upd, row);
                    int n = upd.executeUpdate();
                    if (n == 0) {
                        bindInsert(ins, row);
                        try {
                            ins.executeUpdate();
                        } catch (SQLException insertEx) {
                            // Race: another flush inserted — reload, fold, update.
                            CdrEntity raced = prepareForUpsert(sel, incoming);
                            bindUpdate(upd, raced);
                            if (upd.executeUpdate() == 0) {
                                throw insertEx;
                            }
                        }
                    }
                }
                c.commit();
            } catch (SQLException e) {
                try { c.rollback(); } catch (SQLException ignored) { }
                throw e;
            } finally {
                c.setAutoCommit(prevAuto);
            }
        }
    }

    private static CdrEntity prepareForUpsert(PreparedStatement sel, CdrEntity incoming)
            throws SQLException {
        if (incoming.startedAt == null) {
            incoming.startedAt = incoming.recordedAt != null ? incoming.recordedAt : Instant.now();
        }
        if (incoming.updatedAt == null) {
            incoming.updatedAt = incoming.recordedAt != null ? incoming.recordedAt : incoming.startedAt;
        }
        if (incoming.eventCount == null) {
            incoming.eventCount = 1;
        }
        if (incoming.csvLine == null) {
            incoming.csvLine = "";
        }
        if (incoming.id == null) {
            incoming.id = UUID.randomUUID();
        }
        String corr = incoming.correlationId == null ? "" : incoming.correlationId;
        sel.setString(1, corr);
        try (ResultSet rs = sel.executeQuery()) {
            if (!rs.next()) {
                return incoming;
            }
            return CdrSessionRollup.foldIncomingSession(readSession(rs), incoming);
        }
    }

    private static CdrEntity readSession(ResultSet rs) throws SQLException {
        CdrEntity e = new CdrEntity();
        Object id = rs.getObject("id");
        if (id instanceof UUID u) {
            e.id = u;
        } else if (id != null) {
            e.id = UUID.fromString(id.toString());
        }
        Timestamp rec = rs.getTimestamp("recorded_at");
        e.recordedAt = rec == null ? null : rec.toInstant();
        e.correlationId = rs.getString("correlation_id");
        e.phase = rs.getString("phase");
        e.status = rs.getString("status");
        e.msisdn = rs.getString("msisdn");
        e.shortCode = rs.getString("short_code");
        e.detail = rs.getString("detail");
        int net = rs.getInt("network_id");
        e.networkId = rs.wasNull() ? null : net;
        e.tenantId = rs.getString("tenant_id");
        e.originationType = rs.getString("origination_type");
        long gate = rs.getLong("gate_ms");
        e.gateMs = rs.wasNull() ? null : gate;
        long ewma = rs.getLong("observed_ewma_ms");
        e.observedEwmaMs = rs.wasNull() ? null : ewma;
        e.hopOutcome = rs.getString("hop_outcome");
        e.refuseReason = rs.getString("refuse_reason");
        e.asUssd = rs.getString("as_ussd");
        e.csvLine = rs.getString("csv_line");
        Timestamp started = rs.getTimestamp("started_at");
        e.startedAt = started == null ? null : started.toInstant();
        Timestamp updated = rs.getTimestamp("updated_at");
        e.updatedAt = updated == null ? null : updated.toInstant();
        int ec = rs.getInt("event_count");
        e.eventCount = rs.wasNull() ? 1 : ec;
        e.eventsJson = rs.getString("events_json");
        return e;
    }

    static void bindUpdate(PreparedStatement ps, CdrEntity row) throws SQLException {
        ps.setTimestamp(1, Timestamp.from(row.recordedAt != null ? row.recordedAt : row.updatedAt));
        ps.setString(2, row.phase);
        ps.setString(3, row.status);
        setNullableString(ps, 4, row.msisdn);
        setNullableString(ps, 5, row.shortCode);
        setNullableString(ps, 6, row.detail);
        setNullableInt(ps, 7, row.networkId);
        setNullableString(ps, 8, row.tenantId);
        setNullableString(ps, 9, row.originationType);
        setNullableLong(ps, 10, row.gateMs);
        setNullableLong(ps, 11, row.observedEwmaMs);
        setNullableString(ps, 12, row.hopOutcome);
        setNullableString(ps, 13, row.refuseReason);
        setNullableString(ps, 14, row.asUssd);
        ps.setString(15, row.csvLine == null ? "" : row.csvLine);
        ps.setTimestamp(16, Timestamp.from(row.updatedAt != null ? row.updatedAt : row.recordedAt));
        ps.setInt(17, row.eventCount == null ? 1 : row.eventCount);
        setNullableString(ps, 18, row.eventsJson);
        ps.setString(19, row.correlationId == null ? "" : row.correlationId);
    }

    static void bindInsert(PreparedStatement ps, CdrEntity row) throws SQLException {
        ps.setObject(1, row.id);
        ps.setTimestamp(2, Timestamp.from(row.recordedAt != null ? row.recordedAt : row.startedAt));
        ps.setString(3, row.correlationId == null ? "" : row.correlationId);
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
        setNullableString(ps, 14, row.hopOutcome);
        setNullableString(ps, 15, row.refuseReason);
        setNullableString(ps, 16, row.asUssd);
        ps.setString(17, row.csvLine == null ? "" : row.csvLine);
        ps.setTimestamp(18, Timestamp.from(row.startedAt != null ? row.startedAt : row.recordedAt));
        ps.setTimestamp(19, Timestamp.from(row.updatedAt != null ? row.updatedAt : row.recordedAt));
        ps.setInt(20, row.eventCount == null ? 1 : row.eventCount);
        setNullableString(ps, 21, row.eventsJson);
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
