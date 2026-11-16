package com.reclamos.backend.controller;

import com.reclamos.backend.config.SecurityConfiguration;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.security.BearerTokenAuthenticationFilter;
import com.reclamos.backend.service.AuthService;
import com.reclamos.backend.service.CatalogAdminService;
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
 * BE - DDA2-139/140/141 (US "Verificación de permisos por rol"). No
 * reverifica reglas de negocio (eso ya lo cubre CatalogAdminServiceTest):
 * sólo prueba, endpoint por endpoint, que el gate hasRole("ADMIN") de
 * SecurityConfiguration sobre /api/admin/catalog/** hace lo que promete —
 * los 4 roles del sistema (Entidades V1.49 / Guía funcional M2 §7) contra
 * cada uno de los 15 endpoints administrativos del catálogo.
 */
@WebMvcTest(CatalogAdminController.class)
@Import({SecurityConfiguration.class, BearerTokenAuthenticationFilter.class})
class CatalogAdminControllerSecurityTest {
    private static final String CATEGORY_BODY = """
            {"name":"Categoría de prueba","description":"Descripción de prueba"}""";
    private static final String SUBCATEGORY_BODY = """
            {"categoryId":1,"name":"Subcategoría de prueba","description":"Descripción de prueba"}""";
    private static final String REQUEST_TYPE_BODY = """
            {"subcategoryId":1,"code":"TEST-CODE","name":"Request type de prueba",
            "description":"Descripción de prueba","ticketType":"COMPLAINT",
            "responsibleAreaId":"M2","minimumPriority":"LOW","baseRisk":"LOW",
            "affectedPopulationFactor":1.0,"allowsAnonymous":false,"requiresLocation":false}""";

    private static final RoleAuth CITIZEN = roleAuth("CITIZEN", ModuleRole.CITIZEN, "000000000005");
    private static final RoleAuth AGENT = roleAuth("AGENT", ModuleRole.AGENT, "000000000002");
    private static final RoleAuth AREA_RESPONSIBLE = roleAuth("AREA_RESPONSIBLE", ModuleRole.AREA_RESPONSIBLE, "000000000004");
    private static final RoleAuth ADMIN = roleAuth("ADMIN", ModuleRole.ADMIN, "000000000003");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CatalogAdminService catalogAdminService;

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
                new AdminEndpoint(HttpMethod.GET, "/api/admin/catalog/categories", new Object[0], null, 200),
                new AdminEndpoint(HttpMethod.POST, "/api/admin/catalog/categories", new Object[0], CATEGORY_BODY, 201),
                new AdminEndpoint(HttpMethod.PUT, "/api/admin/catalog/categories/{id}", new Object[]{1L}, CATEGORY_BODY, 200),
                new AdminEndpoint(HttpMethod.GET, "/api/admin/catalog/categories/{id}/subcategories", new Object[]{1L}, null, 200),
                new AdminEndpoint(HttpMethod.POST, "/api/admin/catalog/categories/{id}/deactivate", new Object[]{1L}, null, 200),
                new AdminEndpoint(HttpMethod.POST, "/api/admin/catalog/categories/{id}/activate", new Object[]{1L}, null, 200),
                new AdminEndpoint(HttpMethod.POST, "/api/admin/catalog/subcategories", new Object[0], SUBCATEGORY_BODY, 201),
                new AdminEndpoint(HttpMethod.PUT, "/api/admin/catalog/subcategories/{id}", new Object[]{1L}, SUBCATEGORY_BODY, 200),
                new AdminEndpoint(HttpMethod.POST, "/api/admin/catalog/subcategories/{id}/deactivate", new Object[]{1L}, null, 200),
                new AdminEndpoint(HttpMethod.POST, "/api/admin/catalog/subcategories/{id}/activate", new Object[]{1L}, null, 200),
                new AdminEndpoint(HttpMethod.GET, "/api/admin/catalog/subcategories/{id}/request-types", new Object[]{1L}, null, 200),
                new AdminEndpoint(HttpMethod.POST, "/api/admin/catalog/request-types", new Object[0], REQUEST_TYPE_BODY, 201),
                new AdminEndpoint(HttpMethod.PUT, "/api/admin/catalog/request-types/{id}", new Object[]{1L}, REQUEST_TYPE_BODY, 200),
                new AdminEndpoint(HttpMethod.POST, "/api/admin/catalog/request-types/{id}/deactivate", new Object[]{1L}, null, 200),
                new AdminEndpoint(HttpMethod.POST, "/api/admin/catalog/request-types/{id}/activate", new Object[]{1L}, null, 200)
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

    /** method/urlTemplate/urlVars siguen la firma de MockMvcRequestBuilders.request(...). */
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
