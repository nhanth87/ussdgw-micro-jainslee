package et.restlink.ussdgw.cdr.clickhouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import et.restlink.ussdgw.persist.CdrEntity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** CDR row → NDJSON codec, reader parse and DDL — the app-owned half of the ClickHouse store. */
class CdrNdjsonTest {

    private static CdrEntity row(String corr, String asUssd) {
        CdrEntity e = new CdrEntity();
        e.id = UUID.randomUUID();
        e.recordedAt = Instant.parse("2026-11-15T04:00:00.123Z");
        e.correlationId = corr;
        e.phase = "S1_PULL";
        e.status = "END";
        e.msisdn = "251911230398";
        e.shortCode = "*101#";
        e.networkId = 1;
        e.tenantId = "t1";
        e.originationType = "MAP";
        e.gateMs = 2500L;
        e.asUssd = asUssd;
        e.csvLine = "csv";
        return e;
    }

    @Test
    void ndjsonCarriesEveryColumnAndUnicodeVerbatim() throws Exception {
        byte[] nd = CdrNdjson.toNdjson(List.of(row("c1", "ቀሪ ሂሳብዎ 25 ብር"), row("c2", null)), "n1");
        List<String> lines = new String(nd, StandardCharsets.UTF_8).lines().toList();
        assertThat(lines).hasSize(2);
        JsonNode first = new ObjectMapper().readTree(lines.getFirst());
        assertThat(first.get("as_ussd").asText()).isEqualTo("ቀሪ ሂሳብዎ 25 ብር");
        assertThat(first.get("recorded_at").asText()).isEqualTo("2026-11-15T04:00:00.123Z");
        assertThat(first.get("gate_ms").asLong()).isEqualTo(2500L);
        assertThat(first.get("observed_ewma_ms").isNull()).isTrue();
        assertThat(first.get("node_id").asText()).isEqualTo("n1");
        assertThat(new ObjectMapper().readTree(lines.get(1)).get("as_ussd").asText()).isEmpty();
    }

    @Test
    void readerParsesClickHouseJsonEachRow() throws Exception {
        JsonNode n = new ObjectMapper().readTree("""
                {"event_id":"7d444840-9dc0-11d1-b245-5ffdce74fad2","recorded_at":"2026-11-15 04:00:00.123",
                 "correlation_id":"c1","phase":"S1_PULL","status":"END","msisdn":"251911230398",
                 "short_code":"*101#","detail":"","network_id":1,"tenant_id":"","origination_type":"MAP",
                 "gate_ms":"2500","observed_ewma_ms":null,"hop_outcome":"","refuse_reason":"",
                 "as_ussd":"ሰላም","csv_line":"x"}""");
        CdrEntity e = CdrClickHouseReader.parse(n);
        assertThat(e.recordedAt).isEqualTo(Instant.parse("2026-11-15T04:00:00.123Z"));
        assertThat(e.gateMs).isEqualTo(2500L);
        assertThat(e.observedEwmaMs).isNull();
        assertThat(e.tenantId).isNull();
        assertThat(e.asUssd).isEqualTo("ሰላም");
    }

    @Test
    void retentionNeverBelowThreeMonths() {
        assertThat(CdrClickHouseSchema.effectiveRetentionMonths(1)).isEqualTo(3);
        assertThat(CdrClickHouseSchema.effectiveRetentionMonths(6)).isEqualTo(6);
        assertThat(CdrClickHouseSchema.createTable("ussd", 3))
                .contains("TTL toDateTime(recorded_at) + toIntervalMonth(3)")
                .contains("PARTITION BY toYYYYMM(recorded_at)")
                .contains("ttl_only_drop_parts = 1");
    }
}
