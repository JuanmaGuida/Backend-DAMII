package com.reclamos.backend.controller;

import com.reclamos.backend.config.SecurityConfiguration;
import com.reclamos.backend.identity.*;
import com.reclamos.backend.security.BearerTokenAuthenticationFilter;
import com.reclamos.backend.service.*;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StaffTicketController.class)
@Import({SecurityConfiguration.class, BearerTokenAuthenticationFilter.class})
class StaffTicketLabelControllerSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean TicketService tickets;
    @MockitoBean LabelService labels;
    @MockitoBean DuplicateCandidateService duplicates;
    @MockitoBean AuthService authService;

    @ParameterizedTest @MethodSource("operations")
    void labelMutationRequiresAuthentication(Operation operation) throws Exception {
        perform(operation, null).andExpect(status().isUnauthorized());
    }
    @ParameterizedTest @MethodSource("forbiddenOperations")
    void citizenAndAreaResponsibleCannotMutateLabels(Operation operation, ModuleRole role) throws Exception {
        perform(operation, role).andExpect(status().isForbidden());
    }
    @ParameterizedTest @MethodSource("allowedOperations")
    void agentAndAdminCanReachLabelMutation(Operation operation, ModuleRole role) throws Exception {
        perform(operation, role).andExpect(status().isNoContent());
    }
    ResultActions perform(Operation operation, ModuleRole role) throws Exception {
        UUID ticket = UUID.randomUUID(), label = UUID.randomUUID();
        var builder = operation.body()
                ? request(operation.method(), operation.path(), ticket)
                : request(operation.method(), operation.path(), ticket, label);
        if (operation.body()) builder.contentType(MediaType.APPLICATION_JSON)
                .content("{\"labelIds\":[\"" + label + "\"]}");
        if (role != null) {
            var identity = new AuthenticatedIdentity(role.name(), UUID.randomUUID(), role.name(),
                    role == ModuleRole.AREA_RESPONSIBLE ? "M2" : null, role);
            builder.with(authentication(new UsernamePasswordAuthenticationToken(identity, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role.name())))));
        }
        return mvc.perform(builder);
    }
    static Stream<Operation> operations() { return Stream.of(
            new Operation(HttpMethod.POST, "/api/staff/tickets/{ticketId}/labels", true),
            new Operation(HttpMethod.DELETE, "/api/staff/tickets/{ticketId}/labels/{labelId}", false)); }
    static Stream<Arguments> forbiddenOperations() { return operations().flatMap(o -> Stream.of(ModuleRole.CITIZEN, ModuleRole.AREA_RESPONSIBLE).map(r -> Arguments.of(o,r))); }
    static Stream<Arguments> allowedOperations() { return operations().flatMap(o -> Stream.of(ModuleRole.AGENT, ModuleRole.ADMIN).map(r -> Arguments.of(o,r))); }
    record Operation(HttpMethod method, String path, boolean body) { }
}