package et.restlink.ussdgw.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.restcomm.protocols.ss7.config.Ss7Config;
import org.restcomm.protocols.ss7.config.Ss7ConfigLoader;

class Ss7ApplyServiceWiredDetailTest {

    @Test
    void digicomBalanceFileDetailShowsSctpListenAndRc12NotProps8013() {
        Path seed = Path.of("build/ss7-digicom-balance.json");
        Ss7Config cfg = Ss7ConfigLoader.load(seed);
        String detail = Ss7ApplyService.formatWiredDetail(
                true, cfg, "127.0.0.1", 8013, "127.0.0.1", 8014);

        assertThat(detail).startsWith("ss7=wired;source=file;");
        assertThat(detail).contains("L1-BP-1404:server:172.16.144.163:2011←10.177.55.241:2501");
        assertThat(detail).contains("L2-BP-1403:server:172.16.144.163:2019←10.177.54.241:2502");
        assertThat(detail).contains("AS-BP-1404:ipsp/server/rc=12");
        assertThat(detail).contains("AS-BP-1403:ipsp/server/rc=12");
        assertThat(detail).doesNotContain("8013").doesNotContain("8014");
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
