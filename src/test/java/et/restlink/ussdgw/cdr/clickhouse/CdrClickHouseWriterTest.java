package et.restlink.ussdgw.cdr.clickhouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import et.restlink.ussdgw.persist.CdrEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** Batching, spool-on-failure and token-preserving replay, against a fake ClickHouse. */
class CdrClickHouseWriterTest {

    record Insert(String token, String body) { }

    private HttpServer server;
    private final List<Insert> inserts = new CopyOnWriteArrayList<>();
    private final AtomicBoolean down = new AtomicBoolean();
    private ClickHouseHttp ch;

    @TempDir Path spool;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            int code = down.get() ? 503 : 200;
            if (!down.get()) {
                Map<String, String> q = query(ex.getRequestURI());
                if (q.getOrDefault("query", "").startsWith("INSERT")) {
                    inserts.add(new Insert(q.get("insert_deduplication_token"),
                            new String(body, StandardCharsets.UTF_8)));
                }
            }
            byte[] resp = down.get() ? "Code: 210. DB::NetException".getBytes() : new byte[0];
            ex.sendResponseHeaders(code, resp.length == 0 ? -1 : resp.length);
            if (resp.length > 0) {
                ex.getResponseBody().write(resp);
            }
            ex.close();
        });
        server.start();
        ch = new ClickHouseHttp("http://127.0.0.1:" + server.getAddress().getPort(), "u", "p",
                Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private CdrClickHouseWriter writer() {
        return new CdrClickHouseWriter(ch, "ussd.cdr_event", 10_000, 100, 50, spool, 1 << 20, "node1");
    }

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
        byte[] nd = CdrClickHouseWriter.toNdjson(List.of(row("c1", "ቀሪ ሂሳብዎ 25 ብር"), row("c2", null)), "n1");
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
    void batchesAreInsertedWithADedupToken() throws Exception {
        CdrClickHouseWriter w = writer();
        w.writeBatch(List.of(row("c1", "x"), row("c2", "y")));
        assertThat(inserts).hasSize(1);
        assertThat(inserts.getFirst().token()).isNotBlank();
        assertThat(inserts.getFirst().body().lines()).hasSize(2);
        assertThat(w.inserted()).isEqualTo(2);
    }

    @Test
    void downServerSpoolsAndReplayKeepsTheSameToken() throws Exception {
        CdrClickHouseWriter w = writer();
        Files.createDirectories(spool);
        down.set(true);
        w.writeBatch(List.of(row("c1", "x"), row("c2", "y"), row("c3", "z")));
        assertThat(w.spooledRows()).isEqualTo(3);
        assertThat(w.insertFailures()).isEqualTo(1);
        List<Path> files;
        try (var s = Files.list(spool)) {
            files = s.toList();
        }
        assertThat(files).hasSize(1);
        String name = files.getFirst().getFileName().toString();
        String token = name.substring(name.indexOf('-') + 1, name.length() - ".ndjson".length());

        down.set(false);
        Thread.sleep(600);  // first backoff is 500 ms
        w.replaySpool(10);
        assertThat(inserts).hasSize(1);
        assertThat(inserts.getFirst().token()).isEqualTo(token);
        assertThat(inserts.getFirst().body().lines()).hasSize(3);
        assertThat(w.spoolBytes()).isZero();
        try (var s = Files.list(spool)) {
            assertThat(s.toList()).isEmpty();
        }
    }

    @Test
    void writerThreadDrainsTheQueueOnStop() throws Exception {
        CdrClickHouseWriter w = writer();
        w.start();
        for (int i = 0; i < 250; i++) {
            assertThat(w.offer(row("c" + i, "t"))).isTrue();
        }
        w.stop(5_000);
        long rows = inserts.stream().mapToLong(i -> i.body().lines().count()).sum();
        assertThat(rows).isEqualTo(250);
        assertThat(inserts).allSatisfy(i -> assertThat(i.body().lines().count()).isLessThanOrEqualTo(100));
    }

    @Test
    void spoolBudgetIsEnforcedAndCounted() throws Exception {
        CdrClickHouseWriter w = new CdrClickHouseWriter(ch, "ussd.cdr_event", 10_000, 100, 50, spool, 10, "n");
        Files.createDirectories(spool);
        down.set(true);
        w.writeBatch(List.of(row("c1", "x")));
        assertThat(w.droppedSpoolFull()).isEqualTo(1);
        assertThat(w.spooledRows()).isZero();
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

    private static Map<String, String> query(URI uri) {
        String raw = uri.getRawQuery();
        java.util.Map<String, String> m = new java.util.HashMap<>();
        if (raw == null) {
            return m;
        }
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            String k = java.net.URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }
}
