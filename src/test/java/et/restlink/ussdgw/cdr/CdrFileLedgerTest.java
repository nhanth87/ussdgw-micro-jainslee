package et.restlink.ussdgw.cdr;

import et.restlink.ussdgw.persist.CdrEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GMLC 2026-09-30 lesson this class exists to lock: the tenant/status filter must run
 * <em>before</em> the row cap, or every tenant page goes empty as soon as other users write N
 * rows. Plus the codec round-trip and the restart warm-up.
 */
class CdrFileLedgerTest {

    private static CdrFileLedger ledger() {
        return CdrFileLedger.forTests("logs", 1024, 65536);
    }

    private static CdrEntity event(String corr, String msisdn, String status, String tenant,
                                   Instant at) {
        CdrEntity e = new CdrEntity();
        e.id = java.util.UUID.randomUUID();
        e.recordedAt = at;
        e.startedAt = at;
        e.updatedAt = at;
        e.correlationId = corr;
        e.phase = "S1_ACTIVE";
        e.status = status;
        e.msisdn = msisdn;
        e.shortCode = "*804#";
        e.detail = "sc=*804# asUssd=" + status;
        e.tenantId = tenant;
        e.networkId = 0;
        e.originationType = "MAP";
        return e;
    }

    /** THE regression test: tenant row sits below the cap; the old code showed an empty page. */
    @Test
    void tenantFilterRunsBeforeTheRowCap() {
        CdrFileLedger l = ledger();
        Instant t = Instant.parse("2026-09-30T10:00:00Z");
        // 60 newer rows from another tenant push our row past a naive head(50).
        for (int i = 0; i < 60; i++) {
            l.append(event("other-" + i, "25191100" + i, "END", "other-tenant", t.plusSeconds(i)));
        }
        l.append(event("mine-1", "25191199999", "END", "my-tenant", t.plusSeconds(100)));
        // 60 even newer other-tenant rows — the tenant row is now ~121st from the end.
        for (int i = 0; i < 60; i++) {
            l.append(event("noise-" + i, "25191200" + i, "END", "other-tenant", t.plusSeconds(200 + i)));
        }

        List<CdrEntity> mine = l.sessions(50, e -> "my-tenant".equals(e.tenantId));

        assertEquals(1, mine.size(), "tenant filter must run before the cap, not after");
        assertEquals("mine-1", mine.get(0).correlationId);
    }

    @Test
    void sessionsFoldManyEventsOfOneCorrelationIntoOneRow() {
        CdrFileLedger l = ledger();
        Instant t = Instant.parse("2026-09-30T11:00:00Z");
        l.append(event("c1", "251911", "GATE_ARMED", "acme", t));
        l.append(event("c1", "251911", "MAP2MAP_HOP_CLOSE", "acme", t.plusSeconds(1)));
        l.append(event("c1", "251911", "END", "acme", t.plusSeconds(2)));

        List<CdrEntity> rows = l.sessions(50, e -> true);

        assertEquals(1, rows.size(), "1 correlation -> 1 session row");
        CdrEntity row = rows.get(0);
        assertEquals("END", row.status, "terminal success wins over GATE_ARMED/HOP_CLOSE");
        assertEquals(Integer.valueOf(3), row.eventCount);
        assertNotNull(row.eventsJson);
        // events_json must be causal (oldest first) for the 6-hop spine + multimenu tape.
        List<CdrRecord> timeline = CdrSessionRollup.timelineFromEvents(row);
        assertEquals("GATE_ARMED", timeline.get(0).status);
        assertEquals("END", timeline.get(timeline.size() - 1).status);
    }

    @Test
    void ringDropsOldestBeyondCapacityAndCountsIt() {
        CdrFileLedger l = CdrFileLedger.forTests("logs", 8, 65536);
        Instant t = Instant.parse("2026-09-30T12:00:00Z");
        for (int i = 0; i < 40; i++) {
            l.append(event("c" + i, "251911", "END", "acme", t.plusSeconds(i)));
        }

        assertEquals(8, l.size());
        assertTrue(l.droppedCount() >= 32);
        List<CdrEntity> rows = l.sessions(50, e -> true);
        assertEquals(8, rows.size());
        assertEquals("c39", rows.get(0).correlationId, "newest first");
    }

