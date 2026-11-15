package et.restlink.ussdgw.cdr.clickhouse;

import com.microjainslee.ra.clickhouse.ClickHouseHttp;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * DDL for the CDR event table.
 *
 * <ul>
 *   <li>One row per CDR milestone (the same delta the file ledger writes); sessions are rolled
 *       up at read time exactly like the file ledger does.</li>
 *   <li>{@code PARTITION BY toYYYYMM} + {@code TTL … + toIntervalMonth(N)} +
 *       {@code ttl_only_drop_parts}: expiry drops whole monthly parts, never rewrites them, and a
 *       month is only dropped once <em>every</em> row in it is older than N months — so the
 *       effective retention is between N and N+1 months, never less than N.</li>
 *   <li>{@code non_replicated_deduplication_window}: a retried batch carrying the same
 *       {@code insert_deduplication_token} is ignored, so spool replay cannot double-count.</li>
 * </ul>
 */
public final class CdrClickHouseSchema {

    /** Requirement: CDR kept for at least three months. */
    public static final int MIN_RETENTION_MONTHS = 3;
    public static final String TABLE = "cdr_event";

    private CdrClickHouseSchema() { }

    /** Configured retention, never below {@link #MIN_RETENTION_MONTHS}. */
    public static int effectiveRetentionMonths(int configured) {
        return Math.max(MIN_RETENTION_MONTHS, configured);
    }

    public static String ttlExpression(int months) {
        return "toDateTime(recorded_at) + toIntervalMonth(" + months + ")";
    }

    public static String createDatabase(String db) {
        return "CREATE DATABASE IF NOT EXISTS " + ClickHouseHttp.requireIdent(db);
    }

    public static String createTable(String db, int retentionMonths) {
        String t = ClickHouseHttp.requireIdent(db) + "." + TABLE;
        return """
                CREATE TABLE IF NOT EXISTS %s
                (
                    event_id          UUID,
                    recorded_at       DateTime64(3, 'UTC'),
                    correlation_id    String,
                    phase             LowCardinality(String),
                    status            LowCardinality(String),
                    msisdn            String,
                    short_code        LowCardinality(String),
                    detail            String CODEC(ZSTD(3)),
                    network_id        Int32,
                    tenant_id         LowCardinality(String),
                    origination_type  LowCardinality(String),
                    gate_ms           Nullable(Int64),
                    observed_ewma_ms  Nullable(Int64),
                    hop_outcome       LowCardinality(String),
                    refuse_reason     String CODEC(ZSTD(3)),
                    as_ussd           String CODEC(ZSTD(3)),
                    csv_line          String CODEC(ZSTD(3)),
                    node_id           LowCardinality(String),
                    inserted_at       DateTime DEFAULT now(),
                    INDEX idx_corr   correlation_id TYPE bloom_filter(0.01) GRANULARITY 4,
                    INDEX idx_msisdn msisdn         TYPE bloom_filter(0.01) GRANULARITY 4
                )
                ENGINE = MergeTree
                PARTITION BY toYYYYMM(recorded_at)
                ORDER BY (recorded_at, correlation_id)
                TTL %s
                SETTINGS ttl_only_drop_parts = 1,
                         non_replicated_deduplication_window = 10000
                """.formatted(t, ttlExpression(retentionMonths));
    }

    /**
     * Bring an existing table's TTL to {@code retentionMonths} without rewriting old parts
     * ({@code materialize_ttl_after_modify = 0}); no-op when it already matches.
     *
     * @return true when the TTL was changed
     */
    public static boolean ensureTtl(ClickHouseHttp ch, String db, int retentionMonths) throws IOException {
        List<String> rows = ch.query(
                "SELECT engine_full FROM system.tables WHERE database = {db:String} AND name = {t:String} "
                        + "FORMAT TSVRaw",
                Map.of("db", db, "t", TABLE));
        String want = "toIntervalMonth(" + retentionMonths + ")";
        if (!rows.isEmpty() && rows.getFirst().contains(want)) {
            return false;
        }
        ch.execute("ALTER TABLE " + ClickHouseHttp.requireIdent(db) + "." + TABLE
                + " MODIFY TTL " + ttlExpression(retentionMonths),
                Map.of("materialize_ttl_after_modify", "0"));
        return true;
    }
}
