package et.restlink.ussdgw.cdr.clickhouse;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import et.restlink.ussdgw.persist.CdrEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * CDR row → NDJSON serialisation for the shared {@code ra-clickhouse} writer.
 *
 * <p>What a row contains stays here (app business logic); the shared RA only moves bytes.
 * Unicode (Amharic {@code as_ussd}, MSISDN) is written verbatim — never escaped or folded.</p>
 */
public final class CdrNdjson {
    private static final JsonFactory JSON = new JsonFactory();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private CdrNdjson() { }

    /** One JSON object per line, columns of {@code cdr_event}. */
    public static byte[] toNdjson(List<CdrEntity> rows, String nodeId) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(rows.size() * 512);
        try (JsonGenerator g = JSON.createGenerator(out)) {
            g.setRootValueSeparator(null);
            for (CdrEntity r : rows) {
                g.writeStartObject();
                g.writeStringField("event_id", (r.id == null ? UUID.randomUUID() : r.id).toString());
                g.writeStringField("recorded_at", r.recordedAt == null ? ISO.format(java.time.Instant.now())
                        : ISO.format(r.recordedAt));
                g.writeStringField("correlation_id", nz(r.correlationId));
                g.writeStringField("phase", nz(r.phase));
                g.writeStringField("status", nz(r.status));
                g.writeStringField("msisdn", nz(r.msisdn));
                g.writeStringField("short_code", nz(r.shortCode));
                g.writeStringField("detail", nz(r.detail));
                g.writeNumberField("network_id", r.networkId == null ? 0 : r.networkId);
                g.writeStringField("tenant_id", nz(r.tenantId));
                g.writeStringField("origination_type", nz(r.originationType));
                if (r.gateMs == null) {
                    g.writeNullField("gate_ms");
                } else {
                    g.writeNumberField("gate_ms", r.gateMs);
                }
                if (r.observedEwmaMs == null) {
                    g.writeNullField("observed_ewma_ms");
                } else {
                    g.writeNumberField("observed_ewma_ms", r.observedEwmaMs);
                }
                g.writeStringField("hop_outcome", nz(r.hopOutcome));
                g.writeStringField("refuse_reason", nz(r.refuseReason));
                g.writeStringField("as_ussd", nz(r.asUssd));
                g.writeStringField("csv_line", nz(r.csvLine));
                g.writeStringField("node_id", nodeId);
                g.writeEndObject();
                g.writeRaw('\n');
            }
        }
        return out.toByteArray();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
