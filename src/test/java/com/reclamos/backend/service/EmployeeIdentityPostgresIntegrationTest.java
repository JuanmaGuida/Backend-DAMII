package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.*;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.*;
import com.reclamos.backend.identity.*;
import com.reclamos.backend.repository.ModuleUserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.jpa.show-sql=false",
        "ticket.information-request.expiration-scan-delay=3600000",
        "ticket.resolution.confirmation-scan-delay=3600000", "ticket.sla.milestone-scan-delay=3600000"})
@ActiveProfiles("dev") @AutoConfigureMockMvc @Transactional
class EmployeeIdentityPostgresIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ModuleUserRepository users;
    @Autowired ModuleUserService profiles;
    @Autowired TicketService tickets;
    @Autowired TicketMessageService messages;
    @Autowired TicketResolutionService resolutions;
    @Autowired InformationRequestService information;
    @Autowired TicketCancellationRequestService cancellations;
    @Autowired OwnerTrackingCodeService tracking;
    @Autowired MockMvc mvc;
    @Autowired EntityManager entityManager;
    UUID citizenId = UUID.randomUUID(), employeeId = UUID.randomUUID();
    ModuleUser employee;
    AuthenticatedIdentity citizen, staff;
    Long type;

    @BeforeEach
    void setup() {
        ModuleUser owner = user(citizenId, IdentityActorType.CIUDADANO, ModuleRole.CITIZEN);
        users.saveAndFlush(owner);
        employee = users.saveAndFlush(user(employeeId, IdentityActorType.EMPLEADO, ModuleRole.AGENT));
        citizen = new AuthenticatedIdentity("1", citizenId, "Citizen Test", null, ModuleRole.CITIZEN);
        staff = new AuthenticatedIdentity("1", employeeId, IdentityActorType.EMPLEADO, null,
                "Employee Test", null, ModuleRole.AGENT);
        String marker = "IDENTITY-" + UUID.randomUUID();
        type = db.queryForObject("INSERT INTO request_types "
                + "(code,subcategory_id,name,description,ticket_type,responsible_area_id,minimum_priority,base_risk,affected_population_factor,allows_anonymous,requires_location,active) "
                + "SELECT ?,id,?,?,'COMPLAINT','M2','LOW','LOW',0,true,false,true FROM subcategories ORDER BY id LIMIT 1 RETURNING id",
                Long.class, marker, marker, marker);
    }

    @Test
    void employeeCannotCreateReadOrActAsCitizenOwnerAndNullNeverListsAnonymousTickets() throws Exception {
        var anonymous = tickets.create(request(), null, new MultipartFile[0]);
        var identified = tickets.create(request(), citizen, new MultipartFile[0]);

        assertThatThrownBy(() -> tickets.create(request(), staff, new MultipartFile[0]))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
        assertThatThrownBy(() -> tickets.listMyTickets(staff, PageRequest.of(0, 20)))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
        assertThatThrownBy(() -> tickets.getById(identified.ticketId(), staff))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
        assertThatThrownBy(() -> tracking.get(identified.ticketId(), staff))
                .isInstanceOf(OwnerTrackingCodeNotFoundException.class);
        assertThatThrownBy(() -> cancellations.ownHistory(anonymous.ticketId(), staff))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
        assertThat(tracking.get(identified.ticketId(), citizen).trackingCode()).isEqualTo(identified.trackingCode());

        var authentication = new UsernamePasswordAuthenticationToken(staff, null,
                List.of(new SimpleGrantedAuthority("ROLE_AGENT")));
        mvc.perform(get("/api/me/tickets").with(authentication(authentication))).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").with(authentication(authentication)))
                .andExpect(status().isOk()).andExpect(jsonPath("actorId").value(employeeId.toString()))
                .andExpect(jsonPath("actorType").value("EMPLEADO"))
                .andExpect(jsonPath("role").value("AGENT"));
    }

    @Test
    void employeeUsesInternalAssignmentButActorUuidForMessagesActivitiesAndResolution() {
        UUID id = tickets.create(request(), citizen, new MultipartFile[0]).ticketId();
        tickets.startReview(id, staff);
        entityManager.flush();
        assertThat(db.queryForObject("SELECT assigned_agent_id FROM tickets WHERE id=?", Long.class, id))
                .isEqualTo(employee.getId());
        assertThat(db.queryForObject("SELECT actor_id FROM ticket_activities WHERE ticket_id=? AND action_type='REVIEW_STARTED'", String.class, id))
                .isEqualTo(employeeId.toString());

        var reply = messages.create(id, message("Staff response"), staff);
        assertThat(reply.authorId()).isEqualTo(employeeId.toString());
        assertThat(reply.authorType()).isEqualTo(ActorType.AGENT);
        messages.create(id, message("Citizen response"), citizen);
        entityManager.flush();
        assertThat(db.queryForObject("SELECT count(*) FROM notification_logs WHERE ticket_id=? AND recipient_actor_id=? AND citizen_id IS NULL",
                Integer.class, id, employeeId)).isGreaterThan(0);

        tickets.routeToArea(id, staff);
        resolutions.resolveManually(id, new ResolveTicketRequest(ResolutionType.ACTION_COMPLETED, "Completed", null), staff);
        entityManager.flush();
        assertThat(db.queryForObject("SELECT resolved_by_id FROM ticket_resolutions WHERE ticket_id=?", String.class, id))
                .isEqualTo(employeeId.toString());
    }

    @Test
    void employeeCanRequestInformationForAnonymousTicketWithoutBecomingItsOwner() {
        UUID id = tickets.create(request(), null, new MultipartFile[0]).ticketId();
        tickets.startReview(id, staff);
        information.requestInformation(id, new CreateInformationRequest("More information", null), staff);
        entityManager.flush();

        assertThat(db.queryForObject("SELECT requested_by_actor_id FROM information_requests WHERE ticket_id=?", String.class, id))
                .isEqualTo(employeeId.toString());
    }

    @Test
    void partialProfileMergePreservesAuthorizationContactIdentityAndInternalId() {
        employee.setActive(false);
        employee.setPreferredNotificationChannel("EMAIL");
        employee.setEmail("keep@example.test");
        employee.setPhone("+541155555555");
        employee.setProfileImageUrl("https://example.test/avatar");
        users.saveAndFlush(employee);
        var partial = new ExternalIdentityProfile("unrelated-sub", employeeId, IdentityActorType.EMPLEADO,
                IdentitySource.M1, null, "Updated", null, null, null, null, null);

        ModuleUser result = profiles.synchronize(partial);

        assertThat(result.getId()).isEqualTo(employee.getId());
        assertThat(result.getActorId()).isEqualTo(employeeId);
        assertThat(result.getCitizenId()).isNull();
        assertThat(result.getFirstName()).isEqualTo("Updated");
        assertThat(result.getLastName()).isEqualTo("Test");
        assertThat(result.getRole()).isEqualTo(ModuleRole.AGENT);
        assertThat(result.isActive()).isFalse();
        assertThat(result.getPreferredNotificationChannel()).isEqualTo("EMAIL");
        assertThat(result.getEmail()).isEqualTo("keep@example.test");
        assertThat(result.getPhone()).isEqualTo("+541155555555");
        assertThat(result.getProfileImageUrl()).isEqualTo("https://example.test/avatar");
    }

    @Test
    void citizenWithStaffRoleStillCannotTriageTheirOwnTicket() {
        UUID id = tickets.create(request(), citizen, new MultipartFile[0]).ticketId();
        var citizenAdmin = new AuthenticatedIdentity("1", citizenId, "Citizen Admin", null, ModuleRole.ADMIN);
        assertThatThrownBy(() -> tickets.startReview(id, citizenAdmin))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
        assertThat(tickets.listMyTickets(citizenAdmin, PageRequest.of(0, 20)).getContent())
                .extracting(t -> t.getId()).contains(id);
    }

    private CreateTicketRequest request() {
        return new CreateTicketRequest(type, "Identity test", "Synthetic local test", Map.of(), null);
    }

    private TicketMessageRequest message(String text) {
        TicketMessageRequest request = new TicketMessageRequest();
        request.setVisibility(MessageVisibility.PUBLIC);
        request.setText(text);
        return request;
    }

    private ModuleUser user(UUID id, IdentityActorType actorType, ModuleRole role) {
        ModuleUser user = new ModuleUser();
        user.setActorId(id);
        user.setActorType(actorType);
        user.setIdentitySource(IdentitySource.M1);
        user.setCitizenId(actorType == IdentityActorType.CIUDADANO ? id : null);
        user.setFirstName(actorType == IdentityActorType.CIUDADANO ? "Citizen" : "Employee");
        user.setLastName("Test");
        user.setRole(role);
        user.setLastSyncedAt(Instant.now());
        return user;
    }
}
