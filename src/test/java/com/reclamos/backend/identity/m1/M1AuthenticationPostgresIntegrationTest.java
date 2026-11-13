package com.reclamos.backend.identity.m1;

import com.reclamos.backend.entity.ModuleUser;
import com.reclamos.backend.identity.*;
import com.reclamos.backend.repository.ModuleUserRepository;
import com.reclamos.backend.security.M1SessionCookies;
import jakarta.servlet.http.Cookie;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.identity.provider=M1", "spring.jpa.show-sql=false",
        "ticket.information-request.expiration-scan-delay=3600000",
        "ticket.resolution.confirmation-scan-delay=3600000", "ticket.sla.milestone-scan-delay=3600000"})
@ActiveProfiles("dev") @AutoConfigureMockMvc @Transactional
class M1AuthenticationPostgresIntegrationTest {
    private static MockRestServiceServer server;
    @TestBean(name = "m1RestClient", methodName = "simulatedM1", enforceOverride = true)
    RestClient m1Client;
    @Autowired MockMvc mvc;
    @Autowired ModuleUserRepository users;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired EntityManager entityManager;

    static RestClient simulatedM1() {
        RestClient.Builder builder = RestClient.builder().baseUrl(M1TestTokens.BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        return builder.build();
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("app.identity.m1.base-url", () -> M1TestTokens.BASE);
        registry.add("app.identity.m1.jwt-secret-base64", M1TestTokens::encodedKey);
        registry.add("app.identity.m1.browser-origin", () -> M1TestTokens.ORIGIN);
    }

    @BeforeEach void reset() { server.reset(); }
    @AfterEach void verify() { server.verify(); }

    @Test
    void firstLoginCreatesRealCitizenWithLocalRoleAndHttpOnlyRefreshCookie() throws Exception {
        String token = citizenToken();
        expectLogin(token, M1IdentityProviderTest.citizenBody());
        login().andExpect(status().isOk()).andExpect(jsonPath("$.token").value(token))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.identity.actorId").value(M1TestTokens.CITIZEN.toString()))
                .andExpect(jsonPath("$.identity.role").value("CITIZEN"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Secure")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Strict")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("__Host-M2-Refresh=")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/;")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, not(containsString("Domain="))))
                .andExpect(content().string(not(containsString("synthetic-refresh-token"))))
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
        ModuleUser local = users.findByActorId(M1TestTokens.CITIZEN).orElseThrow();
        assertThat(local.getIdentitySource()).isEqualTo(IdentitySource.M1);
        assertThat(local.getCitizenId()).isEqualTo(M1TestTokens.CITIZEN);
        assertThat(local.getRole()).isEqualTo(ModuleRole.CITIZEN);
        assertThat(local.getEmail()).isEqualTo("verified@example.test");
        // No expectation for another HTTP request: me must use local JWT verification.
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("CITIZEN"));
    }

    @Test
    void subsequentLoginMergesPartialProfileWithoutReplacingLocalStaffAuthorization() throws Exception {
        ModuleUser existing = provision(M1TestTokens.CITIZEN, IdentityActorType.CIUDADANO, ModuleRole.AREA_RESPONSIBLE);
        existing.setAreaId("M6");
        users.saveAndFlush(existing);
        expectLogin(citizenToken(), """
                {"auth":true,"actorId":"%s","actorType":"CIUDADANO","citizenId":"%s",
                 "nombre":"Updated","role":"PERSONA"}
                """.formatted(M1TestTokens.CITIZEN, M1TestTokens.CITIZEN));
        login().andExpect(status().isOk()).andExpect(jsonPath("$.identity.role").value("AREA_RESPONSIBLE"))
                .andExpect(jsonPath("$.identity.areaId").value("M6"));
        ModuleUser updated = users.findByActorId(M1TestTokens.CITIZEN).orElseThrow();
        assertThat(updated.getId()).isEqualTo(existing.getId());
        assertThat(updated.getFirstName()).isEqualTo("Updated");
        assertThat(updated.getLastName()).isEqualTo("Local");
        assertThat(updated.getEmail()).isEqualTo("keep@example.test");
        assertThat(updated.getPreferredNotificationChannel()).isEqualTo("EMAIL");
    }

    @Test
    void employeeRequiresProvisioningAndHasNoCitizenOwnership() throws Exception {
        String token = M1TestTokens.token(M1TestTokens.EMPLOYEE, IdentityActorType.EMPLEADO);
        String body = """
                {"auth":true,"actorId":"%s","actorType":"EMPLEADO","nombre":"Employee","apellido":"Test","role":"ADMIN"}
                """.formatted(M1TestTokens.EMPLOYEE);
        expectLogin(token, body);
        login().andExpect(status().isForbidden());
        assertThat(users.findByActorId(M1TestTokens.EMPLOYEE)).isEmpty();
        provision(M1TestTokens.EMPLOYEE, IdentityActorType.EMPLEADO, ModuleRole.AGENT);
        server.reset();
        expectLogin(token, body);
        login().andExpect(status().isOk()).andExpect(jsonPath("$.identity.role").value("AGENT"));
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actorType").value("EMPLEADO"));
        mvc.perform(get("/api/me/tickets").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/tickets").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tickets").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestTypeId":1,"summary":"Synthetic","description":"Employee without citizen identity","formData":{}}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void twoCitizensWithTheSameContactRemainDistinctAndCannotManageEachOthersTickets() throws Exception {
        UUID secondId = UUID.fromString("d04cbd7f-1952-47ae-81b9-977654f70103");
        String firstToken = citizenToken();
        String secondToken = M1TestTokens.token(secondId, IdentityActorType.CIUDADANO);
        expectLogin(firstToken, M1IdentityProviderTest.citizenBody());
        expectLogin(firstToken, M1IdentityProviderTest.citizenBody().replace("Citizen", "Updated"));
        expectLogin(secondToken, M1IdentityProviderTest.citizenBody()
                .replace(M1TestTokens.CITIZEN.toString(), secondId.toString()));
        login().andExpect(status().isOk());
        ModuleUser first = users.findByActorId(M1TestTokens.CITIZEN).orElseThrow();
        first.setPreferredNotificationChannel("PHONE");
        users.saveAndFlush(first);
        login().andExpect(status().isOk());
        login().andExpect(status().isOk());
        ModuleUser updated = users.findByActorId(M1TestTokens.CITIZEN).orElseThrow();
        ModuleUser second = users.findByActorId(secondId).orElseThrow();
        assertThat(updated.getId()).isEqualTo(first.getId());
        assertThat(updated.getFirstName()).isEqualTo("Updated");
        assertThat(updated.getPreferredNotificationChannel()).isEqualTo("PHONE");
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getEmail()).isEqualTo(updated.getEmail());
        assertThat(users.findAll().stream().filter(u -> u.getActorId().equals(M1TestTokens.CITIZEN))).hasSize(1);

        String marker = "M1-OWN-" + UUID.randomUUID();
        Long type = db.queryForObject("INSERT INTO request_types(code,subcategory_id,name,description,ticket_type,responsible_area_id,minimum_priority,base_risk,affected_population_factor,allows_anonymous,requires_location,active) "
                + "SELECT ?,id,?,?,'COMPLAINT','M2','LOW','LOW',0,true,false,true FROM subcategories ORDER BY id LIMIT 1 RETURNING id", Long.class, marker, marker, marker);
        String response = mvc.perform(post("/api/tickets").header(HttpHeaders.AUTHORIZATION, "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestTypeId":%d,"summary":"Synthetic ownership","description":"Disposable local case","formData":{}}
                                """.formatted(type)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID ticket = UUID.fromString(json.readTree(response).get("ticketId").asText());
        entityManager.flush();
        mvc.perform(get("/api/tickets/" + ticket).header(HttpHeaders.AUTHORIZATION, "Bearer " + firstToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tickets/" + ticket).header(HttpHeaders.AUTHORIZATION, "Bearer " + secondToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets/" + ticket + "/messages").header(HttpHeaders.AUTHORIZATION, "Bearer " + secondToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"PUBLIC\",\"text\":\"Not the owner\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets/" + ticket + "/review").header(HttpHeaders.AUTHORIZATION, "Bearer " + secondToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void refresh204IsNormalizedAndPreservesProfileRoleAndContact() throws Exception {
        provision(M1TestTokens.CITIZEN, IdentityActorType.CIUDADANO, ModuleRole.ADMIN);
        String token = citizenToken();
        server.expect(requestTo(M1TestTokens.BASE + "/auth/refresh"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                        .json("{\"refreshToken\":\"old-refresh\"}"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT).headers(M1IdentityProviderTest.headers(token)));
        mvc.perform(post("/api/auth/refresh").header("Origin", M1TestTokens.ORIGIN)
                        .header(M1SessionCookies.CSRF_HEADER, "1").cookie(refreshCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").value(token))
                .andExpect(jsonPath("$.identity.role").value("ADMIN"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=604800")))
                .andExpect(content().string(not(containsString("synthetic-refresh-token"))));
        assertThat(users.findByActorId(M1TestTokens.CITIZEN).orElseThrow().getEmail()).isEqualTo("keep@example.test");
    }

    @Test
    void invalidOrMissingRefreshClearsCookieAndAvailabilityFailureRetainsIt() throws Exception {
        server.expect(requestTo(M1TestTokens.BASE + "/auth/refresh")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(M1TestTokens.BASE + "/auth/refresh")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        mvc.perform(post("/api/auth/refresh").header("Origin", M1TestTokens.ORIGIN)
                        .header(M1SessionCookies.CSRF_HEADER, "1"))
                .andExpect(status().isUnauthorized()).andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
        mvc.perform(post("/api/auth/refresh").header("Origin", M1TestTokens.ORIGIN)
                        .header(M1SessionCookies.CSRF_HEADER, "1").cookie(refreshCookie()))
                .andExpect(status().isUnauthorized()).andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
        mvc.perform(post("/api/auth/refresh").header("Origin", M1TestTokens.ORIGIN)
                        .header(M1SessionCookies.CSRF_HEADER, "1").cookie(refreshCookie()))
                .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void csrfChecksRejectMissingCrossOriginAndMissingCustomHeaderWithoutChangingCookies() throws Exception {
        for (String origin : new String[]{"null", "https://evil.example.test", "https://sub.m2.example.test"}) {
            mvc.perform(post("/api/auth/refresh").header("Origin", origin)
                            .header(M1SessionCookies.CSRF_HEADER, "1").cookie(refreshCookie()))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"))
                    .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        }
        mvc.perform(post("/api/auth/refresh").header("Origin", M1TestTokens.ORIGIN).cookie(refreshCookie()))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        mvc.perform(post("/api/auth/logout").header(M1SessionCookies.CSRF_HEADER, "1").cookie(refreshCookie()))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials()))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void logoutOnlyClearsCookieAndDoesNotPromiseJwtRevocation() throws Exception {
        provision(M1TestTokens.CITIZEN, IdentityActorType.CIUDADANO, ModuleRole.CITIZEN);
        String token = citizenToken();
        mvc.perform(post("/api/auth/logout").header("Origin", M1TestTokens.ORIGIN)
                        .header(M1SessionCookies.CSRF_HEADER, "1").cookie(refreshCookie()))
                .andExpect(status().isNoContent()).andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void restrictedLocalAccountsAreForbiddenForLoginAndBearer() throws Exception {
        ModuleUser local = provision(M1TestTokens.CITIZEN, IdentityActorType.CIUDADANO, ModuleRole.ADMIN);
        local.setActive(false);
        users.saveAndFlush(local);
        String token = citizenToken();
        expectLogin(token, M1IdentityProviderTest.citizenBody());
        login().andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void distinguishesCredentialPermissionAvailabilityAndProtocolErrorsWithoutRemoteDetails() throws Exception {
        for (HttpStatus status : new HttpStatus[]{HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.SERVICE_UNAVAILABLE}) {
            server.expect(requestTo(M1TestTokens.BASE + "/auth/usuario/login")).andRespond(withStatus(status).body("private-upstream-error"));
        }
        login().andExpect(status().isUnauthorized());
        login().andExpect(status().isForbidden());
        login().andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("IDENTITY_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("private-upstream-error"))))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void rejectsMockTakeoverAndInvalidJwtWithoutCreatingOrRelinkingUsers() throws Exception {
        ModuleUser mock = provision(M1TestTokens.CITIZEN, IdentityActorType.CIUDADANO, ModuleRole.ADMIN, IdentitySource.MOCK);
        expectLogin(citizenToken(), M1IdentityProviderTest.citizenBody());
        expectLogin("invalid-access", M1IdentityProviderTest.citizenBody());
        login().andExpect(status().isForbidden());
        login().andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("IDENTITY_RESPONSE_INVALID"));
        ModuleUser unchanged = users.findByActorId(M1TestTokens.CITIZEN).orElseThrow();
        assertThat(unchanged.getId()).isEqualTo(mock.getId());
        assertThat(unchanged.getIdentitySource()).isEqualTo(IdentitySource.MOCK);
        assertThat(unchanged.getFirstName()).isEqualTo("Existing");
    }

    @Test
    void jwtOwnershipUsesCitizenUuidAndStaffCannotTriageTheirOwnTicket() throws Exception {
        provision(M1TestTokens.CITIZEN, IdentityActorType.CIUDADANO, ModuleRole.ADMIN);
        String marker = "M1-AUTH-" + UUID.randomUUID();
        Long type = db.queryForObject("INSERT INTO request_types(code,subcategory_id,name,description,ticket_type,responsible_area_id,minimum_priority,base_risk,affected_population_factor,allows_anonymous,requires_location,active) "
                + "SELECT ?,id,?,?,'COMPLAINT','M2','LOW','LOW',0,true,false,true FROM subcategories ORDER BY id LIMIT 1 RETURNING id", Long.class, marker, marker, marker);
        String token = citizenToken();
        String response = mvc.perform(post("/api/tickets").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestTypeId":%d,"summary":"Synthetic","description":"Synthetic local case","formData":{}}
                                """.formatted(type)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID ticket = UUID.fromString(json.readTree(response).get("ticketId").asText());
        entityManager.flush();
        assertThat(db.queryForObject("SELECT citizen_id FROM tickets WHERE id=?", UUID.class, ticket)).isEqualTo(M1TestTokens.CITIZEN);
        mvc.perform(post("/api/tickets/" + ticket + "/review").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    private ModuleUser provision(UUID id, IdentityActorType type, ModuleRole role) {
        return provision(id, type, role, IdentitySource.M1);
    }

    private ModuleUser provision(UUID id, IdentityActorType type, ModuleRole role, IdentitySource source) {
        ModuleUser user = new ModuleUser();
        user.setActorId(id); user.setActorType(type); user.setIdentitySource(source);
        user.setCitizenId(type == IdentityActorType.CIUDADANO ? id : null);
        user.setFirstName("Existing"); user.setLastName("Local"); user.setEmail("keep@example.test");
        user.setRole(role); user.setPreferredNotificationChannel("EMAIL"); user.setLastSyncedAt(Instant.now());
        if (role == ModuleRole.AREA_RESPONSIBLE) user.setAreaId("M6");
        return users.saveAndFlush(user);
    }

    private void expectLogin(String token, String body) {
        server.expect(requestTo(M1TestTokens.BASE + "/auth/usuario/login"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON).headers(M1IdentityProviderTest.headers(token)));
    }

    private ResultActions login() throws Exception {
        return mvc.perform(post("/api/auth/login").header("Origin", M1TestTokens.ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).content(credentials()));
    }

    private String credentials() { return "{\"username\":\"citizen@example.test\",\"password\":\"synthetic-password\"}"; }
    private String citizenToken() { return M1TestTokens.token(M1TestTokens.CITIZEN, IdentityActorType.CIUDADANO); }
    private Cookie refreshCookie() { return new Cookie(M1SessionCookies.NAME, "old-refresh"); }
}
