package et.restlink.ussdgw.cdr;

import et.restlink.ussdgw.persist.CdrEntity;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CdrService#list} filter contract against the file ledger. The admin CDR page keeps the
 * same semantics it had on PostgreSQL — most importantly that filters apply before the cap.
 */
class CdrFileLedgerListTest {

    /** CdrService needs CDI for the ledger/flusher; inject the two flags by hand. */
    private static CdrService service(CdrFileLedger ledger, boolean dbEnabled) throws Exception {
        CdrService s = new CdrService();
        inject(s, "ledger", ledger);
        s.enabled = true;
        s.dbEnabled = dbEnabled;
        return s;
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static CdrEntity ev(String corr, String msisdn, String status, String tenant, int sec) {
        CdrEntity e = new CdrEntity();
        e.id = java.util.UUID.randomUUID();
        Instant t = Instant.parse("2026-10-01T09:00:00Z").plusSeconds(sec);
        e.recordedAt = t;
        e.startedAt = t;
        e.updatedAt = t;
        e.correlationId = corr;
        e.phase = "S1_ACTIVE";
        e.status = status;
        e.msisdn = msisdn;
        e.shortCode = "*804#";
        e.detail = "sc=*804#";
        e.tenantId = tenant;
        e.networkId = 0;
        e.originationType = "MAP";
        return e;
    }

    @Test
    void dbDisabled_meansNoDatabaseAtAll() throws Exception {
        CdrFileLedger l = CdrFileLedger.forTests("logs", 4096, 65536);
        CdrService s = service(l, false);
        l.append(ev("c1", "25191100000", "END", "acme", 0));

        List<CdrEntity> rows = s.list(50, "acme", null, null, null);

        assertEquals(1, rows.size());
        assertEquals("c1", rows.get(0).correlationId);
        // em/dataSource/flusher are null in this seam — reaching them would have thrown.
    }

    @Test
    void tenantScopeFilter_appliesBeforeLimit() throws Exception {
        CdrFileLedger l = CdrFileLedger.forTests("logs", 4096, 65536);
        CdrService s = service(l, false);
        for (int i = 0; i < 200; i++) {
            l.append(ev("o" + i, "25191100" + i, "END", "other", i));
        }
        l.append(ev("mine", "25191199999", "END", "acme", 500));

        List<CdrEntity> rows = s.list(50, "acme", null, null, null);

        assertEquals(1, rows.size(), "tenant page must not be empty because others wrote 200 rows");
        assertEquals("mine", rows.get(0).correlationId);
    }

    @Test
    void msisdnAndCorrFiltersAreExact() throws Exception {
        CdrFileLedger l = CdrFileLedger.forTests("logs", 4096, 65536);
        CdrService s = service(l, false);
        l.append(ev("c1", "25191100001", "END", "acme", 0));
        l.append(ev("c2", "25191100002", "END", "acme", 1));

        assertEquals(1, s.list(50, null, "25191100002", null, null).size());
        assertEquals(1, s.list(50, null, null, "c1", null).size());
        assertEquals(0, s.list(50, null, "251911", null, null).size(), "no substring matching");
        assertEquals(0, s.list(50, null, null, "c", null).size());
    }

    @Test
    void statusFilterAcceptsExactAndStarPrefix() throws Exception {
        CdrFileLedger l = CdrFileLedger.forTests("logs", 4096, 65536);
        CdrService s = service(l, false);
        l.append(ev("m1", "25191100001", "MAP2MAP_ARMED", "acme", 0));
        l.append(ev("m1", "25191100001", "MAP2MAP_END", "acme", 1));
        l.append(ev("g1", "25191100002", "GATE_EXPIRED", "acme", 2));
        l.append(ev("e1", "25191100003", "END", "acme", 3));

        assertEquals(1, s.list(50, "acme", null, null, "map2map_end").size(), "exact, case-insensitive");
        assertEquals(1, s.list(50, "acme", null, null, "MAP2MAP_*").size(), "prefix filter");
        assertEquals(0, s.list(50, "acme", null, null, "GATED*").size(), "GATED* must not match GATE_EXPIRED");
        assertEquals(1, s.list(50, "acme", null, null, "GATE*").size());
        // 4 events but 3 correlations — the ledger is 1 row per correlation.
        assertEquals(3, s.list(50, "acme", null, null, null).size());
    }

    @Test
    void limitIsClampedAndDefaulted() {
        assertEquals(CdrService.DEFAULT_LIMIT, CdrService.clampLimit(null));
        assertEquals(CdrService.DEFAULT_LIMIT, CdrService.clampLimit(""));
        assertEquals(CdrService.DEFAULT_LIMIT, CdrService.clampLimit("abc"));
        assertEquals(CdrService.DEFAULT_LIMIT, CdrService.clampLimit("0"));
        assertEquals(CdrService.DEFAULT_LIMIT, CdrService.clampLimit("-5"));
        assertEquals(1, CdrService.clampLimit("1"));
        assertEquals(CdrService.MAX_LIMIT, CdrService.clampLimit("9999"));
    }

    @Test
    void newestFirstOrdering() throws Exception {
        CdrFileLedger l = CdrFileLedger.forTests("logs", 4096, 65536);
        CdrService s = service(l, false);
        for (int i = 0; i < 5; i++) {
            l.append(ev("c" + i, "2519110000" + i, "END", "acme", i));
        }

        List<CdrRecord> rows = s.listRecords(50, "acme");

        assertEquals(5, rows.size());
        assertEquals("c4", rows.get(0).correlationId);
        assertEquals("c0", rows.get(4).correlationId);
        assertTrue(rows.get(0).eventsJson != null && !rows.get(0).eventsJson.isBlank(),
                "ledger rows must carry the event tape so the 6-hop spine still works");
        assertEquals(0, rows.stream().filter(r -> r.legacyEventTape).count(),
                "file-ledger rows are not legacy tape");
    }
}