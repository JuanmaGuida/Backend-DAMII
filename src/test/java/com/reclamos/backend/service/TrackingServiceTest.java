package com.reclamos.backend.service;

import com.reclamos.backend.dto.response.TrackingTicketResponse;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Subcategory;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.TrackingTicketNotFoundException;
import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TrackingServiceTest {
    private static final String CODE = "0123456789abcdefghijklmnopqrstuv";
    private static final String HASH = "secure-hash";
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final TrackingCodeService trackingCodes = mock(TrackingCodeService.class);
    private final TrackingService service = new TrackingService(tickets, trackingCodes);

    @Test
    void validCodeReturnsOnlyWhitelistedCurrentPublicTicketData() {
        Ticket ticket = ticket();
        when(trackingCodes.isValid(CODE)).thenReturn(true);
        when(trackingCodes.hash(CODE)).thenReturn(HASH);
        when(tickets.findByTrackingCodeHash(HASH)).thenReturn(Optional.of(ticket));

        TrackingTicketResponse response = service.findByTrackingCode(CODE);

        assertEquals("OP-1234567890", response.getPublicId());
        assertEquals(TicketStatus.IN_PROGRESS, response.getStatus());
        assertEquals("Resumen público", response.getSummary());
        assertEquals(Instant.parse("2026-09-01T10:00:00Z"), response.getCreatedAt());
        assertEquals(Instant.parse("2026-09-02T10:00:00Z"), response.getStatusChangedAt());
        assertEquals("Tipo de solicitud", response.getRequestType().getName());
        assertEquals("Categoría", response.getCategory().getName());
        assertEquals("Subcategoría", response.getSubcategory().getName());
        verify(trackingCodes).hash(CODE);
        verify(tickets).findByTrackingCodeHash(HASH);
        verify(tickets, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unknownCodeRaisesUniformControlledNotFoundError() {
        when(trackingCodes.isValid(CODE)).thenReturn(true);
        when(trackingCodes.hash(CODE)).thenReturn(HASH);

        TrackingTicketNotFoundException exception = assertThrows(TrackingTicketNotFoundException.class,
                () -> service.findByTrackingCode(CODE));

        assertEquals(TrackingTicketNotFoundException.MESSAGE, exception.getMessage());
    }

    @Test
    void malformedCodeUsesTheSameControlledNotFoundWithoutHashingOrQuerying() {
        when(trackingCodes.isValid("invalid")).thenReturn(false);

        assertThrows(TrackingTicketNotFoundException.class, () -> service.findByTrackingCode("invalid"));

        verify(trackingCodes, never()).hash("invalid");
        verifyNoInteractions(tickets);
    }

    @Test
    void publicDtoContainsNoReservedPropertiesIncludingNestedInternalIds() {
        Set<String> forbidden = Set.of("ticketId", "citizenId", "subjectId", "trackingCode", "trackingCodeHash",
                "trackingAccessCode",
                "riskScore", "riskLevel", "internalMessage", "actorId", "resolvedById", "sourceModuleId",
                "responsibleAreaId", "firstResponseDueAt", "resolutionDueAt");
        Set<String> fieldNames = Arrays.stream(TrackingTicketResponse.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).collect(java.util.stream.Collectors.toSet());
        assertTrueNoIntersection(fieldNames, forbidden);
        assertFalse(Arrays.stream(TrackingTicketResponse.RequestTypeSummary.class.getDeclaredFields())
                .anyMatch(field -> field.getName().equals("id")));
        assertFalse(Arrays.stream(TrackingTicketResponse.RequestTypeSummary.class.getDeclaredFields())
                .anyMatch(field -> field.getName().equals("code")));
        assertFalse(Arrays.stream(TrackingTicketResponse.CategorySummary.class.getDeclaredFields())
                .anyMatch(field -> field.getName().equals("id")));
        assertFalse(Arrays.stream(TrackingTicketResponse.SubcategorySummary.class.getDeclaredFields())
                .anyMatch(field -> field.getName().equals("id")));
    }

    @Test
    void lookupRemainsTransactionallyReadOnly() throws NoSuchMethodException {
        Transactional transactional = TrackingService.class
                .getMethod("findByTrackingCode", String.class)
                .getAnnotation(Transactional.class);

        assertTrue(transactional.readOnly());
    }

    private void assertTrueNoIntersection(Set<String> actual, Set<String> forbidden) {
        assertEquals(Set.of(), actual.stream().filter(forbidden::contains)
                .collect(java.util.stream.Collectors.toSet()));
    }

    private Ticket ticket() {
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
        ticket.setId(UUID.fromString("20000000-0000-0000-0000-000000000001"));
        ticket.setPublicId("OP-1234567890");
        ticket.setTrackingCodeHash("never-public");
        ticket.setCitizenId(UUID.randomUUID());
        ticket.setResponsibleAreaId("internal-area");
        ticket.setRequestType(requestType);
        ticket.setSummary("Resumen público");
        ticket.setCurrentStatus(TicketStatus.IN_PROGRESS);
        ticket.setCreatedAt(Instant.parse("2026-09-01T10:00:00Z"));
        ticket.setStatusChangedAt(Instant.parse("2026-09-02T10:00:00Z"));
        return ticket;
    }
}