package com.reclamos.backend.service;

import com.reclamos.backend.entity.CancellationReasonCode;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.OutboxEvent;
import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.ResolutionType;
import com.reclamos.backend.entity.Subcategory;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.entity.TicketType;
import com.reclamos.backend.entity.TicketUpdatedType;
import com.reclamos.backend.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TicketOutboxServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final TicketOutboxService service = new TicketOutboxService(
            repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        reset(repository);
    }

    @Test
    void ticketCreatedPublishesEveryIdentifiedRegisteredTicketButNeverAnonymous() {
        Ticket selfManaged = ticket(false, "M2", TicketStatus.REGISTERED);
        Ticket external = ticket(false, "M6", TicketStatus.REGISTERED);
        Ticket anonymous = ticket(true, "M6", TicketStatus.REGISTERED);

        service.ticketCreated(selfManaged, null, List.of());
        service.ticketCreated(external, null, List.of());
        service.ticketCreated(anonymous, null, List.of());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(event -> {
            assertThat(event.getEventType()).isEqualTo("ticketCreated");
            assertThat(event.getUpdateType()).isNull();
            assertThat(event.getPayload().get("subject")).isEqualTo("tickets/" + event.getTicket().getId());
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) event.getPayload().get("data");
            assertThat(data).containsEntry("publicId", event.getTicket().getPublicId())
                    .containsEntry("isAnonymous", false)
                    .containsEntry("status", "REGISTERED");
        });
    }

    @Test
    void internalProjectionEventsPublishOnlyForIdentifiedTickets() {
        Ticket identified = ticket(false, "M2", TicketStatus.IN_PROGRESS);
        Ticket anonymous = ticket(true, "M2", TicketStatus.IN_PROGRESS);

        service.statusChanged(identified, null, NOW);
        service.contentUpdated(identified, NOW);
        service.resolved(identified, ResolutionType.ACTION_COMPLETED, "Resuelto", NOW);
        service.statusChanged(anonymous, null, NOW);
        service.contentUpdated(anonymous, NOW);
        service.resolved(anonymous, ResolutionType.ACTION_COMPLETED, "Resuelto", NOW);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(OutboxEvent::getUpdateType)
                .containsExactly(TicketUpdatedType.STATUS_CHANGED,
                        TicketUpdatedType.CONTENT_UPDATED, TicketUpdatedType.RESOLVED);
    }

    @Test
    void routedIsExclusiveToExternalManagementForIdentifiedAndAnonymous() {
        service.routed(ticket(false, "M6", TicketStatus.ROUTED), null, NOW);
        service.routed(ticket(true, "M6", TicketStatus.ROUTED), null, NOW);
        service.routed(ticket(false, "M2", TicketStatus.IN_PROGRESS), null, NOW);
        service.routed(ticket(true, "M2", TicketStatus.IN_PROGRESS), null, NOW);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(event -> {
            assertThat(event.getUpdateType()).isEqualTo(TicketUpdatedType.ROUTED);
            assertThat(event.getTicket().getResponsibleAreaId()).isEqualTo("M6");
        });
    }

    @Test
    void informationProvidedPublishesForIdentifiedAndForAnonymousExternalRequestOnly() {
        service.informationProvided(ticket(false, "M2", TicketStatus.IN_PROGRESS), "Dato", false, NOW);
        service.informationProvided(ticket(false, "M6", TicketStatus.IN_PROGRESS), "Dato", true, NOW);
        service.informationProvided(ticket(true, "M6", TicketStatus.IN_PROGRESS), "Dato", true, NOW);
        service.informationProvided(ticket(true, "M6", TicketStatus.IN_PROGRESS), "Dato", false, NOW);
        service.informationProvided(ticket(true, "M2", TicketStatus.IN_PROGRESS), "Dato", false, NOW);

        verify(repository, times(3)).save(any());
    }

    @Test
    void reopenedPublishesForIdentifiedOrExternalAnonymous() {
        service.reopened(ticket(false, "M2", TicketStatus.IN_PROGRESS), "Sigue", NOW);
        service.reopened(ticket(false, "M6", TicketStatus.IN_PROGRESS), "Sigue", NOW);
        service.reopened(ticket(true, "M6", TicketStatus.IN_PROGRESS), "Sigue", NOW);
        service.reopened(ticket(true, "M2", TicketStatus.IN_PROGRESS), "Sigue", NOW);

        verify(repository, times(3)).save(any());
    }

    @Test
    void cancelledAvoidsAnonymousExternalEchoButPublishesM2OriginatedExternalCancellation() {
        service.cancelled(ticket(false, "M2", TicketStatus.CANCELLED),
                CancellationReasonCode.INFO_TIMEOUT, "Cancelado", true, NOW);
        service.cancelled(ticket(false, "M6", TicketStatus.CANCELLED),
                CancellationReasonCode.OUT_OF_SCOPE, "Cancelado", false, NOW);
        service.cancelled(ticket(true, "M6", TicketStatus.CANCELLED),
                CancellationReasonCode.INFO_TIMEOUT, "Cancelado", true, NOW);
        service.cancelled(ticket(true, "M6", TicketStatus.CANCELLED),
                CancellationReasonCode.OUT_OF_SCOPE, "Cancelado", false, NOW);
        service.cancelled(ticket(true, "M2", TicketStatus.CANCELLED),
                CancellationReasonCode.INFO_TIMEOUT, "Cancelado", true, NOW);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(event ->
                assertThat(event.getUpdateType()).isEqualTo(TicketUpdatedType.CANCELLED));
    }

    @Test
    void resolvedNeverEchoesAnAnonymousExternalFact() {
        service.resolved(ticket(true, "M6", TicketStatus.RESOLVED),
                ResolutionType.ACTION_COMPLETED, "Resuelto", NOW);

        verify(repository, never()).save(any());
    }

    private Ticket ticket(boolean anonymous, String areaId, TicketStatus status) {
        Category category = new Category();
        category.setName("Servicios urbanos");
        Subcategory subcategory = new Subcategory();
        subcategory.setName("Alumbrado");
        subcategory.setCategory(category);
        RequestType requestType = new RequestType();
        requestType.setName("Informar luminaria apagada");
        requestType.setSubcategory(subcategory);

        Ticket ticket = new Ticket();
        ticket.setId(UUID.randomUUID());
        ticket.setPublicId("TK-2026-000123");
        ticket.setCitizenId(anonymous ? null : UUID.randomUUID());
        ticket.setAnonymous(anonymous);
        ticket.setRequestType(requestType);
        ticket.setTicketType(TicketType.COMPLAINT);
        ticket.setSummary("Luminaria apagada");
        ticket.setDescription("La luminaria no funciona");
        ticket.setFormData(Map.of());
        ticket.setCurrentStatus(status);
        ticket.setCurrentPriority(Priority.MEDIUM);
        ticket.setResponsibleAreaId(areaId);
        ticket.setCreatedAt(NOW);
        return ticket;
    }
}
