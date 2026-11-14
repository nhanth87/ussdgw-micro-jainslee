package et.restlink.ussdgw.ra.cdr;

import com.microjainslee.api.OutboundCommand;
import com.sun.net.httpserver.HttpServer;
import et.restlink.ussdgw.cdr.clickhouse.CdrClickHouseWriter;
import et.restlink.ussdgw.cdr.clickhouse.ClickHouseHttp;
import et.restlink.ussdgw.persist.CdrEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** 3-port contract of {@code cdr-clickhouse-ra}: lifecycle starts/stops I/O, commands carry rows. */
class CdrClickHouseRaEndpointTest {

    private HttpServer server;
    private final List<String> statements = new CopyOnWriteArrayList<>();
    @TempDir Path spool;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String q = ex.getRequestURI().getRawQuery() == null ? "" : java.net.URLDecoder.decode(
                    ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            statements.add(q.contains("INSERT INTO") ? "INSERT " + body.lines().count() : body.strip());
            byte[] resp = body.startsWith("SELECT engine_full")
                    ? "MergeTree TTL toDateTime(recorded_at) + toIntervalMonth(3)\n".getBytes() : new byte[0];
            ex.sendResponseHeaders(200, resp.length == 0 ? -1 : resp.length);
            if (resp.length > 0) {
                ex.getResponseBody().write(resp);
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private CdrClickHouseRaEndpoint endpoint() {
        ClickHouseHttp ch = new ClickHouseHttp("http://127.0.0.1:" + server.getAddress().getPort(), "u", "p",
                Duration.ofSeconds(2), Duration.ofSeconds(5));
        CdrClickHouseWriter w = new CdrClickHouseWriter(ch, "ussd.cdr_event", 10_000, 100, 20, spool,
                1 << 20, "n1");
        return new CdrClickHouseRaEndpoint(ch, "ussd", 3, w);
    }

    private static CdrEntity row(String corr) {
        CdrEntity e = new CdrEntity();
        e.id = UUID.randomUUID();
        e.recordedAt = Instant.now();
        e.correlationId = corr;
        e.phase = "S1_PULL";
        e.status = "END";
        return e;
    }

    @Test
    void lifecycleCommandsAndSchema() throws Exception {
        CdrClickHouseRaEndpoint ep = endpoint();
        assertThat(ep.getRaName()).isEqualTo("cdr-clickhouse-ra");
        ep.activate(null);
        ep.activate(null);  // idempotent
        for (int i = 0; i < 7; i++) {
            ep.sendCommand(new CdrStoreCommand.Record(row("c" + i)));
        }
        ep.sendCommand(new OutboundCommand() { });  // unknown command: ignored, never thrown
        for (int i = 0; i < 50 && !ep.isSchemaReady(); i++) {
            Thread.sleep(50);
        }
        assertThat(ep.isSchemaReady()).isTrue();
        ep.deactivate();
        ep.deactivate();  // idempotent
        assertThat(statements).anyMatch(s -> s.startsWith("CREATE DATABASE IF NOT EXISTS ussd"));
        assertThat(statements).anyMatch(s -> s.startsWith("CREATE TABLE IF NOT EXISTS ussd.cdr_event"));
        long inserted = statements.stream().filter(s -> s.startsWith("INSERT "))
                .mapToLong(s -> Long.parseLong(s.substring(7))).sum();
        assertThat(inserted).isEqualTo(7);
        assertThat(ep.writer().inserted()).isEqualTo(7);
    }
}