    @Test
    void codecRoundTripKeepsPipesInDetailAndAsUssd() {
        Instant at = Instant.parse("2026-09-30T13:45:01.123Z");
        CdrEntity src = event("corr-pipe", "25191100000", "END", "acme", at);
        src.detail = "hopOutcome=CLOSE|asUssd=Balance 100|sc=*804#";
        src.asUssd = "Balance 100 ETB";
        src.gateMs = 25000L;
        src.observedEwmaMs = 1200L;
        src.hopOutcome = "CLOSE";
        src.refuseReason = "a|b\\c";

        CdrEntity back = CdrFileLedger.parse(CdrFileLedger.line(src));

        assertNotNull(back);
        assertEquals(at, back.recordedAt);
        assertEquals("corr-pipe", back.correlationId);
        assertEquals("S1_ACTIVE", back.phase);
        assertEquals("25191100000", back.msisdn);
        assertEquals("*804#", back.shortCode);
        assertEquals("END", back.status);
        // The pipe-delimited digest must survive verbatim or CdrSessionDigest/spine break.
        assertEquals(src.detail, back.detail);
        assertEquals("acme", back.tenantId);
        assertEquals(Long.valueOf(25000L), back.gateMs);
        assertEquals(Long.valueOf(1200L), back.observedEwmaMs);
        assertEquals("MAP", back.originationType);
        assertEquals("CLOSE", back.hopOutcome);
        assertEquals("a|b\\c", back.refuseReason);
        assertEquals("Balance 100 ETB", back.asUssd);
    }

    @Test
    void codecStripsNewlinesSoOneCdrIsOneLine() {
        CdrEntity src = event("c-nl", "251911", "END", "acme", Instant.now());
        src.detail = "line1\nline2\r\nline3";

        String line = CdrFileLedger.line(src);

        assertFalse(line.contains("\n"));
        assertFalse(line.contains("\r"));
        assertEquals(1, line.split("\n", -1).length);
    }

    @Test
    void parseSkipsHeaderBlankAndUnparsableLines() {
        assertNull(CdrFileLedger.parse(CdrFileLedger.LINE_HEADER));
        assertNull(CdrFileLedger.parse(""));
        assertNull(CdrFileLedger.parse("   "));
        assertNull(CdrFileLedger.parse("not-a-timestamp|corr|phase"));
        assertNull(CdrFileLedger.parse("2026-09-30T10:00:00Z|only-two"));
    }

    /** Pre-upgrade lines (log4j %d prefix + 10 fields) still render — no blank page after upgrade. */
    @Test
    void parsesLegacyLogTimestampPrefixedLines() {
        String legacy = "2026-08-09 10:44:01.123 c-legacy|S1_ACTIVE|251911|*804#|END|asUssd=Hi|0|acme|25000|1200";

        CdrEntity e = CdrFileLedger.parse(legacy);

        assertNotNull(e);
        assertEquals("c-legacy", e.correlationId);
        assertEquals("END", e.status);
        assertEquals("*804#", e.shortCode);
        assertEquals("acme", e.tenantId);
        assertEquals(Long.valueOf(25000L), e.gateMs);
        assertEquals(Instant.parse("2026-08-09T10:44:01.123Z"), e.recordedAt);
    }

