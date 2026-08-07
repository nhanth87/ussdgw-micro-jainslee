package et.restlink.ussdgw.sip;

import et.restlink.ussdgw.persist.SipTrunkEntity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SipTrunkServiceTest {

    @Test
    void trunkAllowsSharedOrMatchingTenant() {
        SipTrunkEntity shared = new SipTrunkEntity();
        shared.tenantId = null;
        assertThat(SipTrunkService.trunkAllowsTenant(shared, "bank-a")).isTrue();
        assertThat(SipTrunkService.trunkAllowsTenant(shared, null)).isTrue();

        SipTrunkEntity owned = new SipTrunkEntity();
        owned.tenantId = "bank-a";
        assertThat(SipTrunkService.trunkAllowsTenant(owned, "bank-a")).isTrue();
        assertThat(SipTrunkService.trunkAllowsTenant(owned, "bank-b")).isFalse();
        assertThat(SipTrunkService.trunkAllowsTenant(owned, null)).isFalse();
        assertThat(SipTrunkService.trunkAllowsTenant(owned, "  ")).isFalse();
    }

    @Test
    void resolveToUriSanitizesMsisdnToDigits() {
        SipTrunkService svc = new SipTrunkService();
        SipTrunkEntity t = new SipTrunkEntity();
        t.peerHost = "as.example.com";
        t.peerPort = 5060;
        t.requestUriTemplate = "sip:{msisdn}@as.example.com";
        assertThat(svc.resolveToUri(t, "+251-911-000-111", null))
                .isEqualTo("sip:251911000111@as.example.com");
        // Non-digits stripped so host cannot be rewritten via msisdn
        assertThat(svc.resolveToUri(t, "251911000111@evil.com", null))
                .isEqualTo("sip:251911000111@as.example.com");
        assertThat(svc.resolveToUri(t, "251911000111;x=1", null))
                .isEqualTo("sip:2519110001111@as.example.com");
    }

    @Test
    void resolveToUriFallbackUsesDigitsAndPeer() {
        SipTrunkService svc = new SipTrunkService();
        SipTrunkEntity t = new SipTrunkEntity();
        t.peerHost = "peer.example.com";
        t.peerPort = 5070;
        t.requestUriTemplate = null;
        assertThat(svc.resolveToUri(t, "2519-1100", null))
                .isEqualTo("sip:25191100@peer.example.com:5070");
    }

    @Test
    void digitsOnlyStripsNonDigits() {
        assertThat(SipTrunkService.digitsOnly("+251 (911) 000")).isEqualTo("251911000");
        assertThat(SipTrunkService.digitsOnly(null)).isEmpty();
    }

    @Test
    void ensurePeerHostAvailableRejectsDuplicateEnabledPeer() {
        SipTrunkEntity existing = new SipTrunkEntity();
        existing.trunkId = "t1";
        existing.peerHost = "AS.Example.COM";
        assertThatThrownBy(() -> SipTrunkService.ensurePeerHostAvailable(
                "t2", "as.example.com", List.of(existing)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("peerHost already used");
        // Same trunk id may keep its peer
        SipTrunkService.ensurePeerHostAvailable("t1", "as.example.com", List.of(existing));
    }
}
