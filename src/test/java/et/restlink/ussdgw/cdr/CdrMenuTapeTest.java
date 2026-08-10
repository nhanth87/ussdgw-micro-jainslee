package et.restlink.ussdgw.cdr;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CdrMenuTapeTest {

    @Test
    void multimenuPath_foldsDigitsAndContinuesWithGen() {
        CdrRecord root = row("CONTINUE",
                "service=VirtualSessionBridge|gen=1|asUssd=Root menu 1.bal 2.pkg|asLen=20");
        CdrRecord dig = row(CdrStatuses.MS_DIGIT,
                "service=MapUssdParentSbb|MS_DIGIT|path=as-pull|gen=2|digit=2|note=MS→menu");
        CdrRecord pkg = row("CONTINUE",
                "service=VirtualSessionBridge|gen=2|menuTurn=2|asUssd=packages Amharic|asLen=16");

        CdrMenuTape.Summary s = CdrMenuTape.fromTimeline(List.of(root, dig, pkg));
        assertThat(s.digitCount()).isEqualTo(1);
        assertThat(s.continueCount()).isEqualTo(2);
        assertThat(s.maxGen()).isEqualTo(2);
        assertThat(s.multimenu()).isTrue();
        assertThat(s.path()).contains("continue[1]=").contains("dig[2]=2").contains("continue[2]=");
        assertThat(CdrServiceStatuses.timelineSummary(dig)).isEqualTo("digit=2 gen=2");
        assertThat(CdrServiceStatuses.timelineSummary(pkg)).contains("AS:").contains("gen=2");
    }

    @Test
    void asDropSummary_showsGenMismatch() {
        CdrRecord drop = row(CdrStatuses.AS_DROP,
                "service=VirtualSessionBridge|AS_DROP|reason=genMismatch|wireGen=1|sessionGen=2|state=AWAITING_AS");
        assertThat(CdrServiceStatuses.humanStatus(CdrStatuses.AS_DROP)).contains("drop");
        assertThat(CdrServiceStatuses.timelineSummary(drop))
                .contains("genMismatch")
                .contains("wireGen=1")
                .contains("sessionGen=2");
        assertThat(CdrStatuses.ledgerChipClass("S1_ACTIVE", CdrStatuses.AS_DROP))
                .isEqualTo("cdr-status--fail");
    }

    private static CdrRecord row(String status, String detail) {
        CdrRecord r = new CdrRecord();
        r.status = status;
        r.detail = detail;
        r.phase = "S1_ACTIVE";
        return r;
    }
}
