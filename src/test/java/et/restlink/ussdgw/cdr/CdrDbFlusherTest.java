package et.restlink.ussdgw.cdr;

import et.restlink.ussdgw.persist.CdrEntity;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CdrDbFlusherTest {
    private JdbcDataSource ds;
    private CdrDbFlusher flusher;

    @BeforeEach
    void setUp() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:ussd_cdr_flush_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE ussd_cdr_session (
                        id                UUID PRIMARY KEY,
                        recorded_at       TIMESTAMP WITH TIME ZONE NOT NULL,
                        correlation_id    VARCHAR(128) NOT NULL,
                        phase             VARCHAR(32)  NOT NULL,
                        status            VARCHAR(64)  NOT NULL,
                        msisdn            VARCHAR(32),
                        short_code        VARCHAR(32),
                        detail            VARCHAR(1024),
                        network_id        INT,
                        tenant_id         VARCHAR(128),
                        origination_type  VARCHAR(32),
                        gate_ms           BIGINT,
                        observed_ewma_ms  BIGINT,
                        hop_outcome       VARCHAR(32),
                        refuse_reason     VARCHAR(128),
                        as_ussd           VARCHAR(256),
                        csv_line          VARCHAR(4000) NOT NULL,
                        started_at        TIMESTAMP WITH TIME ZONE NOT NULL,
                        updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
                        event_count       INT NOT NULL DEFAULT 1,
                        events_json       VARCHAR(8192),
                        CONSTRAINT uk_ussd_cdr_session_corr UNIQUE (correlation_id)
                    )
                    """);
            // Legacy tape present for dual-read compatibility (unused by flusher).
            st.execute("""
                    CREATE TABLE ussd_cdr (
                        id                UUID PRIMARY KEY,
                        recorded_at       TIMESTAMP WITH TIME ZONE NOT NULL,
                        correlation_id    VARCHAR(128) NOT NULL,
                        phase             VARCHAR(32)  NOT NULL,
                        status            VARCHAR(64)  NOT NULL,
                        msisdn            VARCHAR(32),
                        short_code        VARCHAR(32),
                        detail            VARCHAR(1024),
                        network_id        INT,
                        tenant_id         VARCHAR(128),
                        origination_type  VARCHAR(32),
                        gate_ms           BIGINT,
                        observed_ewma_ms  BIGINT,
                        hop_outcome       VARCHAR(32),
                        refuse_reason     VARCHAR(128),
                        as_ussd           VARCHAR(256),
                        csv_line          VARCHAR(4000) NOT NULL
                    )
                    """);
        }
        flusher = new CdrDbFlusher(ds, null, /* batchSize */ 10, /* queueCap */ 100);
    }

    @Test
    void enqueueDropsWhenQueueFull() {
        CdrDbFlusher tiny = new CdrDbFlusher(ds, null, 2, 2);
        assertThat(tiny.enqueue(sample("a", "AWAITING_AS"))).isTrue();
        assertThat(tiny.enqueue(sample("b", "AWAITING_AS"))).isTrue();
        assertThat(tiny.enqueue(sample("c", "AWAITING_AS"))).isFalse();
        assertThat(tiny.droppedCount()).isEqualTo(1);
        assertThat(tiny.queueSize()).isEqualTo(2);
    }

    @Test
    void flushCoalescesSevenMap2MapEventsToOneRow() throws Exception {
        String corr = "corr-map2map-7";
        String[] statuses = {
                Map2MapCdr.ARMED, Map2MapCdr.HOP_START, CdrStatuses.GATE_ARMED,
                Map2MapCdr.USSD_SENT, Map2MapCdr.HOP_CLOSE, Map2MapCdr.AS_ROUTED, "END"
        };
        for (String st : statuses) {
            CdrEntity e = sample("251911230398", st);
            e.correlationId = corr;
            if ("END".equals(st)) {
                e.phase = "COMPLETED";
            } else if (Map2MapCdr.HOP_CLOSE.equals(st)) {
                e.phase = "FAILED";
            }
            flusher.enqueue(e);
        }
        assertThat(flusher.flushOnce()).isEqualTo(7);
        assertThat(flusher.upsertedCount()).isEqualTo(1);

        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "select count(*), max(event_count), max(status) from ussd_cdr_session "
                             + "where correlation_id='" + corr + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(rs.getInt(2)).isEqualTo(7);
            assertThat(rs.getString(3)).isEqualTo("END");
        }
    }

    @Test
    void continueAcrossFlushesUpdatesSameRow() throws Exception {
        String corr = "corr-continue";
        CdrEntity e1 = sample("2519", "CONTINUE");
        e1.correlationId = corr;
        e1.asUssd = "menu1";
        e1.detail = "asUssd=menu1";
        flusher.enqueue(e1);
        assertThat(flusher.flushOnce()).isEqualTo(1);

        CdrEntity e2 = sample("2519", "CONTINUE");
        e2.correlationId = corr;
        e2.asUssd = "menu2";
        e2.detail = "asUssd=menu2";
        flusher.enqueue(e2);
        CdrEntity e3 = sample("2519", "END");
        e3.correlationId = corr;
        e3.phase = "COMPLETED";
        e3.asUssd = "bye";
        e3.detail = "asUssd=bye";
        flusher.enqueue(e3);
        assertThat(flusher.flushOnce()).isEqualTo(2);

        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "select event_count, status, as_ussd, events_json from ussd_cdr_session "
                             + "where correlation_id='" + corr + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("event_count")).isEqualTo(3);
            assertThat(rs.getString("status")).isEqualTo("END");
            assertThat(rs.getString("as_ussd")).isEqualTo("bye");
            assertThat(rs.getString("events_json")).contains("CONTINUE");
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void flushPersistsGateAndEwmaColumns() throws Exception {
        CdrEntity e = sample("gated", CdrStatuses.GATE_ARMED);
        e.gateMs = 3500L;
        e.observedEwmaMs = 2100L;
        e.detail = "service=VirtualSessionBridge|AdaptiveTimeout";
        flusher.enqueue(e);
        assertThat(flusher.flushOnce()).isEqualTo(1);

        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "select status, gate_ms, observed_ewma_ms, detail from ussd_cdr_session "
                             + "where msisdn='gated'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("status")).isEqualTo(CdrStatuses.GATE_ARMED);
            assertThat(rs.getLong("gate_ms")).isEqualTo(3500L);
            assertThat(rs.getLong("observed_ewma_ms")).isEqualTo(2100L);
            assertThat(rs.getString("detail")).contains("AdaptiveTimeout");
        }
    }

    @Test
    void mergeSessionPreferring_dedupesLegacyWhenSessionPresent() {
        CdrEntity session = sample("x", "END");
        session.correlationId = "same";
        session.eventCount = 7;
        session.eventsJson = "[]";
        session.updatedAt = Instant.parse("2026-08-09T12:01:00Z");

        CdrEntity legacy = sample("x", Map2MapCdr.ARMED);
        legacy.correlationId = "same";
        legacy.eventCount = null;
        legacy.eventsJson = null;
        legacy.updatedAt = Instant.parse("2026-08-09T12:00:00Z");

        CdrEntity onlyLegacy = sample("y", "END");
        onlyLegacy.correlationId = "legacy-only";
        onlyLegacy.eventCount = null;
        onlyLegacy.updatedAt = Instant.parse("2026-08-09T12:02:00Z");

        var merged = CdrService.mergeSessionPreferring(
                java.util.List.of(session),
                java.util.List.of(legacy, onlyLegacy),
                10);
        assertThat(merged).hasSize(2);
        assertThat(merged.getFirst().correlationId).isEqualTo("legacy-only");
        assertThat(merged.get(1).correlationId).isEqualTo("same");
        assertThat(merged.get(1).eventCount).isEqualTo(7);
    }

    private static CdrEntity sample(String msisdn, String status) {
        CdrEntity e = new CdrEntity();
        e.id = UUID.randomUUID();
        Instant now = Instant.now();
        e.recordedAt = now;
        e.startedAt = now;
        e.updatedAt = now;
        e.correlationId = "corr-" + msisdn;
        e.phase = "S1_ACTIVE";
        e.status = status;
        e.msisdn = msisdn;
        e.shortCode = "*123#";
        e.detail = "test";
        e.networkId = 1;
        e.tenantId = "lab";
        e.originationType = "MAP";
        e.csvLine = "corr|" + msisdn;
        e.eventCount = 1;
        return e;
    }
}
