package com.reclamos.backend.service;

import com.reclamos.backend.dto.TicketFilter;
import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.entity.ActivityType;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.Neighborhood;
import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Subcategory;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketActivity;
import com.reclamos.backend.entity.TicketLocation;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.entity.TicketType;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.TicketStateConflictException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.NeighborhoodRepository;
import com.reclamos.backend.repository.RequestTypeRepository;
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketLocationRepository;
import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    @Mock
    private RequestTypeRepository requestTypeRepository;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private TicketActivityRepository activityRepository;
    @Mock
    private TicketLocationRepository locationRepository;
    @Mock
    private NeighborhoodRepository neighborhoodRepository;
    @Mock
    private FormValidationService formValidationService;
    @Mock
    private RiskCalculationService riskCalculationService;

    private TicketService ticketService;

    private final UUID ticketId = UUID.randomUUID();
    private final AuthenticatedIdentity actor = new AuthenticatedIdentity(
            "agent-1", UUID.randomUUID(), "Agente Uno", "area-obras", Set.of(ModuleRole.AGENT));

    @BeforeEach
    void setUp() {
        ticketService = new TicketService(requestTypeRepository, ticketRepository, activityRepository,
                locationRepository, neighborhoodRepository, formValidationService, riskCalculationService);
    }

    // ---- startReview ----

    @Test
    void startReviewMovesRegisteredTicketToInReviewAndAssignsAgent() {
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.MEDIUM);
        ticket.setAssignedAgentId(null);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicket_Id(ticketId)).thenReturn(0L);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        TicketResponse response = ticketService.startReview(ticketId, actor);

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.IN_REVIEW);
        assertThat(ticket.getAssignedAgentId()).isEqualTo("agent-1");
        assertThat(ticket.getCurrentStatus()).isEqualTo(TicketStatus.IN_REVIEW);

        ArgumentCaptor<TicketActivity> activityCaptor = ArgumentCaptor.forClass(TicketActivity.class);
        verify(activityRepository).save(activityCaptor.capture());
        TicketActivity activity = activityCaptor.getValue();
        assertThat(activity.getActionType()).isEqualTo(ActivityType.REVIEW_STARTED);
        assertThat(activity.getPreviousStatus()).isEqualTo(TicketStatus.REGISTERED);
        assertThat(activity.getNewStatus()).isEqualTo(TicketStatus.IN_REVIEW);
        assertThat(activity.getSequence()).isEqualTo(1);
    }

    @Test
    void startReviewDoesNotOverwriteAnAlreadyAssignedAgent() {
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.MEDIUM);
        ticket.setAssignedAgentId("agent-original");
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicket_Id(ticketId)).thenReturn(0L);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        ticketService.startReview(ticketId, actor);

        assertThat(ticket.getAssignedAgentId()).isEqualTo("agent-original");
    }

    @Test
    void startReviewOnNonRegisteredTicketThrowsConflict() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.MEDIUM);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> ticketService.startReview(ticketId, actor))
                .isInstanceOf(TicketStateConflictException.class);

        verify(activityRepository, never()).save(any());
    }

    @Test
    void startReviewOnMissingTicketThrowsNotFound() {
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.startReview(ticketId, actor))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- correctClassification ----

    @Test
    void correctClassificationRecalculatesAreaAffectedCountAndPriorityFloor() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        Neighborhood neighborhood = new Neighborhood();
        neighborhood.setId(UUID.randomUUID());
        neighborhood.setName("Palermo");
        neighborhood.setPopulation(200_000);
        TicketLocation location = new TicketLocation();
        location.setNeighborhood(neighborhood);

        RequestType newRequestType = requestType(20L, "FLOODING", "obras-hidraulicas",
                Priority.HIGH, new BigDecimal("0.1000"));

        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(requestTypeRepository.findById(20L)).thenReturn(Optional.of(newRequestType));
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.of(location));
        when(activityRepository.countByTicket_Id(ticketId)).thenReturn(0L);

        TicketResponse response = ticketService.correctClassification(ticketId, 20L, actor);

        assertThat(ticket.getResponsibleAreaId()).isEqualTo("obras-hidraulicas");
        assertThat(ticket.getEstimatedAffectedCount()).isEqualTo(20_000);
        assertThat(ticket.getCurrentPriority()).isEqualTo(Priority.HIGH);
        assertThat(response.getRequestTypeCode()).isEqualTo("FLOODING");
    }

    @Test
    void correctClassificationNeverLowersAnAlreadyHigherPriority() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.CRITICAL);
        RequestType newRequestType = requestType(20L, "FLOODING", "obras-hidraulicas",
                Priority.LOW, new BigDecimal("0.1000"));

        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(requestTypeRepository.findById(20L)).thenReturn(Optional.of(newRequestType));
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(activityRepository.countByTicket_Id(ticketId)).thenReturn(0L);

        ticketService.correctClassification(ticketId, 20L, actor);

        assertThat(ticket.getCurrentPriority()).isEqualTo(Priority.CRITICAL);
    }

    @Test
    void correctClassificationWithoutLocationEstimatesZeroAffectedCount() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        RequestType newRequestType = requestType(20L, "FLOODING", "obras-hidraulicas",
                Priority.MEDIUM, new BigDecimal("0.1000"));

        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(requestTypeRepository.findById(20L)).thenReturn(Optional.of(newRequestType));
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(activityRepository.countByTicket_Id(ticketId)).thenReturn(0L);

        ticketService.correctClassification(ticketId, 20L, actor);

        assertThat(ticket.getEstimatedAffectedCount()).isZero();
    }

    @Test
    void correctClassificationRejectsWhenTicketIsNotInFirstReview() {
        Ticket ticket = ticket(TicketStatus.ROUTED, Priority.LOW);

        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> ticketService.correctClassification(ticketId, 20L, actor))
                .isInstanceOf(TicketStateConflictException.class);

        verify(requestTypeRepository, never()).findById(any());
    }

    @Test
    void correctClassificationRejectsWhenClassificationAlreadyFinalized() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        ticket.setClassificationFinalizedAt(Instant.now());

        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> ticketService.correctClassification(ticketId, 20L, actor))
                .isInstanceOf(TicketStateConflictException.class);
    }

    @Test
    void correctClassificationRejectsInactiveOrMissingRequestType() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);

        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(requestTypeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.correctClassification(ticketId, 99L, actor))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- listTickets ----

    @Test
    void listTicketsMapsNeighborhoodFromBatchedLocations() {
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.MEDIUM);
        Neighborhood neighborhood = new Neighborhood();
        neighborhood.setId(UUID.randomUUID());
        neighborhood.setName("Recoleta");
        neighborhood.setPopulation(150_000);
        TicketLocation location = new TicketLocation();
        location.setTicket(ticket);
        location.setNeighborhood(neighborhood);

        TicketFilter filter = new TicketFilter(null, null, null, null, null);
        Pageable pageable = Pageable.unpaged();
        Page<Ticket> page = new PageImpl<>(List.of(ticket));

        when(ticketRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        when(locationRepository.findAllByTicket_IdIn(anyList())).thenReturn(List.of(location));

        Page<TicketResponse> result = ticketService.listTickets(filter, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getNeighborhoodName()).isEqualTo("Recoleta");
    }

    @Test
    void listTicketsLeavesNeighborhoodNullWhenTicketHasNoLocation() {
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.MEDIUM);

        TicketFilter filter = new TicketFilter(null, null, null, null, null);
        Pageable pageable = Pageable.unpaged();
        Page<Ticket> page = new PageImpl<>(List.of(ticket));

        when(ticketRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        when(locationRepository.findAllByTicket_IdIn(anyList())).thenReturn(List.of());

        Page<TicketResponse> result = ticketService.listTickets(filter, pageable);

        assertThat(result.getContent().get(0).getNeighborhoodId()).isNull();
    }

    // ---- fixtures ----

    private Ticket ticket(TicketStatus status, Priority priority) {
        Category category = new Category();
        category.setId(1L);
        category.setName("Infraestructura");

        Subcategory subcategory = new Subcategory();
        subcategory.setId(10L);
        subcategory.setCategory(category);
        subcategory.setName("Vía pública");

        RequestType requestType = requestType(5L, "POTHOLE", "obras-viales", Priority.LOW,
                new BigDecimal("0.0500"));
        requestType.setSubcategory(subcategory);

        Ticket ticket = new Ticket();
        ticket.setId(ticketId);
        ticket.setPublicId("OP-0000000001");
        ticket.setRequestType(requestType);
        ticket.setTicketType(TicketType.INDIVIDUAL);
        ticket.setResponsibleAreaId("obras-viales");
        ticket.setSummary("Bache en la vereda");
        ticket.setCurrentStatus(status);
        ticket.setCurrentPriority(priority);
        ticket.setEstimatedAffectedCount(0);
        ticket.setEscalated(false);
        ticket.setStatusChangedAt(Instant.now());
        return ticket;
    }

    private RequestType requestType(Long id, String code, String responsibleAreaId,
                                     Priority minimumPriority, BigDecimal affectedPopulationFactor) {
        Category category = new Category();
        category.setId(1L);
        category.setName("Infraestructura");

        Subcategory subcategory = new Subcategory();
        subcategory.setId(10L);
        subcategory.setCategory(category);
        subcategory.setName("Vía pública");

        RequestType requestType = new RequestType();
        requestType.setId(id);
        requestType.setCode(code);
        requestType.setName(code);
        requestType.setSubcategory(subcategory);
        requestType.setTicketType(TicketType.INDIVIDUAL);
        requestType.setResponsibleAreaId(responsibleAreaId);
        requestType.setMinimumPriority(minimumPriority);
        requestType.setAffectedPopulationFactor(affectedPopulationFactor);
        requestType.setActive(true);
        return requestType;
    }
}
