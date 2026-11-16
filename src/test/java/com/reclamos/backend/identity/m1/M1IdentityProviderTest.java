package com.reclamos.backend.identity.m1;

import com.reclamos.backend.exception.IdentityAccessDeniedException;
import com.reclamos.backend.exception.IdentityProviderException;
import com.reclamos.backend.identity.IdentityActorType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Clock;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class M1IdentityProviderTest {
    private final RestClient.Builder builder = RestClient.builder().baseUrl(M1TestTokens.BASE);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final M1IdentityProvider provider = new M1IdentityProvider(
            new M1AccessTokenVerifier(M1TestTokens.settings(), Clock.systemUTC()), builder.build());

    @AfterEach void verify() { server.verify(); }

    @Test
    void translatesLoginReadsHeadersChecksIdsAndProjectsVerifiedContacts() {
        String token = M1TestTokens.token(M1TestTokens.CITIZEN, IdentityActorType.CIUDADANO);
        server.expect(requestTo(M1TestTokens.BASE + "/auth/usuario/login"))
                .andExpect(method(HttpMethod.POST)).andExpect(content().json("""
                        {"gmail":"citizen@example.test","password":"synthetic-password"}
                        """))
                .andRespond(withSuccess(citizenBody(), MediaType.APPLICATION_JSON).headers(headers(token)));

        var session = provider.authenticate("citizen@example.test", "synthetic-password").orElseThrow();

        assertThat(session.token()).isEqualTo(token);
        assertThat(session.refreshToken()).isEqualTo("synthetic-refresh-token");
        assertThat(session.profile().firstName()).isEqualTo("Citizen");
        assertThat(session.profile().lastName()).isEqualTo("Test");
        assertThat(session.profile().email()).isEqualTo("verified@example.test");
        assertThat(session.profile().phone()).isEqualTo("+541155555555");
        assertThat(session.toString()).doesNotContain(token, session.refreshToken());
    }

    @Test
    void employeeMayOmitCitizenAndPartialContactDoesNotInventFields() {
        String token = M1TestTokens.token(M1TestTokens.EMPLOYEE, IdentityActorType.EMPLEADO);
        server.expect(requestTo(M1TestTokens.BASE + "/auth/usuario/login"))
                .andRespond(withSuccess("""
                        {"auth":true,"actorId":"%s","actorType":"EMPLEADO","user_id":14,
                         "nombre":"Employee","apellido":"Test","role":"ADMINISTRATIVO","contactos":[]}
                        """.formatted(M1TestTokens.EMPLOYEE), MediaType.APPLICATION_JSON).headers(headers(token)));
        var profile = provider.authenticate("employee@example.test", "synthetic").orElseThrow().profile();
        assertThat(profile.citizenId()).isNull();
        assertThat(profile.actorId()).isEqualTo(M1TestTokens.EMPLOYEE);
        assertThat(profile.email()).isNull();
    }

    @Test
    void bearerResolutionValidatesLocallyWithoutAnyHttpRequest() {
        String token = M1TestTokens.token(M1TestTokens.CITIZEN, IdentityActorType.CIUDADANO);
        assertThat(provider.resolve(token)).isPresent();
        assertThat(provider.resolve("invalid")).isEmpty();
    }

    @Test
    void refreshReadsNoContentResponseHeadersAndRetainsPartialIdentity() {
        String token = M1TestTokens.token(M1TestTokens.CITIZEN, IdentityActorType.CIUDADANO);
        server.expect(requestTo(M1TestTokens.BASE + "/auth/refresh"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"refreshToken\":\"old-refresh\"}"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT).headers(headers(token)));
        var session = provider.refresh("old-refresh").orElseThrow();
        assertThat(session.token()).isEqualTo(token);
        assertThat(session.profile().firstName()).isNull();
        assertThat(session.profile().citizenId()).isEqualTo(M1TestTokens.CITIZEN);
    }

    @Test
    void rejectsJwtAndBodyIdentityMismatch() {
        String token = M1TestTokens.token(M1TestTokens.CITIZEN, IdentityActorType.CIUDADANO);
        server.expect(requestTo(M1TestTokens.BASE + "/auth/usuario/login"))
                .andRespond(withSuccess(citizenBody().replace(M1TestTokens.CITIZEN.toString(), M1TestTokens.EMPLOYEE.toString()),
                        MediaType.APPLICATION_JSON).headers(headers(token)));
        assertThatThrownBy(() -> provider.authenticate("citizen@example.test", "synthetic"))
                .isInstanceOf(IdentityProviderException.class);
    }

    @Test
    void missingTokensAndInvalidJsonAreProtocolErrorsWithoutLeakingRemoteData() {
        server.expect(requestTo(M1TestTokens.BASE + "/auth/usuario/login"))
                .andRespond(withSuccess("private-invalid-body", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.authenticate("citizen@example.test", "synthetic"))
                .isInstanceOf(IdentityProviderException.class).hasNoCause().hasMessageNotContaining("private-invalid-body");
    }

    @Test
    void invalidAccessReturnedByRefreshCannotCreateASession() {
        server.expect(requestTo(M1TestTokens.BASE + "/auth/refresh"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT).headers(headers("invalid-access")));
        assertThatThrownBy(() -> provider.refresh("old-refresh")).isInstanceOf(IdentityProviderException.class);
    }

    @Test
    void refreshRequires204AndMandatoryTokenHeaders() {
        server.expect(requestTo(M1TestTokens.BASE + "/auth/refresh"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(M1TestTokens.BASE + "/auth/refresh"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        assertThatThrownBy(() -> provider.refresh("old-refresh")).isInstanceOf(IdentityProviderException.class);
        assertThatThrownBy(() -> provider.refresh("old-refresh")).isInstanceOf(IdentityProviderException.class);
    }

    @Test
    void distinguishesInvalidCredentialsBlockedAccountAndUpstreamAvailability() {
        for (HttpStatus status : new HttpStatus[]{HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN,
                HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.TOO_MANY_REQUESTS, HttpStatus.BAD_REQUEST}) {
            server.expect(requestTo(M1TestTokens.BASE + "/auth/usuario/login"))
                    .andRespond(withStatus(status).body("private-remote-error"));
        }
        assertThat(provider.authenticate("citizen@example.test", "synthetic")).isEmpty();
        assertThatThrownBy(() -> provider.authenticate("citizen@example.test", "synthetic"))
                .isInstanceOf(IdentityAccessDeniedException.class);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> provider.authenticate("citizen@example.test", "synthetic"))
                    .isInstanceOfSatisfying(IdentityProviderException.class, e -> assertThat(e.isUnavailable()).isTrue())
                    .hasNoCause().hasMessageNotContaining("private-remote-error");
        }
        assertThatThrownBy(() -> provider.authenticate("citizen@example.test", "synthetic"))
                .isInstanceOfSatisfying(IdentityProviderException.class, e -> assertThat(e.isUnavailable()).isFalse());
    }

    @Test
    void ioFailureIsSanitizedAndNeverFallsBackToMock() {
        server.expect(requestTo(M1TestTokens.BASE + "/auth/refresh"))
                .andRespond(withException(new IOException("private-token-or-password")));
        assertThatThrownBy(() -> provider.refresh("old-refresh"))
                .isInstanceOfSatisfying(IdentityProviderException.class, e -> assertThat(e.isUnavailable()).isTrue())
                .hasNoCause().hasMessageNotContaining("private-token-or-password");
    }

    @Test
    void missingCredentialsOrMalformedCookieNeverContactM1() {
        assertThat(provider.authenticate(null, "synthetic")).isEmpty();
        assertThat(provider.authenticate("", "synthetic")).isEmpty();
        assertThat(provider.authenticate("citizen@example.test", null)).isEmpty();
        assertThat(provider.refresh(null)).isEmpty();
        assertThat(provider.refresh("bad token")).isEmpty();
        assertThat(provider.refresh("x".repeat(3801))).isEmpty();
    }

    public static HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Refresh-Token", "synthetic-refresh-token");
        headers.set("X-Token-Expires-In", "900");
        return headers;
    }

    public static String citizenBody() {
        return """
                {"auth":true,"actorId":"%s","citizenId":"%s","uuid":"%s","actorType":"CIUDADANO",
                 "user_id":14,"nombre":"Citizen","apellido":"Test","role":"ADMIN",
                 "contactos":[{"tipo":"EMAIL","valor":"ignore@example.test","verificado":false},
                              {"tipo":"EMAIL","valor":"verified@example.test","verificado":true},
                              {"tipo":"TELEFONO","valor":"+541155555555","verificado":true}]}
                """.formatted(M1TestTokens.CITIZEN, M1TestTokens.CITIZEN, M1TestTokens.CITIZEN);
    }
}
