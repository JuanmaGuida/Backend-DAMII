package com.reclamos.backend.identity.m1;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.reclamos.backend.controller.AuthController;
import com.reclamos.backend.controller.M1IdentityErrorHandler;
import com.reclamos.backend.controller.M1SessionController;
import com.reclamos.backend.exception.GlobalExceptionHandler;
import com.reclamos.backend.exception.IdentityProviderException;
import com.reclamos.backend.security.M1SessionCookies;
import com.reclamos.backend.service.AuthService;
import com.reclamos.backend.service.ModuleUserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.reclamos.backend.openapi.ProblemContractAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real sockets, production RestClient/provider/service/controllers and both exception advices. */
class M1TimeoutWireMockIntegrationTest {
    private WireMockServer m1;
    private MockMvc mvc;
    private ModuleUserService users;

    @BeforeEach
    void start() throws Exception {
        m1 = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        m1.start();
        // Read production defaults so a regression back to 30s fails these tests.
        var sources = new MutablePropertySources();
        sources.addLast(new ResourcePropertySource("classpath:application.properties"));
        var properties = new PropertySourcesPropertyResolver(sources);
        var conversion = org.springframework.boot.convert.ApplicationConversionService.getSharedInstance();
        M1Settings settings = spy(new M1Settings(M1TestTokens.BASE, M1TestTokens.encodedKey(), M1TestTokens.ORIGIN,
                conversion.convert(properties.getProperty("app.identity.m1.connect-timeout"), Duration.class),
                conversion.convert(properties.getProperty("app.identity.m1.read-timeout"), Duration.class)));
        // Loopback HTTP only in this test; production settings still require HTTPS.
        doReturn(URI.create(m1.baseUrl())).when(settings).baseUrl();
        var client = new M1HttpConfiguration().m1RestClient(settings);
        var provider = new M1IdentityProvider(new M1AccessTokenVerifier(settings, Clock.systemUTC()), client);
        users = mock(ModuleUserService.class);
        var auth = new AuthService(provider, users);
        var cookies = new M1SessionCookies(settings);
        var beans = new StaticListableBeanFactory();
        beans.addBean("cookies", cookies);
        var limiter = new com.reclamos.backend.security.LoginAttemptLimiter(
                new com.reclamos.backend.config.LoginRateLimitProperties(),
                new com.reclamos.backend.security.ClientAddressResolver(List.of()), Clock.systemUTC());
        mvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(auth, beans.getBeanProvider(M1SessionCookies.class), limiter),
                        new M1SessionController(auth, cookies, limiter))
                .setControllerAdvice(new GlobalExceptionHandler(), new M1IdentityErrorHandler(cookies)).build();
        // Warm MVC/Jackson and the connection before measuring the HTTP wait.
        m1.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/auth/usuario/login"))
                .willReturn(aResponse().withStatus(401)));
        assertThat(request("login").getResponse().getStatus()).isEqualTo(401);
        m1.verify(1, postRequestedFor(urlEqualTo("/auth/usuario/login")));
        m1.resetAll();
    }

    @AfterEach
    void stop() { if (m1 != null) m1.stop(); }

    @ParameterizedTest
    @ValueSource(strings = {"login", "refresh"})
    void sixSecondM1ResponseIsInterruptedAtFiveSecondsWithSanitized503(String operation) throws Exception {
        String upstream = operation.equals("login") ? "/auth/usuario/login" : "/auth/refresh";
        m1.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(upstream))
                .willReturn(aResponse().withFixedDelay(6000).withStatus(500)
                        .withHeader("Authorization", "Bearer private-upstream-token")
                        .withBody("private-upstream-body java.lang.IllegalStateException at m1.internal")));
        assertTimeoutResponse(operation);
        m1.verify(1, postRequestedFor(urlEqualTo(upstream)));
    }

    @Test
    void slowBodySharesTheSameDeadlineAndReturnsUnavailable() throws Exception {
        m1.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/auth/usuario/login"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"auth\":true,\"private\":\"private-upstream-body\"}")
                        .withChunkedDribbleDelay(6, 6000)));
        assertTimeoutResponse("login");
        m1.verify(1, postRequestedFor(urlEqualTo("/auth/usuario/login")));
    }

    @Test
    void fastUpstreamErrorIsSanitizedWithoutWaitingForTimeout() throws Exception {
        m1.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/auth/usuario/login"))
                .willReturn(aResponse().withStatus(500).withBody("private-upstream-body")));
        assertSafeError(request("login"));
        m1.verify(1, postRequestedFor(urlEqualTo("/auth/usuario/login")));
    }

    private void assertTimeoutResponse(String operation) throws Exception {
        long start = System.nanoTime();
        MvcResult result = request(operation);
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        System.out.printf("INT-03 %s: M1 delay=6000ms, M2 elapsed=%dms, HTTP=%d%n",
                operation, elapsed, result.getResponse().getStatus());
        // Allow scheduling/HTTP overhead, but fail if M2 waits for M1's 6s response.
        m1.verify(1, postRequestedFor(urlMatching("/auth/.*")));
        assertThat(elapsed).isBetween(4700L, 5700L);
        assertSafeError(result);
    }

    private MvcResult request(String operation) throws Exception {
        var request = post("/api/auth/" + operation).secure(true)
                .header("Origin", M1TestTokens.ORIGIN).header(M1SessionCookies.CSRF_HEADER, "1");
        if (operation.equals("login")) request.contentType("application/json")
                .content("{\"username\":\"private-user@example.test\",\"password\":\"private-password\"}");
        else request.cookie(new Cookie(M1SessionCookies.NAME, "private-refresh-token"));
        return mvc.perform(request).andReturn();
    }

    private void assertSafeError(MvcResult result) throws Exception {
        var response = result.getResponse();
        assertProblem("POST", result.getRequest().getRequestURI(), 503,
                response.getStatus(), response.getContentType(), response.getContentAsString());
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        var problem = new JsonMapper().readTree(result.getResponse().getContentAsByteArray());
        assertThat(problem.path("type").asText()).isEqualTo("about:blank");
        assertThat(problem.path("status").asInt()).isEqualTo(503);
        assertThat(problem.path("instance").asText()).isEqualTo(result.getRequest().getRequestURI());
        assertThat(problem.path("code").asText()).isEqualTo("IDENTITY_UNAVAILABLE");
        assertThat(problem.path("detail").asText())
                .isEqualTo("El servicio de identidad no está disponible. Intente nuevamente.");
        assertThat(problem.path("message")).isEqualTo(problem.path("detail"));
        assertThat(result.getResponse().getHeaderNames()).doesNotContain("Authorization", "X-Refresh-Token", "Set-Cookie");
        assertThat(result.getResolvedException()).isInstanceOf(IdentityProviderException.class).hasNoCause();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("trace", "stack", "Exception",
                "private-", m1.baseUrl(), "java.", "org.springframework");
        verifyNoInteractions(users);
    }
}
