package com.reclamos.backend.service;

import com.reclamos.backend.dto.response.TrackingTicketResponse;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Subcategory;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrackingServiceTest {
    private static final String CODE = "AbC-123_tracking";
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final TrackingCodeService trackingCodes = new TrackingCodeService();
    private final TrackingService service = new TrackingService(tickets, trackingCodes);

    @Test
    void validCodeReturnsCurrentPublicTicketDataForIdentifiedAndAnonymousTickets() {
        String hash = trackingCodes.hash(CODE);
        Ticket identified = ticket(false);
        Ticket anonymous = ticket(true);
        when(tickets.findByTrackingCodeHash(hash)).thenReturn(Optional.of(identified), Optional.of(anonymous));

        TrackingTicketResponse first = service.findByTrackingCode(CODE);
        TrackingTicketResponse second = service.findByTrackingCode(CODE);

        assertEquals("OP-1234567890", first.getPublicId());
        assertEquals(TicketStatus.IN_PROGRESS, first.getCurrentStatus());
        assertEquals(Instant.parse("2026-09-01T10:00:00Z"), first.getCreatedAt());
        assertEquals(Instant.parse("2026-09-02T10:00:00Z"), first.getStatusChangedAt());
        assertEquals("RT-1", first.getRequestType().getCode());
        assertEquals("Categoría", first.getCategory().getName());
        assertEquals("Subcategoría", first.getSubcategory().getName());
        assertEquals(first.getPublicId(), second.getPublicId());
        verify(tickets, org.mockito.Mockito.times(2)).findByTrackingCodeHash(hash);
    }

    @Test
    void lookupUsesExistingExactCaseSensitiveHashingWithoutNewNormalization() {
        when(tickets.findByTrackingCodeHash(trackingCodes.hash(CODE))).thenReturn(Optional.of(ticket(false)));

        service.findByTrackingCode(CODE);

        verify(tickets).findByTrackingCodeHash(trackingCodes.hash(CODE));
    }

    @Test
    void unknownCodeRaisesGenericNotFoundError() {
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                () -> service.findByTrackingCode("unknown"));

        assertEquals("No se encontró un ticket para el código de seguimiento informado", exception.getMessage());
    }

    @Test
    void publicDtoHasNoInternalOrTrackingHashFields() {
        Set<String> fieldNames = Arrays.stream(TrackingTicketResponse.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertFalse(fieldNames.contains("citizenId"));
        assertFalse(fieldNames.contains("id"));
        assertFalse(fieldNames.contains("riskScore"));
        assertFalse(fieldNames.contains("riskLevel"));
        assertFalse(fieldNames.contains("currentPriority"));
        assertFalse(fieldNames.contains("trackingCodeHash"));
    }

    private Ticket ticket(boolean anonymous) {
        Category category = new Category();
        category.setId(2L);
        category.setName("Categoría");
        Subcategory subcategory = new Subcategory();
        subcategory.setId(5L);
        subcategory.setName("Subcategoría");
        subcategory.setCategory(category);
        RequestType requestType = new RequestType();
        requestType.setId(15L);
        requestType.setCode("RT-1");
        requestType.setName("Tipo de solicitud");
        requestType.setSubcategory(subcategory);
        Ticket ticket = new Ticket();
        ticket.setPublicId("OP-1234567890");
        ticket.setAnonymous(anonymous);
        ticket.setRequestType(requestType);
        ticket.setSummary("Resumen público");
        ticket.setCurrentStatus(TicketStatus.IN_PROGRESS);
        ticket.setCreatedAt(Instant.parse("2026-09-01T10:00:00Z"));
        ticket.setStatusChangedAt(Instant.parse("2026-09-02T10:00:00Z"));
        return ticket;
    }
}