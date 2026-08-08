package et.restlink.ussdgw.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.restcomm.protocols.ss7.config.Ss7Config;
import org.restcomm.protocols.ss7.config.Ss7ConfigLoader;

class Ss7ApplyServiceWiredDetailTest {

    @Test
    void multiLinkLabFileDetailShowsSctpListenAndRcNotProps8013() throws Exception {
        Path seed = Path.of(
                getClass().getResource("/ss7-multi-link-lab.json").toURI());
        Ss7Config cfg = Ss7ConfigLoader.load(seed);
        String detail = Ss7ApplyService.formatWiredDetail(
                true, cfg, "127.0.0.1", 8013, "127.0.0.1", 8014);

        assertThat(detail).startsWith("ss7=wired;source=file;");
        assertThat(detail).contains("L1-PEER-A:server:192.0.2.10:3011←198.51.100.10:3501");
        assertThat(detail).contains("L2-PEER-B:server:192.0.2.10:3019←198.51.100.11:3502");
        assertThat(detail).contains("AS-PEER-A:ipsp/server/rc=12");
        assertThat(detail).contains("AS-PEER-B:ipsp/server/rc=12");
        assertThat(detail).doesNotContain("8013").doesNotContain("8014");
    }

    @Test
    void labLoopbackFileDetailShows8013Listen() {
        Path seed = Path.of("build/ss7-lab.json");
        Ss7Config cfg = Ss7ConfigLoader.load(seed);
        String detail = Ss7ApplyService.formatWiredDetail(
                true, cfg, "10.0.0.1", 2011, "10.0.0.2", 2501);

        assertThat(detail).startsWith("ss7=wired;source=file;");
        assertThat(detail).contains("L1:server:127.0.0.1:8013←127.0.0.1:8014");
        assertThat(detail).contains("AS1:ipsp/server/rc=0");
        assertThat(detail).doesNotContain("2011").doesNotContain("2501");
    }

    @Test
    void propsFallbackKeepsSingleLinkArrow() {
        String detail = Ss7ApplyService.formatWiredDetail(
                false, null, "127.0.0.1", 8013, "127.0.0.1", 8014);
        assertThat(detail).isEqualTo(
                "ss7=wired;source=props;links=[127.0.0.1:8013→127.0.0.1:8014]");
    }

    @Test
    void missingFileFallsBackHonestly() {
        String detail = Ss7ApplyService.formatWiredDetail(
                true, null, "10.0.0.1", 2011, "10.0.0.2", 2501);
        assertThat(detail).isEqualTo(
                "ss7=wired;source=file-missing;links=[10.0.0.1:2011→10.0.0.2:2501]");
    }
}
