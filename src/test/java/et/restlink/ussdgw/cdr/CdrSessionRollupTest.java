package et.restlink.ussdgw.cdr;

import et.restlink.ussdgw.persist.CdrEntity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CdrSessionRollupTest {

    @Test
    void hopCloseIsNotTerminalFail_soAsRoutedAndEndWin() {
        assertThat(CdrSessionRollup.isTerminalFail(Map2MapCdr.HOP_CLOSE)).isFalse();
        assertThat(CdrSessionRollup.rank(Map2MapCdr.HOP_CLOSE, "FAILED"))
                .isEqualTo(CdrSessionRollup.Rank.IN_FLIGHT);

        CdrEntity session = null;
        Instant t0 = Instant.parse("2026-08-09T12:00:00Z");
        session = CdrSessionRollup.merge(session, delta("c1", Map2MapCdr.ARMED, "S1_ACTIVE", t0));
        session = CdrSessionRollup.merge(session,
                delta("c1", Map2MapCdr.HOP_CLOSE, "FAILED", t0.plusMillis(100)));
        session = CdrSessionRollup.merge(session,
                delta("c1", Map2MapCdr.AS_ROUTED, "S1_ACTIVE", t0.plusMillis(200)));
        session = CdrSessionRollup.merge(session,
                delta("c1", "END", "COMPLETED", t0.plusMillis(300)));

        assertThat(session.status).isEqualTo("END");
        assertThat(session.phase).isEqualTo("COMPLETED");
        assertThat(session.eventCount).isEqualTo(4);
        assertThat(CdrSessionRollup.parseEvents(session.eventsJson)).hasSize(4);
    }

    @Test
    void terminalFailBeatsLaterInFlight() {
        Instant t0 = Instant.parse("2026-08-09T12:00:00Z");
        CdrEntity session = CdrSessionRollup.merge(null,
                delta("c2", Map2MapCdr.HOP_FAIL, "FAILED", t0));
        session = CdrSessionRollup.merge(session,
                delta("c2", "CONTINUE", "S1_ACTIVE", t0.plusMillis(50)));
        assertThat(session.status).isEqualTo(Map2MapCdr.HOP_FAIL);
        assertThat(session.eventCount).isEqualTo(2);
    }

    @Test
    void eventsJsonKeepsPipeSeparatorsForAsUssd() {
        Instant t0 = Instant.parse("2026-08-09T12:00:00Z");
        String detail = "service=VirtualSessionBridge|sync|asAction=END|asUssd="
                + "A".repeat(50) + "|asLen=50|note=AS→UE";
        CdrEntity session = CdrSessionRollup.merge(null,
                delta("pipe", "END", "COMPLETED", t0, detail));
        assertThat(session.eventsJson).contains("|asUssd=");
        assertThat(session.eventsJson).doesNotContain("/asUssd=");
        List<CdrSessionRollup.Event> events = CdrSessionRollup.parseEvents(session.eventsJson);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().detail()).contains("|asUssd=");
        assertThat(CdrSessionDigest.parseDetail(events.getFirst().detail()).get("asUssd"))
                .isEqualTo("A".repeat(50));
    }

    @Test
    void normalizeEventDetail_restoresMangledSlashPipes() {
        String mangled = "service=VirtualSessionBridge/sync/asAction=END/asUssd=(xyz)/asLen=5/note=AS→UE";
        String restored = CdrSessionRollup.normalizeEventDetail(mangled);
        assertThat(restored).contains("|asUssd=(xyz)|").contains("|asLen=5|");
        assertThat(CdrSessionDigest.parseDetail(restored).get("asUssd")).isEqualTo("(xyz)");
    }

    @Test
    void continueUpdatesSameSessionAndLatestAsUssd() {
        Instant t0 = Instant.parse("2026-08-09T12:00:00Z");
        CdrEntity session = CdrSessionRollup.merge(null,
                delta("c3", "CONTINUE", "S1_ACTIVE", t0, "asUssd=menu1"));
        session = CdrSessionRollup.merge(session,
                delta("c3", "CONTINUE", "S1_ACTIVE", t0.plusMillis(10), "asUssd=menu2"));
        session = CdrSessionRollup.merge(session,
                delta("c3", "CONTINUE", "S1_ACTIVE", t0.plusMillis(20), "asUssd=menu3"));
        session = CdrSessionRollup.merge(session,
                delta("c3", "END", "COMPLETED", t0.plusMillis(30), "asUssd=bye"));

        assertThat(session.status).isEqualTo("END");
        assertThat(session.asUssd).isEqualTo("bye");
        assertThat(session.eventCount).isEqualTo(4);
        assertThat(session.detail).contains("asUssd=bye");
    }

    @Test
    void coalesceByCorrelation_map2mapSevenEventsToOne() {
        Instant t0 = Instant.parse("2026-08-09T12:00:00Z");
        String[] statuses = {
                Map2MapCdr.ARMED, Map2MapCdr.HOP_START, CdrStatuses.GATE_ARMED,
                Map2MapCdr.USSD_SENT, Map2MapCdr.HOP_CLOSE, Map2MapCdr.AS_ROUTED, "END"
        };
        String[] phases = {
                "S1_ACTIVE", "S1_ACTIVE", "S1_ACTIVE", "S1_ACTIVE", "FAILED", "S1_ACTIVE", "COMPLETED"
        };
        List<CdrEntity> batch = new ArrayList<>();
        for (int i = 0; i < statuses.length; i++) {
            batch.add(delta("corr-7", statuses[i], phases[i], t0.plusMillis(i * 10)));
        }
        batch.add(delta("other", "END", "COMPLETED", t0));

        List<CdrEntity> merged = CdrSessionRollup.coalesceByCorrelation(batch);
        assertThat(merged).hasSize(2);
        CdrEntity seven = merged.stream()
                .filter(e -> "corr-7".equals(e.correlationId))
                .findFirst()
                .orElseThrow();
        assertThat(seven.eventCount).isEqualTo(7);
        assertThat(seven.status).isEqualTo("END");
        assertThat(seven.phase).isEqualTo("COMPLETED");
        assertThat(CdrSessionRollup.parseEvents(seven.eventsJson)).hasSize(7);
    }

    @Test
    void capEventsKeepsFirstAndTail() {
        List<CdrSessionRollup.Event> events = new ArrayList<>();
        Instant t0 = Instant.parse("2026-08-09T12:00:00Z");
        for (int i = 0; i < CdrSessionRollup.MAX_EVENTS + 5; i++) {
            events.add(new CdrSessionRollup.Event(t0.plusSeconds(i), "S1_ACTIVE", "E" + i, null));
        }
        List<CdrSessionRollup.Event> capped = CdrSessionRollup.capEvents(events);
        assertThat(capped).hasSize(CdrSessionRollup.MAX_EVENTS);
        assertThat(capped.getFirst().status()).isEqualTo("E0");
        assertThat(capped.getLast().status()).isEqualTo("E" + (events.size() - 1));
    }

    @Test
    void foldIncomingSessionAppendsAcrossFlushes() {
        Instant t0 = Instant.parse("2026-08-09T12:00:00Z");
        CdrEntity first = CdrSessionRollup.coalesceByCorrelation(List.of(
                delta("c4", Map2MapCdr.ARMED, "S1_ACTIVE", t0),
                delta("c4", CdrStatuses.GATE_ARMED, "S1_ACTIVE", t0.plusMillis(5))
        )).getFirst();
        CdrEntity second = CdrSessionRollup.seed(
                delta("c4", "END", "COMPLETED", t0.plusMillis(20), "asUssd=done"));
        CdrEntity folded = CdrSessionRollup.foldIncomingSession(first, second);
        assertThat(folded.id).isEqualTo(first.id);
        assertThat(folded.startedAt).isEqualTo(first.startedAt);
        assertThat(folded.eventCount).isEqualTo(3);
        assertThat(folded.status).isEqualTo("END");
        assertThat(CdrSessionRollup.parseEvents(folded.eventsJson)).hasSize(3);
    }

    @Test
    void timelineFromEventsOldestFirst() {
        Instant t0 = Instant.parse("2026-08-09T12:00:00Z");
        CdrEntity session = CdrSessionRollup.merge(null,
                delta("c5", Map2MapCdr.ARMED, "S1_ACTIVE", t0));
        session = CdrSessionRollup.merge(session,
                delta("c5", "END", "COMPLETED", t0.plusSeconds(1)));
        List<CdrRecord> tl = CdrSessionRollup.timelineFromEvents(session);
        assertThat(tl).hasSize(2);
        assertThat(tl.getFirst().status).isEqualTo(Map2MapCdr.ARMED);
        assertThat(tl.getLast().status).isEqualTo("END");
    }

    private static CdrEntity delta(String corr, String status, String phase, Instant at) {
        return delta(corr, status, phase, at, null);
    }

    private static CdrEntity delta(String corr, String status, String phase, Instant at, String detail) {
        CdrEntity e = new CdrEntity();
        e.id = UUID.randomUUID();
        e.correlationId = corr;
        e.status = status;
        e.phase = phase;
        e.recordedAt = at;
        e.startedAt = at;
        e.updatedAt = at;
        e.detail = detail;
        e.csvLine = corr + "|" + status;
        e.msisdn = "251911230398";
        e.shortCode = "*804#";
        e.originationType = "MAP";
        e.networkId = 0;
        if (detail != null && detail.contains("asUssd=")) {
            e.asUssd = CdrSessionDigest.parseDetail(detail).get("asUssd");
        }
        return e;
    }
}
