package com.reclamos.backend.controller;

import com.reclamos.backend.config.SecurityConfiguration;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.security.BearerTokenAuthenticationFilter;
import com.reclamos.backend.service.AuthService;
import com.reclamos.backend.service.FormAdminService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE - DDA2-139/140/141 (US "Verificación de permisos por rol"), misma idea
 * que {@link CatalogAdminControllerSecurityTest} pero para
 * /api/admin/catalog/request-types/{id}/form (DDA2-133/134/135). No
 * reverifica versionado ni validación de RiskRule — eso lo cubre
 * FormAdminServiceTest — sólo el gate de rol.
 */
@WebMvcTest(FormAdminController.class)
@Import({SecurityConfiguration.class, BearerTokenAuthenticationFilter.class})
class FormAdminControllerSecurityTest {
    private static final String FORM_BODY = """
            {"fields":[{"code":"Q1","label":"Pregunta de prueba","type":"BOOLEAN",
            "required":true,"allowUnknown":false,"displayOrder":1,"config":{},"riskRules":[]}]}""";

    private static final RoleAuth CITIZEN = roleAuth("CITIZEN", ModuleRole.CITIZEN, "000000000005");
    private static final RoleAuth AGENT = roleAuth("AGENT", ModuleRole.AGENT, "000000000002");
    private static final RoleAuth AREA_RESPONSIBLE = roleAuth("AREA_RESPONSIBLE", ModuleRole.AREA_RESPONSIBLE, "000000000004");
    private static final RoleAuth ADMIN = roleAuth("ADMIN", ModuleRole.ADMIN, "000000000003");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FormAdminService formAdminService;

    @MockitoBean
    private AuthService authService;

    @ParameterizedTest(name = "{0} sin token responde 401")
    @MethodSource("endpoints")
    void endpointRequiresAuthentication(AdminEndpoint endpoint) throws Exception {
        perform(endpoint, null).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "{1} contra {0} responde 403")
    @MethodSource("endpointsByNonAdminRole")
    void endpointIsForbiddenForNonAdminRoles(AdminEndpoint endpoint, RoleAuth role) throws Exception {
        perform(endpoint, role).andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "ADMIN contra {0} responde {1}")
    @MethodSource("endpoints")
    void endpointIsReachableForAdmin(AdminEndpoint endpoint) throws Exception {
        perform(endpoint, ADMIN).andExpect(status().is(endpoint.adminSuccessStatus()));
    }

    private ResultActions perform(AdminEndpoint endpoint, RoleAuth role) throws Exception {
        var builder = request(endpoint.method(), endpoint.urlTemplate(), endpoint.urlVars());
        if (endpoint.jsonBody() != null) {
            builder = builder.contentType(MediaType.APPLICATION_JSON).content(endpoint.jsonBody());
        }
        if (role != null) {
            builder = builder.with(authentication(role.token()));
        }
        return mockMvc.perform(builder);
    }

    private static Stream<AdminEndpoint> endpoints() {
        return Stream.of(
                new AdminEndpoint(HttpMethod.GET, "/api/admin/catalog/request-types/{id}/form", new Object[]{1L}, null, 200),
                new AdminEndpoint(HttpMethod.PUT, "/api/admin/catalog/request-types/{id}/form", new Object[]{1L}, FORM_BODY, 200)
        );
    }

    private static Stream<Arguments> endpointsByNonAdminRole() {
        return endpoints().flatMap(endpoint ->
                Stream.of(CITIZEN, AGENT, AREA_RESPONSIBLE).map(role -> Arguments.of(endpoint, role)));
    }

    private static RoleAuth roleAuth(String label, ModuleRole role, String citizenIdSuffix) {
        AuthenticatedIdentity identity = new AuthenticatedIdentity(
                "test-" + label.toLowerCase(),
                UUID.fromString("10000000-0000-0000-0000-" + citizenIdSuffix),
                label + " de prueba",
                role == ModuleRole.AREA_RESPONSIBLE ? "M2" : null,
                role);
        return new RoleAuth(label, new UsernamePasswordAuthenticationToken(
                identity, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    private record AdminEndpoint(
            HttpMethod method, String urlTemplate, Object[] urlVars, String jsonBody, int adminSuccessStatus) {
        @Override
        public String toString() {
            return method + " " + urlTemplate;
        }
    }

    private record RoleAuth(String label, UsernamePasswordAuthenticationToken token) {
        @Override
        public String toString() {
            return label;
        }
    }
}
