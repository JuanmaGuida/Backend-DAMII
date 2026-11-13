package com.reclamos.backend.identity.m1;

import com.reclamos.backend.entity.ModuleUser;
import com.reclamos.backend.identity.*;
import com.reclamos.backend.repository.ModuleUserRepository;
import com.reclamos.backend.security.M1SessionCookies;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Opt-in network test. Never part of ordinary CI; never prints request/response bodies. */
@EnabledIfEnvironmentVariable(named = "M1_REAL_TESTS", matches = "YES")
@SpringBootTest(properties = {"app.identity.provider=M1", "spring.jpa.show-sql=false",
        "logging.level.root=OFF", "spring.main.banner-mode=off"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ContextConfiguration(initializers = M1RealAuthenticationTest.IsolationGuard.class)
class M1RealAuthenticationTest {
    @Autowired MockMvc mvc;
    @Autowired M1IdentityProvider provider;
    @Autowired ModuleUserRepository users;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;

    /** Runs before datasource construction, including Flyway. */
    public static class IsolationGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            var env = context.getEnvironment();
            check("YES".equals(System.getenv("M1_REAL_DISPOSABLE")), "Disposable acknowledgement required");
            check("M1".equals(System.getenv("IDENTITY_PROVIDER")), "Explicit M1 selection required");
            check("https://ciudadanos-m1-backend.onrender.com".equals(env.getProperty("app.identity.m1.base-url")),
                    "Only the authorized M1 authentication origin is allowed");
            check(env.getProperty("spring.datasource.url", "").matches(
                    "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{4,5}/m2_m1_lab_[a-f0-9]{32}"),
                    "Only a new loopback lab database is allowed");
            check(env.getProperty("app.identity.m1.browser-origin", "").matches("https://localhost:[0-9]{4,5}"),
                    "Only a local HTTPS browser origin is allowed");
            for (String flag : new String[]{"app.core.outbox.enabled", "app.core.inbox.enabled",
                    "app.core.outbox.auto-approve-new-events", "app.simulator.enabled", "app.ai.enabled"}) {
                check(!Boolean.parseBoolean(env.getProperty(flag, "false")), "External integrations must be disabled");
            }
            for (String name : new String[]{"APP_JWT_SECRET", "M1_TEST_CITIZEN1_PASSWORD",
                    "M1_TEST_CITIZEN2_PASSWORD", "M1_TEST_EMPLOYEE_PASSWORD"}) {
                check(System.getenv(name) != null && !System.getenv(name).isBlank(), "Missing private variable: " + name);
            }
        }
    }

    @Test void authorizedAccountsLocalUsersOwnershipAndSessions() throws Exception {
        check(users.count() == 0, "A fresh lab database with no ModuleUsers is required");
        check(db.queryForObject("SELECT count(*) FROM tickets", Long.class) == 0, "Historical tickets are forbidden");
        var first = probe("ciudadano1.m2@example.com", "M1_TEST_CITIZEN1_PASSWORD", IdentityActorType.CIUDADANO);
        var second = probe("ciudadano2.m2@example.com", "M1_TEST_CITIZEN2_PASSWORD", IdentityActorType.CIUDADANO);
        var employee = probe("empleado1.m2@example.com", "M1_TEST_EMPLOYEE_PASSWORD", IdentityActorType.EMPLEADO);
        check(!first.profile().actorId().equals(second.profile().actorId()), "Citizens must have distinct actors");
        System.out.println("M1_REAL: three authorized accounts authenticated; HS256 and JWT/JSON identities verified");

        JsonNode firstLogin = login("ciudadano1.m2@example.com", "M1_TEST_CITIZEN1_PASSWORD", 200);
        ModuleUser local = users.findByActorId(first.profile().actorId()).orElseThrow();
        check(local.getRole() == ModuleRole.CITIZEN && local.getIdentitySource() == IdentitySource.M1,
                "Citizen must have an M1 projection and local CITIZEN role");
        check(Objects.equals(local.getEmail(), first.profile().email()) && Objects.equals(local.getPhone(), first.profile().phone()),
                "Available verified contacts must be synchronized");
        local.setPreferredNotificationChannel("EMAIL");
        users.saveAndFlush(local);
        login("ciudadano1.m2@example.com", "M1_TEST_CITIZEN1_PASSWORD", 200);
        ModuleUser again = users.findByActorId(first.profile().actorId()).orElseThrow();
        check(again.getId().equals(local.getId()) && users.count() == 1, "Second login must not duplicate users");
        check("EMAIL".equals(again.getPreferredNotificationChannel()) && again.getRole() == ModuleRole.CITIZEN
                && again.getAreaId() == null, "Local preferences and permissions must survive login");
        check(Objects.equals(again.getFirstName(), first.profile().firstName())
                && Objects.equals(again.getLastName(), first.profile().lastName()), "Names must be synchronized");
        JsonNode secondLogin = login("ciudadano2.m2@example.com", "M1_TEST_CITIZEN2_PASSWORD", 200);
        check(users.count() == 2, "Distinct citizen projections required");

        Long type = db.queryForObject("INSERT INTO request_types(code,subcategory_id,name,description,ticket_type,responsible_area_id,minimum_priority,base_risk,affected_population_factor,allows_anonymous,requires_location,active) "
                + "SELECT 'M1_LAB_ONLY',id,'M1 lab','Disposable authentication check','COMPLAINT','M2','LOW','LOW',0,true,false,true FROM subcategories ORDER BY id LIMIT 1 RETURNING id", Long.class);
        String ticketBody = "{\"requestTypeId\":" + type + ",\"summary\":\"M1 local test\",\"description\":\"Disposable isolated case\",\"formData\":{}}";
        MvcResult created = mvc.perform(post("/api/tickets").header(HttpHeaders.AUTHORIZATION, bearer(firstLogin))
                .contentType(MediaType.APPLICATION_JSON).content(ticketBody)).andReturn();
        status(created, 201, "Citizen ticket creation");
        UUID ticket = UUID.fromString(json.readTree(created.getResponse().getContentAsString()).get("ticketId").asText());
        status(mvc.perform(get("/api/tickets/" + ticket).header(HttpHeaders.AUTHORIZATION, bearer(firstLogin))).andReturn(), 200, "Owner read");
        status(mvc.perform(get("/api/tickets/" + ticket).header(HttpHeaders.AUTHORIZATION, bearer(secondLogin))).andReturn(), 403, "Other citizen read");
        status(mvc.perform(post("/api/tickets/" + ticket + "/messages").header(HttpHeaders.AUTHORIZATION, bearer(secondLogin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"PUBLIC\",\"text\":\"Not owner\"}")).andReturn(), 403, "Other citizen write");
        login("empleado1.m2@example.com", "M1_TEST_EMPLOYEE_PASSWORD", 403);
        check(users.findByActorId(employee.profile().actorId()).isEmpty(), "Unprovisioned employee must not acquire local privileges");

        if ("YES".equals(System.getenv("M1_TEST_PROVISION_EMPLOYEE"))) {
            // Explicit local opt-in only, using identity obtained from a verified real login, never an email match.
            ModuleUser staff = new ModuleUser();
            staff.setActorId(employee.profile().actorId()); staff.setActorType(IdentityActorType.EMPLEADO);
            staff.setIdentitySource(IdentitySource.M1); staff.setCitizenId(null);
            staff.setFirstName(employee.profile().firstName()); staff.setLastName(employee.profile().lastName());
            staff.setRole(ModuleRole.AGENT); staff.setAreaId(null); staff.setLastSyncedAt(Instant.now());
            users.saveAndFlush(staff);
            JsonNode staffLogin = login("empleado1.m2@example.com", "M1_TEST_EMPLOYEE_PASSWORD", 200);
            check("AGENT".equals(staffLogin.get("identity").get("role").asText()), "Only local AGENT role allowed");
            status(mvc.perform(get("/api/tickets").header(HttpHeaders.AUTHORIZATION, bearer(staffLogin))).andReturn(), 200, "Staff list");
            status(mvc.perform(post("/api/tickets").header(HttpHeaders.AUTHORIZATION, bearer(staffLogin))
                    .contentType(MediaType.APPLICATION_JSON).content(ticketBody)).andReturn(), 403, "Employee citizen creation denied");
            System.out.println("M1_REAL: employee provisioned as local AGENT with verified actorId; staff access and citizen restriction passed");
        } else System.out.println("M1_REAL: PENDING employee AGENT provisioning; rerun with explicit -ProvisionEmployee in a NEW lab");

        Cookie cookie = new Cookie(M1SessionCookies.NAME, first.refreshToken());
        status(mvc.perform(post("/api/auth/refresh").cookie(cookie).header("Origin", "https://other.invalid")
                .header(M1SessionCookies.CSRF_HEADER, "1")).andReturn(), 403, "Foreign origin");
        status(mvc.perform(post("/api/auth/refresh").cookie(cookie).header("Origin", origin())).andReturn(), 403, "Missing CSRF");
        MvcResult renewed = mvc.perform(post("/api/auth/refresh").cookie(cookie).header("Origin", origin())
                .header(M1SessionCookies.CSRF_HEADER, "1")).andReturn();
        status(renewed, 200, "Real refresh");
        checkCookie(renewed);
        JsonNode refreshBody = json.readTree(renewed.getResponse().getContentAsString());
        check(first.profile().actorId().toString().equals(refreshBody.get("identity").get("actorId").asText()), "Refresh identity preserved");
        status(mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(refreshBody))).andReturn(), 200, "Renewed access");
        status(mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer invalid")).andReturn(), 401, "Invalid token");
        MvcResult logout = mvc.perform(post("/api/auth/logout").header("Origin", origin()).header(M1SessionCookies.CSRF_HEADER, "1")).andReturn();
        status(logout, 204, "Logout");
        check(logout.getResponse().getHeader(HttpHeaders.SET_COOKIE).contains("Max-Age=0"), "Logout cookie deletion");
        System.out.println("M1_REAL: citizens, no duplicate users, contacts, preferences, ownership and controller session checks passed");
        System.out.println("M1_REAL: browser React/HTTPS cookie persistence still requires the documented browser checks");
    }

    private ExternalAuthenticatedSession probe(String account, String passwordVariable, IdentityActorType type) {
        var session = provider.authenticate(account, System.getenv(passwordVariable));
        check(session.isPresent(), "Authorized account login rejected; confirm existence, enablement and private password: " + account);
        check(session.orElseThrow().profile().actorType() == type, "Unexpected actor type for authorized account");
        return session.orElseThrow();
    }

    private JsonNode login(String account, String passwordVariable, int expected) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("username", account, "password", System.getenv(passwordVariable)));
        MvcResult result = mvc.perform(post("/api/auth/login").header("Origin", origin()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        status(result, expected, "M2 login for " + account);
        if (expected != 200) return null;
        checkCookie(result);
        JsonNode data = json.readTree(result.getResponse().getContentAsString());
        check(data.get("refreshToken") == null, "Refresh token must not be in JSON");
        return data;
    }

    private static String origin() { return System.getenv("M2_AUTH_BROWSER_ORIGIN"); }
    private static String bearer(JsonNode data) { return "Bearer " + data.get("token").asText(); }
    private static void checkCookie(MvcResult result) {
        String cookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        check(cookie != null && cookie.startsWith("__Host-M2-Refresh=") && cookie.contains("Secure")
                && cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict") && cookie.contains("Path=/")
                && !cookie.contains("Domain="), "Secure refresh cookie contract required");
    }
    private static void status(MvcResult result, int expected, String operation) {
        check(result.getResponse().getStatus() == expected, operation + ": unexpected HTTP status " + result.getResponse().getStatus());
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