    @Test
    void warmUpRereadsTheFileSoRestartIsNotAnEmptyPage() throws IOException {
        Path dir = Files.createTempDirectory("cdr-ledger-warm");
        CdrFileLedger writer = CdrFileLedger.forTests(dir.toString(), 1024, 1 << 20);
        Instant t = Instant.parse("2026-09-30T14:00:00Z");
        List<String> lines = new ArrayList<>();
        lines.add(CdrFileLedger.LINE_HEADER);
        for (int i = 0; i < 5; i++) {
            lines.add(CdrFileLedger.line(event("warm-" + i, "2519110000" + i, "END", "acme",
                    t.plusSeconds(i))));
        }
        Files.writeString(dir.resolve(CdrFileLedger.ACTIVE_FILE),
                String.join("\n", lines) + "\n", StandardCharsets.UTF_8);

        CdrFileLedger restarted = CdrFileLedger.forTests(dir.toString(), 1024, 1 << 20);
        int n = restarted.warmFromFile();

        assertEquals(5, n);
        List<CdrEntity> rows = restarted.sessions(50, e -> true);
        assertEquals(5, rows.size());
        assertEquals("warm-4", rows.get(0).correlationId);
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort temp cleanup
                }
            });
        }
    }

    @Test
    void statusPrefixFilterMatchesRolledUpStatus() {
        CdrFileLedger l = ledger();
        Instant t = Instant.parse("2026-09-30T15:00:00Z");
        l.append(event("m1", "251911", "MAP2MAP_ARMED", "acme", t));
        l.append(event("m1", "251911", "MAP2MAP_END", "acme", t.plusSeconds(1)));
        l.append(event("g1", "251911", "GATE_EXPIRED", "acme", t.plusSeconds(2)));

        assertEquals(1, l.sessions(50, e -> "MAP2MAP_".equals(e.status) ? false : e.status.startsWith("MAP2MAP_")).size());
        assertEquals(1, l.sessions(50, e -> e.status.startsWith("GATE")).size());
    }

    /**
     * C6 (capacity_5k_tps.md): events of one correlation INTERLEAVE under concurrency.
     *
     * <p>The first implementation treated a correlation as "complete" the moment a different
     * one was seen. That is only correct when a correlation's events are contiguous, which
     * holds for a single sequential caller and is false for every real gateway. With two
     * sessions in flight the tape alternates A,B,A,B — so each run became a fragment row,
     * the six-hop spine lost its middle, and one session appeared many times on the ledger.
     *
     * <p>This test writes the interleaving explicitly, so the bug cannot come back.
     */
    @Test
    void interleavedCorrelationsCollapseToOneRowEach() {
        CdrFileLedger l = ledger();
        Instant t = Instant.parse("2026-10-02T09:00:00Z");

        // A and B interleave, which is what two concurrent MO dialogs produce.
        l.append(event("A", "25191100001", "GATE_ARMED", "acme", t));
        l.append(event("B", "25191100002", "GATE_ARMED", "acme", t.plusMillis(10)));
        l.append(event("A", "25191100001", "BRIDGED", "acme", t.plusMillis(20)));
        l.append(event("B", "25191100002", "BRIDGED", "acme", t.plusMillis(30)));
        l.append(event("A", "25191100001", "END", "acme", t.plusMillis(40)));
        l.append(event("B", "25191100002", "END", "acme", t.plusMillis(50)));

        List<CdrEntity> rows = l.sessions(50, e -> true);

        assertEquals(2, rows.size(),
                "one row per correlation even when events interleave, got: "
                        + rows.stream().map(e -> e.correlationId).toList());
        for (CdrEntity row : rows) {
            assertEquals(Integer.valueOf(3), row.eventCount,
                    "corr " + row.correlationId + " must carry all 3 of its events, not 1");
            assertEquals("END", row.status, "the terminal event must win the rollup");
            List<CdrRecord> timeline = CdrSessionRollup.timelineFromEvents(row);
            assertEquals("GATE_ARMED", timeline.get(0).status,
                    "events_json must be causal (oldest first) or the spine is wrong");
            assertEquals(3, timeline.size());
        }
    }

    /** Same trap with three correlations rotating, which is what a busy gateway looks like. */
    @Test
    void roundRobinInterleavingStillYieldsOneRowPerCorrelation() {
        CdrFileLedger l = ledger();
        Instant t = Instant.parse("2026-10-02T10:00:00Z");
        String[] corrs = {"A", "B", "C"};
        for (int i = 0; i < 9; i++) {
            // Only C ever reaches a terminal status; A and B stay in flight. That asymmetry is
            // deliberate — it proves the rollup is per-correlation and not "last event wins".
            String corr = corrs[i % 3];
            l.append(event(corr, "2519110000" + i, "C".equals(corr) ? "END" : "GATE_ARMED",
                    "acme", t.plusSeconds(i)));
        }

        List<CdrEntity> rows = l.sessions(50, e -> true);

        assertEquals(3, rows.size(), "3 correlations -> 3 rows, got: "
                + rows.stream().map(e -> e.correlationId).toList());
        for (CdrEntity row : rows) {
            assertEquals(Integer.valueOf(3), row.eventCount,
                    "corr " + row.correlationId + " must aggregate its own 3 events, got "
                            + row.eventCount);
            String expected = "C".equals(row.correlationId) ? "END" : "GATE_ARMED";
            assertEquals(expected, row.status,
                    "corr " + row.correlationId + " rolled up another correlation's status");
        }
    }
}