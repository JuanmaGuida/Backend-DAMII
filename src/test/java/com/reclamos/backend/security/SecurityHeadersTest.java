package com.reclamos.backend.security;

import com.reclamos.backend.config.SecurityConfiguration;
import com.reclamos.backend.controller.AuthController;
import com.reclamos.backend.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * DDA2-262 (SEG-09): cabeceras de seguridad y CORS. Es el equivalente automático del {@code curl -I}
 * del criterio de aceptación: se verifica sobre respuestas reales de la cadena de filtros, incluidas
 * las de error (401), que son las que más fácil se quedan sin cabeceras.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfiguration.class, BearerTokenAuthenticationFilter.class})
class SecurityHeadersTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private LoginAttemptLimiter loginAttemptLimiter;

    @Test
    void anAuthenticatedEndpointRejectedWith401StillCarriesTheSecurityHeaders() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/me")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertSecurityHeaders(result);
    }

    @Test
    void aPublicEndpointCarriesTheSecurityHeaders() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertSecurityHeaders(result);
    }

    @Test
    void anUnknownRouteDoesNotLoseTheHeadersEither() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/no-such-resource")).andReturn();

        assertSecurityHeaders(result);
    }

    @Test
    void hstsIsEmittedEvenThoughTlsTerminatesAtTheProxy() throws Exception {
        // MockMvc habla "HTTP" plano: sin requestMatcher propio Spring no emitiría HSTS.
        MvcResult result = mockMvc.perform(get("/api/auth/me")).andReturn();

        assertThat(result.getRequest().isSecure()).isFalse();
        assertThat(result.getResponse().getHeader("Strict-Transport-Security"))
                .contains("max-age=31536000").contains("includeSubDomains");
    }

    @Test
    void corsIsNeverGrantedToAForeignOrigin() throws Exception {
        for (MvcResult result : new MvcResult[]{
                mockMvc.perform(get("/api/auth/me").header("Origin", "https://evil.example.test")).andReturn(),
                mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "https://evil.example.test")
                        .header("Access-Control-Request-Method", "POST")).andReturn()}) {
            assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
            assertThat(result.getResponse().getHeader("Access-Control-Allow-Credentials")).isNull();
        }
    }

    @Test
    void cspPolicyIsTightByDefaultAndRelaxedOnlyForDocsAndAttachments() {
        assertThat(ApiContentSecurityPolicyHeaderWriter.policyFor("/api/tickets"))
                .isEqualTo(ApiContentSecurityPolicyHeaderWriter.API_POLICY)
                .contains("default-src 'none'")
                .contains("frame-ancestors 'none'");
        assertThat(ApiContentSecurityPolicyHeaderWriter.policyFor("/api/auth/login"))
                .isEqualTo(ApiContentSecurityPolicyHeaderWriter.API_POLICY);

        assertThat(ApiContentSecurityPolicyHeaderWriter.policyFor("/swagger-ui.html"))
                .isEqualTo(ApiContentSecurityPolicyHeaderWriter.DOCS_POLICY);
        assertThat(ApiContentSecurityPolicyHeaderWriter.policyFor("/swagger-ui/index.html"))
                .isEqualTo(ApiContentSecurityPolicyHeaderWriter.DOCS_POLICY)
                .contains("script-src 'self';")
                .contains("style-src 'self' 'unsafe-inline';");

        assertThat(ApiContentSecurityPolicyHeaderWriter.policyFor("/api/attachments/abc/content"))
                .isEqualTo(ApiContentSecurityPolicyHeaderWriter.ATTACHMENT_POLICY);
        assertThat(ApiContentSecurityPolicyHeaderWriter.policyFor("/api/tracking/actions/attachments/abc/content"))
                .isEqualTo(ApiContentSecurityPolicyHeaderWriter.ATTACHMENT_POLICY);

        // Un path que sólo se le parece no hereda la política relajada.
        assertThat(ApiContentSecurityPolicyHeaderWriter.policyFor("/api/tickets/abc/content"))
                .isEqualTo(ApiContentSecurityPolicyHeaderWriter.API_POLICY);
        assertThat(ApiContentSecurityPolicyHeaderWriter.policyFor("/swagger-ui-evil"))
                .isEqualTo(ApiContentSecurityPolicyHeaderWriter.API_POLICY);
    }

    @Test
    void theApiPolicyAllowsNoScriptsAtAll() {
        assertThat(ApiContentSecurityPolicyHeaderWriter.API_POLICY)
                .doesNotContain("unsafe-inline")
                .doesNotContain("unsafe-eval")
                .doesNotContain("*");
    }

    private static void assertSecurityHeaders(MvcResult result) {
        var response = result.getResponse();
        assertThat(response.getHeader("Strict-Transport-Security")).contains("max-age=31536000")
                .contains("includeSubDomains");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Content-Security-Policy")).isNotBlank().contains("frame-ancestors 'none'");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }
}
