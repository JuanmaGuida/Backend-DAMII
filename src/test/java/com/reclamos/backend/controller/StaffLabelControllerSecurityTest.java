package com.reclamos.backend.controller;

import com.reclamos.backend.config.SecurityConfiguration;
import com.reclamos.backend.exception.LabelConflictException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.identity.*;
import com.reclamos.backend.security.BearerTokenAuthenticationFilter;
import com.reclamos.backend.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import java.util.*;
import java.util.stream.Stream;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StaffLabelController.class)
@Import({SecurityConfiguration.class, BearerTokenAuthenticationFilter.class})
class StaffLabelControllerSecurityTest {
    private static final String CREATE = "{\"code\":\"URGENTE\",\"name\":\"Urgente\"}";
    private static final String UPDATE = "{\"name\":\"Urgente editada\",\"active\":false}";
    private static final RoleAuth ADMIN = auth(ModuleRole.ADMIN);
    private static final RoleAuth AGENT = auth(ModuleRole.AGENT);
    private static final RoleAuth AREA = auth(ModuleRole.AREA_RESPONSIBLE);
    private static final RoleAuth CITIZEN = auth(ModuleRole.CITIZEN);
    @Autowired MockMvc mvc;
    @MockitoBean LabelService labels;
    @MockitoBean AuthService authService;

    @ParameterizedTest @MethodSource("writes")
    void administrationRequiresAuthentication(Endpoint endpoint) throws Exception {
        perform(endpoint, null).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest @MethodSource("nonAdminWrites")
    void administrationRejectsEveryNonAdminRole(Endpoint endpoint, RoleAuth role) throws Exception {
        perform(endpoint, role).andExpect(status().isForbidden());
    }

    @ParameterizedTest @MethodSource("writes")
    void administrationAllowsAdmin(Endpoint endpoint) throws Exception {
        perform(endpoint, ADMIN).andExpect(status().is(endpoint.success()));
    }

    @Test void referencedLabelDeleteReturnsConflict() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new LabelConflictException("referenciada")).when(labels).delete(id);
        mvc.perform(request(HttpMethod.DELETE, "/api/staff/labels/{id}", id).with(authentication(ADMIN.token())))
                .andExpect(status().isConflict());
    }

    @Test void updateMissingLabelReturnsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(labels.update(eq(id), any())).thenThrow(new ResourceNotFoundException("no existe"));
        mvc.perform(request(HttpMethod.PUT, "/api/staff/labels/{id}", id)
                        .with(authentication(ADMIN.token())).contentType(MediaType.APPLICATION_JSON).content(UPDATE))
                .andExpect(status().isNotFound());
    }

    private ResultActions perform(Endpoint endpoint, RoleAuth role) throws Exception {
        var request = endpoint.path().contains("{id}")
                ? request(endpoint.method(), endpoint.path(), UUID.randomUUID())
                : request(endpoint.method(), endpoint.path());
        if (endpoint.body() != null) request.contentType(MediaType.APPLICATION_JSON).content(endpoint.body());
        if (role != null) request.with(authentication(role.token()));
        return mvc.perform(request);
    }
    static Stream<Endpoint> writes() { return Stream.of(
            new Endpoint(HttpMethod.POST, "/api/staff/labels", CREATE, 201),
            new Endpoint(HttpMethod.PUT, "/api/staff/labels/{id}", UPDATE, 200),
            new Endpoint(HttpMethod.DELETE, "/api/staff/labels/{id}", null, 204)); }
    static Stream<Arguments> nonAdminWrites() { return writes().flatMap(e -> Stream.of(AGENT, AREA, CITIZEN).map(r -> Arguments.of(e, r))); }
    static RoleAuth auth(ModuleRole role) {
        var identity = new AuthenticatedIdentity(role.name(), UUID.randomUUID(), role.name(),
                role == ModuleRole.AREA_RESPONSIBLE ? "M2" : null, role);
        return new RoleAuth(new UsernamePasswordAuthenticationToken(identity, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }
    record Endpoint(HttpMethod method, String path, String body, int success) { }
    record RoleAuth(UsernamePasswordAuthenticationToken token) { }
}