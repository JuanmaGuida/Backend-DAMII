package com.reclamos.backend.security;

import com.reclamos.backend.identity.m1.M1TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DDA2-262 (SEG-09): la cookie de refresh sale Secure, HttpOnly, SameSite=Strict y con prefijo __Host-. */
class M1SessionCookiesTest {
    private final M1SessionCookies cookies = new M1SessionCookies(M1TestTokens.settings());

    @Test
    void theRefreshCookieIsSecureHttpOnlyStrictAndHostPrefixed() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookies.write(response, "valid.refresh-token_1~2");

        String cookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(cookie).startsWith("__Host-M2-Refresh=valid.refresh-token_1~2;")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .contains("Path=/")
                .contains("Max-Age=604800")
                .doesNotContain("Domain=");
    }

    @Test
    void clearingKeepsTheSameProtectionsAndExpiresImmediately() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookies.clear(response);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains("Secure").contains("HttpOnly").contains("SameSite=Strict").contains("Max-Age=0");
    }

    @Test
    void aMalformedTokenIsNeverWrittenIntoAHeader() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> cookies.write(response, "bad value; Domain=evil.test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }
}
