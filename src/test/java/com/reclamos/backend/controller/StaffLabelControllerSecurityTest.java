package com.reclamos.backend.controller;

import com.reclamos.backend.config.SecurityConfiguration;
import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.entity.TicketStatus;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

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

    @ParameterizedTest
    @MethodSource("agentAllowedWrites")
    void agentCanCreateAndUpdateLabels(Endpoint endpoint) throws Exception {
        perform(endpoint, AGENT)
                .andExpect(status().is(endpoint.success()));
    }

    @ParameterizedTest
    @MethodSource("writes")
    void areaResponsibleCannotAdministerLabels(Endpoint endpoint) throws Exception {
        perform(endpoint, AREA)
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @MethodSource("writes")
    void citizenCannotAdministerLabels(Endpoint endpoint) throws Exception {
        perform(endpoint, CITIZEN)
                .andExpect(status().isForbidden());
    }

    @Test
    void agentCannotDeleteLabels() throws Exception {
        perform(
                new Endpoint(
                        HttpMethod.DELETE,
                        "/api/staff/labels/{id}",
                        null,
                        204
                ),
                AGENT
        ).andExpect(status().isForbidden());
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

    @Test void agentListsLabelTicketsWithPagingAndSorting() throws Exception {
        UUID id = UUID.randomUUID();
        TicketResponse ticket = new TicketResponse();
        ticket.setId(UUID.randomUUID());
        ticket.setCurrentStatus(TicketStatus.REGISTERED);
        when(labels.listTickets(eq(id), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ticket)));

        mvc.perform(request(HttpMethod.GET, "/api/staff/labels/{id}/tickets", id)
                        .with(authentication(AGENT.token()))
                        .param("page", "0").param("size", "10").param("sort", "createdAt,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(ticket.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(labels).listTickets(eq(id), argThat(pageable -> pageable.getPageSize() == 10
                && pageable.getSort().getOrderFor("createdAt").isAscending()));
    }

    @Test void adminCanListLabelTickets() throws Exception {
        UUID id = UUID.randomUUID();
        when(labels.listTickets(eq(id), any(Pageable.class))).thenReturn(Page.empty());
        mvc.perform(request(HttpMethod.GET, "/api/staff/labels/{id}/tickets", id)
                        .with(authentication(ADMIN.token())))
                .andExpect(status().isOk());
    }

    @ParameterizedTest @MethodSource("rolesWithoutLabelReadAccess")
    void labelTicketsRejectUnauthorizedStaffRoles(RoleAuth role) throws Exception {
        mvc.perform(request(HttpMethod.GET, "/api/staff/labels/{id}/tickets", UUID.randomUUID())
                        .with(authentication(role.token())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(labels);
    }

    @Test void labelTicketsRequireAuthentication() throws Exception {
        mvc.perform(request(HttpMethod.GET, "/api/staff/labels/{id}/tickets", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test void missingLabelReturnsNotFoundAndMalformedUuidReturnsBadRequest() throws Exception {
        UUID id = UUID.randomUUID();
        when(labels.listTickets(eq(id), any(Pageable.class)))
                .thenThrow(new ResourceNotFoundException("La etiqueta no existe"));
        mvc.perform(request(HttpMethod.GET, "/api/staff/labels/{id}/tickets", id)
                        .with(authentication(AGENT.token())))
                .andExpect(status().isNotFound());
        mvc.perform(request(HttpMethod.GET, "/api/staff/labels/not-a-uuid/tickets")
                        .with(authentication(AGENT.token())))
                .andExpect(status().isBadRequest());
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
    static Stream<Endpoint> agentAllowedWrites() {
        return Stream.of(
                new Endpoint(HttpMethod.POST, "/api/staff/labels", CREATE, 201),
                new Endpoint(HttpMethod.PUT, "/api/staff/labels/{id}", UPDATE, 200)
        );
    }
    static Stream<RoleAuth> rolesWithoutLabelReadAccess() { return Stream.of(AREA, CITIZEN); }
    static RoleAuth auth(ModuleRole role) {
        var identity = new AuthenticatedIdentity(role.name(), UUID.randomUUID(), role.name(),
                role == ModuleRole.AREA_RESPONSIBLE ? "M2" : null, role);
        return new RoleAuth(new UsernamePasswordAuthenticationToken(identity, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }
    record Endpoint(HttpMethod method, String path, String body, int success) { }
    record RoleAuth(UsernamePasswordAuthenticationToken token) { }
}