package et.restlink.ussdgw.sip;

import et.restlink.ussdgw.persist.SipTrunkEntity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SipTrunkPeerHostUniqueTest {

    @Test
    void rejectsDuplicateEnabledPeerHostCaseInsensitive() {
        SipTrunkEntity a = trunk("a", "As.Example");
        assertThatThrownBy(() ->
                SipTrunkService.ensurePeerHostAvailable("b", "as.example", List.of(a)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("peerHost already used");
    }

    @Test
    void allowsSameHostOnSameTrunkId() {
        SipTrunkEntity a = trunk("a", "as.example");
        assertThatCode(() ->
                SipTrunkService.ensurePeerHostAvailable("a", "as.example", List.of(a)))
                .doesNotThrowAnyException();
    }

    private static SipTrunkEntity trunk(String id, String peer) {
        SipTrunkEntity e = new SipTrunkEntity();
        e.trunkId = id;
        e.peerHost = peer;
        e.enabled = true;
        return e;
    }
}
