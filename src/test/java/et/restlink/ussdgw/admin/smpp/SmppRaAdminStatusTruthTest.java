package et.restlink.ussdgw.admin.smpp;

import com.microjainslee.admin.AdminDashboardRegistry;
import com.microjainslee.admin.RaAdminHttpRequest;
import com.microjainslee.admin.RaAdminHttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SMPP hub status truth: anyPeerUp only when ≥1 peer bound; LISTEN alone is not green. */
class SmppRaAdminStatusTruthTest {

    @AfterEach
    void tearDown() {
        SmppAdminBindings.clear();
    }

    @Test
    void unboundStatusHasEmptyPeersAndFalseAnyPeerUp() {
        AdminDashboardRegistry reg = AdminDashboardRegistry.of(new SmppRaAdminContributor());
        Optional<RaAdminHttpResponse> hit = reg.dispatch(
                RaAdminHttpRequest.of("GET", "/api/ra/smpp-ra/status", null));
        assertTrue(hit.isPresent());
        String body = hit.get().bodyAsString();
        assertTrue(body.contains("\"anyPeerUp\":false"));
        assertTrue(body.contains("\"clients\":[]"));
        assertTrue(body.contains("\"boundSessionCount\":0"));
        assertFalse(body.contains("\"anyPeerUp\":true"));
    }

    @Test
    void statusHtmlFragment() {
        AdminDashboardRegistry reg = AdminDashboardRegistry.of(new SmppRaAdminContributor());
        Optional<RaAdminHttpResponse> hit = reg.dispatch(
                RaAdminHttpRequest.of("GET", "/api/ra/smpp-ra/status.html", null));
        assertTrue(hit.isPresent());
        assertEquals(200, hit.get().status());
        assertTrue(hit.get().bodyAsString().contains("link-status-panel"));
        assertFalse(hit.get().bodyAsString().contains("<script"));
    }

    @Test
    void synthesizeDetailListeningVsBound() {
        assertEquals("smpp=listening;peer=none",
                SmppAdminController.synthesizeSmppDetail(false, true, false));
        assertEquals("smpp=peer-bound;peer=bound",
                SmppAdminController.synthesizeSmppDetail(true, true, true));
        assertEquals("smpp=n/a",
                SmppAdminController.synthesizeSmppDetail(false, false, false));
    }

    @Test
    void statusMapSoftWhenUnbound() {
        Map<String, Object> m = SmppAdminController.statusMap();
        assertEquals(false, m.get("anyPeerUp"));
        assertEquals(0, m.get("boundSessionCount"));
        assertTrue(m.get("clients") instanceof java.util.List);
        assertTrue(m.get("server") instanceof Map);
    }

    @Test
    void manifestTabId() {
        assertEquals("smpp", new SmppRaAdminContributor().manifest().tabId());
        assertEquals("smpp-ra", new SmppRaAdminContributor().manifest().raName());
    }
}
