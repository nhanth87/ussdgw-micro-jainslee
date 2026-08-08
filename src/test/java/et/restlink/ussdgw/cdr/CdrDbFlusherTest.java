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
                        csv_line          VARCHAR(4000) NOT NULL
                    )
                    """);
        }
        flusher = new CdrDbFlusher(ds, null, /* batchSize */ 2, /* queueCap */ 10);
    }

    @Test
    void enqueueDropsWhenQueueFull() {
        CdrDbFlusher tiny = new CdrDbFlusher(ds, null, 2, 2);
        assertThat(tiny.enqueue(sample("a"))).isTrue();
        assertThat(tiny.enqueue(sample("b"))).isTrue();
        assertThat(tiny.enqueue(sample("c"))).isFalse();
        assertThat(tiny.droppedCount()).isEqualTo(1);
        assertThat(tiny.queueSize()).isEqualTo(2);
    }

    @Test
    void flushOnceBatchInsertsViaJdbc() throws Exception {
        flusher.enqueue(sample("ok1"));
        flusher.enqueue(sample("ok2"));
        flusher.enqueue(sample("ok3"));

        assertThat(flusher.flushOnce()).isEqualTo(2);
        assertThat(flusher.queueSize()).isEqualTo(1);
        assertThat(flusher.flushOnce()).isEqualTo(1);
        assertThat(flusher.queueSize()).isEqualTo(0);
        assertThat(flusher.flushedCount()).isEqualTo(3);

        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from ussd_cdr")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(3);
        }
    }

    @Test
    void flushPersistsGateAndEwmaColumns() throws Exception {
        CdrEntity e = sample("gated");
        e.gateMs = 3500L;
        e.observedEwmaMs = 2100L;
        e.status = "GATED";
        e.detail = "service=VirtualSessionBridge|AdaptiveTimeout";
        flusher.enqueue(e);
        assertThat(flusher.flushOnce()).isEqualTo(1);

        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "select status, gate_ms, observed_ewma_ms, detail from ussd_cdr where msisdn='gated'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("status")).isEqualTo("GATED");
            assertThat(rs.getLong("gate_ms")).isEqualTo(3500L);
            assertThat(rs.getLong("observed_ewma_ms")).isEqualTo(2100L);
            assertThat(rs.getString("detail")).contains("AdaptiveTimeout");
        }
    }

    private static CdrEntity sample(String msisdn) {
        CdrEntity e = new CdrEntity();
        e.id = UUID.randomUUID();
        e.recordedAt = Instant.now();
        e.correlationId = "corr-" + msisdn;
        e.phase = "S1_ACTIVE";
        e.status = "AWAITING_AS";
        e.msisdn = msisdn;
        e.shortCode = "*123#";
        e.detail = "test";
        e.networkId = 1;
        e.tenantId = "lab";
        e.originationType = "MAP";
        e.csvLine = "corr|" + msisdn;
        return e;
    }
}
