package et.restlink.ussdgw.cdr.clickhouse;

import com.microjainslee.ra.clickhouse.ClickHouseHttp;
import et.restlink.ussdgw.persist.CdrEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against a real ClickHouse. Run with
 * {@code -Dclickhouse.it.url=http://127.0.0.1:8123 -Dclickhouse.it.user=… -Dclickhouse.it.password=…}.
 */
@EnabledIfSystemProperty(named = "clickhouse.it.url", matches = ".+")
class CdrClickHouseIT {

    private static final String DB = "ussd_it_" + Long.toHexString(System.nanoTime());
    private static ClickHouseHttp ch;

    @BeforeAll
    static void schema() throws Exception {
        ch = new ClickHouseHttp(System.getProperty("clickhouse.it.url"),
                System.getProperty("clickhouse.it.user", "default"),
                System.getProperty("clickhouse.it.password", ""),
                Duration.ofSeconds(3), Duration.ofSeconds(30));
        ch.execute(CdrClickHouseSchema.createDatabase(DB));
        ch.execute(CdrClickHouseSchema.createTable(DB, 3));
    }

    private static CdrEntity row(String corr, String status, String asUssd, Instant at) {
        CdrEntity e = new CdrEntity();
        e.id = UUID.randomUUID();
        e.recordedAt = at;
        e.correlationId = corr;
        e.phase = "S1_PULL";
        e.status = status;
        e.msisdn = "251911230398";
        e.shortCode = "*101#";
        e.networkId = 1;
        e.tenantId = "t1";
        e.originationType = "MAP";
        e.asUssd = asUssd;
        e.csvLine = "csv";
        return e;
    }

    @Test
    void insertDedupReadAndTtl() throws Exception {
        String table = DB + "." + CdrClickHouseSchema.TABLE;
        Instant now = Instant.now();
        String corr = "it-" + UUID.randomUUID();
        byte[] nd = CdrNdjson.toNdjson(List.of(
                row(corr, "BEGIN", null, now.minusMillis(20)),
                row(corr, "END", "ቀሪ ሂሳብዎ 25.50 ብር ነው። €{}", now)), "it");
        ch.insertJsonEachRow(table, nd, "tok-1");
        ch.insertJsonEachRow(table, nd, "tok-1");  // spool replay of a batch that already landed

        List<String> count = ch.query("SELECT count() FROM " + table
                + " WHERE correlation_id = {c:String} FORMAT TSV", Map.of("c", corr));
        assertThat(count).containsExactly("2");

        var reader = new CdrClickHouseReader(ch, table, 3);
        List<CdrEntity> sessions = reader.sessions(10, "t1", null, corr, null, false);
        assertThat(sessions).hasSize(1);
        assertThat(sessions.getFirst().correlationId).isEqualTo(corr);
        assertThat(sessions.getFirst().asUssd).isEqualTo("ቀሪ ሂሳብዎ 25.50 ብር ነው። €{}");

        List<String> ttl = ch.query("SELECT engine_full FROM system.tables WHERE database = {d:String} "
                + "AND name = 'cdr_event' FORMAT TSVRaw", Map.of("d", DB));
        assertThat(ttl.getFirst()).contains("toIntervalMonth(3)").contains("ttl_only_drop_parts = 1");

        assertThat(CdrClickHouseSchema.ensureTtl(ch, DB, 3)).isFalse();
        assertThat(CdrClickHouseSchema.ensureTtl(ch, DB, 6)).isTrue();
        assertThat(ch.query("SELECT engine_full FROM system.tables WHERE database = {d:String} "
                + "AND name = 'cdr_event' FORMAT TSVRaw", Map.of("d", DB)).getFirst())
                .contains("toIntervalMonth(6)");

        ch.execute("DROP DATABASE " + DB);
    }
}
