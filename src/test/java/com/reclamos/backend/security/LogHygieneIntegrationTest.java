package com.reclamos.backend.security;

import com.reclamos.backend.service.CatalogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * DDA2-263 (SEG-10): nada interno hacia el cliente y nada sensible en los logs.
 *
 * <p>Levanta la aplicación completa con servidor real (el {@code MockMvc} no hace el despacho a
 * {@code /error}, así que no sirve para ver cómo responde el contenedor ante una excepción no controlada).
 * El {@code @MockitoBean} le da a este test un contexto propio: el arranque ocurre dentro de esta clase y su
 * salida queda capturada, de modo que el chequeo de "Using generated security password" no es vacuo.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.identity.mock.citizen-password=citizen-test-password",
        "app.identity.mock.agent-password=agent-test-password",
        "app.identity.mock.area-responsible-password=area-test-password",
        "app.identity.mock.admin-password=admin-test-password"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class LogHygieneIntegrationTest {
    private static final String INTERNAL_DETAIL = "jdbc:postgresql://db.internal:5432/reclamos user=svc_internal";

    @Autowired private ApplicationContext context;
    @Autowired private Environment environment;
    @Autowired private MockMvc mockMvc;
    @Value("${local.server.port}") private int port;

    @MockitoBean private CatalogService catalogService;

    @Test
    void noGeneratedCredentialIsCreatedOrPrinted(CapturedOutput output) {
        assertThat(output.getAll()).doesNotContain("Using generated security password");
        assertThat(context.getBeansOfType(InMemoryUserDetailsManager.class)).isEmpty();
        assertThat(context.getBeansOfType(UserDetailsService.class)).hasSize(1);
        UserDetailsService noLocalUsers = context.getBean(UserDetailsService.class);
        assertThatThrownBy(() -> noLocalUsers.loadUserByUsername("user"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void errorResponsesAreConfiguredToExposeNothingInternal() {
        assertThat(environment.getProperty("server.error.include-stacktrace")).isEqualTo("never");
        assertThat(environment.getProperty("server.error.include-message")).isEqualTo("never");
        assertThat(environment.getProperty("server.error.include-binding-errors")).isEqualTo("never");
        assertThat(environment.getProperty("server.error.include-exception")).isEqualTo("false");
    }

    @Test
    void anUnhandledExceptionReachesTheClientAsABareInternalError() throws Exception {
        when(catalogService.getCategories()).thenThrow(new IllegalStateException(INTERNAL_DETAIL));

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/catalog/categories"))
                        .header("Accept", MediaType.APPLICATION_JSON_VALUE).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body())
                .doesNotContain(INTERNAL_DETAIL)
                .doesNotContain("svc_internal")
                .doesNotContain("IllegalStateException")
                .doesNotContain("java.lang")
                .doesNotContain("com.reclamos")
                .doesNotContainIgnoringCase("stacktrace")
                .doesNotContainIgnoringCase("\"trace\"")
                .doesNotContain(".java:");
    }

    @Test
    void credentialsAndTokensNeverReachTheLogs(CapturedOutput output) throws Exception {
        String password = "log-hygiene-password-7f3a9c";
        String bearer = "log-hygiene-bearer-5d21be.payload.signature";
        String trackingCode = "TRK-LOGHYGIENE-8841";
        String accessPassword = "log-hygiene-access-password-2c70";

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"nobody@example.test\",\"password\":\"" + password + "\"}"));
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + bearer));
        mockMvc.perform(post("/api/tracking/access").contentType(MediaType.APPLICATION_JSON)
                .content("{\"trackingCode\":\"" + trackingCode + "\",\"anonymousAccessPassword\":\""
                        + accessPassword + "\"}"));

        assertThat(output.getAll())
                .doesNotContain(password)
                .doesNotContain(bearer)
                .doesNotContain(trackingCode)
                .doesNotContain(accessPassword);
    }
}
