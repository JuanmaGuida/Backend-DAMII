package com.reclamos.backend.service;

import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.dto.UpdateTicketStatusRequest;
import com.reclamos.backend.entity.ActivityType;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Subcategory;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketActivity;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.entity.TicketType;
import com.reclamos.backend.entity.UpdateTicketStatusType;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.TicketStateConflictException;
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketLocationRepository;
import com.reclamos.backend.repository.TicketMessageRepository;
import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketStatusUpdateServiceTest {

    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private TicketActivityRepository activityRepository;
    @Mock
    private TicketLocationRepository locationRepository;
    @Mock
    private TicketMessageRepository messageRepository;

    private TicketStatusUpdateService service;

    private final UUID ticketId = UUID.randomUUID();
    private final UpdateTicketStatusRequest.Actor areaActor =
            new UpdateTicketStatusRequest.Actor("AREA_USER", "USR-M6-77");

    @BeforeEach
    void setUp() {
        service = new TicketStatusUpdateService(ticketRepository, activityRepository, locationRepository,
                messageRepository);
    }

    @Test
    void startedMovesRoutedTicketToInProgress() {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicket_Id(ticketId)).thenReturn(0L);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        UpdateTicketStatusRequest request = new UpdateTicketStatusRequest(
                UpdateTicketStatusType.STARTED, "Comenzamos a trabajar en esto.", null,
                null, null, areaActor, Instant.now(), "M6");

        TicketResponse response = service.applyUpdate(ticketId, request);

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        verify(messageRepository).save(any());

        ArgumentCaptor<TicketActivity> captor = ArgumentCaptor.forClass(TicketActivity.class);
        verify(activityRepository).save(captor.capture());
        assertThat(captor.getValue().getActionType()).isEqualTo(ActivityType.STATE_CHANGED);
    }

    @Test
    void startedOnWrongStateThrowsConflict() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        UpdateTicketStatusRequest request = new UpdateTicketStatusRequest(
                UpdateTicketStatusType.STARTED, null, null, null, null, areaActor, Instant.now(), "M6");

        assertThatThrownBy(() -> service.applyUpdate(ticketId, request))
                .isInstanceOf(TicketStateConflictException.class);
    }

    @Test
    void progressWithoutAnyContentIsRejected() {
        Ticket ticket = ticket(TicketStatus.IN_PROGRESS);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        UpdateTicketStatusRequest request = new UpdateTicketStatusRequest(
                UpdateTicketStatusType.PROGRESS, null, null, null, null, areaActor, Instant.now(), "M6");

        assertThatThrownBy(() -> service.applyUpdate(ticketId, request))
                .isInstanceOf(InvalidTicketRequestException.class);
    }

    @Test
    void progressUpdatesCurrentProgressAndStaysInProgress() {
        Ticket ticket = ticket(TicketStatus.IN_PROGRESS);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicket_Id(ticketId)).thenReturn(1L);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        UpdateTicketStatusRequest request = new UpdateTicketStatusRequest(
                UpdateTicketStatusType.PROGRESS, null, null, 40, null, areaActor, Instant.now(), "M6");

        TicketResponse response = service.applyUpdate(ticketId, request);

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(ticket.getCurrentProgress()).isEqualTo((short) 40);
    }

    @Test
    void informationRequiredWithoutMessageForCitizenIsRejected() {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        UpdateTicketStatusRequest request = new UpdateTicketStatusRequest(
                UpdateTicketStatusType.INFORMATION_REQUIRED, null, null, null,
                new UpdateTicketStatusRequest.Details(null, null, null, null),
                areaActor, Instant.now(), "M6");

        assertThatThrownBy(() -> service.applyUpdate(ticketId, request))
                .isInstanceOf(InvalidTicketRequestException.class);
    }

    @Test
    void informationRequiredMovesTicketToPendingInformation() {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicket_Id(ticketId)).thenReturn(0L);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        UpdateTicketStatusRequest.Details details = new UpdateTicketStatusRequest.Details(
                new UpdateTicketStatusRequest.InformationRequest("Indique la altura aproximada.", null),
                null, null, null);
        UpdateTicketStatusRequest request = new UpdateTicketStatusRequest(
                UpdateTicketStatusType.INFORMATION_REQUIRED, null, null, null, details,
                areaActor, Instant.now(), "M6");

        TicketResponse response = service.applyUpdate(ticketId, request);

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.PENDING_INFORMATION);
    }

    @Test
    void returnedMovesTicketBackToInReview() {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicket_Id(ticketId)).thenReturn(0L);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        UpdateTicketStatusRequest.Details details = new UpdateTicketStatusRequest.Details(
                null, new UpdateTicketStatusRequest.ReturnInfo("REQUEST_TYPE_MISMATCH"), null, null);
        UpdateTicketStatusRequest request = new UpdateTicketStatusRequest(
                UpdateTicketStatusType.RETURNED, null, "No corresponde a nuestra área.", null, details,
                areaActor, Instant.now(), "M6");

        TicketResponse response = service.applyUpdate(ticketId, request);

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.IN_REVIEW);

        ArgumentCaptor<TicketActivity> captor = ArgumentCaptor.forClass(TicketActivity.class);
        verify(activityRepository).save(captor.capture());
        assertThat(captor.getValue().getReasonCode()).isEqualTo("REQUEST_TYPE_MISMATCH");
    }

    @Test
    void resolvedRequiresPublicMessage() {
        Ticket ticket = ticket(TicketStatus.IN_PROGRESS);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        UpdateTicketStatusRequest.Details details = new UpdateTicketStatusRequest.Details(
                null, null, new UpdateTicketStatusRequest.Resolution("ACTION_COMPLETED"), null);
        UpdateTicketStatusRequest request = new UpdateTicketStatusRequest(
                UpdateTicketStatusType.RESOLVED, null, "Se cambió la lámpara.", null, details,
                areaActor, Instant.now(), "M6");

        assertThatThrownBy(() -> service.applyUpdate(ticketId, request))
                .isInstanceOf(InvalidTicketRequestException.class);
    }

    @Test
    void resolvedMovesTicketToResolved() {
        Ticket ticket = ticket(TicketStatus.IN_PROGRESS);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicket_Id(ticketId)).thenReturn(2L);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        UpdateTicketStatusRequest.Details details = new UpdateTicketStatusRequest.Details(
                null, null, new UpdateTicketStatusRequest.Resolution("ACTION_COMPLETED"), null);
        UpdateTicketStatusRequest request = new UpdateTicketStatusRequest(
                UpdateTicketStatusType.RESOLVED, "La luminaria fue reparada.", "Se reemplazó el artefacto.",
                null, details, areaActor, Instant.now(), "M6");

        TicketResponse response = service.applyUpdate(ticketId, request);

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.RESOLVED);
    }

    @Test
    void rejectedMovesTicketToCancelled() {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicket_Id(ticketId)).thenReturn(0L);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        UpdateTicketStatusRequest.Details details = new UpdateTicketStatusRequest.Details(
                null, null, null, new UpdateTicketStatusRequest.Cancellation("OUT_OF_SCOPE"));
        UpdateTicketStatusRequest request = new UpdateTicketStatusRequest(
                UpdateTicketStatusType.REJECTED, "No corresponde a esta gestión.", null, null, details,
                areaActor, Instant.now(), "M6");

        TicketResponse response = service.applyUpdate(ticketId, request);

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.CANCELLED);
    }

    @Test
    void unknownActorTypeIsRejected() {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        UpdateTicketStatusRequest.Actor badActor = new UpdateTicketStatusRequest.Actor("ROBOT", "x");
        UpdateTicketStatusRequest request = new UpdateTicketStatusRequest(
                UpdateTicketStatusType.STARTED, null, null, null, null, badActor, Instant.now(), "M6");

        assertThatThrownBy(() -> service.applyUpdate(ticketId, request))
                .isInstanceOf(InvalidTicketRequestException.class);

        verify(ticketRepository, never()).save(any());
    }

    @Test
    void missingTicketThrowsNotFound() {
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.empty());

        UpdateTicketStatusRequest request = new UpdateTicketStatusRequest(
                UpdateTicketStatusType.STARTED, null, null, null, null, areaActor, Instant.now(), "M6");

        assertThatThrownBy(() -> service.applyUpdate(ticketId, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private Ticket ticket(TicketStatus status) {
        Category category = new Category();
        category.setId(1L);
        category.setName("Infraestructura");

        Subcategory subcategory = new Subcategory();
        subcategory.setId(10L);
        subcategory.setCategory(category);
        subcategory.setName("Vía pública");

        RequestType requestType = new RequestType();
        requestType.setId(5L);
        requestType.setCode("POTHOLE");
        requestType.setName("POTHOLE");
        requestType.setSubcategory(subcategory);
        requestType.setTicketType(TicketType.COMPLAINT);
        requestType.setResponsibleAreaId("M6");
        requestType.setMinimumPriority(Priority.LOW);
        requestType.setAffectedPopulationFactor(new BigDecimal("0.0500"));
        requestType.setActive(true);

        Ticket ticket = new Ticket();
        ticket.setId(ticketId);
        ticket.setPublicId("OP-0000000002");
        ticket.setRequestType(requestType);
        ticket.setTicketType(TicketType.COMPLAINT);
        ticket.setResponsibleAreaId("M6");
        ticket.setSummary("Bache en la vereda");
        ticket.setCurrentStatus(status);
        ticket.setCurrentPriority(Priority.MEDIUM);
        ticket.setEstimatedAffectedCount(0);
        ticket.setEscalated(false);
        ticket.setStatusChangedAt(Instant.now());
        return ticket;
    }
}
