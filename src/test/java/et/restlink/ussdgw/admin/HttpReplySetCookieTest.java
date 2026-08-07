package et.restlink.ussdgw.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpReplySetCookieTest {

    @Test
    void addSetCookieJoinsWithoutClobber() {
        AdminHttpHandler.HttpReply r = AdminHttpHandler.HttpReply.redirect("/admin")
                .addSetCookie("ussd_admin_session=aaa; Path=/; HttpOnly")
                .addSetCookie("ussd_admin_csrf=bbb; Path=/");
        String setCookie = r.headers().get("Set-Cookie");
        assertThat(setCookie).contains("ussd_admin_session=aaa");
        assertThat(setCookie).contains("ussd_admin_csrf=bbb");
        assertThat(setCookie).contains(AdminHttpHandler.HttpReply.SET_COOKIE_SEP);
        assertThat(setCookie.split("\n", -1)).hasSize(2);
    }

    @Test
    void csrfTokenRoundTrip() {
        String secret = "unit-test-hmac";
        String session = SignedSessionCookie.issue(secret, "admin", "ADMIN", null,
                java.time.Instant.now().plusSeconds(3600));
        String csrf = SignedSessionCookie.csrfToken(secret, session);
        assertThat(csrf).isNotBlank();
        assertThat(SignedSessionCookie.csrfMatches(secret, session, csrf)).isTrue();
        assertThat(SignedSessionCookie.csrfMatches(secret, session, "nope")).isFalse();
    }
}
